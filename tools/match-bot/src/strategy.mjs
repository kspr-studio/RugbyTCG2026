import { SUPPORTED_CARD_IDS, getCardMeta, isPlayerCard, isUtilityCard, normalizeCardId } from "./cardMeta.mjs";

const AI_MAX_PLAYS = 4;
const DEFAULT_MAX_CANDIDATES = 5;
const BALL_MIN = -3;
const BALL_MAX = 3;
const FALLBACK_HAND_TEMPLATE = Object.freeze([
  ...SUPPORTED_CARD_IDS.filter((cardId) => isPlayerCard(cardId)),
  ...SUPPORTED_CARD_IDS.filter((cardId) => isUtilityCard(cardId))
]);

function asInt(value, fallback = 0) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.floor(n);
}

function asBool(value, fallback = false) {
  if (typeof value === "boolean") return value;
  return fallback;
}

function cloneJson(value) {
  return JSON.parse(JSON.stringify(value));
}

function getField(raw, keys, fallback = undefined) {
  for (const key of keys) {
    if (!key) continue;
    if (Object.prototype.hasOwnProperty.call(raw, key)) {
      const value = raw[key];
      if (value !== undefined && value !== null) return value;
    }
  }
  return fallback;
}

function normalizeTurnOwner(value) {
  if (typeof value !== "string") return "";
  const owner = value.trim().toLowerCase();
  return owner === "player_a" || owner === "player_b" ? owner : "";
}

function normalizeHand(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    const id = normalizeCardId(item);
    if (!id) continue;
    if (!getCardMeta(id)) continue;
    out.push(id);
  }
  return out;
}

function normalizeBoard(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const row of raw) {
    if (!row || typeof row !== "object") continue;
    const id = normalizeCardId(row.id);
    const meta = getCardMeta(id);
    if (!meta || meta.type !== "player") continue;
    const staRaw = asInt(row.sta, meta.staMax);
    const sta = Math.max(0, Math.min(meta.staMax, staRaw));
    out.push({ id, sta });
  }
  return out;
}

function normalizeSnapshot(canonicalState) {
  const raw = canonicalState && typeof canonicalState === "object" ? canonicalState : {};
  return {
    turnOwner: normalizeTurnOwner(getField(raw, ["turn_owner", "turnOwner"], "")),
    scoreA: asInt(getField(raw, ["score_a", "scoreA"], 0), 0),
    scoreB: asInt(getField(raw, ["score_b", "scoreB"], 0), 0),
    ballCanonical: Math.max(
      BALL_MIN,
      Math.min(BALL_MAX, asInt(getField(raw, ["ball_canonical", "ballCanonical", "ball"], 0), 0))
    ),
    momentumA: Math.max(0, asInt(getField(raw, ["momentum_a", "momentumA"], 0), 0)),
    momentumB: Math.max(0, asInt(getField(raw, ["momentum_b", "momentumB"], 0), 0)),
    handA: normalizeHand(getField(raw, ["hand_a", "handA"], [])),
    handB: normalizeHand(getField(raw, ["hand_b", "handB"], [])),
    boardA: normalizeBoard(getField(raw, ["board_a", "boardA"], [])),
    boardB: normalizeBoard(getField(raw, ["board_b", "boardB"], [])),
    tempPwrA: asInt(getField(raw, ["temp_pwr_a", "tempPwrA"], 0), 0),
    tempSklA: asInt(getField(raw, ["temp_skl_a", "tempSklA"], 0), 0),
    tempPwrB: asInt(getField(raw, ["temp_pwr_b", "tempPwrB"], 0), 0),
    tempSklB: asInt(getField(raw, ["temp_skl_b", "tempSklB"], 0), 0),
    cardsPlayedA: Math.max(0, asInt(getField(raw, ["cards_played_a", "cardsPlayedA"], 0), 0)),
    cardsPlayedB: Math.max(0, asInt(getField(raw, ["cards_played_b", "cardsPlayedB"], 0), 0)),
    tightPlayA: asBool(getField(raw, ["tight_play_a", "tightPlayA"], false), false),
    tightPlayB: asBool(getField(raw, ["tight_play_b", "tightPlayB"], false), false),
    driveUsedA: asBool(getField(raw, ["drive_used_a", "driveUsedA"], false), false),
    driveUsedB: asBool(getField(raw, ["drive_used_b", "driveUsedB"], false), false),
    activeTacticA: normalizeCardId(getField(raw, ["active_tactic_a", "activeTacticA"], "")),
    activeTacticB: normalizeCardId(getField(raw, ["active_tactic_b", "activeTacticB"], "")),
    // Optional fields: available when the authoritative client includes them.
    lostLastPhaseA: asBool(getField(raw, ["lost_last_phase_a", "lostLastPhaseA"], false), false),
    lostLastPhaseB: asBool(getField(raw, ["lost_last_phase_b", "lostLastPhaseB"], false), false)
  };
}

