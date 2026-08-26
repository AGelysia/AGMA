export interface ReplayCacheOptions {
  readonly ttlMilliseconds: number;
  readonly maximumEntries: number;
  readonly evictOldestEntries?: boolean;
}

export class HandshakeReplayCache {
  readonly #entries = new Map<string, number>();
  readonly #ttlMilliseconds: number;
  readonly #maximumEntries: number;
  readonly #evictOldestEntries: boolean;

  public constructor(options: ReplayCacheOptions) {
    if (
      !Number.isSafeInteger(options.ttlMilliseconds) ||
      options.ttlMilliseconds < 1 ||
      !Number.isSafeInteger(options.maximumEntries) ||
      options.maximumEntries < 2
    ) {
      throw new TypeError("Replay cache limits must be positive bounded integers.");
    }
    this.#ttlMilliseconds = options.ttlMilliseconds;
    this.#maximumEntries = options.maximumEntries;
    this.#evictOldestEntries = options.evictOldestEntries ?? false;
  }

  public accept(messageId: string, nonce: string, nowMilliseconds: number): boolean {
    this.#purge(nowMilliseconds);
    const messageKey = `message:${messageId}`;
    const nonceKey = `nonce:${nonce}`;
    if (this.#entries.has(messageKey) || this.#entries.has(nonceKey)) {
      return false;
    }
    if (this.#entries.size + 2 > this.#maximumEntries) {
      if (!this.#evictOldestEntries) {
        return false;
      }
      // Eviction is only safe for traffic that is also rejected as stale outside a
      // bounded freshness window; an evicted identity is then too old to replay.
      while (this.#entries.size + 2 > this.#maximumEntries) {
        const oldest = this.#entries.keys().next().value as string | undefined;
        if (oldest === undefined) {
          break;
        }
        this.#entries.delete(oldest);
      }
    }

    const expiresAt = nowMilliseconds + this.#ttlMilliseconds;
    this.#entries.set(messageKey, expiresAt);
    this.#entries.set(nonceKey, expiresAt);
    return true;
  }

  public get size(): number {
    return this.#entries.size;
  }

  #purge(nowMilliseconds: number): void {
    for (const [key, expiresAt] of this.#entries) {
      if (expiresAt <= nowMilliseconds) {
        this.#entries.delete(key);
      }
    }
  }
}
