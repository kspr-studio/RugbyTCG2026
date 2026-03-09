param(
    [switch]$Once,
    [int]$RefreshMs = 500,
    [int]$ReasoningLines = 10,
    [switch]$ShowAllLogs
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainScript = Join-Path $scriptDir "src/main.mjs"
$pidFile = Join-Path $scriptDir ".bot.pid"
$logFile = Join-Path $scriptDir ".bot.log"

if (-not (Test-Path $mainScript)) {
    throw "Cannot find daemon entrypoint: $mainScript"
}

function Get-RunningState {
    param(
        [string]$PidPath,
        [string]$MainScriptPath
    )

    $pidText = ""
    if (Test-Path $PidPath) {
        try {
            $pidText = (Get-Content -Path $PidPath -Raw -ErrorAction Stop).Trim()
        } catch {
            $pidText = ""
        }
    }

    $isRunning = $false
    $source = "none"
    $pidFromFile = $false
    if ($pidText) {
        $pidInt = 0
        if ([int]::TryParse($pidText, [ref]$pidInt)) {
            $proc = Get-Process -Id $pidInt -ErrorAction SilentlyContinue
            if ($proc) {
                $isRunning = $true
                $source = "pid_file"
                $pidText = [string]$pidInt
                $pidFromFile = $true
            }
        }
    }

    if (-not $isRunning -and -not [string]::IsNullOrWhiteSpace($MainScriptPath)) {
        try {
            $fallback = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction Stop |
                Where-Object {
                    $cmd = [string]$_.CommandLine
                    $cmd -and $cmd -like "*match-bot*src*main.mjs*" -and $cmd -like "*--run*"
                } |
                Sort-Object ProcessId |
                Select-Object -First 1
            if ($fallback) {
                $isRunning = $true
                $pidText = [string]$fallback.ProcessId
                $source = "process_scan"
            }
        } catch {
            # best effort only
        }
    }

    return [pscustomobject]@{
        PidText = $pidText
        IsRunning = $isRunning
        Source = $source
        PidFilePresent = $pidFromFile
    }
}

function Get-StatusSnapshot {
    param([string]$MainScriptPath)

    $status = $null
    $errorText = ""
    try {
        $output = & node $MainScriptPath --status-json 2>$null
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            $errorText = "status-json failed (exit $exitCode)"
        } else {
            $jsonText = ($output | Out-String).Trim()
            if ([string]::IsNullOrWhiteSpace($jsonText)) {
                $errorText = "status-json returned empty output"
            } else {
                try {
                    $status = $jsonText | ConvertFrom-Json -ErrorAction Stop
                } catch {
                    $errorText = "invalid status-json payload"
                }
            }
        }
    } catch {
        $errorText = $_.Exception.Message
    }

    return [pscustomobject]@{
        Status = $status
        ErrorText = $errorText
    }
}

function Format-EpochMillis {
    param([object]$EpochMsValue)

    $epochMs = 0L
    if (-not [long]::TryParse([string]$EpochMsValue, [ref]$epochMs)) {
        return "(never)"
    }
    if ($epochMs -le 0) {
        return "(never)"
    }
    try {
        return [DateTimeOffset]::FromUnixTimeMilliseconds($epochMs).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
    } catch {
        return "(invalid)"
    }
}

function Add-RingLine {
    param(
        [System.Collections.Generic.List[string]]$Buffer,
        [string]$Line,
        [int]$MaxLines
    )

    if ([string]::IsNullOrWhiteSpace($Line)) {
        return
    }
    $Buffer.Add($Line)
    while ($Buffer.Count -gt $MaxLines) {
        $Buffer.RemoveAt(0)
    }
}

function Get-ObjectStringValue {
    param(
        [object]$Obj,
        [string]$Name,
        [string]$Default = ""
    )

    if ($null -eq $Obj) {
        return $Default
    }
    $prop = $Obj.PSObject.Properties[$Name]
    if ($null -eq $prop) {
        return $Default
    }
    if ($null -eq $prop.Value) {
        return $Default
    }
    return [string]$prop.Value
}

function Format-LogTimestamp {
    param([string]$RawTimestamp)

    if ([string]::IsNullOrWhiteSpace($RawTimestamp)) {
        return ""
    }
    try {
        return [DateTimeOffset]::Parse($RawTimestamp).ToLocalTime().ToString("HH:mm:ss")
    } catch {
        return $RawTimestamp
    }
}

