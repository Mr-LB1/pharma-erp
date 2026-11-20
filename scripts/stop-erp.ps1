#Requires -Version 5.1
<#
.SYNOPSIS
    pharma-erp 一键停止脚本（Windows PowerShell 5.1）。

.DESCRIPTION
    按以下顺序停止 pharma-erp 运行中的进程：
      1. 读取 logs\pids.json，按记录的启动器/进程 PID 杀进程树（taskkill /T /F）；
      2. 兜底按端口 8080 / 5173 的 OwningProcess 找到进程并杀进程树；
      3. 兜底按命令行特征清理残留（mvn spring-boot:run / serve-dist.mjs / cpolar http 5173）；
      4. 关闭 cpolar 公网隧道进程。
    不会停止 PostgreSQL(5432) 与 Redis(6379)，也不会停止其它无关进程。

.PARAMETER DryRun
    演练模式：只打印将要停止的服务与 PID，不真正结束进程（用于确认影响范围）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

# ==================== 路径与常量 ====================
$BackendPort  = 8080
$FrontendPort = 5173
$PgPort       = 5432
$RedisPort    = 6379

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LogDir   = Join-Path $RepoRoot 'logs'
$PidsFile = Join-Path $LogDir 'pids.json'

$Script:Stopped = New-Object System.Collections.ArrayList

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

# ==================== 进程 / 端口辅助 ====================
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

# 结束进程（含子进程）；$AllowedNames 非空时先校验进程名，避免 PID 复用误杀
function Stop-ProcessTree {
    param(
        [int]$ProcId,
        [string]$Label,
        [string[]]$AllowedNames = @()
    )
    if ($ProcId -le 0) { return $false }
    $proc = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        Show-Info ($Label + '（PID ' + $ProcId + '）已不存在')
        return $false
    }
    if ($AllowedNames.Count -gt 0) {
        $hit = $false
        foreach ($n in $AllowedNames) {
            if ($proc.ProcessName -eq $n) { $hit = $true; break }
        }
        if (-not $hit) {
            Show-Warn ('PID ' + $ProcId + ' 当前进程是 ' + $proc.ProcessName + '，与「' + $Label + '」不符，跳过以免误杀')
            return $false
        }
    }
    if ($DryRun) {
        Show-Info ('[演练] 将结束 ' + $Label + '（PID ' + $ProcId + '，进程 ' + $proc.ProcessName + '）及其子进程')
        [void]$Script:Stopped.Add('[演练] ' + $Label + ' PID=' + $ProcId + '（' + $proc.ProcessName + '）')
        return $true
    }
    # taskkill 失败时会把错误写到 stderr，EAP=Stop 下会抛终止错误，这里临时降级并捕获
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = @()
    try {
        $output = @(& taskkill.exe /PID $ProcId /T /F 2>&1)
        $code = $LASTEXITCODE
    } catch {
        $output = @('taskkill 调用异常：' + $_.Exception.Message)
        $code = -1
    } finally {
        $ErrorActionPreference = $prevEap
    }
    Start-Sleep -Milliseconds 400
    $still = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
    if ($null -eq $still) {
        Show-Ok ('已停止 ' + $Label + '（PID ' + $ProcId + '）')
        [void]$Script:Stopped.Add($Label + ' PID=' + $ProcId)
        return $true
    }
    Show-Warn ('结束失败（taskkill 退出码 ' + $code + '）：' + $Label + ' PID ' + $ProcId)
    foreach ($line in @($output)) {
        if ($line -and ([string]$line).Trim().Length -gt 0) { Show-Info ('  ' + $line) }
    }
    return $false
}

