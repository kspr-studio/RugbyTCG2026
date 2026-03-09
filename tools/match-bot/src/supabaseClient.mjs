function isBlank(value) {
  return typeof value !== "string" || value.trim().length === 0;
}

function parsePayload(value) {
  if (!value) return {};
  if (typeof value === "object" && !Array.isArray(value)) return value;
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        return parsed;
      }
      return {};
    } catch (_error) {
      return {};
    }
  }
  return {};
}

function parseCreatedAtEpochMs(value) {
  if (typeof value !== "string" || !value.trim()) return 0;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export class SupabaseClient {
  constructor({ baseUrl, publishableKey, logger, timeoutMs = 10000, clientVersion = 2 }) {
    if (isBlank(baseUrl)) throw new Error("Missing SUPABASE_URL");
    if (isBlank(publishableKey)) throw new Error("Missing SUPABASE_PUBLISHABLE_KEY");
    this.baseUrl = baseUrl.trim().replace(/\/+$/g, "");
    this.publishableKey = publishableKey.trim();
    this.logger = logger;
    this.timeoutMs = timeoutMs;
    this.clientVersion = clientVersion;
  }

  async signInAnonymously() {
    const root = await this.requestJson({
      method: "POST",
      endpoint: "/auth/v1/signup",
      body: { data: {} },
      expectArray: false
    });
    return this.parseSession(root);
  }

  async refreshSession(refreshToken) {
    if (isBlank(refreshToken)) throw new Error("refreshToken is empty");
    const root = await this.requestJson({
      method: "POST",
      endpoint: "/auth/v1/token",
      query: { grant_type: "refresh_token" },
      body: { refresh_token: refreshToken.trim() },
      expectArray: false
    });
    return this.parseSession(root);
  }

  async fetchProfile(accessToken, userId) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    if (isBlank(userId)) throw new Error("userId is empty");
    const rows = await this.requestJson({
      method: "GET",
      endpoint: "/rest/v1/profiles",
      token: accessToken,
      query: {
        user_id: `eq.${userId.trim()}`,
        select: "user_id,public_id,username,display_name,is_guest"
      },
      expectArray: true
    });
    if (!rows.length) throw new Error("No profile row found for user");
    const row = rows[0] || {};
    return {
      userId: row.user_id || "",
      publicId: row.public_id || "",
      username: row.username || "",
      displayName: row.display_name || "",
      isGuest: !!row.is_guest
    };
  }

  async fetchLatestIncomingChallenge(accessToken, userId) {
    if (isBlank(accessToken) || isBlank(userId)) return null;
    const rows = await this.requestJson({
      method: "GET",
      endpoint: "/rest/v1/challenge_requests",
      token: accessToken,
      query: {
        to_user_id: `eq.${userId.trim()}`,
        status: "eq.pending",
        select: "id,from_user_id,expires_at,mode,created_at",
        order: "created_at.desc",
        limit: "1"
      },
      expectArray: true
    });
    if (!rows.length) return null;
    const row = rows[0] || {};
    return {
      challengeId: row.id || "",
      fromUserId: row.from_user_id || "",
      expiresAt: row.expires_at || "",
      mode: row.mode || "casual",
      createdAt: row.created_at || ""
    };
  }

  async respondChallenge(accessToken, challengeId, accept = true) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    if (isBlank(challengeId)) throw new Error("challengeId is empty");
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/respond_challenge",
      token: accessToken,
      body: {
        p_challenge_id: challengeId.trim(),
        p_accept: !!accept
      },
      expectArray: true
    });
    if (!rows.length) throw new Error("respond_challenge returned no rows");
    const row = rows[0] || {};
    return {
      status: row.status || "",
      matchId: row.match_id || ""
    };
  }

  async fetchActiveMatch(accessToken, userId) {
    if (isBlank(accessToken) || isBlank(userId)) return null;
    const uid = userId.trim();
    const rows = await this.requestJson({
      method: "GET",
      endpoint: "/rest/v1/matches",
      token: accessToken,
      query: {
        or: `(player_a.eq.${uid},player_b.eq.${uid})`,
        status: "in.(pending,active)",
        select: "id,status,player_a,player_b,created_at",
        order: "created_at.desc",
        limit: "1"
      },
      expectArray: true
    });
    if (!rows.length) return null;
    const row = rows[0] || {};
    return {
      matchId: row.id || "",
      status: row.status || "",
      playerA: row.player_a || "",
      playerB: row.player_b || "",
      createdAt: row.created_at || ""
    };
  }

  async joinMatchV2(accessToken, matchId) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    if (isBlank(matchId)) throw new Error("matchId is empty");
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/join_match_v2",
      token: accessToken,
      body: {
        p_match_id: matchId.trim(),
        p_client_version: this.clientVersion
      },
      expectArray: true
    });
    if (!rows.length) throw new Error("join_match_v2 returned no rows");
    const row = rows[0] || {};
    return {
      accepted: !!row.accepted,
      reason: row.reason || "",
      matchId: row.match_id || matchId.trim(),
      status: row.status || "",
      playerA: row.player_a || "",
      playerB: row.player_b || "",
      yourUserId: row.your_user_id || "",
      protocolVersion: Number(row.protocol_version || 0),
      lastSeq: Number(row.last_seq ?? -1),
      turnOwner: row.turn_owner || "",
      turnRemainingMs: Number(row.turn_remaining_ms ?? -1),
      matchElapsedMs: Number(row.match_elapsed_ms ?? -1),
      awaitingRekickoff: !!row.awaiting_rekickoff,
      kickoffGeneration: Number(row.kickoff_generation || 0),
      canonicalState: parsePayload(row.canonical_state),
      localKickoffReady: !!row.local_kickoff_ready,
      remoteKickoffReady: !!row.remote_kickoff_ready
    };
  }

  async submitMatchActionV2(accessToken, matchId, actionType, payload = {}, expectedSeq = null) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    if (isBlank(matchId)) throw new Error("matchId is empty");
    if (isBlank(actionType)) throw new Error("actionType is empty");
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/submit_match_action_v2",
      token: accessToken,
      body: {
        p_match_id: matchId.trim(),
        p_action_type: actionType.trim(),
        p_payload: payload && typeof payload === "object" ? payload : {},
        p_expected_seq: expectedSeq === null || expectedSeq === undefined ? null : expectedSeq,
        p_client_version: this.clientVersion
      },
      expectArray: true
    });
    if (!rows.length) throw new Error("submit_match_action_v2 returned no rows");
    const row = rows[0] || {};
    return {
      accepted: !!row.accepted,
      seq: Number(row.seq ?? -1),
      reason: row.reason || "",
      lastSeq: Number(row.last_seq ?? -1),
      turnOwner: row.turn_owner || "",
      turnRemainingMs: Number(row.turn_remaining_ms ?? -1),
      matchElapsedMs: Number(row.match_elapsed_ms ?? -1),
      awaitingRekickoff: !!row.awaiting_rekickoff,
      kickoffGeneration: Number(row.kickoff_generation ?? 0),
      canonicalState: parsePayload(row.canonical_state)
    };
  }

  async fetchMatchActionsSinceV2(accessToken, matchId, afterSeq = -1, pageSize = 200) {
    if (isBlank(accessToken) || isBlank(matchId)) return { actions: [], hasMore: false };
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/fetch_actions_since_v2",
      token: accessToken,
      body: {
        p_match_id: matchId.trim(),
        p_after_seq: Math.max(-1, Math.floor(Number(afterSeq) || -1)),
        p_page_size: Math.max(1, Math.min(500, Math.floor(Number(pageSize) || 200))),
        p_client_version: this.clientVersion
      },
      expectArray: true
    });
    const actions = [];
    let hasMore = false;
    for (const row of rows) {
      if (!row || typeof row !== "object") continue;
      const action = {
        seq: Number(row.seq ?? -1),
        actorUserId: row.actor_user_id || "",
        actionType: row.action_type || "",
        payload: parsePayload(row.payload),
        createdAtEpochMs: parseCreatedAtEpochMs(row.created_at || "")
      };
      if (action.seq >= 0) {
        actions.push(action);
      }
      hasMore = hasMore || !!row.has_more;
    }
    actions.sort((a, b) => a.seq - b.seq);
    return { actions, hasMore };
  }

  async heartbeatPresenceV2(accessToken, matchId = null, appActive = true) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/heartbeat_presence_v2",
      token: accessToken,
      body: {
        p_match_id: isBlank(matchId) ? null : matchId.trim(),
        p_app_active: !!appActive,
        p_client_version: this.clientVersion
      },
      expectArray: true
    });
    if (!rows.length) throw new Error("heartbeat_presence_v2 returned no rows");
    const row = rows[0] || {};
    return {
      accepted: !!row.accepted,
      reason: row.reason || "",
      onlineCount: Number(row.online_count ?? -1),
      awaitingRekickoff: !!row.awaiting_rekickoff,
      kickoffGeneration: Number(row.kickoff_generation ?? 0)
    };
  }

  async forfeitMyActiveMatch(accessToken) {
    if (isBlank(accessToken)) throw new Error("accessToken is empty");
    const rows = await this.requestJson({
      method: "POST",
      endpoint: "/rest/v1/rpc/forfeit_my_active_match",
      token: accessToken,
      body: {},
      expectArray: true
    });
    if (!rows.length) {
      return {
        matchId: "",
        status: "none"
      };
    }
    const row = rows[0] || {};
    return {
      matchId: row.match_id || "",
      status: row.status || ""
    };
  }

  parseSession(root) {
    const source = root && typeof root === "object" ? root : {};
    const user = source.user && typeof source.user === "object" ? source.user : {};
    const sessionRoot = source.session && typeof source.session === "object" ? source.session : source;
    const accessToken = typeof sessionRoot.access_token === "string" ? sessionRoot.access_token.trim() : "";
    const refreshToken = typeof sessionRoot.refresh_token === "string" ? sessionRoot.refresh_token.trim() : "";
    let expiresAtEpochSeconds = Number(sessionRoot.expires_at || 0);
    const expiresIn = Number(sessionRoot.expires_in || 0);
    if ((!Number.isFinite(expiresAtEpochSeconds) || expiresAtEpochSeconds <= 0) && Number.isFinite(expiresIn) && expiresIn > 0) {
      expiresAtEpochSeconds = Math.floor(Date.now() / 1000) + Math.floor(expiresIn);
    }
    const userId = typeof user.id === "string" ? user.id.trim() : "";
    if (isBlank(accessToken) || isBlank(refreshToken) || isBlank(userId)) {
      throw new Error(`Supabase auth response missing session fields: ${JSON.stringify(source)}`);
    }
    return {
      accessToken,
      refreshToken,
      expiresAtEpochSeconds: Number.isFinite(expiresAtEpochSeconds) ? expiresAtEpochSeconds : 0,
      userId
    };
  }

  async requestJson({ method, endpoint, token = "", query = null, body = null, expectArray = false }) {
    const url = new URL(endpoint, this.baseUrl);
    if (query && typeof query === "object") {
      for (const [key, value] of Object.entries(query)) {
        if (value === null || value === undefined) continue;
        url.searchParams.append(key, String(value));
      }
    }

    const headers = {
      apikey: this.publishableKey,
      Accept: "application/json"
    };
    if (!isBlank(token)) {
      headers.Authorization = `Bearer ${token.trim()}`;
    }
    if (method !== "GET") {
      headers["Content-Type"] = "application/json";
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    let response;
    try {
      response = await fetch(url, {
        method,
        headers,
        body: method === "GET" ? undefined : JSON.stringify(body || {}),
        signal: controller.signal
      });
    } catch (error) {
      if (error && error.name === "AbortError") {
        throw new Error(`HTTP timeout on ${url.toString()}`);
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }

    let text = "";
    try {
      text = await response.text();
    } catch (_error) {
      text = "";
    }

    if (!response.ok) {
      throw new Error(`HTTP ${response.status} on ${url.toString()}: ${text}`);
    }

    if (isBlank(text)) return expectArray ? [] : {};
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      throw new Error(`Invalid JSON on ${url.toString()}: ${text}`);
    }
    if (expectArray) {
      if (Array.isArray(parsed)) return parsed;
      throw new Error(`Expected JSON array on ${url.toString()}`);
    }
    if (parsed && typeof parsed === "object") return parsed;
    throw new Error(`Expected JSON object on ${url.toString()}`);
  }
}
