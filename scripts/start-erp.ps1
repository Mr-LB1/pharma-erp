#Requires -Version 5.1
<#
.SYNOPSIS
    pharma-erp 一键启动脚本（Windows PowerShell 5.1 专用，勿使用 pwsh 7 语法）。

.DESCRIPTION
    幂等启动 pharma-erp：
      1. 检查工具链（.tools\jdk-17 / Maven / Node）与 PostgreSQL(5432)、Redis(6379) 是否已监听；
      2. 前端 dist 缺失或落后于 frontend/src 最新文件时自动构建（可用 -SkipBuild 跳过）；
      3. 后台启动 Spring Boot 后端（8080），日志 logs\backend.log，最多等待 180 秒；
      4. 后台启动前端生产静态服务（5173），日志 logs\frontend.log；
      5. 可选 -Public 启动 cpolar 公网隧道（日志 logs\cpolar.log）并打印公网地址。
    端口已在监听时自动跳过并提示「已在运行」，不会重复拉起；PID 写入 logs\pids.json 供停止脚本使用。

.PARAMETER Public
    同时启动 cpolar 公网隧道（别名 -Tunnel），并打印公网访问地址。

.PARAMETER SkipBuild
    跳过前端 dist 自动构建（要求 dist 已存在且为最新）。

.PARAMETER BackendOnly
    只启动后端，不启动前端静态服务。

.PARAMETER FrontendOnly
    只启动前端静态服务，不启动后端。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -Public

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -SkipBuild -BackendOnly

.NOTES
    后端必须使用 .tools\jdk-17（JDK 17）；系统默认 JAVA_HOME 指向 JDK 1.8，不可用于 Spring Boot 3.3。
    本脚本不使用 Start-Process（本机会因 NO_PROXY 与 no_proxy 重复而抛错），改用 .NET 拉起独立 cmd 进程。
