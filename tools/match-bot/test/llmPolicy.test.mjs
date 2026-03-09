import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { LlmTurnSelector } from "../src/llmPolicy.mjs";

function makeCandidates() {
  return [
    {
      id: "cand_1",
      score: 4.2,
      summary: "best",
      actions: [{ type: "play_card", payload: { card_id: "PROP" }, reason: "best_projected_total" }]
    },
    {
      id: "cand_2",
      score: 3.1,
      summary: "alt",
      actions: [{ type: "end_turn", payload: {}, reason: "candidate_end_turn" }]
    }
  ];
}

test("llm selector parses schema-valid response", async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "llm-policy-"));
  const rulesPath = path.join(dir, "rules.json");
  await fs.writeFile(rulesPath, JSON.stringify({ strategyPrinciples: ["x"] }), "utf8");

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    async text() {
      return JSON.stringify({
        choices: [
          {
            message: {
              content: JSON.stringify({
                candidate_id: "cand_2",
                reason: "Safer line."
              })
            }
          }
        ]
      });
    }
  });

  try {
    const selector = new LlmTurnSelector({
      apiKey: "test-key",
      model: "gpt-4.1-mini",
      timeoutMs: 1500,
      rulesPath
    });
    const out = await selector.selectTurnPlan({
      matchId: "match-1",
      lastSeq: 9,
      canonicalState: { turn_owner: "player_b" },
      candidates: makeCandidates()
    });
    assert.equal(out.candidateId, "cand_2");
    assert.equal(typeof out.reason, "string");
    assert.ok(out.promptBytes > 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("llm selector throws when model content is not json", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    async text() {
      return JSON.stringify({
        choices: [
          {
            message: {
              content: "not-json"
            }
          }
        ]
      });
    }
  });

  try {
    const selector = new LlmTurnSelector({
      apiKey: "test-key",
      model: "gpt-4.1-mini",
      timeoutMs: 1500
    });

    await assert.rejects(
      () => selector.selectTurnPlan({
        matchId: "match-1",
        lastSeq: 4,
        canonicalState: { turn_owner: "player_b" },
        candidates: makeCandidates()
      }),
      /llm_content_not_json/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("llm selector parses ollama response", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    async text() {
      return JSON.stringify({
        message: {
          content: JSON.stringify({
            candidate_id: "cand_1",
            reason: "Pressure line"
          })
        }
      });
    }
  });

  try {
    const selector = new LlmTurnSelector({
      provider: "ollama",
      model: "qwen2.5:3b",
      timeoutMs: 5000
    });
    const out = await selector.selectTurnPlan({
      matchId: "match-2",
      lastSeq: 12,
      canonicalState: { turn_owner: "player_b" },
      candidates: makeCandidates()
    });
    assert.equal(out.candidateId, "cand_1");
    assert.equal(out.provider, "ollama");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("llm selector supports timeout=0 with hard watchdog", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, options = {}) => {
    return await new Promise((_resolve, reject) => {
      const signal = options.signal;
      if (signal) {
        signal.addEventListener("abort", () => {
          const err = new Error("aborted");
          err.name = "AbortError";
          reject(err);
        }, { once: true });
      }
    });
  };

  try {
    const selector = new LlmTurnSelector({
      provider: "ollama",
      model: "qwen2.5:3b",
      timeoutMs: 0,
      hardMaxWaitMs: 1000
    });
    await assert.rejects(
      () => selector.selectTurnPlan({
        matchId: "match-watchdog",
        lastSeq: 2,
        canonicalState: { turn_owner: "player_b" },
        candidates: makeCandidates()
      }),
      /llm_watchdog_timeout/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("llm selector throws when openai provider is configured without key", async () => {
  const selector = new LlmTurnSelector({
    provider: "openai",
    apiKey: "",
    model: "gpt-4.1-mini",
    timeoutMs: 1500
  });

  await assert.rejects(
    () => selector.selectTurnPlan({
      matchId: "match-1",
      lastSeq: 4,
      canonicalState: { turn_owner: "player_b" },
      candidates: makeCandidates()
    }),
    /missing_openai_api_key/
  );
});
