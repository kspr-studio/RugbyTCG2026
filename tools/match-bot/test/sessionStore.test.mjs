import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { SessionStore } from "../src/sessionStore.mjs";

async function makeTempStore() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "rugby-bot-session-"));
  const filePath = path.join(dir, ".bot-session.json");
  return { dir, filePath, store: new SessionStore(filePath) };
}

test("session store saves and reloads session", async () => {
  const { store, filePath } = await makeTempStore();
  const session = {
    accessToken: "token-a",
    refreshToken: "token-r",
    expiresAtEpochSeconds: 9999999999,
    userId: "user-1"
  };

  await store.save(session);
  const loaded = await store.load();
  assert.deepEqual(loaded, session);

  const raw = await fs.readFile(filePath, "utf8");
  assert.ok(raw.includes("token-a"));
});

test("session store detects near expiry", async () => {
  const { store } = await makeTempStore();
  const now = Math.floor(Date.now() / 1000);
  const near = {
    accessToken: "token-a",
    refreshToken: "token-r",
    expiresAtEpochSeconds: now + 20,
    userId: "user-1"
  };
  const far = {
    accessToken: "token-a",
    refreshToken: "token-r",
    expiresAtEpochSeconds: now + 3600,
    userId: "user-1"
  };

  assert.equal(store.isNearExpiry(near, 60), true);
  assert.equal(store.isNearExpiry(far, 60), false);
});
