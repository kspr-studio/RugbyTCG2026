import { buildTurnPlanCandidates } from "./strategy.mjs";
import { clearMatchState, normalizeState } from "./stateStore.mjs";

const ONLINE_ACTION_PAGE_SIZE = 200;
const ONLINE_ACTION_MAX_PAGES_PER_POLL = 8;
const AUTH_SAFETY_WINDOW_SECONDS = 90;
const INCOMPLETE_STATE_FALLBACK_STREAK = 2;
const INCOMPLETE_STATE_RESYNC_MIN_INTERVAL_MS = 3500;

function isBlank(value) {
  return typeof value !== "string" || value.trim().length === 0;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

function isAuthError(error) {
  if (!error || !error.message) return false;
  const lower = String(error.message).toLowerCase();
  return (
    lower.includes("401") ||
    lower.includes("403") ||
    lower.includes("jwt") ||
    lower.includes("token") ||
    lower.includes("not_authenticated")
  );
}

function isMissingForfeitRpcError(error) {
  if (!error || !error.message) return false;
  const lower = String(error.message).toLowerCase();
  return lower.includes("forfeit_my_active_match") &&
    (lower.includes("does not exist") || lower.includes("could not find the function") || lower.includes("pgrst202"));
}

function hasNonEmptyObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) && Object.keys(value).length > 0;
}

