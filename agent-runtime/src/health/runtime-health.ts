import type { FastifyInstance } from "fastify";

import {
  type ModelProviderHealthResult,
  type ProviderHealthProbe,
  type ProviderHealthProbeRegistration,
  currentProviderHealthProbe,
} from "./model-provider.js";
import { SUPPORTED_PROTOCOL_VERSION, runtimeIdentity } from "../version.js";

export const runtimeHealthCheckNames = [
  "config",
  "logging",
  "protocol",
  "sqlite",
  "provider",
] as const;

export type RuntimeHealthCheckName = (typeof runtimeHealthCheckNames)[number];
export type RuntimeHealthStatus = "STARTING" | "READY" | "DEGRADED" | "STOPPED";

/** Minimum age of the cached provider result before the view re-probes. */
export const DEFAULT_PROVIDER_PROBE_INTERVAL_MILLISECONDS = 30_000;

export interface RuntimeHealthCheckView {
  readonly name: RuntimeHealthCheckName;
  readonly status: "PASS" | "FAIL";
}

export interface RuntimeHealthView {
  readonly status: RuntimeHealthStatus;
  readonly runtimeVersion: string;
  readonly protocolVersion: string;
  readonly checkedAt: string;
  readonly checks: readonly RuntimeHealthCheckView[];
}

export interface RuntimeHealthStateOptions {
  readonly now?: () => number;
  readonly providerProbe?: ProviderHealthProbe;
  readonly probeIntervalMilliseconds?: number;
}

interface ProviderProbeCache {
  readonly probe: ProviderHealthProbe;
  result: ModelProviderHealthResult;
  probedAt: number;
}

export class RuntimeHealthState {
  #status: RuntimeHealthStatus = "STARTING";
  #checkedAt: string;
  readonly #protocolVersion: string;
  readonly #now: () => number;
  readonly #probeIntervalMilliseconds: number;
  readonly #providerProbeCache: ProviderProbeCache | undefined;
  #probeInFlight: Promise<void> | undefined;

  public constructor(
    checkedAt = new Date().toISOString(),
    protocolVersion: string = SUPPORTED_PROTOCOL_VERSION,
    options: RuntimeHealthStateOptions = {},
  ) {
    this.#checkedAt = checkedAt;
    this.#protocolVersion = protocolVersion;
    this.#now = options.now ?? Date.now;
    this.#probeIntervalMilliseconds =
      options.probeIntervalMilliseconds ?? DEFAULT_PROVIDER_PROBE_INTERVAL_MILLISECONDS;
    const registration: ProviderHealthProbeRegistration | undefined =
      options.providerProbe === undefined
        ? currentProviderHealthProbe()
        : { probe: options.providerProbe, result: { ok: true }, probedAt: 0 };
    if (registration !== undefined) {
      this.#providerProbeCache = {
        probe: registration.probe,
        result: registration.result,
        probedAt: registration.probedAt,
      };
    }
  }

  public markReady(): void {
    if (this.#status !== "STARTING") {
      return;
    }
    this.#status = "READY";
  }

  public markStopped(): void {
    this.#status = "STOPPED";
  }

  /**
   * Refreshes the cached provider probe when it is older than the probe
   * interval and waits for the refreshed (or already in-flight) result. The
   * /health route awaits this so a dead provider flips the view immediately.
   */
  public async refresh(): Promise<void> {
    if (this.#probeInFlight !== undefined) {
      await this.#probeInFlight;
      return;
    }
    if (this.#probeDue(this.#now())) {
      await this.#beginProbe();
    }
  }

  public view(): RuntimeHealthView {
    const now = this.#now();
    this.#checkedAt = new Date(now).toISOString();
    if (this.#probeDue(now)) {
      // The handshake path reads views synchronously; a stale cache is kicked
      // to the background here so the next view reflects the fresh probe.
      void this.#beginProbe();
    }
    const providerFailed = this.#providerProbeCache?.result.ok === false;
    return {
      status:
        this.#status === "STOPPED" || this.#status === "STARTING"
          ? this.#status
          : providerFailed
            ? "DEGRADED"
            : this.#status,
      runtimeVersion: runtimeIdentity.version,
      protocolVersion: this.#protocolVersion,
      checkedAt: this.#checkedAt,
      checks: runtimeHealthCheckNames.map((name) => ({
        name,
        status: name === "provider" && providerFailed ? "FAIL" : "PASS",
      })),
    };
  }

  #probeDue(now: number): boolean {
    return (
      this.#providerProbeCache !== undefined &&
      this.#probeInFlight === undefined &&
      now - this.#providerProbeCache.probedAt >= this.#probeIntervalMilliseconds
    );
  }

  #beginProbe(): Promise<void> {
    const cache = this.#providerProbeCache;
    if (cache === undefined) {
      return Promise.resolve();
    }
    const operation = (async (): Promise<void> => {
      const signal = AbortSignal.timeout(cache.probe.timeoutMilliseconds);
      let result: ModelProviderHealthResult;
      try {
        result = await cache.probe.run(signal);
      } catch {
        result = { ok: false, code: "PROVIDER_UNAVAILABLE" };
      }
      if (this.#providerProbeCache === cache) {
        cache.result = result;
        cache.probedAt = this.#now();
      }
    })();
    this.#probeInFlight = operation;
    void operation.then(() => {
      if (this.#probeInFlight === operation) {
        this.#probeInFlight = undefined;
      }
    });
    return operation;
  }
}

export function registerHealthRoute(app: FastifyInstance, state: RuntimeHealthState): void {
  app.get("/health", async (_request, reply) => {
    await state.refresh();
    const view = state.view();
    reply.header("Cache-Control", "no-store");
    return reply.code(view.status === "READY" ? 200 : 503).send(view);
  });
}
