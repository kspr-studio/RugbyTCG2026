import fs from "node:fs/promises";
import path from "node:path";

function sanitizeSession(raw) {
  if (!raw || typeof raw !== "object") return null;
  const accessToken = typeof raw.accessToken === "string" ? raw.accessToken.trim() : "";
  const refreshToken = typeof raw.refreshToken === "string" ? raw.refreshToken.trim() : "";
  const userId = typeof raw.userId === "string" ? raw.userId.trim() : "";
  const expiresAtEpochSeconds = Number(raw.expiresAtEpochSeconds || 0);
  if (!accessToken || !refreshToken || !userId) {
    return null;
  }
  return {
    accessToken,
    refreshToken,
    expiresAtEpochSeconds: Number.isFinite(expiresAtEpochSeconds) ? expiresAtEpochSeconds : 0,
    userId
  };
}

export class SessionStore {
  constructor(filePath) {
    this.filePath = filePath;
  }

  async load() {
    try {
      const raw = await fs.readFile(this.filePath, "utf8");
      return sanitizeSession(JSON.parse(raw));
    } catch (error) {
      if (error && error.code === "ENOENT") return null;
      return null;
    }
  }

  async save(session) {
    const normalized = sanitizeSession(session);
    if (!normalized) {
      throw new Error("Cannot save invalid bot session");
    }
    await fs.mkdir(path.dirname(this.filePath), { recursive: true });
    const payload = `${JSON.stringify(normalized, null, 2)}\n`;
    await fs.writeFile(this.filePath, payload, "utf8");
    return normalized;
  }

  async clear() {
    try {
      await fs.unlink(this.filePath);
    } catch (error) {
      if (!error || error.code !== "ENOENT") throw error;
    }
  }

  isNearExpiry(session, safetyWindowSeconds = 60) {
    const normalized = sanitizeSession(session);
    if (!normalized) return true;
    if (!Number.isFinite(normalized.expiresAtEpochSeconds) || normalized.expiresAtEpochSeconds <= 0) {
      return true;
    }
    const nowSec = Math.floor(Date.now() / 1000);
    return normalized.expiresAtEpochSeconds <= (nowSec + Math.max(0, safetyWindowSeconds));
  }
}
