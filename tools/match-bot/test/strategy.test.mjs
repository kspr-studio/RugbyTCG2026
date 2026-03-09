import test from "node:test";
import assert from "node:assert/strict";

import { buildTurnPlan, buildTurnPlanCandidates } from "../src/strategy.mjs";

function baseCanonical() {
  return {
    turn_owner: "player_b",
    score_a: 0,
    score_b: 0,
    ball_canonical: 0,
    momentum_a: 8,
    momentum_b: 8,
    hand_a: ["PROP"],
    hand_b: [],
    board_a: [{ id: "PROP", sta: 3 }],
    board_b: [{ id: "PROP", sta: 3 }],
    temp_pwr_a: 0,
    temp_skl_a: 0,
    temp_pwr_b: 0,
    temp_skl_b: 0,
    cards_played_a: 0,
    cards_played_b: 0,
    tight_play_a: false,
    tight_play_b: false,
    drive_used_a: false,
    drive_used_b: false
  };
}

test("strategy does not play utility cards when bot board is empty", () => {
  const canonical = baseCanonical();
  canonical.board_b = [];
  canonical.hand_b = ["QUICK_PASS", "TIGHT_PLAY"];
  const plan = buildTurnPlan(canonical);
  const played = plan.actions.filter((a) => a.type === "play_card");
  assert.equal(played.length, 0);
  assert.equal(plan.actions[0].type, "end_turn");
});

test("strategy requires two player cards on board before QUICK_PASS", () => {
  const canonical = baseCanonical();
  canonical.board_b = [{ id: "PROP", sta: 3 }];
  canonical.hand_b = ["QUICK_PASS", "TIGHT_PLAY"];
  const plan = buildTurnPlan(canonical);
  const quickPassPlays = plan.actions.filter((a) => a.type === "play_card" && a.payload.card_id === "QUICK_PASS");
  assert.equal(quickPassPlays.length, 0);
});

test("strategy respects drive once-per-turn rule", () => {
  const canonical = baseCanonical();
  canonical.drive_used_b = true;
  canonical.hand_b = ["DRIVE", "QUICK_PASS"];
  const plan = buildTurnPlan(canonical);
  const drivePlays = plan.actions.filter((a) => a.type === "play_card" && a.payload.card_id === "DRIVE");
  assert.equal(drivePlays.length, 0);
});

test("strategy always ends turn and caps play count", () => {
  const canonical = baseCanonical();
  canonical.momentum_b = 20;
  canonical.hand_b = [
    "COUNTER_RUCK",
    "QUICK_PASS",
    "OPPORTUNIST",
    "ANCHOR",
    "FLANKER",
    "PLAYMAKER",
    "PROP"
  ];
  canonical.board_b = [{ id: "PROP", sta: 3 }];
  canonical.board_a = [];

  const plan = buildTurnPlan(canonical);
  const playCount = plan.actions.filter((a) => a.type === "play_card").length;
  const endTurnCount = plan.actions.filter((a) => a.type === "end_turn").length;
  assert.ok(playCount <= 4);
  assert.equal(endTurnCount, 1);
  assert.equal(plan.actions[plan.actions.length - 1].type, "end_turn");
});

test("strategy can still play proactive cards when behind", () => {
  const canonical = baseCanonical();
  canonical.score_a = 12;
  canonical.score_b = 0;
  canonical.board_a = [{ id: "PROP", sta: 3 }];
  canonical.board_b = [];
  canonical.hand_b = ["PROP", "ANCHOR"];
  const plan = buildTurnPlan(canonical);
  const first = plan.actions[0];
  assert.equal(first.type, "play_card");
  assert.ok(first.payload.card_id === "PROP" || first.payload.card_id === "ANCHOR");
});

test("strategy exposes ranked legal candidates", () => {
  const canonical = baseCanonical();
  canonical.hand_b = ["COUNTER_RUCK", "QUICK_PASS", "PROP"];
  canonical.board_b = [{ id: "PROP", sta: 3 }];
  const planning = buildTurnPlanCandidates(canonical, { maxCandidates: 3 });
  assert.ok(planning.candidates.length >= 1);
  assert.ok(planning.candidates.length <= 3);
  for (const candidate of planning.candidates) {
    const playCount = candidate.actions.filter((a) => a.type === "play_card").length;
    const endTurnCount = candidate.actions.filter((a) => a.type === "end_turn").length;
    assert.ok(playCount <= 4);
    assert.equal(endTurnCount, 1);
  }
});

test("strategy skips incomplete canonical bot turn and marks missing fields", () => {
  const planning = buildTurnPlanCandidates({ turn_owner: "player_b" }, { maxCandidates: 3 });
  assert.equal(planning.candidates.length, 0);
  assert.equal(planning.skipReason, "state_incomplete");
  assert.ok(Array.isArray(planning.meta.missingStateFields));
  assert.ok(planning.meta.missingStateFields.includes("hand_b"));
});

test("strategy can synthesize incomplete canonical when fallback is enabled", () => {
  const planning = buildTurnPlanCandidates({ turn_owner: "player_b" }, {
    maxCandidates: 3,
    allowIncompleteFallback: true
  });
  assert.ok(planning.candidates.length > 0);
  assert.equal(planning.skipReason, "");
  assert.equal(planning.meta.incompleteFallbackUsed, true);
});