function missingRequiredBotFields(raw) {
  const source = raw && typeof raw === "object" ? raw : {};
  const missing = [];
  if (!Array.isArray(getField(source, ["hand_b", "handB"]))) missing.push("hand_b");
  if (!Array.isArray(getField(source, ["board_b", "boardB"]))) missing.push("board_b");
  if (!Array.isArray(getField(source, ["board_a", "boardA"]))) missing.push("board_a");
  if (!Number.isFinite(Number(getField(source, ["momentum_b", "momentumB"])))) missing.push("momentum_b");
  if (!Number.isFinite(Number(getField(source, ["score_a", "scoreA"])))) missing.push("score_a");
  if (!Number.isFinite(Number(getField(source, ["score_b", "scoreB"])))) missing.push("score_b");
  return missing;
}

function synthesizeIncompleteCanonical(canonicalRaw) {
  const raw = canonicalRaw && typeof canonicalRaw === "object" ? canonicalRaw : {};
  const next = { ...raw };

  const turnOwner = normalizeTurnOwner(getField(raw, ["turn_owner", "turnOwner"], ""));
  next.turn_owner = turnOwner || "player_b";

  const scoreA = getField(raw, ["score_a", "scoreA"]);
  if (!Number.isFinite(Number(scoreA))) next.score_a = 0;

  const scoreB = getField(raw, ["score_b", "scoreB"]);
  if (!Number.isFinite(Number(scoreB))) next.score_b = 0;

  const momentumA = getField(raw, ["momentum_a", "momentumA"]);
  if (!Number.isFinite(Number(momentumA))) next.momentum_a = 8;

  const momentumB = getField(raw, ["momentum_b", "momentumB"]);
  if (!Number.isFinite(Number(momentumB))) next.momentum_b = 8;

  const ballCanonical = getField(raw, ["ball_canonical", "ballCanonical", "ball"]);
  if (!Number.isFinite(Number(ballCanonical))) next.ball_canonical = 0;

  const boardA = getField(raw, ["board_a", "boardA"]);
  if (!Array.isArray(boardA)) next.board_a = [];

  const boardB = getField(raw, ["board_b", "boardB"]);
  if (!Array.isArray(boardB)) next.board_b = [];

  const handB = getField(raw, ["hand_b", "handB"]);
  if (!Array.isArray(handB) || handB.length === 0) {
    next.hand_b = [...FALLBACK_HAND_TEMPLATE];
  }

  return next;
}

function clampBall(value) {
  return Math.max(BALL_MIN, Math.min(BALL_MAX, value));
}

function computeBoardTotal(board, yourCardsPlayed, oppCardsPlayed, tightPlay) {
  let total = 0;
  for (const slot of board) {
    const meta = getCardMeta(slot.id);
    if (!meta || meta.type !== "player") continue;
    let pwr = meta.pwr;
    let skl = meta.skl;
    if (tightPlay) pwr += 1;
    if (slot.id === "FLANKER") pwr += 1;
    if (slot.id === "OPPORTUNIST" && oppCardsPlayed > yourCardsPlayed) pwr += 2;
    total += pwr + skl;
  }
  return total;
}

function computeSideTotal(snapshot, side) {
  const board = side === "B" ? snapshot.boardB : snapshot.boardA;
  const yourCardsPlayed = side === "B" ? snapshot.cardsPlayedB : snapshot.cardsPlayedA;
  const oppCardsPlayed = side === "B" ? snapshot.cardsPlayedA : snapshot.cardsPlayedB;
  const tightPlay = side === "B" ? snapshot.tightPlayB : snapshot.tightPlayA;
  const tempPwr = side === "B" ? snapshot.tempPwrB : snapshot.tempPwrA;
  const tempSkl = side === "B" ? snapshot.tempSklB : snapshot.tempSklA;

  const boardTotal = computeBoardTotal(board, yourCardsPlayed, oppCardsPlayed, tightPlay);
  return boardTotal + tempPwr + tempSkl;
}

function computeTotals(snapshot) {
  const botTotal = computeSideTotal(snapshot, "B");
  const oppTotal = computeSideTotal(snapshot, "A");
  return { botTotal, oppTotal };
}

function removeCardFromHand(hand, cardId) {
  const index = hand.findIndex((id) => id === cardId);
  if (index >= 0) hand.splice(index, 1);
}

