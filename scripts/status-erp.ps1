#Requires -Version 5.1
<#
.SYNOPSIS
    pharma-erp 运行状态查看脚本（Windows PowerShell 5.1）。

.DESCRIPTION
    打印：
      1. 8080 / 5173 / 5432 / 6379 端口监听状态与占用进程；
      2. 本地 HTTP 探测：前端首页是否 200、POST /api/auth/login（admin/admin123）是否返回 code=200；
      3. cpolar 公网地址（优先取 logs\cpolar.log 最近一条，兜底查 cpolar 本地管理页 127.0.0.1:4040）；
      4. logs 目录下日志文件大小与最后写入时间、logs\pids.json；
      5. 前端 dist 是否为最新构建。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$ProgressPreference = 'SilentlyContinue'

# ==================== 路径与常量 ====================
$BackendPort  = 8080
$FrontendPort = 5173
$PgPort       = 5432
$RedisPort    = 6379
$LoginUser    = 'admin'
$LoginPass    = 'admin123'

$RepoRoot     = Split-Path -Parent $PSScriptRoot
$LogDir       = Join-Path $RepoRoot 'logs'
$PidsFile     = Join-Path $LogDir 'pids.json'
$BackendLog   = Join-Path $LogDir 'backend.log'
$FrontendLog  = Join-Path $LogDir 'frontend.log'
$CpolarLog    = Join-Path $LogDir 'cpolar.log'
$CpolarPage   = 'http://127.0.0.1:4040/'
$FrontendRoot = Join-Path $RepoRoot 'frontend'

# ==================== 输出辅助 ====================
function Show-Banner([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
    Write-Host ('  ' + $Text) -ForegroundColor Cyan
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
}
function Show-Step([string]$Text) { Write-Host ''; Write-Host ('>> ' + $Text) -ForegroundColor Cyan }
function Show-Ok([string]$Text)   { Write-Host ('  [OK] ' + $Text) -ForegroundColor Green }
function Show-Warn([string]$Text) { Write-Host ('  [!!] ' + $Text) -ForegroundColor Yellow }
function Show-Err([string]$Text)  { Write-Host ('  [XX] ' + $Text) -ForegroundColor Red }
function Show-Info([string]$Text) { Write-Host ('  .. ' + $Text) -ForegroundColor Gray }

# ==================== 辅助函数 ====================
function Get-PortOwnerId {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if ($null -ne $conn) { return [int]$conn.OwningProcess }
    } catch { }
    # 兜底：解析 netstat（EAP 临时降为 Continue，避免原生命令 stderr 触发终止错误）
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(netstat -ano 2>$null)
        foreach ($line in $lines) {
            if ($line -match ('^\s*TCP\s+\S+:' + $Port + '\s+\S+\s+LISTENING\s+(\d+)')) {
                return [int]$Matches[1]
            }
        }
    } catch { } finally {
        $ErrorActionPreference = $prevEap
    }
    return 0
}

function Get-ProcessDescription {
    param([int]$ProcId)
    if ($ProcId -le 0) { return '' }
    $p = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
    if ($null -eq $p) { return 'PID ' + $ProcId + '（进程已退出）' }
    return 'PID ' + $ProcId + ' / ' + $p.ProcessName
}

function Get-LogUrl {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $pattern = 'https://[A-Za-z0-9][A-Za-z0-9\.-]*\.cpolar\.(?:cn|top|io)'
    $lines = @(Get-Content -LiteralPath $Path -Encoding UTF8 -ErrorAction SilentlyContinue)
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        $m = [regex]::Match([string]$lines[$i], $pattern)
        if ($m.Success) { return $m.Value }
    }
    return ''
}

function Get-CpolarPublicUrl {
    $url = Get-LogUrl -Path $CpolarLog
    if ($url) { return $url }
    try {
        $resp = Invoke-WebRequest -Uri $CpolarPage -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        $m = [regex]::Match([string]$resp.Content, 'https://[A-Za-z0-9][A-Za-z0-9\.-]*\.cpolar\.(?:cn|top|io)')
        if ($m.Success) { return $m.Value }
    } catch { }
    return ''
}

function Invoke-ProbeGet {
    param([string]$Url)
    $result = @{ ok = $false; status = 0; bytes = 0; error = '' }
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $result.ok = $true
        $result.status = [int]$resp.StatusCode
        $result.bytes = [int]$resp.RawContentLength
    } catch {
        $result.error = $_.Exception.Message
        if ($null -ne $_.Exception.Response) {
            try { $result.status = [int]$_.Exception.Response.StatusCode } catch { }
        }
    }
    return $result
}