function Should-IncludeEvent {
    param(
        [string]$EventName,
        [switch]$IncludeAll
    )

    if ($IncludeAll) {
        return $true
    }
    if ([string]::IsNullOrWhiteSpace($EventName)) {
        return $false
    }
    if ($EventName.StartsWith("decision:", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    return $EventName -in @(
        "transport:action_accepted",
        "transport:action_rejected",
        "match:unsupported_role_forfeit",
        "match:join_rejected",
        "state:turn_owner_mismatch",
        "state:turn_owner_patched",
        "state:state_incomplete_resync",
        "state:state_incomplete_fallback"
    )
}

function Get-FirstStep {
    param([object]$Payload)

    if ($null -eq $Payload) {
        return $null
    }
    $stepsProp = $Payload.PSObject.Properties["steps"]
    if ($null -eq $stepsProp -or $null -eq $stepsProp.Value) {
        return $null
    }
    $stepsValue = $stepsProp.Value
    if ($stepsValue -is [string]) {
        return $null
    }
    if ($stepsValue -is [System.Collections.IEnumerable]) {
        foreach ($step in $stepsValue) {
            return $step
        }
    }
    return $null
}

function Format-EventLine {
    param(
        [string]$Timestamp,
        [string]$EventName,
        [object]$Payload,
        [string]$RawLine,
        [switch]$IncludeAll
    )

    if (-not (Should-IncludeEvent -EventName $EventName -IncludeAll:$IncludeAll)) {
        return $null
    }

    $timePart = Format-LogTimestamp -RawTimestamp $Timestamp
    $prefix = if ([string]::IsNullOrWhiteSpace($timePart)) { $EventName } else { "$timePart $EventName" }

    $eventKey = ""
    if ($null -ne $EventName) {
        $eventKey = $EventName.ToLowerInvariant()
    }
    switch ($eventKey) {
        "decision:submit" {
            $actionType = Get-ObjectStringValue -Obj $Payload -Name "actionType" -Default "unknown"
            $reason = Get-ObjectStringValue -Obj $Payload -Name "reason" -Default ""
            if ($reason) {
                return "$prefix action=$actionType reason=$reason"
            }
            return "$prefix action=$actionType"
        }
        "decision:turn_plan" {
            $summary = Get-ObjectStringValue -Obj $Payload -Name "summary" -Default ""
            $lastSeq = Get-ObjectStringValue -Obj $Payload -Name "lastSeq" -Default ""
            $firstStep = Get-FirstStep -Payload $Payload
            $firstAction = Get-ObjectStringValue -Obj $firstStep -Name "action" -Default ""
            $firstReason = Get-ObjectStringValue -Obj $firstStep -Name "reason" -Default ""

            $parts = New-Object System.Collections.Generic.List[string]
            if ($lastSeq) {
                $parts.Add("seq=$lastSeq")
            }
            if ($summary) {
                $parts.Add("summary=$summary")
            }
            if ($firstAction) {
                if ($firstReason) {
                    $parts.Add("first=$firstAction($firstReason)")
                } else {
                    $parts.Add("first=$firstAction")
                }
            }
            if ($parts.Count -eq 0) {
                return $prefix
            }
            return "$prefix $([string]::Join(' ', $parts))"
        }
        "transport:action_accepted" {
            $actionType = Get-ObjectStringValue -Obj $Payload -Name "actionType" -Default "unknown"
            $seq = Get-ObjectStringValue -Obj $Payload -Name "seq" -Default ""
            $turnOwner = Get-ObjectStringValue -Obj $Payload -Name "turnOwner" -Default ""
            $parts = New-Object System.Collections.Generic.List[string]
            $parts.Add("action=$actionType")
            if ($seq) {
                $parts.Add("seq=$seq")
            }
            if ($turnOwner) {
                $parts.Add("turn=$turnOwner")
            }
            return "$prefix $([string]::Join(' ', $parts))"
        }
        "transport:action_rejected" {
            $actionType = Get-ObjectStringValue -Obj $Payload -Name "actionType" -Default "unknown"
            $reason = Get-ObjectStringValue -Obj $Payload -Name "reason" -Default ""
            if ($reason) {
                return "$prefix action=$actionType reason=$reason"
            }
            return "$prefix action=$actionType"
        }
        "match:unsupported_role_forfeit" {
            $role = Get-ObjectStringValue -Obj $Payload -Name "role" -Default "unknown"
            return "$prefix role=$role"
        }
        "match:join_rejected" {
            $reason = Get-ObjectStringValue -Obj $Payload -Name "reason" -Default ""
            if ($reason) {
                return "$prefix reason=$reason"
            }
            return $prefix
        }
        "state:turn_owner_mismatch" {
            $server = Get-ObjectStringValue -Obj $Payload -Name "serverTurnOwner" -Default "?"
            $canonical = Get-ObjectStringValue -Obj $Payload -Name "canonicalTurnOwner" -Default "?"
            $lastSeq = Get-ObjectStringValue -Obj $Payload -Name "lastSeq" -Default ""
            if ($lastSeq) {
                return "$prefix server=$server canonical=$canonical seq=$lastSeq"
            }
            return "$prefix server=$server canonical=$canonical"
        }
        "state:turn_owner_patched" {
            $from = Get-ObjectStringValue -Obj $Payload -Name "canonicalTurnOwner" -Default "?"
            $to = Get-ObjectStringValue -Obj $Payload -Name "patchedTurnOwner" -Default "?"
            $lastSeq = Get-ObjectStringValue -Obj $Payload -Name "lastSeq" -Default ""
            if ($lastSeq) {
                return "$prefix from=$from to=$to seq=$lastSeq"
            }
            return "$prefix from=$from to=$to"
        }
        "state:state_incomplete_resync" {
            $lastSeq = Get-ObjectStringValue -Obj $Payload -Name "lastSeq" -Default ""
            if ($lastSeq) {
                return "$prefix seq=$lastSeq"
            }
            return $prefix
        }
        "state:state_incomplete_fallback" {
            $lastSeq = Get-ObjectStringValue -Obj $Payload -Name "lastSeq" -Default ""
            $streak = Get-ObjectStringValue -Obj $Payload -Name "streak" -Default ""
            $parts = New-Object System.Collections.Generic.List[string]
            if ($lastSeq) {
                $parts.Add("seq=$lastSeq")
            }
            if ($streak) {
                $parts.Add("streak=$streak")
            }
            if ($parts.Count -eq 0) {
                return $prefix
            }
            return "$prefix $([string]::Join(' ', $parts))"
        }
        default {
            if ($IncludeAll) {
                return $RawLine
            }
            return $RawLine
        }
    }
}

function Convert-LogLineToDisplay {
    param(
        [string]$Line,
        [switch]$IncludeAll
    )

    if ([string]::IsNullOrWhiteSpace($Line)) {
        return $null
    }

    $pattern = "^(?<timestamp>\S+)\s+\[(?<level>[A-Z]+)\]\s+(?<event>\S+)(?:\s+(?<json>\{.*\}))?$"
    $parsed = [regex]::Match($Line, $pattern)
    if (-not $parsed.Success) {
        if ($IncludeAll) {
            return $Line
        }
        if ($Line -match "\sdecision:\S+" -or
            $Line -match "transport:action_(accepted|rejected)" -or
            $Line -match "match:(unsupported_role_forfeit|join_rejected)" -or
            $Line -match "state:turn_owner_(mismatch|patched)" -or
            $Line -match "state:state_incomplete_(resync|fallback)") {
            return $Line
        }
        return $null
    }

    $eventName = $parsed.Groups["event"].Value
    $jsonRaw = $parsed.Groups["json"].Value
    $payload = $null
    if (-not [string]::IsNullOrWhiteSpace($jsonRaw)) {
        try {
            $payload = $jsonRaw | ConvertFrom-Json -ErrorAction Stop
        } catch {
            if (-not $IncludeAll -and -not (Should-IncludeEvent -EventName $eventName -IncludeAll:$IncludeAll)) {
                return $null
            }
            return $Line
        }
    }

    return Format-EventLine `
        -Timestamp $parsed.Groups["timestamp"].Value `
        -EventName $eventName `
        -Payload $payload `
        -RawLine $Line `
        -IncludeAll:$IncludeAll
}

function Initialize-ReasoningBuffer {
    param(
        [string]$LogPath,
        [System.Collections.Generic.List[string]]$Buffer,
        [int]$MaxLines,
        [switch]$IncludeAll,
        [ref]$Offset,
        [ref]$PartialLine
    )

    $Offset.Value = 0L
    $PartialLine.Value = ""

    if (-not (Test-Path $LogPath)) {
        return
    }

    try {
        $raw = Get-Content -Path $LogPath -Raw -ErrorAction Stop
        if (-not [string]::IsNullOrEmpty($raw)) {
            $lines = $raw -split "`r?`n"
            foreach ($line in $lines) {
                $display = Convert-LogLineToDisplay -Line $line -IncludeAll:$IncludeAll
                Add-RingLine -Buffer $Buffer -Line $display -MaxLines $MaxLines
            }
        }
        $Offset.Value = [int64](Get-Item -LiteralPath $LogPath).Length
    } catch {
        $Offset.Value = 0L
        $PartialLine.Value = ""
    }
}

function Update-ReasoningBuffer {
    param(
        [string]$LogPath,
        [System.Collections.Generic.List[string]]$Buffer,
        [int]$MaxLines,
        [switch]$IncludeAll,
        [ref]$Offset,
        [ref]$PartialLine
    )

    if (-not (Test-Path $LogPath)) {
        $Offset.Value = 0L
        $PartialLine.Value = ""
        return
    }

    $fileLength = [int64](Get-Item -LiteralPath $LogPath).Length
    if ($fileLength -lt [int64]$Offset.Value) {
        $Offset.Value = 0L
        $PartialLine.Value = ""
    }
    if ($fileLength -eq [int64]$Offset.Value) {
        return
    }

    $stream = $null
    $reader = $null
    $chunk = ""
    try {
        $stream = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $null = $stream.Seek([int64]$Offset.Value, [System.IO.SeekOrigin]::Begin)
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
        $chunk = $reader.ReadToEnd()
        $Offset.Value = [int64]$stream.Position
    } catch {
        return
    } finally {
        if ($reader) {
            $reader.Dispose()
        }
        if ($stream) {
            $stream.Dispose()
        }
    }

    if ([string]::IsNullOrEmpty($chunk)) {
        return
    }

    $combined = "$($PartialLine.Value)$chunk"
    $segments = $combined -split "`r?`n"
    $complete = @()

    if ($combined.EndsWith("`n")) {
        $PartialLine.Value = ""
        $complete = $segments
    } else {
        if ($segments.Count -gt 0) {
            $PartialLine.Value = $segments[$segments.Count - 1]
            if ($segments.Count -gt 1) {
                $complete = $segments[0..($segments.Count - 2)]
            }
        } else {
            $PartialLine.Value = $combined
        }
    }

    foreach ($line in $complete) {
        $display = Convert-LogLineToDisplay -Line $line -IncludeAll:$IncludeAll
        Add-RingLine -Buffer $Buffer -Line $display -MaxLines $MaxLines
    }
}

function Write-StatusView {
    param(
        [pscustomobject]$RunningState,
        [pscustomobject]$StatusSnapshot,
        [System.Collections.Generic.List[string]]$ReasoningBuffer,
        [int]$MaxReasoningLines,
        [int]$PollMs,
        [switch]$IncludeAll,
        [switch]$SingleShot
    )

    if ($RunningState.IsRunning) {
        if ($RunningState.Source -eq "process_scan") {
            Write-Host "Status: RUNNING (PID=$($RunningState.PidText), detected via process scan)"
        } else {
            Write-Host "Status: RUNNING (PID=$($RunningState.PidText))"
        }
    } else {
        Write-Host "Status: STOPPED"
    }

    $status = $StatusSnapshot.Status
    if ($status) {
        $guestId = if ([string]::IsNullOrWhiteSpace([string]$status.publicId)) { "(not initialized)" } else { [string]$status.publicId }
        $matchId = if ([string]::IsNullOrWhiteSpace([string]$status.currentMatchId)) { "(none)" } else { [string]$status.currentMatchId }
        $lastSeq = Get-ObjectStringValue -Obj $status -Name "lastSeq" -Default "-1"
        $mode = Get-ObjectStringValue -Obj $status -Name "mode" -Default "(unknown)"
        $heartbeat = Format-EpochMillis -EpochMsValue $status.lastHeartbeatEpochMs

        Write-Host "Guest ID: $guestId"
        Write-Host "Current Match: $matchId"
        Write-Host "Last Seq: $lastSeq"
        Write-Host "Mode: $mode"
        Write-Host "Last Heartbeat: $heartbeat"
    } else {
        Write-Host "Guest ID: (not available yet)"
        Write-Host "Current Match: (not available yet)"
        Write-Host "Last Seq: (not available yet)"
        Write-Host "Mode: (not available yet)"
        Write-Host "Last Heartbeat: (not available yet)"
        if (-not [string]::IsNullOrWhiteSpace($StatusSnapshot.ErrorText)) {
            Write-Host "Status Source: $($StatusSnapshot.ErrorText)"
        }
    }

    Write-Host ""
    Write-Host "Reasoning (latest $MaxReasoningLines)"
    if ($IncludeAll) {
        Write-Host "Filter: all log events"
    } else {
        Write-Host "Filter: decision + selected transport/match/state events"
    }

    if (-not (Test-Path $logFile)) {
        Write-Host "(not available yet)"
    } elseif ($ReasoningBuffer.Count -eq 0) {
        Write-Host "(no matching log events yet)"
    } else {
        foreach ($line in $ReasoningBuffer) {
            Write-Host $line
        }
    }

    $llmSelectedCount = ($ReasoningBuffer | Where-Object { $_ -match "decision:llm_selected" }).Count
    $llmFallbackCount = ($ReasoningBuffer | Where-Object { $_ -match "decision:llm_fallback" }).Count
    $stateIncompleteCount = ($ReasoningBuffer | Where-Object { $_ -match "state:state_incomplete_resync" }).Count
    $stateIncompleteFallbackCount = ($ReasoningBuffer | Where-Object { $_ -match "state:state_incomplete_fallback" }).Count
    Write-Host ""
    Write-Host "Checklist"
    Write-Host "LLM selected (tail): $llmSelectedCount"
    Write-Host "LLM fallback (tail): $llmFallbackCount"
    Write-Host "State incomplete resync (tail): $stateIncompleteCount"
    Write-Host "State incomplete fallback (tail): $stateIncompleteFallbackCount"

    if (-not $SingleShot) {
        Write-Host ""
        $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        Write-Host "Refreshing every ${PollMs}ms (Ctrl+C to exit)"
        Write-Host "Updated: $stamp"
    }
}

$reasoningBuffer = New-Object "System.Collections.Generic.List[string]"
$logOffset = 0L
$logPartial = ""
Initialize-ReasoningBuffer `
    -LogPath $logFile `
    -Buffer $reasoningBuffer `
    -MaxLines $ReasoningLines `
    -IncludeAll:$ShowAllLogs `
    -Offset ([ref]$logOffset) `
    -PartialLine ([ref]$logPartial)

if ($Once) {
    $runningState = Get-RunningState -PidPath $pidFile -MainScriptPath $mainScript
    $statusSnapshot = Get-StatusSnapshot -MainScriptPath $mainScript
    Write-StatusView `
        -RunningState $runningState `
        -StatusSnapshot $statusSnapshot `
        -ReasoningBuffer $reasoningBuffer `
        -MaxReasoningLines $ReasoningLines `
        -PollMs $RefreshMs `
        -IncludeAll:$ShowAllLogs `
        -SingleShot:$true
    exit 0
}

while ($true) {
    $runningState = Get-RunningState -PidPath $pidFile -MainScriptPath $mainScript
    $statusSnapshot = Get-StatusSnapshot -MainScriptPath $mainScript

    Update-ReasoningBuffer `
        -LogPath $logFile `
        -Buffer $reasoningBuffer `
        -MaxLines $ReasoningLines `
        -IncludeAll:$ShowAllLogs `
        -Offset ([ref]$logOffset) `
        -PartialLine ([ref]$logPartial)

    Clear-Host
    Write-StatusView `
        -RunningState $runningState `
        -StatusSnapshot $statusSnapshot `
        -ReasoningBuffer $reasoningBuffer `
        -MaxReasoningLines $ReasoningLines `
        -PollMs $RefreshMs `
        -IncludeAll:$ShowAllLogs `
        -SingleShot:$false

    Start-Sleep -Milliseconds $RefreshMs
}
