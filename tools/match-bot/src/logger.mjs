const LEVEL_VALUE = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40
};

function normalizeLevel(value) {
  if (typeof value !== "string") return "info";
  const key = value.trim().toLowerCase();
  return Object.prototype.hasOwnProperty.call(LEVEL_VALUE, key) ? key : "info";
}

export class Logger {
  constructor(level = "info") {
    this.level = normalizeLevel(level);
  }

  setLevel(level) {
    this.level = normalizeLevel(level);
  }

  shouldLog(level) {
    const threshold = LEVEL_VALUE[this.level] ?? LEVEL_VALUE.info;
    const incoming = LEVEL_VALUE[normalizeLevel(level)] ?? LEVEL_VALUE.info;
    return incoming >= threshold;
  }

  log(level, message, fields = null) {
    const normalizedLevel = normalizeLevel(level);
    if (!this.shouldLog(normalizedLevel)) return;

    const timestamp = new Date().toISOString();
    const prefix = `${timestamp} [${normalizedLevel.toUpperCase()}]`;
    if (fields && typeof fields === "object") {
      console.log(`${prefix} ${message} ${JSON.stringify(fields)}`);
      return;
    }
    console.log(`${prefix} ${message}`);
  }

  debug(message, fields = null) {
    this.log("debug", message, fields);
  }

  info(message, fields = null) {
    this.log("info", message, fields);
  }

  warn(message, fields = null) {
    this.log("warn", message, fields);
  }

  error(message, fields = null) {
    this.log("error", message, fields);
  }
}
