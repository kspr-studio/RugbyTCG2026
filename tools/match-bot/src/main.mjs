import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { runCleanup, BotEngine } from "./engine.mjs";
import { Logger } from "./logger.mjs";
import { SessionStore } from "./sessionStore.mjs";
import { StateStore } from "./stateStore.mjs";
import { SupabaseClient } from "./supabaseClient.mjs";
import { createTurnSelector } from "./llmPolicy.mjs";

const TOOL_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const REPO_DIR = path.resolve(TOOL_DIR, "..", "..");
const SESSION_PATH = path.join(TOOL_DIR, ".bot-session.json");
const STATE_PATH = path.join(TOOL_DIR, ".bot-state.json");
const PID_PATH = path.join(TOOL_DIR, ".bot.pid");
const OPENAI_DEFAULT_BASE_URL = "https://api.openai.com";
const OLLAMA_DEFAULT_BASE_URL = "http://127.0.0.1:11434";

function parseArgs(argv) {
  const out = { _: [] };
  for (const part of argv) {
    if (!part.startsWith("--")) {
      out._.push(part);
      continue;
    }
    const stripped = part.slice(2);
    const idx = stripped.indexOf("=");
    if (idx < 0) {
      out[stripped] = true;
      continue;
    }
    const key = stripped.slice(0, idx);
    const value = stripped.slice(idx + 1);
    out[key] = value;
  }
  return out;
}

function asPositiveInt(value, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return fallback;
  return Math.floor(n);
}

function asNonNegativeInt(value, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return fallback;
  return Math.floor(n);
}

function asBoolean(value, fallback) {
  if (value === undefined || value === null) return fallback;
  const raw = String(value).trim().toLowerCase();
  if (raw === "1" || raw === "true" || raw === "yes" || raw === "on") return true;
  if (raw === "0" || raw === "false" || raw === "no" || raw === "off") return false;
  return fallback;
}

function normalizeProvider(value, fallback = "auto") {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (raw === "auto" || raw === "none" || raw === "openai" || raw === "ollama") {
    return raw;
  }
  return fallback;
}

function providerDefaultModel(provider) {
  if (provider === "ollama") return "qwen2.5:3b";
  return "gpt-4.1-mini";
}

function providerDefaultTimeoutMs(provider) {
  if (provider === "ollama") return 0;
  return 1500;
}

function normalizeBaseUrl(value, provider) {
  const trimmed = typeof value === "string" ? value.trim() : "";
  if (trimmed) {
    return trimmed.replace(/\/+$/g, "");
  }
  return provider === "ollama" ? OLLAMA_DEFAULT_BASE_URL : OPENAI_DEFAULT_BASE_URL;
}

function configValue(name, localProps, fallback = "") {
  const envValue = process.env[name];
  if (envValue !== undefined) return envValue;
  if (localProps[name] !== undefined) return localProps[name];
  return fallback;
}

function modelInstalled(installedNames, requestedModel) {
  const requested = (requestedModel || "").trim().toLowerCase();
  if (!requested) return false;
  const requestedBase = requested.includes(":") ? requested.split(":")[0] : requested;
  for (const name of installedNames) {
    const normalized = (name || "").trim().toLowerCase();
    if (!normalized) continue;
    if (normalized === requested) return true;
    if (normalized.startsWith(`${requested}:`)) return true;
    if (requested.includes(":") && normalized.startsWith(`${requestedBase}:`)) return true;
  }
  return false;
}

async function probeOllama(baseUrl, timeoutMs = 900) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(200, timeoutMs));
  try {
    const response = await fetch(`${baseUrl}/api/tags`, {
      method: "GET",
      signal: controller.signal
    });
    if (!response.ok) {
      return { reachable: false, modelNames: [] };
    }
    const parsed = await response.json();
    const modelNames = Array.isArray(parsed?.models)
      ? parsed.models.map((row) => (row && typeof row.name === "string" ? row.name : "")).filter((name) => !!name)
      : [];
    return { reachable: true, modelNames };
  } catch (_error) {
    return { reachable: false, modelNames: [] };
  } finally {
    clearTimeout(timer);
  }
}

async function loadLocalProperties(filePath) {
  try {
    const raw = await fs.readFile(filePath, "utf8");
    const out = {};
    for (const lineRaw of raw.split(/\r?\n/g)) {
      const line = lineRaw.trim();
      if (!line || line.startsWith("#")) continue;
      const idx = line.indexOf("=");
      if (idx <= 0) continue;
      const key = line.slice(0, idx).trim();
      const value = decodePropertiesValue(line.slice(idx + 1).trim());
      if (!key) continue;
      out[key] = value;
    }
    return out;
  } catch (_error) {
    return {};
  }
}

function decodePropertiesValue(value) {
  if (typeof value !== "string" || value.length === 0) return "";
  let out = "";
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    if (ch !== "\\" || i >= (value.length - 1)) {
      out += ch;
      continue;
    }
    const next = value[i + 1];
    i += 1;
    if (next === "n") out += "\n";
    else if (next === "t") out += "\t";
    else if (next === "r") out += "\r";
    else if (next === "f") out += "\f";
    else if (next === "u" && (i + 4) < value.length) {
      const hex = value.slice(i + 1, i + 5);
      if (/^[0-9a-fA-F]{4}$/.test(hex)) {
        out += String.fromCharCode(parseInt(hex, 16));
        i += 4;
      } else {
        out += next;
      }
    } else {
      out += next;
    }
  }
  return out;
}

