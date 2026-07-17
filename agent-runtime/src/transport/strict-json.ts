const DEFAULT_MAXIMUM_DEPTH = 32;
const DEFAULT_MAXIMUM_TOKENS = 4096;

export class StrictJsonError extends Error {
  public constructor(message: string) {
    super(message);
    this.name = "StrictJsonError";
  }
}

interface StrictJsonLimits {
  readonly maximumDepth?: number;
  readonly maximumTokens?: number;
}

class JsonScanner {
  readonly #source: string;
  readonly #maximumDepth: number;
  readonly #maximumTokens: number;
  #index = 0;
  #tokens = 0;

  public constructor(source: string, limits: StrictJsonLimits) {
    this.#source = source;
    this.#maximumDepth = limits.maximumDepth ?? DEFAULT_MAXIMUM_DEPTH;
    this.#maximumTokens = limits.maximumTokens ?? DEFAULT_MAXIMUM_TOKENS;
  }

  public scan(): void {
    this.#skipWhitespace();
    this.#scanValue(0);
    this.#skipWhitespace();
    if (this.#index !== this.#source.length) {
      throw new StrictJsonError("JSON contains trailing data.");
    }
  }

  #scanValue(depth: number): void {
    this.#countToken();
    if (depth > this.#maximumDepth) {
      throw new StrictJsonError("JSON nesting exceeds the supported limit.");
    }

    switch (this.#source[this.#index]) {
      case "{":
        this.#scanObject(depth);
        return;
      case "[":
        this.#scanArray(depth);
        return;
      case '"':
        this.#scanString();
        return;
      case "t":
        this.#scanLiteral("true");
        return;
      case "f":
        this.#scanLiteral("false");
        return;
      case "n":
        this.#scanLiteral("null");
        return;
      default:
        this.#scanNumber();
    }
  }

  #scanObject(depth: number): void {
    this.#index += 1;
    this.#skipWhitespace();
    if (this.#consume("}")) {
      return;
    }

    const keys = new Set<string>();
    while (true) {
      if (this.#source[this.#index] !== '"') {
        throw new StrictJsonError("JSON object keys must be strings.");
      }
      this.#countToken();
      const key = this.#scanString();
      if (keys.has(key)) {
        throw new StrictJsonError("JSON contains a duplicate object key.");
      }
      keys.add(key);

      this.#skipWhitespace();
      this.#expect(":");
      this.#skipWhitespace();
      this.#scanValue(depth + 1);
      this.#skipWhitespace();
      if (this.#consume("}")) {
        return;
      }
      this.#expect(",");
      this.#skipWhitespace();
    }
  }

  #scanArray(depth: number): void {
    this.#index += 1;
    this.#skipWhitespace();
    if (this.#consume("]")) {
      return;
    }

    while (true) {
      this.#scanValue(depth + 1);
      this.#skipWhitespace();
      if (this.#consume("]")) {
        return;
      }
      this.#expect(",");
      this.#skipWhitespace();
    }
  }

  #scanString(): string {
    const start = this.#index;
    this.#expect('"');

    while (this.#index < this.#source.length) {
      const character = this.#source[this.#index];
      const codePoint = this.#source.charCodeAt(this.#index);
      if (character === '"') {
        this.#index += 1;
        try {
          return JSON.parse(this.#source.slice(start, this.#index)) as string;
        } catch (error) {
          throw new StrictJsonError(
            `JSON contains an invalid string: ${error instanceof Error ? error.name : "error"}.`,
          );
        }
      }
      if (codePoint <= 0x1f) {
        throw new StrictJsonError("JSON strings cannot contain unescaped control characters.");
      }
      if (character !== "\\") {
        this.#index += 1;
        continue;
      }

      this.#index += 1;
      const escape = this.#source[this.#index];
      if (escape === "u") {
        const digits = this.#source.slice(this.#index + 1, this.#index + 5);
        if (digits.length !== 4 || !/^[0-9A-Fa-f]{4}$/u.test(digits)) {
          throw new StrictJsonError("JSON contains an invalid Unicode escape.");
        }
        this.#index += 5;
      } else if (escape !== undefined && /^(?:["\\/bfnrt])$/u.test(escape)) {
        this.#index += 1;
      } else {
        throw new StrictJsonError("JSON contains an invalid escape sequence.");
      }
    }

    throw new StrictJsonError("JSON contains an unterminated string.");
  }

  #scanLiteral(literal: string): void {
    if (!this.#source.startsWith(literal, this.#index)) {
      throw new StrictJsonError("JSON contains an invalid literal.");
    }
    this.#index += literal.length;
  }

  #scanNumber(): void {
    const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/u.exec(
      this.#source.slice(this.#index),
    );
    if (match?.[0] === undefined) {
      throw new StrictJsonError("JSON contains an invalid value.");
    }
    this.#index += match[0].length;
  }

  #skipWhitespace(): void {
    while (/^[\t\n\r ]$/u.test(this.#source[this.#index] ?? "")) {
      this.#index += 1;
    }
  }

  #consume(expected: string): boolean {
    if (this.#source[this.#index] !== expected) {
      return false;
    }
    this.#index += 1;
    return true;
  }

  #expect(expected: string): void {
    if (!this.#consume(expected)) {
      throw new StrictJsonError("JSON has invalid punctuation.");
    }
  }

  #countToken(): void {
    this.#tokens += 1;
    if (this.#tokens > this.#maximumTokens) {
      throw new StrictJsonError("JSON contains too many values.");
    }
  }
}

export function parseStrictJson(source: string, limits: StrictJsonLimits = {}): unknown {
  if (source.length === 0) {
    throw new StrictJsonError("JSON input is empty.");
  }

  new JsonScanner(source, limits).scan();
  try {
    return JSON.parse(source) as unknown;
  } catch (error) {
    throw new StrictJsonError(
      `JSON parsing failed: ${error instanceof Error ? error.name : "error"}.`,
    );
  }
}