function simulateCardPlay(snapshot, cardId) {
  const meta = getCardMeta(cardId);
  if (!meta) return;

  removeCardFromHand(snapshot.handB, cardId);
  snapshot.momentumB = Math.max(0, snapshot.momentumB - meta.cost);
  snapshot.cardsPlayedB += 1;

  if (meta.type === "player") {
    snapshot.boardB.push({ id: cardId, sta: meta.staMax });
    return;
  }

  if (cardId === "COUNTER_RUCK") {
    const lostLastPhase = snapshot.lostLastPhaseB || (snapshot.scoreB < snapshot.scoreA);
    if (lostLastPhase) {
      snapshot.tempPwrB += 3;
    }
    return;
  }

  if (cardId === "QUICK_PASS") {
    snapshot.tempSklB += 2;
    return;
  }

  if (cardId === "DRIVE") {
    snapshot.driveUsedB = true;
    snapshot.ballCanonical = clampBall(snapshot.ballCanonical - 1);
    return;
  }

  if (cardId === "TIGHT_PLAY") {
    snapshot.tightPlayB = true;
    snapshot.activeTacticB = "TIGHT_PLAY";
  }
}

function utilityScore(snapshot, cardId) {
  if (cardId === "COUNTER_RUCK") {
    const lostLastPhase = snapshot.lostLastPhaseB || (snapshot.scoreB < snapshot.scoreA);
    return lostLastPhase ? 4 : -1;
  }
  if (cardId === "QUICK_PASS") return 3;
  if (cardId === "DRIVE") {
    return snapshot.ballCanonical > BALL_MIN ? 2 : -1;
  }
  if (cardId === "TIGHT_PLAY") {
    return snapshot.tightPlayB ? -1 : 1;
  }
  return -1;
}

function pickAiUtilityCard(snapshot) {
  let bestScore = -999;
  let bestCard = "";
  for (const cardId of snapshot.handB) {
    const meta = getCardMeta(cardId);
    if (!meta || meta.type === "player") continue;
    if ((meta.type === "play" || meta.type === "tactic") && snapshot.boardB.length === 0) continue;
    if (snapshot.momentumB < meta.cost) continue;
    if (cardId === "DRIVE" && snapshot.driveUsedB) continue;
    if (cardId === "QUICK_PASS" && snapshot.boardB.length < 2) continue;
    if (cardId === "TIGHT_PLAY" && snapshot.boardB.length < 3) continue;
    const score = utilityScore(snapshot, cardId);
    if (score > bestScore) {
      bestScore = score;
      bestCard = cardId;
    }
  }

  if (!bestCard || bestScore <= 0) return null;
  return { cardId: bestCard, score: bestScore };
}

function pickBestPlayerCard(snapshot) {
  let best = null;
  let bestNewTotal = computeSideTotal(snapshot, "B");
  let bestSta = -999;

  for (const cardId of snapshot.handB) {
    const meta = getCardMeta(cardId);
    if (!meta || meta.type !== "player") continue;
    if (snapshot.momentumB < meta.cost) continue;

    const trial = cloneJson(snapshot);
    trial.boardB.push({ id: cardId, sta: meta.staMax });
    const newTotal = computeSideTotal(trial, "B");
    const staTie = meta.staMax;

    if (newTotal > bestNewTotal || (newTotal === bestNewTotal && staTie > bestSta)) {
      bestNewTotal = newTotal;
      bestSta = staTie;
      best = { cardId, newTotal, sta: staTie };
    }
  }

  return best;
}

function buildAiPlan(initialSnapshot) {
  const snapshot = cloneJson(initialSnapshot);
  const actions = [];
  const steps = [];
  let plays = 0;
  let utilityPhase = true;

  while (true) {
    if (plays >= AI_MAX_PLAYS) {
      actions.push({ type: "end_turn", payload: {}, reason: "max_plays_reached" });
      steps.push({ phase: "finalize", action: "end_turn", reason: "max_plays_reached" });
      break;
    }

    if (utilityPhase) {
      const utility = pickAiUtilityCard(snapshot);
      if (utility) {
        actions.push({
          type: "play_card",
          payload: { card_id: utility.cardId },
          reason: "utility_choice"
        });
        steps.push({
          phase: "utility",
          action: "play_card",
          cardId: utility.cardId,
          reason: "utility_choice",
          score: Number(utility.score.toFixed(2))
        });
        simulateCardPlay(snapshot, utility.cardId);
        plays += 1;
        continue;
      }
      utilityPhase = false;
    }

    const yourTotal = computeSideTotal(snapshot, "A");
    const oppTotal = computeSideTotal(snapshot, "B");
    if (oppTotal > yourTotal) {
      actions.push({ type: "end_turn", payload: {}, reason: "ahead_on_totals" });
      steps.push({ phase: "finalize", action: "end_turn", reason: "ahead_on_totals" });
      break;
    }

    const bestPlayer = pickBestPlayerCard(snapshot);
    if (!bestPlayer) {
      actions.push({ type: "end_turn", payload: {}, reason: "no_affordable_player" });
      steps.push({ phase: "finalize", action: "end_turn", reason: "no_affordable_player" });
      break;
    }

    actions.push({
      type: "play_card",
      payload: { card_id: bestPlayer.cardId },
      reason: "best_projected_total"
    });
    steps.push({
      phase: "player",
      action: "play_card",
      cardId: bestPlayer.cardId,
      reason: "best_projected_total",
      newTotal: bestPlayer.newTotal
    });
    simulateCardPlay(snapshot, bestPlayer.cardId);
    plays += 1;
  }

  if (!actions.length || actions[actions.length - 1].type !== "end_turn") {
    actions.push({ type: "end_turn", payload: {}, reason: "turn_complete" });
    steps.push({ phase: "finalize", action: "end_turn", reason: "turn_complete" });
  }

  return { actions, steps, plays, finalSnapshot: snapshot };
}

