$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainScript = Join-Path $scriptDir "src/main.mjs"
$pidFile = Join-Path $scriptDir ".bot.pid"
$logFile = Join-Path $scriptDir ".bot.log"
$stateFile = Join-Path $scriptDir ".bot-state.json"

if (-not (Test-Path $mainScript)) {
    throw "Cannot find daemon entrypoint: $mainScript"
}

function Find-ExistingBotProcess {
    try {
        $proc = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction Stop |
            Where-Object {
                $cmd = [string]$_.CommandLine
                $cmd -and $cmd -like "*match-bot*src*main.mjs*" -and $cmd -like "*--run*"
            } |
            Sort-Object ProcessId |
            Select-Object -First 1
        return $proc
    } catch {
        return $null
    }
}

if (Test-Path $pidFile) {
    $existingPidRaw = (Get-Content -Path $pidFile -Raw).Trim()
    if ($existingPidRaw) {
        $existingProc = Get-Process -Id $existingPidRaw -ErrorAction SilentlyContinue
        if ($existingProc) {
            Write-Host "Bot already running. PID=$existingPidRaw"
            exit 0
        }
    }
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
}

$existingDetached = Find-ExistingBotProcess
if ($existingDetached) {
    $detachedPid = [string]$existingDetached.ProcessId
    Set-Content -Path $pidFile -Value $detachedPid -NoNewline
    Write-Host "Bot already running. PID=$detachedPid (recovered from process scan)"
    exit 0
}

Write-Host "Running startup cleanup (self-heal)..."
node $mainScript --cleanup-only
$cleanupExit = $LASTEXITCODE
if ($cleanupExit -ne 0) {
    throw "Cleanup failed with exit code $cleanupExit. Bot start aborted."
}

if (-not (Test-Path $logFile)) {
    New-Item -ItemType File -Path $logFile -Force | Out-Null
}

$launchCommand = "Set-Location -LiteralPath '$scriptDir'; node `"$mainScript`" --run >> `"$logFile`" 2>&1"
$proc = Start-Process `
    -FilePath "powershell" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $launchCommand `
    -WorkingDirectory $scriptDir `
    -WindowStyle Hidden `
    -PassThru

Set-Content -Path $pidFile -Value $proc.Id -NoNewline

$publicId = ""
for ($i = 0; $i -lt 80; $i++) {
    Start-Sleep -Milliseconds 250
    if (Test-Path $stateFile) {
        try {
            $state = Get-Content -Path $stateFile -Raw | ConvertFrom-Json
            if ($state.publicId) {
                $publicId = [string]$state.publicId
                break
            }
        } catch {
            # keep waiting
        }
    }
}

if ($publicId) {
    Write-Host "Bot started. PID=$($proc.Id) GuestID=$publicId"
    exit 0
}

Write-Host "Bot started. PID=$($proc.Id). Guest ID not ready yet."
Write-Host "Check logs: $logFile"
exit 0