#>
[CmdletBinding()]
param(
    [Alias('Tunnel')]
    [switch]$Public,
    [switch]$SkipBuild,
    [switch]$BackendOnly,
    [switch]$FrontendOnly
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
$ProgressPreference = 'SilentlyContinue'

# ==================== 常量与路径（全部按脚本位置动态解析，不写死盘符） ====================
$BackendPort    = 8080
$FrontendPort   = 5173
$PgPort         = 5432
$RedisPort      = 6379
$DefaultAccount = 'admin / admin123'

$RepoRoot      = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $RepoRoot
$ToolsRoot     = Join-Path $WorkspaceRoot '.tools'
$JdkHome       = Join-Path $ToolsRoot 'jdk-17'
$MvnBin        = Join-Path (Join-Path $ToolsRoot 'apache-maven-3.9.16') 'bin'
$MvnCmd        = Join-Path $MvnBin 'mvn.cmd'
$FrontendRoot  = Join-Path $RepoRoot 'frontend'
$LogDir        = Join-Path $RepoRoot 'logs'
$PidsFile      = Join-Path $LogDir 'pids.json'
$BackendLog    = Join-Path $LogDir 'backend.log'
$FrontendLog   = Join-Path $LogDir 'frontend.log'
$CpolarLog     = Join-Path $LogDir 'cpolar.log'
$BuildLog      = Join-Path $LogDir 'build.log'
$CpolarExe     = 'C:\Program Files\cpolar\cpolar.exe'
$CpolarPage    = 'http://127.0.0.1:4040/'

# ==================== 输出辅助 ====================
function Show-Banner([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
    Write-Host ('  ' + $Text) -ForegroundColor Cyan
    Write-Host ('=' * 62) -ForegroundColor DarkCyan
}
function Show-Step([string]$Text) {
    Write-Host ''
    Write-Host ('>> ' + $Text) -ForegroundColor Cyan
}
function Show-Ok([string]$Text)   { Write-Host ('  [OK] ' + $Text) -ForegroundColor Green }
function Show-Warn([string]$Text) { Write-Host ('  [!!] ' + $Text) -ForegroundColor Yellow }
function Show-Err([string]$Text)  { Write-Host ('  [XX] ' + $Text) -ForegroundColor Red }
function Show-Info([string]$Text) { Write-Host ('  .. ' + $Text) -ForegroundColor Gray }

# ==================== 端口 / 进程辅助 ====================
# 返回端口的 LISTEN 进程 PID；未监听返回 0
function Get-PortOwnerId {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if ($null -ne $conn) { return [int]$conn.OwningProcess }
    } catch { }
    # 兜底：解析 netstat（老系统或 CIM 不可用时；EAP 临时降为 Continue，避免原生命令 stderr 触发终止错误）
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

# 轮询等待端口监听，返回 PID；超时返回 0
function Wait-PortListening {
    param([int]$Port, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $owner = Get-PortOwnerId -Port $Port
        if ($owner -gt 0) { return $owner }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    return 0
}

function Get-ProcessNameSafe {
    param([int]$ProcId)
    if ($ProcId -le 0) { return '' }
    $p = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
    if ($null -eq $p) { return '' }
    return [string]$p.ProcessName
}

# 找父进程 PID（找不到返回 0）
function Get-ParentProcessId {
    param([int]$ProcId)
    if ($ProcId -le 0) { return 0 }
    $p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $ProcId) -ErrorAction SilentlyContinue
    if ($null -eq $p) { return 0 }
    return [int]$p.ParentProcessId
}

# 在指定父进程的直属子进程里找匹配名称者
function Find-ChildProcessId {
    param([int]$ParentId, [string]$NamePattern)
    if ($ParentId -le 0) { return 0 }
    $kids = @(Get-CimInstance Win32_Process -Filter ('ParentProcessId=' + $ParentId) -ErrorAction SilentlyContinue)
    foreach ($k in $kids) {
        if ($k.Name -match $NamePattern) { return [int]$k.ProcessId }
    }
    return 0
}

# ==================== 后台拉起（不用 Start-Process） ====================
<#
  用 .NET Process 拉起独立的 cmd.exe，由 cmd 负责重定向与设置环境变量：
    cmd.exe /c "set "JAVA_HOME=..." & "exe" args > "log" 2>&1"
  这样既避开 Start-Process 的 NO_PROXY/no_proxy 重复键异常，也避开 .NET 环境变量快照；
  父 PowerShell 退出后子进程继续存活，返回值为 cmd 包装进程 PID（杀进程树时用 /T）。
#>
function Start-DetachedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$ExePath,
        [string]$ArgumentLine = '',
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [string]$JavaHome = ''
    )
    if (-not (Test-Path -LiteralPath $ExePath)) {
        throw ('找不到可执行文件：' + $ExePath)
    }
    $cmdPath = $env:ComSpec
    if (-not $cmdPath) { $cmdPath = 'C:\Windows\System32\cmd.exe' }

    $inner = ''
    if ($JavaHome) {
        $inner += 'set "JAVA_HOME=' + $JavaHome + '" & '
        $inner += 'set "PATH=' + (Join-Path $JavaHome 'bin') + ';' + $MvnBin + ';%PATH%" & '
    }
    $inner += '"' + $ExePath + '"'
    if ($ArgumentLine) { $inner += ' ' + $ArgumentLine }
    $inner += ' > "' + $LogPath + '" 2>&1'

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $cmdPath
    $psi.Arguments = '/c "' + $inner + '"'
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    return [int]$proc.Id
}

# ==================== 日志 ====================
function Show-LogTail {
    param([string]$Path, [int]$LineCount = 40)
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host ('  （日志不存在：' + $Path + '）') -ForegroundColor Yellow
        return
    }
    $size = (Get-Item -LiteralPath $Path).Length
    Write-Host ('  ---- ' + (Split-Path -Path $Path -Leaf) + ' 末尾 ' + $LineCount + ' 行（共 ' + $size + ' 字节）----') -ForegroundColor DarkGray
    $tail = @(Get-Content -LiteralPath $Path -Tail $LineCount -Encoding UTF8 -ErrorAction SilentlyContinue)
    foreach ($line in $tail) { Write-Host ('  | ' + $line) -ForegroundColor DarkGray }
}

function Test-LogContains {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $hit = Select-String -LiteralPath $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue
    if ($null -eq $hit) { return $false }
    return [bool]$hit
}

