import fs from "node:fs/promises";

const OPENAI_DEFAULT_BASE_URL = "https://api.openai.com";
const OLLAMA_DEFAULT_BASE_URL = "http://127.0.0.1:11434";
const DEFAULT_HARD_MAX_WAIT_MS = 120000;

function trimReason(value) {
  if (typeof value !== "string") return "";
  const trimmed = value.trim();
  return trimmed.length > 240 ? `${trimmed.slice(0, 237)}...` : trimmed;
}

function normalizeProvider(value, fallback = "openai") {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (raw === "openai" || raw === "ollama") return raw;
  return fallback;
}

function normalizeBaseUrl(value, provider) {
  const raw = typeof value === "string" ? value.trim() : "";
  if (raw) {
    return raw.replace(/\/+$/g, "");
  }
  if (provider === "ollama") return OLLAMA_DEFAULT_BASE_URL;
  return OPENAI_DEFAULT_BASE_URL;
}

function parseJsonSafely(raw) {
  try {
    return JSON.parse(raw);
  } catch (_error) {
    return null;
  }
}

function normalizePromptProfile(value) {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (raw === "full") return "full";
  return "compact";
}

function summarizeBoard(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .slice(0, 8)
    .map((slot) => {
      if (!slot || typeof slot !== "object") return null;
      const id = typeof slot.id === "string" ? slot.id.trim().toUpperCase() : "";
      if (!id) return null;
      const staRaw = Number(slot.sta);
      const sta = Number.isFinite(staRaw) ? Math.max(0, Math.floor(staRaw)) : 0;
      return { id, sta };
    })
    .filter((slot) => !!slot);
}

function summarizeHand(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .map((value) => (typeof value === "string" ? value.trim().toUpperCase() : ""))
    .filter((value) => !!value)
    .slice(0, 12);
}

function collectReferencedCards(candidatePreview, canonicalState) {
  const referenced = new Set();
  for (const candidate of candidatePreview || []) {
    for (const action of candidate.actions || []) {
      const cardId = typeof action.card_id === "string" ? action.card_id.trim().toUpperCase() : "";
      if (cardId) referenced.add(cardId);
    }
  }
  for (const cardId of summarizeHand(canonicalState?.hand_b)) {
    referenced.add(cardId);
  }
  return Array.from(referenced.values());
}

function compactRulesKnowledge(rulesKnowledge, candidatePreview, canonicalState) {
  const rules = rulesKnowledge && typeof rulesKnowledge === "object" ? rulesKnowledge : {};
  const referencedCards = collectReferencedCards(candidatePreview, canonicalState);
  const cardGuidance = {};
  for (const cardId of referencedCards) {
    const guide = rules?.cardGuidance?.[cardId];
    if (!guide || typeof guide !== "object") continue;
    cardGuidance[cardId] = {
      type: typeof guide.type === "string" ? guide.type : "",
      cost: Number.isFinite(Number(guide.cost)) ? Number(guide.cost) : null,
      heuristics: Array.isArray(guide.heuristics) ? guide.heuristics.slice(0, 3) : []
    };
  }

  return {
    strategy_principles: Array.isArray(rules.strategyPrinciples) ? rules.strategyPrinciples.slice(0, 8) : [],
    card_guidance: cardGuidance
  };
}

function toCandidatePreview(candidates, maxCandidates) {
  const limited = Array.isArray(candidates) ? candidates.slice(0, Math.max(1, maxCandidates)) : [];
  return limited.map((candidate) => {
    const actions = Array.isArray(candidate.actions) ? candidate.actions : [];
    return {
      id: candidate.id,
      score: Number(candidate.score ?? 0),
      summary: candidate.summary || "",
      actions: actions.map((action) => ({
        type: action.type,
        card_id: action.payload?.card_id || "",
        reason: action.reason || ""
      }))
    };
  });
}