function normalizeActionType(value) {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function normalizeTurnOwner(value) {
  const owner = normalizeActionType(value);
  return owner === "player_a" || owner === "player_b" ? owner : "";
}

async function withAuthRetry(context, operationName, fn) {
  try {
    const session = await context.ensureSession(false);
    return await fn(session);
  } catch (error) {
    if (!isAuthError(error)) throw error;
    context.logger.warn(`${operationName}:auth_retry`, { reason: error.message || "" });
    const refreshed = await context.ensureSession(true);
    return await fn(refreshed);
  }
}

export async function runCleanup({
  client,
  sessionStore,
  stateStore,
  logger,
  maxAttempts = 3,
  backoffMs = 500
}) {
  const state = await stateStore.load();
  let session = await sessionStore.load();

  if (!session) {
    const cleared = clearMatchState(state, "idle");
    await stateStore.save(cleared);
    logger.info("cleanup:no_session");
    return { ok: true, code: 0, activeMatchId: "" };
  }

  if (sessionStore.isNearExpiry(session, AUTH_SAFETY_WINDOW_SECONDS)) {
    try {
      session = await client.refreshSession(session.refreshToken);
      await sessionStore.save(session);
      logger.info("cleanup:session_refreshed");
    } catch (error) {
      logger.error("cleanup:refresh_failed", { error: error.message || "" });
      return { ok: false, code: 11, activeMatchId: state.currentMatchId || "" };
    }
  }

  let activeMatch = null;
  let fatalForfeitRpcMissing = false;
  for (let attempt = 1; attempt <= Math.max(1, maxAttempts); attempt += 1) {
    try {
      const forfeit = await client.forfeitMyActiveMatch(session.accessToken);
      logger.info("cleanup:forfeit_attempt", {
        attempt,
        status: forfeit.status || "",
        matchId: forfeit.matchId || ""
      });
    } catch (error) {
      logger.warn("cleanup:forfeit_failed", { attempt, error: error.message || "" });
      if (isMissingForfeitRpcError(error)) {
        fatalForfeitRpcMissing = true;
        break;
      }
    }

    try {
      activeMatch = await client.fetchActiveMatch(session.accessToken, session.userId);
    } catch (error) {
      if (!isAuthError(error)) {
        logger.warn("cleanup:active_fetch_failed", { attempt, error: error.message || "" });
      } else {
        try {
          session = await client.refreshSession(session.refreshToken);
          await sessionStore.save(session);
          activeMatch = await client.fetchActiveMatch(session.accessToken, session.userId);
        } catch (refreshError) {
          logger.warn("cleanup:active_fetch_retry_failed", {
            attempt,
            error: refreshError.message || ""
          });
        }
      }
    }

    if (!activeMatch || isBlank(activeMatch.matchId)) {
      break;
    }
    await sleep(backoffMs * attempt);
  }

  try {
    await client.heartbeatPresenceV2(session.accessToken, null, false);
  } catch (error) {
    logger.warn("cleanup:inactive_heartbeat_failed", { error: error.message || "" });
  }

  let nextState = clearMatchState(state, "idle");
  nextState.publicId = nextState.publicId || state.publicId || "";
  nextState.userId = session.userId || nextState.userId;
  await stateStore.save(nextState);

  if (fatalForfeitRpcMissing) {
    logger.error("cleanup:missing_forfeit_rpc");
    return { ok: false, code: 12, activeMatchId: state.currentMatchId || "" };
  }
  if (activeMatch && !isBlank(activeMatch.matchId)) {
    logger.error("cleanup:active_match_still_present", { matchId: activeMatch.matchId });
    return { ok: false, code: 13, activeMatchId: activeMatch.matchId };
  }
  logger.info("cleanup:success");
  return { ok: true, code: 0, activeMatchId: "" };
}

export class BotEngine {
  constructor({
    client,
    sessionStore,
    stateStore,
    logger,
    pollMs = 1200,
    heartbeatMs = 7000,
    turnSelector = null,
    maxTurnCandidates = 5
  }) {
    this.client = client;
    this.sessionStore = sessionStore;
    this.stateStore = stateStore;
    this.logger = logger;
    this.turnSelector = turnSelector;
    this.pollMs = Math.max(250, pollMs);
    this.heartbeatMs = Math.max(1000, heartbeatMs);
    this.maxTurnCandidates = Math.max(1, Math.floor(Number(maxTurnCandidates) || 5));
    this.running = false;
    this.stopReason = "";
    this.session = null;
    this.state = normalizeState(null);
    this.identityLoggedPublicId = "";
    this.lastTurnMismatchKey = "";
    this.lastTurnPatchKey = "";
    this.lastStateIncompleteResyncSeq = -2;
    this.lastStateIncompleteResyncEpochMs = 0;
    this.stateIncompleteStreak = 0;
  }

  requestStop(reason = "requested") {
    this.stopReason = reason;
    this.running = false;
  }

  async run() {
    this.state = await this.stateStore.load();
    this.state.serverTurnOwner = normalizeTurnOwner(this.state.serverTurnOwner);
    this.running = true;
    this.logger.info("bot:run_started", { pollMs: this.pollMs, heartbeatMs: this.heartbeatMs });

    while (this.running) {
      try {
        await this.tick();
      } catch (error) {
        this.logger.error("bot:tick_error", { error: error.message || "" });
        await sleep(Math.min(2500, this.pollMs));
      }
      if (!this.running) break;
      await sleep(this.pollMs);
    }

    await this.markInactiveHeartbeat();
    await this.stateStore.save(this.state);
    this.logger.info("bot:run_stopped", { reason: this.stopReason || "loop_exit" });
    return 0;
  }

  async ensureSession(forceRefresh) {
    if (!this.session) {
      this.session = await this.sessionStore.load();
    }

    if (!this.session) {
      this.session = await this.client.signInAnonymously();
      await this.sessionStore.save(this.session);
      this.logger.info("auth:anonymous_signin", { userId: this.session.userId });
      return this.session;
    }

    const nearExpiry = this.sessionStore.isNearExpiry(this.session, AUTH_SAFETY_WINDOW_SECONDS);
    if (forceRefresh || nearExpiry) {
      if (isBlank(this.session.refreshToken)) {
        throw new Error("missing_refresh_token");
      }
      this.session = await this.client.refreshSession(this.session.refreshToken);
      await this.sessionStore.save(this.session);
      this.logger.info("auth:session_refreshed", { userId: this.session.userId });
    }
    return this.session;
  }

  async tick() {
    await this.bootstrapProfile();
    await this.maybeHeartbeat();

    const activeMatch = await withAuthRetry(this, "fetch_active_match", (session) =>
      this.client.fetchActiveMatch(session.accessToken, session.userId)
    );

    if (!activeMatch || isBlank(activeMatch.matchId)) {
      if (!isBlank(this.state.currentMatchId)) {
        this.state = clearMatchState(this.state, "active_match_missing");
        this.logger.info("match:cleared_no_active_match");
      }
      await this.tryAcceptIncomingChallenge();
      await this.stateStore.save(this.state);
      return;
    }

    if (activeMatch.matchId !== this.state.currentMatchId) {
      this.attachMatch(activeMatch.matchId);
      this.logger.info("match:attached", { matchId: activeMatch.matchId });
    }

    await this.handleMatch(activeMatch.matchId);
    await this.stateStore.save(this.state);
  }

  async bootstrapProfile() {
    const profile = await withAuthRetry(this, "fetch_profile", async (session) => {
      const row = await this.client.fetchProfile(session.accessToken, session.userId);
      return { session, profile: row };
    });

    this.state.userId = profile.session.userId || "";
    this.state.publicId = profile.profile.publicId || this.state.publicId || "";
    this.state.username = profile.profile.username || "";
    this.state.displayName = profile.profile.displayName || "";
    if (!isBlank(this.state.publicId) && this.state.publicId !== this.identityLoggedPublicId) {
      this.identityLoggedPublicId = this.state.publicId;
      this.logger.info("identity:guest_ready", {
        publicId: this.state.publicId,
        userId: this.state.userId
      });
    }
  }

  async maybeHeartbeat() {
    const now = Date.now();
    if ((now - (this.state.lastHeartbeatEpochMs || 0)) < this.heartbeatMs) {
      return;
    }
    const matchId = isBlank(this.state.currentMatchId) ? null : this.state.currentMatchId;
    const result = await withAuthRetry(this, "heartbeat_presence_v2", (session) =>
      this.client.heartbeatPresenceV2(session.accessToken, matchId, true)
    );
    if (result.accepted) {
      this.state.lastHeartbeatEpochMs = now;
      if (result.kickoffGeneration > 0) {
        this.state.kickoffGeneration = Math.max(this.state.kickoffGeneration || 0, result.kickoffGeneration);
      }
      this.state.awaitingRekickoff = !!result.awaitingRekickoff;
    }
  }

  async markInactiveHeartbeat() {
    try {
      await withAuthRetry(this, "heartbeat_inactive", (session) =>
        this.client.heartbeatPresenceV2(session.accessToken, null, false)
      );
    } catch (_error) {
      // Best effort only.
    }
  }

  async tryAcceptIncomingChallenge() {
    const incoming = await withAuthRetry(this, "fetch_latest_challenge", (session) =>
      this.client.fetchLatestIncomingChallenge(session.accessToken, session.userId)
    );
    if (!incoming || isBlank(incoming.challengeId)) {
      return;
    }
    if (incoming.challengeId === this.state.lastChallengeId) {
      return;
    }

    const response = await withAuthRetry(this, "respond_challenge", (session) =>
      this.client.respondChallenge(session.accessToken, incoming.challengeId, true)
    );
    this.state.lastChallengeId = incoming.challengeId;
    if (normalizeActionType(response.status) === "accepted" && !isBlank(response.matchId)) {
      this.attachMatch(response.matchId);
      this.logger.info("challenge:accepted", { matchId: response.matchId });
      return;
    }
    this.logger.info("challenge:ignored", { status: response.status || "" });
  }

  attachMatch(matchId) {
    this.state = clearMatchState(this.state, "match_attached");
    this.state.currentMatchId = matchId;
    this.state.mode = "match_attached";
    this.lastTurnMismatchKey = "";
    this.lastTurnPatchKey = "";
    this.lastStateIncompleteResyncSeq = -2;
    this.lastStateIncompleteResyncEpochMs = 0;
    this.stateIncompleteStreak = 0;
  }

  syncServerTurnOwner(turnOwner) {
    const normalized = normalizeTurnOwner(turnOwner);
    if (normalized) {
      this.state.serverTurnOwner = normalized;
    }
  }

  getTurnOwnerState() {
    const serverOwner = normalizeTurnOwner(this.state.serverTurnOwner);
    const canonicalOwner = normalizeTurnOwner(this.state.canonicalState?.turn_owner || "");
    const effectiveOwner = serverOwner || canonicalOwner;
    const consistent = !serverOwner || !canonicalOwner || serverOwner === canonicalOwner;
    return { serverOwner, canonicalOwner, effectiveOwner, consistent };
  }

  logTurnOwnerMismatch(matchId, turnState) {
    const key = `${matchId}|${turnState.serverOwner}|${turnState.canonicalOwner}|${this.state.lastSeq}`;
    if (key === this.lastTurnMismatchKey) return;
    this.lastTurnMismatchKey = key;
    this.logger.warn("state:turn_owner_mismatch", {
      matchId,
      serverTurnOwner: turnState.serverOwner,
      canonicalTurnOwner: turnState.canonicalOwner,
      lastSeq: this.state.lastSeq
    });
  }

  logTurnOwnerPatched(matchId, fromOwner, toOwner) {
    const key = `${matchId}|${fromOwner}|${toOwner}|${this.state.lastSeq}`;
    if (key === this.lastTurnPatchKey) return;
    this.lastTurnPatchKey = key;
    this.logger.warn("state:turn_owner_patched", {
      matchId,
      canonicalTurnOwner: fromOwner,
      patchedTurnOwner: toOwner,
      lastSeq: this.state.lastSeq
    });
  }

  async handleMatch(matchId) {
    if (isBlank(matchId)) return;

    const join = await withAuthRetry(this, "join_match_v2", (session) =>
      this.client.joinMatchV2(session.accessToken, matchId)
    );
    if (!join.accepted) {
      const reason = normalizeActionType(join.reason);
      this.logger.warn("match:join_rejected", { matchId, reason });
      if (reason === "match_not_found" || reason === "not_match_participant" || reason === "match_not_active") {
        this.state = clearMatchState(this.state, "join_rejected");
      }
      return;
    }

    const localUserId = this.state.userId || this.session?.userId || join.yourUserId || "";
    const role = localUserId === join.playerA ? "player_a" : localUserId === join.playerB ? "player_b" : "";

    this.state.role = role;
    this.state.authoritativeUserId = join.playerA || "";
    this.state.opponentUserId = role === "player_a" ? join.playerB || "" : join.playerA || "";
    this.state.lastSeq = Math.max(this.state.lastSeq, Number(join.lastSeq ?? -1));
    this.state.awaitingRekickoff = !!join.awaitingRekickoff;
    this.state.kickoffGeneration = Math.max(this.state.kickoffGeneration || 0, Number(join.kickoffGeneration || 0));
    this.state.localKickoffReady = !!join.localKickoffReady;
    this.state.remoteKickoffReady = !!join.remoteKickoffReady;
    this.syncServerTurnOwner(join.turnOwner || "");
    if (hasNonEmptyObject(join.canonicalState)) {
      this.state.canonicalState = join.canonicalState;
    }

    if (role !== "player_b") {
      this.logger.warn("match:unsupported_role_forfeit", { matchId, role: role || "unknown" });
      await this.forfeitAndClear("unsupported_role");
      return;
    }

    if (!this.state.matchReadySubmitted) {
      const result = await this.submitAction("match_ready", {}, null);
      if (!result.accepted) {
        this.logger.warn("match:ready_rejected", { reason: result.reason || "" });
      } else {
        this.state.matchReadySubmitted = true;
      }
    }

    await this.syncActions();

    if (this.state.awaitingRekickoff && !this.state.localKickoffReady) {
      const result = await this.submitAction("kickoff_ready", {
        generation: Math.max(0, this.state.kickoffGeneration || 0)
      });
      if (result.accepted) {
        this.state.localKickoffReady = true;
      }
    }

    await this.syncActions();

    let turnState = this.getTurnOwnerState();
    if (!turnState.consistent) {
      this.logTurnOwnerMismatch(matchId, turnState);
      if (!turnState.serverOwner) {
        return;
      }
      const nextCanonical = { ...(this.state.canonicalState || {}) };
      const previousOwner = normalizeTurnOwner(nextCanonical.turn_owner || "");
      nextCanonical.turn_owner = turnState.serverOwner;
      this.state.canonicalState = nextCanonical;
      this.logTurnOwnerPatched(matchId, previousOwner || "(missing)", turnState.serverOwner);
      turnState = this.getTurnOwnerState();
    }

    if (!this.state.awaitingRekickoff && turnState.effectiveOwner === "player_b") {
      await this.playTurn();
    }
  }

  async chooseTurnPlan(planning) {
    const defaultPlan = planning.candidates[0] || null;
    if (!defaultPlan) {
      return { plan: null, source: "none", reason: "no_candidates" };
    }
    if (!this.turnSelector || planning.candidates.length <= 1) {
      return { plan: defaultPlan, source: "deterministic", reason: "selector_unavailable" };
    }

    const requestStartedAt = Date.now();
    this.logger.info("decision:llm_request", {
      matchId: this.state.currentMatchId,
      lastSeq: this.state.lastSeq,
      candidateCount: planning.candidates.length
    });

    try {
      const selection = await this.turnSelector.selectTurnPlan({
        matchId: this.state.currentMatchId,
        lastSeq: this.state.lastSeq,
        canonicalState: this.state.canonicalState || {},
        candidates: planning.candidates,
        planningMeta: {
          summary: planning.summary,
          skipReason: planning.skipReason || "",
          topScore: planning.candidates[0]?.score ?? 0,
          context: planning.meta || {}
        }
      });

      const candidateId = typeof selection?.candidateId === "string" ? selection.candidateId.trim() : "";
      const selected = candidateId
        ? planning.candidates.find((candidate) => candidate.id === candidateId) || null
        : null;
      if (selected) {
        return {
          plan: selected,
          source: "llm",
          reason: typeof selection?.reason === "string" ? selection.reason : "",
          elapsedMs: Date.now() - requestStartedAt,
          promptBytes: Number(selection?.promptBytes || 0)
        };
      }

      this.logger.warn("decision:llm_fallback", {
        matchId: this.state.currentMatchId,
        reason: "invalid_candidate",
        candidateId,
        elapsedMs: Date.now() - requestStartedAt
      });
      return { plan: defaultPlan, source: "deterministic_fallback", reason: "invalid_candidate" };
    } catch (error) {
      this.logger.warn("decision:llm_fallback", {
        matchId: this.state.currentMatchId,
        reason: error?.message || "selector_error",
        elapsedMs: Date.now() - requestStartedAt
      });
      return { plan: defaultPlan, source: "deterministic_fallback", reason: "selector_error" };
    }
  }

  async requestResyncForIncompleteState(planning) {
    const now = Date.now();
    if (this.lastStateIncompleteResyncSeq === this.state.lastSeq) {
      return;
    }
    if ((now - this.lastStateIncompleteResyncEpochMs) < INCOMPLETE_STATE_RESYNC_MIN_INTERVAL_MS) {
      return;
    }
    this.lastStateIncompleteResyncSeq = this.state.lastSeq;
    this.lastStateIncompleteResyncEpochMs = now;
    this.logger.warn("state:state_incomplete_resync", {
      matchId: this.state.currentMatchId,
      lastSeq: this.state.lastSeq,
      missingStateFields: planning?.meta?.missingStateFields || []
    });

    const result = await this.submitAction("resync_request", {}, null);
    if (!result.accepted) {
      this.logger.warn("transport:action_rejected", {
        actionType: "resync_request",
        reason: result.reason || "",
        lastSeq: this.state.lastSeq
      });
    }
    await this.syncActions();
  }

  async executePlanning(planning, sourceOverride = "") {
    const selected = await this.chooseTurnPlan(planning);
    const plan = selected.plan;
    if (!plan) return;

    if (selected.source === "llm") {
      this.logger.info("decision:llm_selected", {
        matchId: this.state.currentMatchId,
        candidateId: plan.id,
        reason: selected.reason || "",
        elapsedMs: selected.elapsedMs || 0,
        promptBytes: selected.promptBytes || 0
      });
    }

    this.logger.info("decision:turn_plan", {
      matchId: this.state.currentMatchId,
      lastSeq: this.state.lastSeq,
      summary: plan.summary,
      steps: plan.steps,
      candidateId: plan.id,
      source: sourceOverride || selected.source,
      planningMeta: planning.meta || {}
    });

    for (const action of plan.actions) {
      const actionType = normalizeActionType(action.type);
      if (!actionType) continue;
      const payload = action.payload && typeof action.payload === "object" ? action.payload : {};
      this.logger.info("decision:submit", {
        matchId: this.state.currentMatchId,
        actionType,
        payload,
        reason: action.reason || ""
      });

      const result = await this.submitAction(actionType, payload);
      if (!result.accepted) {
        const reason = normalizeActionType(result.reason);
        this.logger.warn("transport:action_rejected", {
          actionType,
          reason: result.reason || "",
          lastSeq: this.state.lastSeq
        });
        if (reason.includes("seq_conflict") || reason.includes("not_your_turn")) {
          await this.syncActions();
        }
        break;
      }

      if (actionType === "kickoff_ready") {
        this.state.localKickoffReady = true;
      }
      if (actionType === "end_turn") {
        break;
      }
    }

    await this.syncActions();
  }

  async playTurn() {
    let planning = buildTurnPlanCandidates(this.state.canonicalState || {}, {
      awaitingRekickoff: this.state.awaitingRekickoff,
      maxCandidates: this.maxTurnCandidates
    });

    if (!planning.candidates.length) {
      this.logger.info("decision:turn_plan", {
        matchId: this.state.currentMatchId,
        lastSeq: this.state.lastSeq,
        summary: planning.summary,
        steps: planning.steps,
        candidateId: "",
        source: "skip",
        planningMeta: planning.meta || {}
      });
      if (planning.skipReason === "state_incomplete") {
        this.stateIncompleteStreak += 1;
        if (this.stateIncompleteStreak >= INCOMPLETE_STATE_FALLBACK_STREAK) {
          const fallbackPlanning = buildTurnPlanCandidates(this.state.canonicalState || {}, {
            awaitingRekickoff: this.state.awaitingRekickoff,
            maxCandidates: this.maxTurnCandidates,
            allowIncompleteFallback: true
          });
          if (fallbackPlanning.candidates.length > 0) {
            planning = fallbackPlanning;
            this.logger.warn("state:state_incomplete_fallback", {
              matchId: this.state.currentMatchId,
              lastSeq: this.state.lastSeq,
              streak: this.stateIncompleteStreak,
              missingStateFields: planning?.meta?.sourceMissingStateFields || planning?.meta?.missingStateFields || []
            });
            await this.executePlanning(planning, "incomplete_fallback");
            return;
          }
        }
        await this.requestResyncForIncompleteState(planning);
        return;
      }
      this.stateIncompleteStreak = 0;
      return;
    }

    this.stateIncompleteStreak = 0;
    await this.executePlanning(planning);
  }

  async submitAction(actionType, payload, expectedSeqOverride = undefined) {
    if (isBlank(this.state.currentMatchId)) {
      return { accepted: false, reason: "missing_match_id" };
    }
    const expectedSeq = expectedSeqOverride !== undefined ? expectedSeqOverride : this.state.lastSeq;
    const result = await withAuthRetry(this, `submit_${actionType}`, (session) =>
      this.client.submitMatchActionV2(
        session.accessToken,
        this.state.currentMatchId,
        actionType,
        payload,
        expectedSeq
      )
    );
    this.applySubmitResult(actionType, result, !!result.accepted);
    if (result.accepted) {
      this.logger.info("transport:action_accepted", {
        actionType,
        seq: result.seq,
        lastSeq: this.state.lastSeq,
        turnOwner: result.turnOwner || ""
      });
    }
    return result;
  }

  applySubmitResult(actionType, result, accepted = false) {
    const seq = Number(result.seq ?? -1);
    const lastSeq = Number(result.lastSeq ?? -1);
    this.state.lastSeq = Math.max(
      this.state.lastSeq,
      Number.isFinite(seq) ? seq : -1,
      Number.isFinite(lastSeq) ? lastSeq : -1
    );
    this.state.awaitingRekickoff = !!result.awaitingRekickoff;
    this.state.kickoffGeneration = Math.max(this.state.kickoffGeneration || 0, Number(result.kickoffGeneration || 0));
    this.syncServerTurnOwner(result.turnOwner || "");

    if (hasNonEmptyObject(result.canonicalState)) {
      this.state.canonicalState = result.canonicalState;
    }
    if (this.state.serverTurnOwner) {
      const next = { ...(this.state.canonicalState || {}) };
      next.turn_owner = this.state.serverTurnOwner;
      this.state.canonicalState = next;
    }

    if (accepted && actionType === "kickoff_ready") {
      this.state.localKickoffReady = true;
    }
  }

  async syncActions() {
    if (isBlank(this.state.currentMatchId)) return;
    let cursor = Math.max(-1, Number(this.state.lastSeq || -1));
    let pageCount = 0;
    let hasMore = false;

    do {
      const page = await withAuthRetry(this, "fetch_actions_since_v2", (session) =>
        this.client.fetchMatchActionsSinceV2(
          session.accessToken,
          this.state.currentMatchId,
          cursor,
          ONLINE_ACTION_PAGE_SIZE
        )
      );
      hasMore = !!page.hasMore;
      pageCount += 1;
      for (const action of page.actions || []) {
        if (!action || Number(action.seq) <= this.state.lastSeq) continue;
        this.applyAction(action);
        cursor = Math.max(cursor, Number(action.seq));
      }
    } while (hasMore && pageCount < ONLINE_ACTION_MAX_PAGES_PER_POLL);
  }

  applyAction(action) {
    this.state.lastSeq = Math.max(this.state.lastSeq, Number(action.seq || -1));
    const actionType = normalizeActionType(action.actionType);
    this.logger.debug("transport:recv_action", {
      seq: action.seq,
      actionType,
      actor: action.actorUserId || "",
      matchId: this.state.currentMatchId
    });

    if (actionType === "phase_state" && action.actorUserId === this.state.authoritativeUserId) {
      if (hasNonEmptyObject(action.payload)) {
        this.state.canonicalState = action.payload;
        this.syncServerTurnOwner(action.payload.turn_owner || "");
      }
      return;
    }

    if (actionType === "kickoff_ready") {
      const generation = Number(action.payload?.generation || 0);
      if (generation > 0 && generation > this.state.kickoffGeneration) {
        this.state.kickoffGeneration = generation;
        this.state.localKickoffReady = false;
        this.state.remoteKickoffReady = false;
      }
      if (action.actorUserId === this.state.userId) {
        this.state.localKickoffReady = true;
      } else {
        this.state.remoteKickoffReady = true;
      }
      if (this.state.localKickoffReady && this.state.remoteKickoffReady) {
        this.state.awaitingRekickoff = false;
      }
    }
  }

  async forfeitAndClear(reason) {
    try {
      await withAuthRetry(this, "forfeit_my_active_match", (session) =>
        this.client.forfeitMyActiveMatch(session.accessToken)
      );
    } catch (error) {
      this.logger.warn("match:forfeit_failed", { reason: error.message || "" });
    }
    this.state = clearMatchState(this.state, reason);
    this.lastTurnMismatchKey = "";
    this.lastTurnPatchKey = "";
    this.lastStateIncompleteResyncSeq = -2;
    this.lastStateIncompleteResyncEpochMs = 0;
    this.stateIncompleteStreak = 0;
  }
}
