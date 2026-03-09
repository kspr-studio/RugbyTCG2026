import fs from "node:fs/promises";
import path from "node:path";

export const DEFAULT_STATE = Object.freeze({
  publicId: "",
  userId: "",
  username: "",
  displayName: "",
  currentMatchId: "",
  role: "",
  authoritativeUserId: "",
  opponentUserId: "",
  matchReadySubmitted: false,
  lastSeq: -1,
  serverTurnOwner: "",
  awaitingRekickoff: false,
  kickoffGeneration: 0,
  localKickoffReady: false,
  remoteKickoffReady: false,
  canonicalState: {},
  lastChallengeId: "",
  lastHeartbeatEpochMs: 0,
  mode: "idle",
  updatedAtEpochMs: 0
});

function cloneDefault() {
  return JSON.parse(JSON.stringify(DEFAULT_STATE));
}

function normalizeBoolean(value, fallback = false) {
  if (typeof value === "boolean") return value;
  return fallback;
}

function normalizeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeNumber(value, fallback = 0) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return n;
}

function normalizeCanonical(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return value;
}

export function normalizeState(input) {
  const base = cloneDefault();
  if (!input || typeof input !== "object") return base;

  base.publicId = normalizeString(input.publicId);
  base.userId = normalizeString(input.userId);
  base.username = normalizeString(input.username);
  base.displayName = normalizeString(input.displayName);
  base.currentMatchId = normalizeString(input.currentMatchId);
  base.role = normalizeString(input.role);
  base.authoritativeUserId = normalizeString(input.authoritativeUserId);
  base.opponentUserId = normalizeString(input.opponentUserId);
  base.matchReadySubmitted = normalizeBoolean(input.matchReadySubmitted, false);
  base.lastSeq = Math.max(-1, Math.floor(normalizeNumber(input.lastSeq, -1)));
  base.serverTurnOwner = normalizeString(input.serverTurnOwner).toLowerCase();
  base.awaitingRekickoff = normalizeBoolean(input.awaitingRekickoff, false);
  base.kickoffGeneration = Math.max(0, Math.floor(normalizeNumber(input.kickoffGeneration, 0)));
  base.localKickoffReady = normalizeBoolean(input.localKickoffReady, false);
  base.remoteKickoffReady = normalizeBoolean(input.remoteKickoffReady, false);
  base.canonicalState = normalizeCanonical(input.canonicalState);
  base.lastChallengeId = normalizeString(input.lastChallengeId);
  base.lastHeartbeatEpochMs = Math.max(0, Math.floor(normalizeNumber(input.lastHeartbeatEpochMs, 0)));
  base.mode = normalizeString(input.mode) || "idle";
  base.updatedAtEpochMs = Math.max(0, Math.floor(normalizeNumber(input.updatedAtEpochMs, 0)));
  return base;
}

export function clearMatchState(state, reason = "clear_match") {
  const next = normalizeState(state);
  next.currentMatchId = "";
  next.role = "";
  next.authoritativeUserId = "";
  next.opponentUserId = "";
  next.matchReadySubmitted = false;
  next.lastSeq = -1;
  next.serverTurnOwner = "";
  next.awaitingRekickoff = false;
  next.kickoffGeneration = 0;
  next.localKickoffReady = false;
  next.remoteKickoffReady = false;
  next.canonicalState = {};
  next.mode = reason;
  next.updatedAtEpochMs = Date.now();
  return next;
}

export class StateStore {
  constructor(filePath) {
    this.filePath = filePath;
  }

  async load() {
    try {
      const raw = await fs.readFile(this.filePath, "utf8");
      return normalizeState(JSON.parse(raw));
    } catch (error) {
      if (error && error.code === "ENOENT") {
        return normalizeState(null);
      }
      return normalizeState(null);
    }
  }

  async save(state) {
    const normalized = normalizeState(state);
    normalized.updatedAtEpochMs = Date.now();
    await fs.mkdir(path.dirname(this.filePath), { recursive: true });
    const payload = `${JSON.stringify(normalized, null, 2)}\n`;
    await fs.writeFile(this.filePath, payload, "utf8");
    return normalized;
  }
}