# 按「进程名 + 命令行特征」兜底清理，只匹配明确属于本项目的进程
function Stop-ByCommandLine {
    param(
        [string]$NamePattern,
        [string]$CmdPattern,
        [string]$Label
    )
    $procs = @(Get-CimInstance Win32_Process -Filter ('Name=''' + $NamePattern + '''') -ErrorAction SilentlyContinue)
    foreach ($p in $procs) {
        if ([string]::IsNullOrEmpty($p.CommandLine)) { continue }
        if ($p.CommandLine -match $CmdPattern) {
            [void](Stop-ProcessTree -ProcId ([int]$p.ProcessId) -Label $Label -AllowedNames @())
        }
    }
}

# ==================== 主流程 ====================
Show-Banner 'pharma-erp 一键停止'
Write-Host ('  仓库根：' + $RepoRoot) -ForegroundColor DarkGray
if ($DryRun) {
    Write-Host '  模式：演练（-DryRun），不会真正结束进程' -ForegroundColor Yellow
}

# 记录本次计划处理的对象，用于最终汇总
$Targets = @()

# 1) 读取 logs\pids.json
if (Test-Path -LiteralPath $PidsFile) {
    Show-Step '1/3 读取 logs\pids.json'
    try {
        $raw = Get-Content -LiteralPath $PidsFile -Raw -Encoding UTF8 -ErrorAction Stop
        $pids = $raw | ConvertFrom-Json -ErrorAction Stop
        $defs = @(
            @{ Key = 'cpolar';   Label = 'cpolar 公网隧道'; Names = @('cpolar') },
            @{ Key = 'frontend'; Label = '前端静态服务';   Names = @('node') },
            @{ Key = 'backend';  Label = '后端 Spring Boot'; Names = @('java', 'cmd') }
        )
        foreach ($def in $defs) {
            $entry = $pids.$($def.Key)
            if ($null -eq $entry) { continue }
            $launcherPid = 0
            if ($null -ne $entry.launcherPid) { $launcherPid = [int]$entry.launcherPid }
            $mainPid = 0
            if ($null -ne $entry.pid) { $mainPid = [int]$entry.pid }
            # 先杀启动器（cmd 包装进程）可整树带走 Maven/Java/Node
            if ($launcherPid -gt 0) {
                [void](Stop-ProcessTree -ProcId $launcherPid -Label ($def.Label + ' 启动器') -AllowedNames @('cmd'))
            }
            if ($mainPid -gt 0 -and $mainPid -ne $launcherPid) {
                [void](Stop-ProcessTree -ProcId $mainPid -Label $def.Label -AllowedNames $def.Names)
            }
        }
    } catch {
        Show-Warn ('logs\pids.json 解析失败，改用端口兜底：' + $_.Exception.Message)
    }
} else {
    Show-Step '1/3 读取 logs\pids.json'
    Show-Info '未找到 logs\pids.json（可能不是脚本启动的），改用端口兜底'
}

# 2) 端口兜底
Show-Step '2/3 按端口兜底（8080 / 5173）'
$backendOwner  = Get-PortOwnerId -Port $BackendPort
$frontendOwner = Get-PortOwnerId -Port $FrontendPort
if ($backendOwner -gt 0) {
    [void](Stop-ProcessTree -ProcId $backendOwner -Label '后端 Spring Boot（8080 端口占用者）' -AllowedNames @('java', 'cmd'))
} else {
    Show-Info ('端口 ' + $BackendPort + ' 已无监听进程')
}
if ($frontendOwner -gt 0) {
    [void](Stop-ProcessTree -ProcId $frontendOwner -Label '前端静态服务（5173 端口占用者）' -AllowedNames @('node', 'cmd'))
} else {
    Show-Info ('端口 ' + $FrontendPort + ' 已无监听进程')
}

# 3) 命令行兜底 + cpolar
Show-Step '3/3 清理残留进程与 cpolar 隧道'
Stop-ByCommandLine -NamePattern 'java.exe' -CmdPattern 'spring-boot:run|com\.pharma\.PharmaApplication' -Label '后端 Java 进程（命令行匹配）'
Stop-ByCommandLine -NamePattern 'node.exe' -CmdPattern 'serve-dist\.mjs' -Label '前端 Node 进程（命令行匹配）'
Stop-ByCommandLine -NamePattern 'cpolar.exe' -CmdPattern 'http\s+5173' -Label 'cpolar 公网隧道（命令行匹配）'

# 兜底：还有 cpolar 进程没被清理（命令行信息取不到时）
$leftCpolar = @(Get-Process -Name 'cpolar' -ErrorAction SilentlyContinue)
foreach ($cp in $leftCpolar) {
    [void](Stop-ProcessTree -ProcId ([int]$cp.Id) -Label 'cpolar 进程（兜底）' -AllowedNames @('cpolar'))
}

# 清理 pids.json（下次启动重新记录）
if (Test-Path -LiteralPath $PidsFile) {
    if ($DryRun) {
        Show-Info '[演练] 将删除 logs\pids.json'
    } else {
        Remove-Item -LiteralPath $PidsFile -Force -ErrorAction SilentlyContinue
        Show-Info '已删除 logs\pids.json'
    }
}

# ==================== 汇总 ====================
Show-Banner '停止汇总'
if ($Script:Stopped.Count -eq 0) {
    if ($DryRun) {
        Show-Info '没有需要停止的进程（演练未执行任何操作）'
    } else {
        Show-Info '没有需要停止的进程（服务本来就没在运行）'
    }
} else {
    if ($DryRun) {
        foreach ($s in $Script:Stopped) { Show-Info ('将停止：' + $s) }
    } else {
        foreach ($s in $Script:Stopped) { Show-Ok ('已停止：' + $s) }
    }
}
$pgOwner = Get-PortOwnerId -Port $PgPort
if ($pgOwner -gt 0) { Show-Info ('PostgreSQL ' + $PgPort + ' 未受影响（PID ' + $pgOwner + '，本脚本不停止数据库）') }
$redisOwner = Get-PortOwnerId -Port $RedisPort
if ($redisOwner -gt 0) { Show-Info ('Redis ' + $RedisPort + ' 未受影响（PID ' + $redisOwner + '，本脚本不停止缓存）') }
$b = Get-PortOwnerId -Port $BackendPort
$f = Get-PortOwnerId -Port $FrontendPort
if ($b -eq 0 -and $f -eq 0) { Show-Ok '端口 8080 / 5173 均已释放' }
else {
    if ($b -gt 0) { Show-Warn ('端口 ' + $BackendPort + ' 仍被 PID ' + $b + ' 占用') }
    if ($f -gt 0) { Show-Warn ('端口 ' + $FrontendPort + ' 仍被 PID ' + $f + ' 占用') }
}
Write-Host ''
exit 0