function Invoke-ProbeLogin {
    param([string]$Url, [string]$UserName, [string]$Password)
    $result = @{ ok = $false; status = 0; code = -1; message = ''; error = ''; elapsed = 0 }
    $body = '{"username":"' + $UserName + '","password":"' + $Password + '"}'
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-WebRequest -Uri $Url -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
        $watch.Stop()
        $result.elapsed = [int]$watch.ElapsedMilliseconds
        $result.ok = $true
        $result.status = [int]$resp.StatusCode
        try {
            $json = [string]$resp.Content | ConvertFrom-Json -ErrorAction Stop
            if ($null -ne $json.code) { $result.code = [int]$json.code }
            if ($null -ne $json.message) { $result.message = [string]$json.message }
        } catch {
            $result.message = '响应不是合法 JSON'
        }
    } catch {
        $watch.Stop()
        $result.elapsed = [int]$watch.ElapsedMilliseconds
        $result.error = $_.Exception.Message
        if ($null -ne $_.Exception.Response) {
            try { $result.status = [int]$_.Exception.Response.StatusCode } catch { }
        }
    }
    return $result
}

function Get-DistState {
    $distIndex = Join-Path (Join-Path $FrontendRoot 'dist') 'index.html'
    if (-not (Test-Path -LiteralPath $distIndex)) { return 'missing' }
    $distTime = (Get-Item -LiteralPath $distIndex).LastWriteTime
    $newest = $distTime
    $files = New-Object System.Collections.ArrayList
    $srcDir = Join-Path $FrontendRoot 'src'
    if (Test-Path -LiteralPath $srcDir) {
        foreach ($f in @(Get-ChildItem -LiteralPath $srcDir -Recurse -File -ErrorAction SilentlyContinue)) { [void]$files.Add($f) }
    }
    foreach ($rel in @('index.html', 'package.json', 'vite.config.ts')) {
        $full = Join-Path $FrontendRoot $rel
        if (Test-Path -LiteralPath $full) { [void]$files.Add((Get-Item -LiteralPath $full)) }
    }
    foreach ($f in $files) { if ($f.LastWriteTime -gt $newest) { $newest = $f.LastWriteTime } }
    if ($newest -gt $distTime.AddSeconds(2)) { return 'stale' }
    return 'fresh'
}

function Format-Size {
    param([long]$Bytes)
    if ($Bytes -lt 1024) { return ([string]$Bytes + ' B') }
    if ($Bytes -lt 1048576) { return ([string][Math]::Round($Bytes / 1024.0, 1) + ' KB') }
    return ([string][Math]::Round($Bytes / 1048576.0, 2) + ' MB')
}

# ==================== 1. 端口状态 ====================
Show-Banner 'pharma-erp 运行状态'
Write-Host ('  仓库根：' + $RepoRoot) -ForegroundColor DarkGray
Write-Host ('  检查时间：' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) -ForegroundColor DarkGray

Show-Step '端口监听状态'
$portDefs = @(
    @{ Port = $BackendPort;  Name = '后端 Spring Boot' },
    @{ Port = $FrontendPort; Name = '前端静态服务' },
    @{ Port = $PgPort;       Name = 'PostgreSQL 17' },
    @{ Port = $RedisPort;    Name = 'Redis' }
)
$statusMap = @{}
foreach ($def in $portDefs) {
    $owner = Get-PortOwnerId -Port $def.Port
    $statusMap[[int]$def.Port] = $owner
    if ($owner -gt 0) {
        Show-Ok ([string]$def.Port + '  ' + $def.Name + '：监听中（' + (Get-ProcessDescription -ProcId $owner) + '）')
    } else {
        Show-Err ([string]$def.Port + '  ' + $def.Name + '：未监听')
    }
}

$backendPid  = $statusMap[$BackendPort]
$frontendPid = $statusMap[$FrontendPort]

# ==================== 2. 本地 HTTP 探测 ====================
Show-Step '本地 HTTP 探测'
$frontendUrl = 'http://127.0.0.1:' + $FrontendPort + '/'
$loginUrl    = 'http://127.0.0.1:' + $BackendPort + '/api/auth/login'

$frontProbe = Invoke-ProbeGet -Url $frontendUrl
if ($frontProbe.ok) {
    if ($frontProbe.status -eq 200) {
        Show-Ok ('前端首页 GET ' + $frontendUrl + ' → HTTP ' + $frontProbe.status + '（' + (Format-Size -Bytes $frontProbe.bytes) + '）')
    } else {
        Show-Warn ('前端首页 GET ' + $frontendUrl + ' → HTTP ' + $frontProbe.status)
    }
} else {
    if ($frontProbe.status -gt 0) {
        Show-Warn ('前端首页 GET ' + $frontendUrl + ' → HTTP ' + $frontProbe.status + '（' + $frontProbe.error + '）')
    } else {
        Show-Err ('前端首页不可访问：' + $frontProbe.error)
    }
}

