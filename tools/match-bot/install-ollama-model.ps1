param(
    [string]$Model = "qwen2.5:3b",
    [switch]$UpdateLocalProperties
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..")
$localPropertiesPath = Join-Path $repoRoot "local.properties"

function Set-PropertyValue {
    param(
        [string[]]$Lines,
        [string]$Key,
        [string]$Value
    )

    $prefix = "$Key="
    $updated = $false
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -like "$prefix*") {
            $Lines[$i] = "$prefix$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        $Lines += "$prefix$Value"
    }
    return $Lines
}

$ollamaCmd = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $ollamaCmd) {
    throw "Ollama CLI is not installed or not on PATH."
}

Write-Host "Pulling Ollama model: $Model"
ollama pull $Model
$pullExit = $LASTEXITCODE
if ($pullExit -ne 0) {
    throw "ollama pull failed with exit code $pullExit"
}

$listOutput = ollama list | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "ollama list failed after pull."
}
if ($listOutput -notmatch [regex]::Escape($Model.Split(":")[0])) {
    Write-Warning "Model pull completed but the model name was not found in 'ollama list' output."
}

if ($UpdateLocalProperties) {
    $lines = @()
    if (Test-Path $localPropertiesPath) {
        $lines = Get-Content -Path $localPropertiesPath
    }
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_ENABLED" -Value "true"
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_PROVIDER" -Value "ollama"
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_MODEL" -Value $Model
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_TIMEOUT_MS" -Value "0"
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_HARD_MAX_WAIT_MS" -Value "120000"
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_PROMPT_PROFILE" -Value "compact"
    $lines = Set-PropertyValue -Lines $lines -Key "BOT_LLM_MAX_CANDIDATES" -Value "5"
    Set-Content -Path $localPropertiesPath -Value $lines
    Write-Host "Updated local.properties with Ollama bot settings."
}

Write-Host "Ollama model is ready."
Write-Host "Start bot: powershell -File tools/match-bot/bot-start.ps1"