function Get-LogUrls {
    param([string]$Path)
    $result = New-Object System.Collections.ArrayList
    if (-not (Test-Path -LiteralPath $Path)) { return $result }
    $pattern = 'https://[A-Za-z0-9][A-Za-z0-9\.-]*\.cpolar\.(?:cn|top|io)'
    $lines = @(Get-Content -LiteralPath $Path -Encoding UTF8 -ErrorAction SilentlyContinue)
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        $m = [regex]::Match([string]$lines[$i], $pattern)
        if ($m.Success) { [void]$result.Add($m.Value); break }
    }
    return $result
}

# 公网地址：先查 logs\cpolar.log，再查 cpolar 本地管理页 127.0.0.1:4040
function Get-CpolarPublicUrl {
    $fromLog = @(Get-LogUrls -Path $CpolarLog)
    if ($fromLog.Count -gt 0) { return [string]($fromLog[0]) }
    try {
        $resp = Invoke-WebRequest -Uri $CpolarPage -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        $m = [regex]::Match([string]$resp.Content, 'https://[A-Za-z0-9][A-Za-z0-9\.-]*\.cpolar\.(?:cn|top|io)')
        if ($m.Success) { return $m.Value }
    } catch { }
    return ''
}

function Wait-CpolarPublicUrl {
    param([int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $url = Get-CpolarPublicUrl
        if ($url) { return $url }
        Start-Sleep -Milliseconds 800
    } while ((Get-Date) -lt $deadline)
    return ''
}

# ==================== 前端 dist 新鲜度 ====================
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
    foreach ($rel in @('index.html', 'package.json', 'vite.config.ts', 'tsconfig.json', 'public')) {
        $full = Join-Path $FrontendRoot $rel
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $item = Get-Item -LiteralPath $full
        if ($item.PSIsContainer) {
            foreach ($f in @(Get-ChildItem -LiteralPath $full -Recurse -File -ErrorAction SilentlyContinue)) { [void]$files.Add($f) }
        } else {
            [void]$files.Add($item)
        }
    }
    foreach ($f in $files) {
        if ($f.LastWriteTime -gt $newest) { $newest = $f.LastWriteTime }
    }
    # 容差 2 秒，避免构建过程中生成的 d.ts 与产物时间抖动造成误判
    if ($newest -gt $distTime.AddSeconds(2)) { return 'stale' }
    return 'fresh'
}

# ==================== PID 记录（供 stop-erp.ps1 使用） ====================
$Script:PidInfo = @{}

function Register-PidEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [int]$ProcId,
        [int]$LauncherPid,
        [int]$PortOwnerPid,
        [int]$Port,
        [string]$LogPath,
        [string]$Source
    )
    $Script:PidInfo[$Key] = @{
        pid          = [int]$ProcId
        launcherPid  = [int]$LauncherPid
        portOwnerPid = [int]$PortOwnerPid
        port         = [int]$Port
        log          = [string]$LogPath
        source       = [string]$Source
        startedAt    = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
}

function Read-PidRecord {
    if (-not (Test-Path -LiteralPath $PidsFile)) { return }
    try {
        $raw = Get-Content -LiteralPath $PidsFile -Raw -Encoding UTF8 -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace($raw)) { return }
        $obj = $raw | ConvertFrom-Json -ErrorAction Stop
        foreach ($key in @('backend', 'frontend', 'cpolar')) {
            $entry = $obj.$key
            if ($null -eq $entry) { continue }
            if ($null -eq $entry.pid) { continue }
            $Script:PidInfo[$key] = @{
                pid          = [int]$entry.pid
                launcherPid  = [int]$entry.launcherPid
                portOwnerPid = [int]$entry.portOwnerPid
                port         = [int]$entry.port
                log          = [string]$entry.log
                source       = [string]$entry.source
                startedAt    = [string]$entry.startedAt
            }
        }
    } catch {
        Show-Warn ('已有 logs\pids.json 无法解析，将重建：' + $_.Exception.Message)
    }
}