export class LlmTurnSelector {
  constructor({
    provider = "openai",
    apiKey,
    baseUrl = "",
    model = "gpt-4.1-mini",
    timeoutMs = 1500,
    hardMaxWaitMs = DEFAULT_HARD_MAX_WAIT_MS,
    maxCandidates = 5,
    rulesPath = "",
    logger = null,
    think = false,
    promptProfile = "compact"
  }) {
    this.provider = normalizeProvider(provider, "openai");
    this.apiKey = typeof apiKey === "string" ? apiKey.trim() : "";
    this.baseUrl = normalizeBaseUrl(baseUrl, this.provider);
    this.model = typeof model === "string" && model.trim() ? model.trim() : "gpt-4.1-mini";
    this.timeoutMs = Number.isFinite(Number(timeoutMs)) ? Math.max(0, Math.floor(Number(timeoutMs))) : 1500;
    this.hardMaxWaitMs = Number.isFinite(Number(hardMaxWaitMs))
      ? Math.max(1000, Math.floor(Number(hardMaxWaitMs)))
      : DEFAULT_HARD_MAX_WAIT_MS;
    this.maxCandidates = Math.max(1, Math.floor(Number(maxCandidates) || 5));
    this.rulesPath = typeof rulesPath === "string" ? rulesPath.trim() : "";
    this.logger = logger;
    this.think = !!think;
    this.promptProfile = normalizePromptProfile(promptProfile);
    this.rulesKnowledge = null;
    this.rulesLoadAttempted = false;
  }

  async ensureRulesKnowledge() {
    if (this.rulesLoadAttempted) return;
    this.rulesLoadAttempted = true;
    if (!this.rulesPath) {
      this.rulesKnowledge = {};
      return;
    }
    try {
      const raw = await fs.readFile(this.rulesPath, "utf8");
      const parsed = JSON.parse(raw);
      this.rulesKnowledge = parsed && typeof parsed === "object" ? parsed : {};
    } catch (error) {
      this.rulesKnowledge = {};
      if (this.logger) {
        this.logger.warn("decision:llm_rules_load_failed", {
          path: this.rulesPath,
          reason: error?.message || ""
        });
      }
    }
  }

  buildPrompt({ matchId, lastSeq, canonicalState, candidatePreview, planningMeta }) {
    if (this.promptProfile === "full") {
      return {
        task: "Choose the best legal turn plan for player_b in RugbyTCG.",
        constraints: [
          "Choose exactly one candidate id from the list.",
          "Do not invent actions or candidate IDs.",
          "Prefer plans that improve immediate board/phase outcome and avoid passive losing lines.",
          "If uncertain, choose the highest-score candidate."
        ],
        rules_context: this.rulesKnowledge || {},
        match_context: {
          match_id: matchId || "",
          last_seq: Number(lastSeq ?? -1),
          canonical_state: canonicalState && typeof canonicalState === "object" ? canonicalState : {},
          planning_meta: planningMeta && typeof planningMeta === "object" ? planningMeta : {}
        },
        candidates: candidatePreview
      };
    }

    const state = canonicalState && typeof canonicalState === "object" ? canonicalState : {};
    return {
      task: "Choose best legal candidate for player_b now.",
      constraints: [
        "Return one candidate id from candidates.",
        "No invented ids.",
        "Prefer active, legal improvements over passive lines.",
        "If tied/uncertain, choose higher score."
      ],
      rules_context: compactRulesKnowledge(this.rulesKnowledge, candidatePreview, state),
      match_context: {
        match_id: matchId || "",
        last_seq: Number(lastSeq ?? -1),
        turn_owner: typeof state.turn_owner === "string" ? state.turn_owner : "",
        score_a: Number.isFinite(Number(state.score_a)) ? Number(state.score_a) : 0,
        score_b: Number.isFinite(Number(state.score_b)) ? Number(state.score_b) : 0,
        ball_canonical: Number.isFinite(Number(state.ball_canonical)) ? Number(state.ball_canonical) : 0,
        momentum_a: Number.isFinite(Number(state.momentum_a)) ? Number(state.momentum_a) : 0,
        momentum_b: Number.isFinite(Number(state.momentum_b)) ? Number(state.momentum_b) : 0,
        hand_b: summarizeHand(state.hand_b),
        board_b: summarizeBoard(state.board_b),
        board_a: summarizeBoard(state.board_a),
        planning_meta: planningMeta && typeof planningMeta === "object" ? planningMeta : {}
      },
      candidates: candidatePreview
    };
  }

