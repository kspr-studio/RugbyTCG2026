import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { runCleanup } from "../src/engine.mjs";
import { Logger } from "../src/logger.mjs";
import { SessionStore } from "../src/sessionStore.mjs";
import { StateStore } from "../src/stateStore.mjs";

async function makeStores() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "rugby-bot-cleanup-"));
  const sessionStore = new SessionStore(path.join(dir, ".bot-session.json"));
  const stateStore = new StateStore(path.join(dir, ".bot-state.json"));
  await sessionStore.save({
    accessToken: "token-a",
    refreshToken: "token-r",
    expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
    userId: "user-1"
  });
  await stateStore.save({
    userId: "user-1",
    publicId: "G-TEST",
    currentMatchId: "match-123",
    mode: "running"
  });
  return { sessionStore, stateStore };
}

test("cleanup succeeds after active match disappears", async () => {
  const { sessionStore, stateStore } = await makeStores();
  let fetchCount = 0;
  const client = {
    async refreshSession() {
      throw new Error("refresh should not be called");
    },
    async forfeitMyActiveMatch() {
      return { matchId: "match-123", status: "forfeit" };
    },
    async fetchActiveMatch() {
      fetchCount += 1;
      if (fetchCount >= 2) return null;
      return { matchId: "match-123", status: "active" };
    },
    async heartbeatPresenceV2() {
      return { accepted: true };
    }
  };

  const result = await runCleanup({
    client,
    sessionStore,
    stateStore,
    logger: new Logger("error"),
    maxAttempts: 3,
    backoffMs: 1
  });
  assert.equal(result.ok, true);
  assert.equal(result.code, 0);
});

test("cleanup fails when active match remains after retries", async () => {
  const { sessionStore, stateStore } = await makeStores();
  const client = {
    async refreshSession() {
      throw new Error("refresh should not be called");
    },
    async forfeitMyActiveMatch() {
      return { matchId: "match-123", status: "forfeit" };
    },
    async fetchActiveMatch() {
      return { matchId: "match-123", status: "active" };
    },
    async heartbeatPresenceV2() {
      return { accepted: true };
    }
  };

  const result = await runCleanup({
    client,
    sessionStore,
    stateStore,
    logger: new Logger("error"),
    maxAttempts: 2,
    backoffMs: 1
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, 13);
});

test("cleanup fails loudly when forfeit RPC is missing", async () => {
  const { sessionStore, stateStore } = await makeStores();
  const client = {
    async refreshSession() {
      throw new Error("refresh should not be called");
    },
    async forfeitMyActiveMatch() {
      throw new Error("HTTP 404 on /rpc/forfeit_my_active_match: function does not exist");
    },
    async fetchActiveMatch() {
      return { matchId: "match-123", status: "active" };
    },
    async heartbeatPresenceV2() {
      return { accepted: true };
    }
  };

  const result = await runCleanup({
    client,
    sessionStore,
    stateStore,
    logger: new Logger("error"),
    maxAttempts: 1,
    backoffMs: 1
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, 12);
});