function Save-PidRecord {
    if (-not (Test-Path -LiteralPath $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    $backendEntry = $null
    if ($Script:PidInfo.ContainsKey('backend'))  { $backendEntry  = $Script:PidInfo['backend'] }
    $frontendEntry = $null
    if ($Script:PidInfo.ContainsKey('frontend')) { $frontendEntry = $Script:PidInfo['frontend'] }
    $cpolarEntry = $null
    if ($Script:PidInfo.ContainsKey('cpolar'))   { $cpolarEntry   = $Script:PidInfo['cpolar'] }

    $payload = [ordered]@{
        updatedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        repo      = $RepoRoot
        backend   = $backendEntry
        frontend  = $frontendEntry
        cpolar    = $cpolarEntry
    }
    $json = $payload | ConvertTo-Json -Depth 6
    Out-File -LiteralPath $PidsFile -InputObject $json -Encoding UTF8 -Force
}

# ==================== 前置检查 ====================
$BitFlags = 0
$DoBackend  = $true
$DoFrontend = $true
if ($BackendOnly -and $FrontendOnly) {
    Show-Err '参数冲突：-BackendOnly 与 -FrontendOnly 不能同时使用。'
    exit 2
}
if ($BackendOnly)  { $DoFrontend = $false }
if ($FrontendOnly) { $DoBackend  = $false }

Show-Banner 'pharma-erp 一键启动'
Write-Host ('  仓库根：' + $RepoRoot) -ForegroundColor DarkGray
Write-Host ('  工具链：' + $JdkHome) -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}
Read-PidRecord

# --- 1/5 基础环境 ---
Show-Step '1/5 基础环境检查'
$EnvOk = $true

if (Test-Path -LiteralPath (Join-Path (Join-Path $JdkHome 'bin') 'java.exe')) {
    Show-Ok ('JDK 17 已就绪：' + $JdkHome)
} else {
    Show-Err ('缺少 JDK 17：' + $JdkHome + '（系统默认 JAVA_HOME 指向 JDK 1.8，不能用于本项目）')
    $EnvOk = $false
}

if (Test-Path -LiteralPath $MvnCmd) {
    Show-Ok ('Maven 已就绪：' + $MvnCmd)
} else {
    Show-Err ('缺少 Maven：' + $MvnCmd)
    $EnvOk = $false
}

$NodeExe = ''
$nodeCmd = Get-Command node -ErrorAction SilentlyContinue
if ($null -ne $nodeCmd) {
    if ($nodeCmd.Path) { $NodeExe = [string]$nodeCmd.Path }
    elseif ($nodeCmd.Source) { $NodeExe = [string]$nodeCmd.Source }
}
if (-not $NodeExe) {
    foreach ($cand in @('C:\Program Files\nodejs\node.exe', (Join-Path $env:ProgramFiles 'nodejs\node.exe'))) {
        if ($cand -and (Test-Path -LiteralPath $cand)) { $NodeExe = $cand; break }
    }
}
if ($NodeExe) {
    Show-Ok ('Node.js 已就绪：' + $NodeExe)
} else {
    Show-Err '未找到 node.exe（请安装 Node.js 18+ 或将其加入 PATH）'
    $EnvOk = $false
}

$PgOwner = Get-PortOwnerId -Port $PgPort
if ($PgOwner -gt 0) {
    Show-Ok ('PostgreSQL ' + $PgPort + ' 已监听（PID ' + $PgOwner + '）')
} else {
    Show-Warn ('PostgreSQL ' + $PgPort + ' 未监听：后端会因数据库连接失败而启动失败，请先启动 PostgreSQL 17（本脚本不负责拉起）')
    $BitFlags = $BitFlags + 1
}
$RedisOwner = Get-PortOwnerId -Port $RedisPort
if ($RedisOwner -gt 0) {
    Show-Ok ('Redis ' + $RedisPort + ' 已监听（PID ' + $RedisOwner + '）')
} else {
    Show-Warn ('Redis ' + $RedisPort + ' 未监听：JWT 黑名单等能力会降级，建议先启动 Redis（本脚本不负责拉起）')
    $BitFlags = $BitFlags + 1
}

if (-not $EnvOk) {
    Show-Err '基础环境不满足，终止启动。'
    exit 1
}

# --- 2/5 前端 dist ---
$DistState = Get-DistState
Show-Step '2/5 前端静态资源（frontend\dist）'
if ($DoFrontend) {
    if ($DistState -eq 'fresh') {
        Show-Ok 'frontend\dist 已是最新，跳过构建'
    } elseif ($SkipBuild) {
        if ($DistState -eq 'missing') {
            Show-Warn 'frontend\dist 不存在且指定了 -SkipBuild：前端将无法正常访问，请去掉 -SkipBuild 重新运行'
        } else {
            Show-Warn 'frontend\dist 落后于 frontend\src 且指定了 -SkipBuild：页面可能白屏，请去掉 -SkipBuild 重新运行'
        }
    } else {
        $viteBin = Join-Path (Join-Path (Join-Path $FrontendRoot 'node_modules') 'vite') 'bin\vite.js'
        if (-not (Test-Path -LiteralPath $viteBin)) {
            Show-Err ('缺少 vite（' + $viteBin + '），请先在 frontend 目录执行 npm install')
            exit 1
        }
        if ($DistState -eq 'missing') { Show-Info 'dist 不存在，开始构建（日志 logs\build.log，约 10~30 秒）…' }
        else { Show-Info 'dist 落后于源码，开始重新构建（日志 logs\build.log，约 10~30 秒）…' }
        Push-Location -Path $FrontendRoot
        # vite 会把告警写 stderr，EAP=Stop 下直接重定向会抛终止错误，这里捕获并落盘
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $buildOut = @(& $NodeExe $viteBin build 2>&1)
            $buildCode = $LASTEXITCODE
        } catch {
            $buildOut = @('构建命令调用异常：' + $_.Exception.Message)
            $buildCode = -1
        } finally {
            $ErrorActionPreference = $prevEap
            Pop-Location
        }
        $buildText = ''
        foreach ($line in $buildOut) { $buildText = $buildText + [string]$line + "`r`n" }
        Out-File -LiteralPath $BuildLog -InputObject $buildText -Encoding UTF8 -Force
        if ($buildCode -ne 0) {
            Show-Err ('前端构建失败（退出码 ' + $buildCode + '），请查看 logs\build.log')
            Show-LogTail -Path $BuildLog -LineCount 40
            exit 1
        }
        Show-Ok '前端 dist 构建完成'
    }
} else {
    Show-Info '按 -BackendOnly 跳过前端构建'
}