  async selectTurnPlan({ matchId, lastSeq, canonicalState, candidates, planningMeta = {} }) {
    if (this.provider === "openai" && !this.apiKey) {
      throw new Error("missing_openai_api_key");
    }
    await this.ensureRulesKnowledge();

    const candidatePreview = toCandidatePreview(candidates, this.maxCandidates);
    if (!candidatePreview.length) {
      return null;
    }

    const prompt = this.buildPrompt({
      matchId,
      lastSeq,
      canonicalState,
      candidatePreview,
      planningMeta
    });
    const promptBytes = Buffer.byteLength(JSON.stringify(prompt), "utf8");

    const schema = {
      name: "turn_choice",
      strict: true,
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          candidate_id: { type: "string" },
          reason: { type: "string" }
        },
        required: ["candidate_id", "reason"]
      }
    };

    const content = await this.requestCompletionContent(prompt, schema);

    let choice;
    if (typeof content === "string") {
      choice = parseJsonSafely(content);
      if (!choice || typeof choice !== "object") {
        throw new Error("llm_content_not_json");
      }
    } else if (content && typeof content === "object" && !Array.isArray(content)) {
      choice = content;
    } else {
      throw new Error("llm_content_not_json");
    }

    const candidateId = typeof choice?.candidate_id === "string" ? choice.candidate_id.trim() : "";
    if (!candidateId) {
      throw new Error("llm_missing_candidate_id");
    }

    const reason = trimReason(choice?.reason);
    return { candidateId, reason, model: this.model, provider: this.provider, promptBytes };
  }

  async requestCompletionContent(prompt, schema) {
    if (this.provider === "openai") {
      return await this.requestOpenAiContent(prompt, schema);
    }
    if (this.provider === "ollama") {
      return await this.requestOllamaContent(prompt, schema);
    }
    throw new Error("llm_invalid_provider");
  }

  createAbortControls() {
    const controller = new AbortController();
    const timers = [];
    let abortReason = "";

    if (this.timeoutMs > 0) {
      timers.push(setTimeout(() => {
        abortReason = "llm_timeout";
        controller.abort();
      }, this.timeoutMs));
    }

    timers.push(setTimeout(() => {
      if (!abortReason) {
        abortReason = "llm_watchdog_timeout";
      }
      controller.abort();
    }, this.hardMaxWaitMs));

    return {
      controller,
      signal: controller.signal,
      clear() {
        for (const timer of timers) {
          clearTimeout(timer);
        }
      },
      getAbortReason() {
        return abortReason || "llm_timeout";
      }
    };
  }

  async requestOpenAiContent(prompt, schema) {
    const abort = this.createAbortControls();
    let response;
    try {
      response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.apiKey}`
        },
        body: JSON.stringify({
          model: this.model,
          temperature: 0,
          max_tokens: 180,
          response_format: {
            type: "json_schema",
            json_schema: schema
          },
          messages: [
            {
              role: "system",
              content: "You are a strict RugbyTCG tactical selector. Only return schema-valid JSON."
            },
            {
              role: "user",
              content: JSON.stringify(prompt)
            }
          ]
        }),
        signal: abort.signal
      });
    } catch (error) {
      if (error?.name === "AbortError") {
        throw new Error(abort.getAbortReason());
      }
      throw error;
    } finally {
      abort.clear();
    }

    let rawText = "";
    try {
      rawText = await response.text();
    } catch (_error) {
      rawText = "";
    }

    if (!response.ok) {
      throw new Error(`llm_http_${response.status}`);
    }

    const parsed = parseJsonSafely(rawText);
    if (!parsed || typeof parsed !== "object") {
      throw new Error("llm_invalid_json");
    }

    const content = parsed?.choices?.[0]?.message?.content;
    if ((typeof content !== "string" || !content.trim()) && (!content || typeof content !== "object")) {
      throw new Error("llm_missing_content");
    }
    return content;
  }

  async requestOllamaContent(prompt, schema) {
    const abort = this.createAbortControls();
    let response;
    try {
      response = await fetch(`${this.baseUrl}/api/chat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: this.model,
          stream: false,
          think: this.think,
          format: schema.schema,
          options: {
            temperature: 0,
            num_predict: 96
          },
          messages: [
            {
              role: "system",
              content: "You are a strict RugbyTCG tactical selector. Only return schema-valid JSON."
            },
            {
              role: "user",
              content: JSON.stringify(prompt)
            }
          ]
        }),
        signal: abort.signal
      });
    } catch (error) {
      if (error?.name === "AbortError") {
        throw new Error(abort.getAbortReason());
      }
      throw error;
    } finally {
      abort.clear();
    }

    let rawText = "";
    try {
      rawText = await response.text();
    } catch (_error) {
      rawText = "";
    }

    if (!response.ok) {
      throw new Error(`llm_http_${response.status}`);
    }

    const parsed = parseJsonSafely(rawText);
    if (!parsed || typeof parsed !== "object") {
      throw new Error("llm_invalid_json");
    }

    const content = parsed?.message?.content ?? parsed?.response;
    if ((typeof content !== "string" || !content.trim()) && (!content || typeof content !== "object")) {
      throw new Error("llm_missing_content");
    }
    return content;
  }
}

export function createTurnSelector(config = {}) {
  return new LlmTurnSelector(config);
}