export function buildTurnPlanCandidates(canonicalState, options = {}) {
  const canonicalRawInput = canonicalState && typeof canonicalState === "object" ? canonicalState : {};
  const sourceMissingStateFields = missingRequiredBotFields(canonicalRawInput);
  const allowIncompleteFallback = !!options.allowIncompleteFallback;
  const canonicalRaw = allowIncompleteFallback && sourceMissingStateFields.length > 0
    ? synthesizeIncompleteCanonical(canonicalRawInput)
    : canonicalRawInput;
  const snapshot = normalizeSnapshot(canonicalRaw);
  const awaitingRekickoff = !!options.awaitingRekickoff;
  const maxCandidates = Math.max(1, Math.floor(Number(options.maxCandidates) || DEFAULT_MAX_CANDIDATES));
  const missingStateFields = missingRequiredBotFields(canonicalRaw);
  const meta = {
    handBCount: snapshot.handB.length,
    boardBCount: snapshot.boardB.length,
    momentumB: snapshot.momentumB,
    legalActionCount: snapshot.handB.length,
    scoreGap: snapshot.scoreB - snapshot.scoreA,
    stateComplete: missingStateFields.length === 0,
    missingStateFields,
    sourceMissingStateFields,
    incompleteFallbackUsed: allowIncompleteFallback && sourceMissingStateFields.length > 0
  };

  if (snapshot.turnOwner !== "player_b") {
    return {
      candidates: [],
      steps: [{ type: "skip", reason: "not_bot_turn" }],
      summary: "skip:not_bot_turn",
      skipReason: "not_bot_turn",
      meta
    };
  }
  if (awaitingRekickoff) {
    return {
      candidates: [],
      steps: [{ type: "skip", reason: "awaiting_rekickoff" }],
      summary: "skip:awaiting_rekickoff",
      skipReason: "awaiting_rekickoff",
      meta
    };
  }
  if (missingStateFields.length > 0) {
    return {
      candidates: [],
      steps: [{ type: "skip", reason: "state_incomplete", missing: missingStateFields }],
      summary: "skip:state_incomplete",
      skipReason: "state_incomplete",
      meta
    };
  }

  const plan = buildAiPlan(snapshot);
  const finalTotals = computeTotals(plan.finalSnapshot);
  const score = finalTotals.botTotal - finalTotals.oppTotal;
  const ranked = [{
    id: "cand_1",
    actions: plan.actions,
    steps: plan.steps,
    score: Number(score.toFixed(2)),
    plays: plan.plays,
    summary: `actions=${plan.actions.length},plays=${plan.plays},score=${score.toFixed(2)}`,
    finalSnapshot: plan.finalSnapshot,
    finalTotals
  }].slice(0, maxCandidates);

  return {
    candidates: ranked,
    steps: ranked[0]?.steps || [],
    summary: ranked.length ? `candidates=${ranked.length}` : "no_candidates",
    skipReason: "",
    meta
  };
}

export function buildTurnPlan(canonicalState, options = {}) {
  const planning = buildTurnPlanCandidates(canonicalState, options);
  if (!planning.candidates.length) {
    return {
      actions: [],
      steps: planning.steps,
      summary: planning.summary,
      finalSnapshot: normalizeSnapshot(canonicalState)
    };
  }
  const best = planning.candidates[0];
  return {
    actions: best.actions,
    steps: best.steps,
    summary: best.summary,
    finalSnapshot: best.finalSnapshot,
    score: best.score,
    candidateId: best.id,
    candidates: planning.candidates
  };
}