# --- 3/5 后端 ---
$BackendOwner = 0
if ($DoBackend) {
    Show-Step ('3/5 后端服务（Spring Boot :' + $BackendPort + '）')
    $BackendOwner = Get-PortOwnerId -Port $BackendPort
    if ($BackendOwner -gt 0) {
        Show-Ok ('已在运行（端口 ' + $BackendPort + ' 被 PID ' + $BackendOwner + ' 占用），跳过启动')
        Register-PidEntry -Key 'backend' -ProcId $BackendOwner -LauncherPid 0 -PortOwnerPid $BackendOwner -Port $BackendPort -LogPath $BackendLog -Source 'detected'
    } else {
        $pomPath = Join-Path (Join-Path $RepoRoot 'backend') 'pom.xml'
        if (Test-Path -LiteralPath $BackendLog) { Remove-Item -LiteralPath $BackendLog -Force -ErrorAction SilentlyContinue }
        Show-Info '正在拉起 Maven（mvn spring-boot:run），通常 9~15 秒…'
        $argLine = '-f "' + $pomPath + '" spring-boot:run -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8'
        $Launcher = Start-DetachedProcess -ExePath $MvnCmd -ArgumentLine $argLine -WorkingDirectory $RepoRoot -LogPath $BackendLog -JavaHome $JdkHome
        Show-Info ('等待 8080 就绪（最多 180 秒，日志 logs\backend.log）…')
        $BackendOwner = Wait-PortListening -Port $BackendPort -TimeoutSeconds 180
        if ($BackendOwner -le 0) {
            Show-Err ('后端启动失败：' + $BackendPort + ' 端口在 180 秒内未监听')
            Show-LogTail -Path $BackendLog -LineCount 40
            Register-PidEntry -Key 'backend' -ProcId $Launcher -LauncherPid $Launcher -PortOwnerPid 0 -Port $BackendPort -LogPath $BackendLog -Source 'script'
            Save-PidRecord
            exit 1
        }
        $MvnPid = Find-ChildProcessId -ParentId $Launcher -NamePattern '^(java|cmd)\.exe$'
        if ($MvnPid -le 0) { $MvnPid = $BackendOwner }
        $LauncherPid = $Launcher
        Show-Ok ('后端已就绪（端口 ' + $BackendPort + '，PID ' + $BackendOwner + '）')
        if (Test-LogContains -Path $BackendLog -Pattern 'Started PharmaApplication') {
            Show-Ok '日志已出现 "Started PharmaApplication"'
        } else {
            Show-Info '端口已监听（日志标记「Started PharmaApplication」暂未出现，属正常现象）'
        }
        Register-PidEntry -Key 'backend' -ProcId $MvnPid -LauncherPid $LauncherPid -PortOwnerPid $BackendOwner -Port $BackendPort -LogPath $BackendLog -Source 'script'
    }
} else {
    Show-Step ('3/5 后端服务（Spring Boot :' + $BackendPort + '）')
    Show-Info '按 -FrontendOnly 跳过后端'
}

