import test from "node:test";
import assert from "node:assert/strict";

import { BotEngine } from "../src/engine.mjs";
import { normalizeState } from "../src/stateStore.mjs";

function makeEngine() {
  return new BotEngine({
    client: {},
    sessionStore: {},
    stateStore: {},
    logger: {
      info() {},
      warn() {},
      error() {},
      debug() {}
    }
  });
}

test("applySubmitResult ingests turn hints even when rejected", () => {
  const engine = makeEngine();
  engine.state = normalizeState({
    canonicalState: { turn_owner: "player_b" },
    serverTurnOwner: "",
    lastSeq: 10
  });

  engine.applySubmitResult("play_card", {
    accepted: false,
    seq: -1,
    lastSeq: 11,
    reason: "not_your_turn",
    turnOwner: "player_a",
    awaitingRekickoff: false,
    kickoffGeneration: 0,
    canonicalState: {}
  }, false);

  assert.equal(engine.state.lastSeq, 11);
  assert.equal(engine.state.serverTurnOwner, "player_a");
  assert.equal(engine.state.canonicalState.turn_owner, "player_a");
});

test("handleMatch patches canonical owner from server hint and does not play when server owner is player_a", async () => {
  let playTurnCount = 0;

  const engine = new BotEngine({
    client: {
      async joinMatchV2() {
        return {
          accepted: true,
          reason: "",
          playerA: "user-a",
          playerB: "user-b",
          yourUserId: "user-b",
          lastSeq: 20,
          turnOwner: "player_a",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          localKickoffReady: false,
          remoteKickoffReady: false,
          canonicalState: { turn_owner: "player_b" }
        };
      },
      async submitMatchActionV2() {
        return {
          accepted: true,
          seq: 21,
          lastSeq: 21,
          reason: "",
          turnOwner: "player_a",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          canonicalState: {}
        };
      },
      async fetchMatchActionsSinceV2() {
        return { actions: [], hasMore: false };
      }
    },
    sessionStore: {},
    stateStore: {},
    logger: {
      info() {},
      warn() {},
      error() {},
      debug() {}
    }
  });

  engine.state = normalizeState({
    currentMatchId: "match-1",
    userId: "user-b",
    matchReadySubmitted: true,
    role: "player_b"
  });

  engine.ensureSession = async () => ({ accessToken: "tok", userId: "user-b" });
  engine.playTurn = async () => { playTurnCount += 1; };

  await engine.handleMatch("match-1");

  assert.equal(playTurnCount, 0);
  assert.equal(engine.state.canonicalState.turn_owner, "player_a");
});

test("handleMatch patches canonical owner from server hint and plays when server owner is player_b", async () => {
  let playTurnCount = 0;

  const engine = new BotEngine({
    client: {
      async joinMatchV2() {
        return {
          accepted: true,
          reason: "",
          playerA: "user-a",
          playerB: "user-b",
          yourUserId: "user-b",
          lastSeq: 30,
          turnOwner: "player_b",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          localKickoffReady: false,
          remoteKickoffReady: false,
          canonicalState: { turn_owner: "player_a" }
        };
      },
      async submitMatchActionV2() {
        return {
          accepted: true,
          seq: 31,
          lastSeq: 31,
          reason: "",
          turnOwner: "player_b",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          canonicalState: {}
        };
      },
      async fetchMatchActionsSinceV2() {
        return { actions: [], hasMore: false };
      }
    },
    sessionStore: {},
    stateStore: {},
    logger: {
      info() {},
      warn() {},
      error() {},
      debug() {}
    }
  });

  engine.state = normalizeState({
    currentMatchId: "match-2",
    userId: "user-b",
    matchReadySubmitted: true,
    role: "player_b"
  });

  engine.ensureSession = async () => ({ accessToken: "tok", userId: "user-b" });
  engine.playTurn = async () => { playTurnCount += 1; };

  await engine.handleMatch("match-2");

  assert.equal(playTurnCount, 1);
  assert.equal(engine.state.canonicalState.turn_owner, "player_b");
});

test("playTurn requests resync when canonical state is incomplete", async () => {
  const submittedActions = [];
  const engine = new BotEngine({
    client: {
      async submitMatchActionV2(_token, _matchId, actionType) {
        submittedActions.push(actionType);
        return {
          accepted: true,
          seq: 41,
          lastSeq: 41,
          reason: "",
          turnOwner: "player_b",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          canonicalState: {}
        };
      },
      async fetchMatchActionsSinceV2() {
        return { actions: [], hasMore: false };
      }
    },
    sessionStore: {},
    stateStore: {},
    logger: {
      info() {},
      warn() {},
      error() {},
      debug() {}
    }
  });

  engine.state = normalizeState({
    currentMatchId: "match-state-incomplete",
    role: "player_b",
    canonicalState: { turn_owner: "player_b" },
    lastSeq: 40
  });
  engine.ensureSession = async () => ({ accessToken: "tok", userId: "user-b" });

  await engine.playTurn();

  assert.deepEqual(submittedActions, ["resync_request"]);
});

test("playTurn uses incomplete fallback after repeated missing canonical state", async () => {
  const submittedActions = [];
  const engine = new BotEngine({
    client: {
      async submitMatchActionV2(_token, _matchId, actionType, payload) {
        submittedActions.push({ actionType, payload });
        const seq = 80 + submittedActions.length;
        return {
          accepted: true,
          seq,
          lastSeq: seq,
          reason: "",
          turnOwner: actionType === "end_turn" ? "player_a" : "player_b",
          awaitingRekickoff: false,
          kickoffGeneration: 0,
          canonicalState: {}
        };
      },
      async fetchMatchActionsSinceV2() {
        return { actions: [], hasMore: false };
      }
    },
    sessionStore: {},
    stateStore: {},
    logger: {
      info() {},
      warn() {},
      error() {},
      debug() {}
    }
  });

  engine.state = normalizeState({
    currentMatchId: "match-state-incomplete-fallback",
    role: "player_b",
    canonicalState: { turn_owner: "player_b" },
    lastSeq: 80
  });
  engine.ensureSession = async () => ({ accessToken: "tok", userId: "user-b" });

  await engine.playTurn();
  await engine.playTurn();

  assert.equal(submittedActions[0]?.actionType, "resync_request");
  assert.ok(submittedActions.some((entry) => entry.actionType === "play_card"));
  assert.ok(submittedActions.some((entry) => entry.actionType === "end_turn"));
});