async function loadConfig(args) {
  const localProps = await loadLocalProperties(path.join(REPO_DIR, "local.properties"));
  const baseUrl = configValue("SUPABASE_URL", localProps, "").trim();
  const publishableKey = configValue("SUPABASE_PUBLISHABLE_KEY", localProps, "").trim();
  const pollMs = asPositiveInt(args["poll-ms"] ?? configValue("BOT_POLL_MS", localProps, ""), 1200);
  const heartbeatMs = asPositiveInt(configValue("BOT_HEARTBEAT_MS", localProps, ""), 7000);
  const clientVersion = asPositiveInt(configValue("BOT_CLIENT_VERSION", localProps, ""), 2);
  const logLevel = (args["log-level"] || configValue("BOT_LOG_LEVEL", localProps, "info")).toString().trim().toLowerCase();
  const llmEnabled = asBoolean(configValue("BOT_LLM_ENABLED", localProps, true), true);
  const llmProvider = normalizeProvider(configValue("BOT_LLM_PROVIDER", localProps, "auto"), "auto");
  const llmModel = configValue("BOT_LLM_MODEL", localProps, "").toString().trim();
  const llmTimeoutRaw = configValue("BOT_LLM_TIMEOUT_MS", localProps, "").toString().trim();
  const llmTimeoutMs = llmTimeoutRaw.length ? asNonNegativeInt(llmTimeoutRaw, -1) : -1;
  const llmHardMaxWaitMs = asPositiveInt(configValue("BOT_LLM_HARD_MAX_WAIT_MS", localProps, ""), 120000);
  const llmMaxCandidates = asPositiveInt(configValue("BOT_LLM_MAX_CANDIDATES", localProps, ""), 5);
  const llmBaseUrl = configValue("BOT_LLM_BASE_URL", localProps, configValue("BOT_OLLAMA_BASE_URL", localProps, "")).toString().trim();
  const llmOllamaThink = asBoolean(configValue("BOT_LLM_OLLAMA_THINK", localProps, false), false);
  const llmPromptProfile = configValue("BOT_LLM_PROMPT_PROFILE", localProps, "compact").toString().trim().toLowerCase();
  const rulesPath = configValue("BOT_RULES_JSON_PATH", localProps, path.join(TOOL_DIR, "data", "rules_knowledge.json")).toString().trim();
  const openAiApiKey = configValue("OPENAI_API_KEY", localProps, "").toString().trim();
  return {
    baseUrl,
    publishableKey,
    pollMs,
    heartbeatMs,
    clientVersion,
    logLevel,
    llmEnabled,
    llmProvider,
    llmModel,
    llmTimeoutMs,
    llmHardMaxWaitMs,
    llmMaxCandidates,
    llmBaseUrl,
    llmOllamaThink,
    llmPromptProfile,
    rulesPath,
    openAiApiKey
  };
}

async function resolveLlmRuntimeConfig(config, logger) {
  if (!config.llmEnabled) {
    return { enabled: false, provider: "none", reason: "disabled" };
  }

  const requestedProvider = normalizeProvider(config.llmProvider, "auto");
  if (requestedProvider === "none") {
    logger.info("decision:llm_disabled", { reason: "provider_none" });
    return { enabled: false, provider: "none", reason: "provider_none" };
  }

  if (requestedProvider === "openai" || (requestedProvider === "auto" && config.openAiApiKey)) {
    if (!config.openAiApiKey) {
      logger.warn("decision:llm_disabled_missing_key");
      return { enabled: false, provider: "none", reason: "missing_openai_api_key" };
    }
    const provider = "openai";
    return {
      enabled: true,
      provider,
      apiKey: config.openAiApiKey,
      model: config.llmModel || providerDefaultModel(provider),
      timeoutMs: config.llmTimeoutMs >= 0 ? config.llmTimeoutMs : providerDefaultTimeoutMs(provider),
      hardMaxWaitMs: config.llmHardMaxWaitMs,
      maxCandidates: config.llmMaxCandidates,
      rulesPath: config.rulesPath,
      baseUrl: normalizeBaseUrl(config.llmBaseUrl, provider),
      think: false,
      promptProfile: config.llmPromptProfile === "full" ? "full" : "compact"
    };
  }

  if (requestedProvider === "ollama" || requestedProvider === "auto") {
    const provider = "ollama";
    const baseUrl = normalizeBaseUrl(config.llmBaseUrl, provider);
    const timeoutMs = config.llmTimeoutMs >= 0 ? config.llmTimeoutMs : providerDefaultTimeoutMs(provider);
    const model = config.llmModel || providerDefaultModel(provider);
    const probe = await probeOllama(baseUrl, Math.min(timeoutMs > 0 ? timeoutMs : 1500, 1500));

    if (!probe.reachable) {
      logger.warn("decision:llm_disabled_provider_unavailable", {
        provider,
        baseUrl
      });
      return { enabled: false, provider: "none", reason: "ollama_unreachable" };
    }

    if (!modelInstalled(probe.modelNames, model)) {
      logger.warn("decision:llm_disabled_model_missing", {
        provider,
        model,
        baseUrl
      });
      return { enabled: false, provider: "none", reason: "ollama_model_missing" };
    }

    return {
      enabled: true,
      provider,
      apiKey: "",
      model,
      timeoutMs,
      hardMaxWaitMs: config.llmHardMaxWaitMs,
      maxCandidates: config.llmMaxCandidates,
      rulesPath: config.rulesPath,
      baseUrl,
      think: config.llmOllamaThink,
      promptProfile: config.llmPromptProfile === "full" ? "full" : "compact"
    };
  }

  logger.warn("decision:llm_disabled_provider_unavailable", {
    provider: requestedProvider
  });
  return { enabled: false, provider: "none", reason: "invalid_provider" };
}

