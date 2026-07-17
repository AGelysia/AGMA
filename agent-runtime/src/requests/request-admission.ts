export interface RequestAdmissionLimits {
  readonly maximumConcurrent: number;
  readonly maximumQueued: number;
  readonly perPlayerCooldownMilliseconds: number;
  readonly dailyRequestsPerPlayer: number;
}

export type RequestAdmissionRejection =
  | "PLAYER_BUSY"
  | "PLAYER_RATE_LIMITED"
  | "PLAYER_DAILY_LIMIT"
  | "RUNTIME_BUSY";

export type RequestAdmissionDecision =
  | { readonly accepted: true; readonly queued: boolean }
  | { readonly accepted: false; readonly reason: RequestAdmissionRejection };

export interface RequestAdmissionEntry {
  readonly requestId: string;
  readonly playerUuid: string;
  start(): void;
}

interface PlayerAdmissionState {
  outstandingRequestId: string | undefined;
  cooldownUntil: number;
  day: string;
  dailyCount: number;
}

function utcDay(timestamp: number): string {
  return new Date(timestamp).toISOString().slice(0, 10);
}

export class RequestAdmissionController {
  readonly #limits: RequestAdmissionLimits;
  readonly #now: () => number;
  readonly #active = new Map<string, RequestAdmissionEntry>();
  readonly #queued: RequestAdmissionEntry[] = [];
  readonly #players = new Map<string, PlayerAdmissionState>();

  public constructor(limits: RequestAdmissionLimits, now: () => number = Date.now) {
    if (
      !Number.isSafeInteger(limits.maximumConcurrent) ||
      limits.maximumConcurrent < 1 ||
      !Number.isSafeInteger(limits.maximumQueued) ||
      limits.maximumQueued < 0 ||
      !Number.isSafeInteger(limits.perPlayerCooldownMilliseconds) ||
      limits.perPlayerCooldownMilliseconds < 0 ||
      !Number.isSafeInteger(limits.dailyRequestsPerPlayer) ||
      limits.dailyRequestsPerPlayer < 1
    ) {
      throw new TypeError("Request admission limits must be bounded non-negative integers.");
    }
    this.#limits = limits;
    this.#now = now;
  }

  public admit(entry: RequestAdmissionEntry): RequestAdmissionDecision {
    const now = this.#now();
    const state = this.#playerState(entry.playerUuid, now);
    if (state.outstandingRequestId !== undefined) {
      return { accepted: false, reason: "PLAYER_BUSY" };
    }
    if (state.cooldownUntil > now) {
      return { accepted: false, reason: "PLAYER_RATE_LIMITED" };
    }
    if (state.dailyCount >= this.#limits.dailyRequestsPerPlayer) {
      return { accepted: false, reason: "PLAYER_DAILY_LIMIT" };
    }

    const queued = this.#active.size >= this.#limits.maximumConcurrent;
    if (queued && this.#queued.length >= this.#limits.maximumQueued) {
      return { accepted: false, reason: "RUNTIME_BUSY" };
    }

    state.outstandingRequestId = entry.requestId;
    state.cooldownUntil = now + this.#limits.perPlayerCooldownMilliseconds;
    state.dailyCount += 1;
    if (queued) {
      this.#queued.push(entry);
    } else {
      this.#activate(entry);
    }
    return { accepted: true, queued };
  }

  public cancelQueued(requestId: string, playerUuid: string): boolean {
    const index = this.#queued.findIndex(
      (entry) => entry.requestId === requestId && entry.playerUuid === playerUuid,
    );
    if (index < 0) {
      return false;
    }
    const [entry] = this.#queued.splice(index, 1);
    if (entry !== undefined) {
      this.#finishPlayer(entry);
    }
    return true;
  }

  public releaseActive(requestId: string): boolean {
    const entry = this.#active.get(requestId);
    if (entry === undefined) {
      return false;
    }
    this.#active.delete(requestId);
    this.#finishPlayer(entry);
    this.#drain();
    return true;
  }

  public phase(requestId: string): "ACTIVE" | "QUEUED" | undefined {
    if (this.#active.has(requestId)) {
      return "ACTIVE";
    }
    if (this.#queued.some((entry) => entry.requestId === requestId)) {
      return "QUEUED";
    }
    return undefined;
  }

  public get activeCount(): number {
    return this.#active.size;
  }

  public get queuedCount(): number {
    return this.#queued.length;
  }

  #playerState(playerUuid: string, now: number): PlayerAdmissionState {
    const day = utcDay(now);
    const current = this.#players.get(playerUuid);
    if (current === undefined) {
      const created: PlayerAdmissionState = {
        outstandingRequestId: undefined,
        cooldownUntil: 0,
        day,
        dailyCount: 0,
      };
      this.#players.set(playerUuid, created);
      return created;
    }
    if (current.day !== day) {
      current.day = day;
      current.dailyCount = 0;
    }
    return current;
  }

  #activate(entry: RequestAdmissionEntry): void {
    this.#active.set(entry.requestId, entry);
    entry.start();
  }

  #drain(): void {
    while (this.#active.size < this.#limits.maximumConcurrent && this.#queued.length > 0) {
      const next = this.#queued.shift();
      if (next !== undefined) {
        this.#activate(next);
      }
    }
  }

  #finishPlayer(entry: RequestAdmissionEntry): void {
    const state = this.#players.get(entry.playerUuid);
    if (state?.outstandingRequestId === entry.requestId) {
      state.outstandingRequestId = undefined;
    }
  }
}