$loginProbe = Invoke-ProbeLogin -Url $loginUrl -UserName $LoginUser -Password $LoginPass
if ($loginProbe.code -eq 200) {
    Show-Ok ('登录接口 POST ' + $loginUrl + '（' + $LoginUser + '）→ HTTP ' + $loginProbe.status + '，code=200 ' + $loginProbe.message + '（' + $loginProbe.elapsed + ' ms）')
} elseif ($loginProbe.status -gt 0 -and $loginProbe.code -ge 0) {
    Show-Warn ('登录接口 POST ' + $loginUrl + ' → HTTP ' + $loginProbe.status + '，code=' + $loginProbe.code + '（' + $loginProbe.message + '），请检查账号或后端日志')
} elseif ($loginProbe.status -eq 403) {
    Show-Err ('登录接口返回 HTTP 403：可能是 CORS/安全配置问题（HTTP ' + $loginProbe.status + '）')
} else {
    Show-Err ('登录接口不可用：' + $loginProbe.error)
}
Show-Info '（安全说明：脚本只读取返回码/消息，不打印 token 等内容）'

# ==================== 3. 公网地址 ====================
Show-Step 'cpolar 公网地址'
$cpolarProcs = @(Get-Process -Name 'cpolar' -ErrorAction SilentlyContinue)
if ($cpolarProcs.Count -gt 0) {
    $cpolarPids = ''
    foreach ($cp in $cpolarProcs) {
        if ($cpolarPids) { $cpolarPids = $cpolarPids + ',' }
        $cpolarPids = $cpolarPids + [string]$cp.Id
    }
    Show-Ok ('cpolar 进程运行中（PID ' + $cpolarPids + '）')
} else {
    Show-Warn 'cpolar 进程未运行（需要外网访问时执行 start-erp.ps1 -Public）'
}
$publicUrl = Get-CpolarPublicUrl
if ($publicUrl) {
    Show-Ok ('公网地址：' + $publicUrl)
    Show-Info '免费版每次重启 cpolar 后域名都会变化，请以本行为准'
} else {
    Show-Warn '未发现公网地址（logs\cpolar.log 无记录，且 127.0.0.1:4040 未返回隧道信息）'
}

# ==================== 4. 日志与 PID 文件 ====================
Show-Step '日志与进程记录'
if (Test-Path -LiteralPath $LogDir) {
    $logFiles = @(Get-ChildItem -LiteralPath $LogDir -File -ErrorAction SilentlyContinue | Sort-Object Name)
    if ($logFiles.Count -gt 0) {
        foreach ($f in $logFiles) {
            $line = '  ' + $f.Name.PadRight(16) + (Format-Size -Bytes $f.Length).PadLeft(9) + '    最后写入 ' + $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
            Write-Host $line -ForegroundColor Gray
        }
    } else {
        Show-Info 'logs 目录下暂无日志文件'
    }
} else {
    Show-Warn ('logs 目录不存在：' + $LogDir)
}
if (Test-Path -LiteralPath $PidsFile) {
    Show-Ok ('PID 记录存在：' + $PidsFile)
} else {
    Show-Info ('PID 记录不存在：' + $PidsFile + '（若服务由脚本启动，请检查是否已被停止脚本清理）')
}

# ==================== 5. 前端 dist ====================
Show-Step '前端构建产物'
$distIndex = Join-Path (Join-Path $FrontendRoot 'dist') 'index.html'
$distState = Get-DistState
if ($distState -eq 'missing') {
    Show-Err 'frontend\dist\index.html 不存在：前端无法访问，请执行 start-erp.ps1（不要加 -SkipBuild）'
} elseif ($distState -eq 'stale') {
    Show-Warn ('frontend\dist 落后于 frontend\src（构建于 ' + (Get-Item -LiteralPath $distIndex).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '）：请重新执行 start-erp.ps1 构建')
} else {
    Show-Ok ('frontend\dist 已是最新（构建于 ' + (Get-Item -LiteralPath $distIndex).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '）')
}
if (Test-Path -LiteralPath $BackendLog) {
    $hit = Select-String -LiteralPath $BackendLog -Pattern 'Started PharmaApplication' -Quiet -ErrorAction SilentlyContinue
    if ($hit) { Show-Info '后端日志包含启动成功标记「Started PharmaApplication」' }
}

# ==================== 结论 ====================
Show-Banner '状态结论'
$healthy = ($backendPid -gt 0) -and ($frontendPid -gt 0) -and ($frontProbe.status -eq 200) -and ($loginProbe.code -eq 200)
if ($healthy) {
    Show-Ok 'ERP 运行正常'
} else {
    Show-Warn 'ERP 存在异常，请对照上方 [!!] / [XX] 项排查'
}
if ($backendPid -gt 0)  { Show-Info ('本地前端：http://localhost:' + $FrontendPort) }
if ($backendPid -gt 0)  { Show-Info ('本地后端：http://localhost:' + $BackendPort + '/api') }
if ($publicUrl)         { Show-Info ('公网访问：' + $publicUrl) }
Show-Info '常见排查：后端 8080 未监听 → logs\backend.log；前端白屏 → 重新构建 dist；登录 403 → 检查 CORS 配置'
Write-Host ''
if ($healthy) { exit 0 }
exit 1