async function writeStatusJson(stateStore, sessionStore) {
  const state = await stateStore.load();
  const session = await sessionStore.load();
  let pid = "";
  try {
    pid = (await fs.readFile(PID_PATH, "utf8")).trim();
  } catch (_error) {
    pid = "";
  }

  const payload = {
    runningPid: pid,
    publicId: state.publicId || "",
    userId: state.userId || "",
    currentMatchId: state.currentMatchId || "",
    lastSeq: state.lastSeq ?? -1,
    mode: state.mode || "",
    hasSession: !!session,
    sessionUserId: session?.userId || "",
    lastHeartbeatEpochMs: state.lastHeartbeatEpochMs || 0,
    updatedAtEpochMs: state.updatedAtEpochMs || 0
  };
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

function printUsage() {
  const usage = [
    "Usage:",
    "  node tools/match-bot/src/main.mjs --run [--poll-ms=1200] [--log-level=info|debug]",
    "  node tools/match-bot/src/main.mjs --cleanup-only",
    "  node tools/match-bot/src/main.mjs --status-json",
    "  Optional:",
    "    --reset-session"
  ].join("\n");
  process.stdout.write(`${usage}\n`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const config = await loadConfig(args);
  const logger = new Logger(config.logLevel);
  const sessionStore = new SessionStore(SESSION_PATH);
  const stateStore = new StateStore(STATE_PATH);

  if (args["status-json"]) {
    await writeStatusJson(stateStore, sessionStore);
    process.exit(0);
  }

  if (args["reset-session"]) {
    await sessionStore.clear();
    logger.info("session:cleared");
  }

  if (!args.run && !args["cleanup-only"]) {
    printUsage();
    process.exit(1);
  }

  if (!config.baseUrl || !config.publishableKey) {
    logger.error("Missing SUPABASE_URL or SUPABASE_PUBLISHABLE_KEY. Set env vars or local.properties.");
    process.exit(2);
  }

  const client = new SupabaseClient({
    baseUrl: config.baseUrl,
    publishableKey: config.publishableKey,
    logger,
    clientVersion: config.clientVersion
  });

  let turnSelector = null;
  const llmRuntime = await resolveLlmRuntimeConfig(config, logger);
  if (llmRuntime.enabled) {
    turnSelector = createTurnSelector({
      provider: llmRuntime.provider,
      apiKey: llmRuntime.apiKey,
      baseUrl: llmRuntime.baseUrl,
      model: llmRuntime.model,
      timeoutMs: llmRuntime.timeoutMs,
      hardMaxWaitMs: llmRuntime.hardMaxWaitMs,
      maxCandidates: llmRuntime.maxCandidates,
      rulesPath: llmRuntime.rulesPath,
      logger,
      think: llmRuntime.think,
      promptProfile: llmRuntime.promptProfile
    });
    logger.info("decision:llm_enabled", {
      provider: llmRuntime.provider,
      model: llmRuntime.model,
      timeoutMs: llmRuntime.timeoutMs,
      hardMaxWaitMs: llmRuntime.hardMaxWaitMs,
      maxCandidates: llmRuntime.maxCandidates,
      baseUrl: llmRuntime.baseUrl,
      promptProfile: llmRuntime.promptProfile
    });
  }

  if (args["cleanup-only"]) {
    const result = await runCleanup({
      client,
      sessionStore,
      stateStore,
      logger
    });
    process.exit(result.code);
  }

  const engine = new BotEngine({
    client,
    sessionStore,
    stateStore,
    logger,
    pollMs: config.pollMs,
    heartbeatMs: config.heartbeatMs,
    turnSelector,
    maxTurnCandidates: config.llmMaxCandidates
  });

  const onSignal = (signal) => {
    logger.warn("process:signal_received", { signal });
    engine.requestStop(`signal_${signal}`);
  };
  process.on("SIGINT", onSignal);
  process.on("SIGTERM", onSignal);

  const code = await engine.run();
  process.exit(code);
}

main().catch((error) => {
  const message = error && error.message ? error.message : String(error);
  process.stderr.write(`match-bot fatal: ${message}\n`);
  process.exit(99);
});
