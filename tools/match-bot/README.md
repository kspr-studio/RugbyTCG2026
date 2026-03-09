# RugbyTCG Match Bot Daemon

## Purpose
This daemon signs in as a persistent anonymous Supabase user, auto-accepts incoming challenges, and plays as a bot opponent for multiplayer synchronization testing.

## Commands
Run from repo root:

```powershell
powershell -File tools/match-bot/bot-start.ps1
powershell -File tools/match-bot/bot-status.ps1
powershell -File tools/match-bot/bot-stop.ps1
```

`bot-status.ps1` defaults to live watch mode and refreshes until `Ctrl+C`.

Useful flags:

```powershell
# One-shot snapshot (script-friendly)
powershell -File tools/match-bot/bot-status.ps1 -Once

# Faster refresh + shorter reasoning tail
powershell -File tools/match-bot/bot-status.ps1 -RefreshMs 500 -ReasoningLines 10

# Include all log events (not only decision/transport focus events)
powershell -File tools/match-bot/bot-status.ps1 -ShowAllLogs
```

Direct CLI:

```powershell
node tools/match-bot/src/main.mjs --run
node tools/match-bot/src/main.mjs --cleanup-only
npm --prefix tools/match-bot run extract-knowledge
npm --prefix tools/match-bot run install-ollama-model
```

## Configuration
The daemon reads `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` from:

1. Environment variables, then
2. `local.properties` at repo root.

Optional env values:

- `BOT_POLL_MS` (default `1200`)
- `BOT_HEARTBEAT_MS` (default `7000`)
- `BOT_CLIENT_VERSION` (default `2`)
- `BOT_LOG_LEVEL` (`info` or `debug`)
- `BOT_LLM_ENABLED` (default `true`)
- `BOT_LLM_PROVIDER` (`auto`, `openai`, `ollama`, `none`; default `auto`)
- `BOT_LLM_MODEL` (default `gpt-4.1-mini` for OpenAI, `qwen2.5:3b` for Ollama)
- `BOT_LLM_BASE_URL` (optional, defaults to provider endpoint)
- `BOT_LLM_TIMEOUT_MS` (default `1500` OpenAI, `0` Ollama, set `0` to wait without provider timeout)
- `BOT_LLM_HARD_MAX_WAIT_MS` (default `120000`, watchdog safety cap even when timeout is `0`)
- `BOT_LLM_MAX_CANDIDATES` (default `5`)
- `BOT_LLM_OLLAMA_THINK` (default `false`)
- `BOT_LLM_PROMPT_PROFILE` (`compact` default, `full` for debugging)
- `BOT_RULES_JSON_PATH` (default `tools/match-bot/data/rules_knowledge.json`)
- `OPENAI_API_KEY` (required only when provider resolves to OpenAI)

Provider behavior:

- `BOT_LLM_PROVIDER=auto`: use OpenAI if key exists, otherwise try local Ollama.
- If no provider is available, bot still runs deterministic strategy and keeps playing.

## Local Ollama Setup
Recommended lightweight competitive local model for this project: `qwen2.5:3b`.

Install and configure:

```powershell
npm --prefix tools/match-bot run install-ollama-model
```

Manual install only:

```powershell
powershell -File tools/match-bot/install-ollama-model.ps1 -Model qwen2.5:3b
```

## Knowledge Extraction
Run once to build structured rule/card context used by the LLM selector:

```powershell
npm --prefix tools/match-bot install
npm --prefix tools/match-bot run extract-knowledge
```

Defaults:

- Rules PDF: `C:\Users\Rickesh Singh\Desktop\RugbyTCG-Rules.pdf`
- Cards PDF: `C:\Users\Rickesh Singh\Desktop\RugbyTCG-Cards.pdf`
- Output JSON: `tools/match-bot/data/rules_knowledge.json`

## Runtime Files
Files are created under `tools/match-bot/`:

- `.bot-session.json`
- `.bot-state.json`
- `.bot.pid`
- `.bot.log`

`bot-status.ps1` also falls back to process scan if `.bot.pid` is missing/stale.

## Stop/Cleanup Safety
`bot-stop.ps1` runs `--cleanup-only` before stop, terminates daemon, then runs `--cleanup-only` again to remove race conditions. Cleanup:

1. Forfeits active match via `forfeit_my_active_match`.
2. Verifies no active/pending match remains (`fetchActiveMatch` retries).
3. Sends inactive heartbeat.
4. Clears current match state.

If cleanup cannot release the account, stop exits non-zero and reports the failure.

## Notes
- Online gameplay currently keeps auto-timeout disabled. The UI timer is advisory (`--:--`) and turns are effectively unbounded.
- `turn_remaining_ms` in `phase_state` is treated as optional/advisory in the latest sync path.
- Current gameplay scope is the reliable path where you challenge the bot ID.
- If bot gets `player_a` role, it forfeits and returns to idle (role unsupported for this phase).
- Bot reasoning and action transport details are emitted to daemon logs (`.bot.log`) and shown in `bot-status.ps1`.

### Quick Log Checklist
Use `bot-status.ps1` and verify the tail contains:
- `decision:llm_selected` during turns with multiple legal candidates.
- Few/transient `decision:llm_fallback` lines (timeouts/invalid output should be fallback only).
- No repeated `state:turn_owner_mismatch` loops.
- `state:state_incomplete_resync` appears only briefly while resyncing.
- If canonical never arrives, expect `state:state_incomplete_fallback` and then normal `decision:submit` actions instead of a stall.
