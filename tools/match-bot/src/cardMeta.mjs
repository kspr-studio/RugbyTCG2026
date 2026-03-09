const RAW_CARD_META = {
  FLANKER: { type: "player", cost: 4, pwr: 3, skl: 1, staMax: 4 },
  PROP: { type: "player", cost: 4, pwr: 4, skl: 0, staMax: 3 },
  PLAYMAKER: { type: "player", cost: 4, pwr: 1, skl: 3, staMax: 3 },
  BREAKER: { type: "player", cost: 5, pwr: 2, skl: 3, staMax: 2 },
  ANCHOR: { type: "player", cost: 2, pwr: 2, skl: 0, staMax: 5 },
  OPPORTUNIST: { type: "player", cost: 3, pwr: 1, skl: 2, staMax: 2 },
  COUNTER_RUCK: { type: "play", cost: 2 },
  QUICK_PASS: { type: "play", cost: 2 },
  DRIVE: { type: "play", cost: 3 },
  TIGHT_PLAY: { type: "tactic", cost: 4 }
};

export const CARD_META = Object.freeze(RAW_CARD_META);
export const SUPPORTED_CARD_IDS = Object.freeze(Object.keys(RAW_CARD_META));

export function normalizeCardId(value) {
  if (typeof value !== "string") return "";
  return value.trim().toUpperCase();
}

export function getCardMeta(cardId) {
  const key = normalizeCardId(cardId);
  if (!key) return null;
  return RAW_CARD_META[key] || null;
}

export function isPlayerCard(cardId) {
  const meta = getCardMeta(cardId);
  return !!meta && meta.type === "player";
}

export function isUtilityCard(cardId) {
  const meta = getCardMeta(cardId);
  if (!meta) return false;
  return meta.type === "play" || meta.type === "tactic";
}