# --- 4/5 前端静态服务 ---
$FrontendOwner = 0
if ($DoFrontend) {
    Show-Step ('4/5 前端静态服务（:' + $FrontendPort + '）')
    $FrontendOwner = Get-PortOwnerId -Port $FrontendPort
    if ($FrontendOwner -gt 0) {
        Show-Ok ('已在运行（端口 ' + $FrontendPort + ' 被 PID ' + $FrontendOwner + ' 占用），跳过启动')
        Register-PidEntry -Key 'frontend' -ProcId $FrontendOwner -LauncherPid 0 -PortOwnerPid $FrontendOwner -Port $FrontendPort -LogPath $FrontendLog -Source 'detected'
    } else {
        $serveScript = Join-Path (Join-Path $FrontendRoot 'scripts') 'serve-dist.mjs'
        if (-not (Test-Path -LiteralPath $serveScript)) {
            Show-Err ('缺少前端静态服务脚本：' + $serveScript)
            Save-PidRecord
            exit 1
        }
        if (Test-Path -LiteralPath $FrontendLog) { Remove-Item -LiteralPath $FrontendLog -Force -ErrorAction SilentlyContinue }
        $Launcher = Start-DetachedProcess -ExePath $NodeExe -ArgumentLine ('"' + $serveScript + '"') -WorkingDirectory $RepoRoot -LogPath $FrontendLog
        $FrontendOwner = Wait-PortListening -Port $FrontendPort -TimeoutSeconds 60
        if ($FrontendOwner -le 0) {
            Show-Err ('前端服务启动失败：' + $FrontendPort + ' 端口在 60 秒内未监听')
            Show-LogTail -Path $FrontendLog -LineCount 40
            Register-PidEntry -Key 'frontend' -ProcId $Launcher -LauncherPid $Launcher -PortOwnerPid 0 -Port $FrontendPort -LogPath $FrontendLog -Source 'script'
            Save-PidRecord
            exit 1
        }
        Show-Ok ('前端已就绪（端口 ' + $FrontendPort + '，PID ' + $FrontendOwner + '）')
        Register-PidEntry -Key 'frontend' -ProcId $FrontendOwner -LauncherPid $Launcher -PortOwnerPid $FrontendOwner -Port $FrontendPort -LogPath $FrontendLog -Source 'script'
    }
} else {
    Show-Step ('4/5 前端静态服务（:' + $FrontendPort + '）')
    Show-Info '按 -BackendOnly 跳过前端静态服务'
}

