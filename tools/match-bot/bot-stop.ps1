$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainScript = Join-Path $scriptDir "src/main.mjs"
$pidFile = Join-Path $scriptDir ".bot.pid"

if (-not (Test-Path $mainScript)) {
    throw "Cannot find daemon entrypoint: $mainScript"
}

function Find-BotRunProcessIds {
    try {
        $rows = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction Stop |
            Where-Object {
                $cmd = [string]$_.CommandLine
                $cmd -and $cmd -like "*match-bot*src*main.mjs*" -and $cmd -like "*--run*"
            }
        return @($rows | ForEach-Object { [int]$_.ProcessId } | Sort-Object -Unique)
    } catch {
        return @()
    }
}

Write-Host "Running cleanup before stop..."
node $mainScript --cleanup-only
$cleanupExit = $LASTEXITCODE

$pidValue = ""
if (Test-Path $pidFile) {
    $pidValue = (Get-Content -Path $pidFile -Raw).Trim()
}

if ($pidValue) {
    $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "Stopping daemon process PID=$pidValue"
        Stop-Process -Id $pidValue -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 400
        $procStillRunning = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
        if ($procStillRunning) {
            Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 200
        }
    }
}

$scanPids = Find-BotRunProcessIds
foreach ($scanPid in $scanPids) {
    if ($pidValue -and [string]$scanPid -eq [string]$pidValue) {
        continue
    }
    $proc = Get-Process -Id $scanPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "Stopping daemon process PID=$scanPid (found via process scan)"
        Stop-Process -Id $scanPid -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 400
        $procStillRunning = Get-Process -Id $scanPid -ErrorAction SilentlyContinue
        if ($procStillRunning) {
            Stop-Process -Id $scanPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 200
        }
    }
}

if (Test-Path $pidFile) {
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
}

if ($pidValue) {
    $stillAlive = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($stillAlive) {
        Write-Error "Bot process is still running (PID=$pidValue)."
        exit 21
    }
}

$remaining = Find-BotRunProcessIds
if ($remaining.Count -gt 0) {
    Write-Error "Bot process is still running (PID=$([string]::Join(', ', $remaining)))."
    exit 21
}

Write-Host "Running cleanup after process stop verification..."
node $mainScript --cleanup-only
$postStopCleanupExit = $LASTEXITCODE

if ($postStopCleanupExit -ne 0) {
    Write-Error "Cleanup failed after stop (exit code $postStopCleanupExit)."
    exit $postStopCleanupExit
}

if ($cleanupExit -ne 0) {
    Write-Warning "Initial pre-stop cleanup failed with code $cleanupExit, but post-stop cleanup succeeded."
}

Write-Host "Bot stopped and cleanup completed."
exit 0
