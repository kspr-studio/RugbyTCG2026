import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import pdfParse from "pdf-parse";

import { CARD_META, SUPPORTED_CARD_IDS } from "../src/cardMeta.mjs";

const TOOL_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DEFAULT_RULES_PDF = "C:/Users/Rickesh Singh/Desktop/RugbyTCG-Rules.pdf";
const DEFAULT_CARDS_PDF = "C:/Users/Rickesh Singh/Desktop/RugbyTCG-Cards.pdf";
const DEFAULT_OUTPUT_JSON = path.join(TOOL_DIR, "data", "rules_knowledge.json");

function parseArgs(argv) {
  const out = {};
  for (const part of argv) {
    if (!part.startsWith("--")) continue;
    const stripped = part.slice(2);
    const idx = stripped.indexOf("=");
    if (idx < 0) {
      out[stripped] = true;
      continue;
    }
    out[stripped.slice(0, idx)] = stripped.slice(idx + 1);
  }
  return out;
}

function splitNonEmptyLines(text) {
  if (typeof text !== "string" || !text.trim()) return [];
  return text
    .split(/\r?\n/g)
    .map((line) => line.trim())
    .filter(Boolean);
}

function normalizeForSearch(text) {
  return (text || "")
    .toUpperCase()
    .replace(/[^A-Z0-9 ]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function uniqueLimit(values, limit) {
  const out = [];
  for (const value of values) {
    if (!value) continue;
    if (out.includes(value)) continue;
    out.push(value);
    if (out.length >= limit) break;
  }
  return out;
}

function extractTopRuleLines(rulesLines) {
  const out = [];
  const seen = new Set();
  for (const line of rulesLines) {
    if (line.length < 20) continue;
    const normalized = normalizeForSearch(line);
    if (normalized.length < 20) continue;
    if (seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(line);
    if (out.length >= 30) break;
  }
  return out;
}

function inferCardHeuristics(cardId) {
  if (cardId === "COUNTER_RUCK") {
    return [
      "Best when trailing on score/phase pressure.",
      "Lower value when already ahead."
    ];
  }
  if (cardId === "DRIVE") {
    return [
      "Use once per turn to shift ball position toward attack.",
      "Avoid wasting when already at best ball position."
    ];
  }
  if (cardId === "QUICK_PASS") {
    return [
      "Boosts temporary skill and phase total immediately.",
      "Strong when board already has at least one player."
    ];
  }
  if (cardId === "TIGHT_PLAY") {
    return [
      "Persistent pressure tool; avoid duplicate casts in same phase.",
      "Most useful when you expect multiple board contests."
    ];
  }
  if (cardId === "PLAYMAKER") {
    return [
      "Provides flexible value and can improve follow-up turns."
    ];
  }
  if (cardId === "ANCHOR") {
    return [
      "Low-cost stabilizer; useful when tempo or momentum is tight."
    ];
  }
  return ["Play when it improves immediate projected phase total."];
}

function extractCardLines(allLines, cardId) {
  const display = cardId.replace(/_/g, " ");
  const terms = uniqueLimit([
    normalizeForSearch(cardId),
    normalizeForSearch(display)
  ], 3);
  const matches = [];
  for (const line of allLines) {
    const hay = normalizeForSearch(line);
    if (!hay) continue;
    if (terms.some((term) => term && hay.includes(term))) {
      matches.push(line);
    }
  }
  return uniqueLimit(matches, 5);
}

async function readPdfText(filePath) {
  const data = await fs.readFile(filePath);
  const parsed = await pdfParse(data);
  return typeof parsed?.text === "string" ? parsed.text : "";
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const rulesPdf = (args.rules || DEFAULT_RULES_PDF).trim();
  const cardsPdf = (args.cards || DEFAULT_CARDS_PDF).trim();
  const outputPath = path.resolve((args.out || DEFAULT_OUTPUT_JSON).trim());

  const [rulesText, cardsText] = await Promise.all([
    readPdfText(rulesPdf),
    readPdfText(cardsPdf)
  ]);

  const rulesLines = splitNonEmptyLines(rulesText);
  const cardsLines = splitNonEmptyLines(cardsText);
  const allLines = [...cardsLines, ...rulesLines];

  const cardGuidance = {};
  for (const cardId of SUPPORTED_CARD_IDS) {
    const meta = CARD_META[cardId] || {};
    cardGuidance[cardId] = {
      type: meta.type || "",
      cost: Number(meta.cost ?? 0),
      heuristics: inferCardHeuristics(cardId),
      source_lines: extractCardLines(allLines, cardId)
    };
  }

  const out = {
    version: 1,
    generatedAtIso: new Date().toISOString(),
    sources: {
      rulesPdf,
      cardsPdf
    },
    strategyPrinciples: [
      "Prioritize legal moves that improve immediate phase totals.",
      "Do not auto-end turn solely because score is behind.",
      "Preserve deterministic fallback behavior whenever model output is invalid."
    ],
    rulesSummary: extractTopRuleLines(rulesLines),
    cardGuidance
  };

  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, `${JSON.stringify(out, null, 2)}\n`, "utf8");
  process.stdout.write(`Wrote ${outputPath}\n`);
}

main().catch((error) => {
  const message = error?.message || String(error);
  process.stderr.write(`extract-knowledge failed: ${message}\n`);
  process.exit(1);
});