# --- 5/5 公网隧道 ---
$PublicUrl = ''
if ($Public) {
    if (-not $DoFrontend) {
        Show-Step '5/5 公网隧道（cpolar）'
        Show-Warn '-Public 需要前端静态服务（:5173）作为隧道目标，-BackendOnly 模式下已忽略'
    } else {
        Show-Step ('5/5 公网隧道（cpolar → 127.0.0.1:' + $FrontendPort + '）')
        if (-not (Test-Path -LiteralPath $CpolarExe)) {
            Show-Warn ('未找到 cpolar：' + $CpolarExe + '，跳过公网隧道')
        } else {
            $cpolarProcs = @(Get-Process -Name 'cpolar' -ErrorAction SilentlyContinue)
            if ($cpolarProcs.Count -gt 0) {
                $cpolarPid = [int]$cpolarProcs[0].Id
                $PublicUrl = Get-CpolarPublicUrl
                Show-Ok ('cpolar 已在运行（PID ' + $cpolarPid + '），跳过启动')
                Register-PidEntry -Key 'cpolar' -ProcId $cpolarPid -LauncherPid 0 -PortOwnerPid 0 -Port $FrontendPort -LogPath $CpolarLog -Source 'detected'
            } else {
                if (Test-Path -LiteralPath $CpolarLog) { Remove-Item -LiteralPath $CpolarLog -Force -ErrorAction SilentlyContinue }
                $Launcher = Start-DetachedProcess -ExePath $CpolarExe -ArgumentLine ('http ' + $FrontendPort + ' -log=stdout') -WorkingDirectory $RepoRoot -LogPath $CpolarLog
                Show-Info '等待 cpolar 分配公网域名（最多 40 秒）…'
                $PublicUrl = Wait-CpolarPublicUrl -TimeoutSeconds 40
                $CpolarChild = Find-ChildProcessId -ParentId $Launcher -NamePattern '^cpolar'
                if ($CpolarChild -le 0) { $CpolarChild = $Launcher }
                if ($PublicUrl) {
                    Show-Ok ('公网地址：' + $PublicUrl)
                } else {
                    Show-Warn '未能从日志抓到公网地址，请查看 logs\cpolar.log（或浏览器访问 http://127.0.0.1:4040/）'
                    Show-LogTail -Path $CpolarLog -LineCount 20
                }
                Register-PidEntry -Key 'cpolar' -ProcId $CpolarChild -LauncherPid $Launcher -PortOwnerPid 0 -Port $FrontendPort -LogPath $CpolarLog -Source 'script'
            }
        }
    }
} else {
    Show-Step '5/5 公网隧道（cpolar）'
    Show-Info '未指定 -Public，跳过公网隧道（需要外网访问时加 -Public）'
}

Save-PidRecord

# --- 汇总 ---
$BackendOwner  = Get-PortOwnerId -Port $BackendPort
$FrontendOwner = Get-PortOwnerId -Port $FrontendPort

Show-Banner '启动汇总'
if ($DoFrontend) {
    if ($FrontendOwner -gt 0) {
        Show-Ok ('本地前端：http://localhost:' + $FrontendPort + '     PID ' + $FrontendOwner)
    } else {
        Show-Err ('本地前端：端口 ' + $FrontendPort + ' 未监听')
    }
}
if ($DoBackend) {
    if ($BackendOwner -gt 0) {
        Show-Ok ('本地后端：http://localhost:' + $BackendPort + '/api     PID ' + $BackendOwner)
    } else {
        Show-Err ('本地后端：端口 ' + $BackendPort + ' 未监听')
    }
}
if ($Public) {
    if ($PublicUrl) {
        Show-Ok ('公网访问：' + $PublicUrl)
    } else {
        Show-Warn '公网访问：未取得地址（见 logs\cpolar.log）'
    }
} else {
    Show-Info '公网访问：未启动（加 -Public 开启 cpolar 隧道）'
}
$PgOwner = Get-PortOwnerId -Port $PgPort
if ($PgOwner -gt 0) { Show-Info ('数据库：PostgreSQL ' + $PgPort + ' 已监听（PID ' + $PgOwner + '）') }
else { Show-Warn ('数据库：PostgreSQL ' + $PgPort + ' 未监听！') }
$RedisOwner = Get-PortOwnerId -Port $RedisPort
if ($RedisOwner -gt 0) { Show-Info ('缓存：Redis ' + $RedisPort + ' 已监听（PID ' + $RedisOwner + '）') }
else { Show-Warn ('缓存：Redis ' + $RedisPort + ' 未监听！') }
Show-Info ('日志目录：' + $LogDir + '（backend.log / frontend.log / cpolar.log）')
Show-Info ('PID 记录：' + $PidsFile)
Show-Info ('默认账号：' + $DefaultAccount)
Write-Host ''
Write-Host '  停止：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1' -ForegroundColor DarkGray
Write-Host '  状态：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1' -ForegroundColor DarkGray
Write-Host ''
exit 0
