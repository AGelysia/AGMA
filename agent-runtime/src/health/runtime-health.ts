import type { FastifyInstance } from "fastify";

import { SUPPORTED_PROTOCOL_VERSION, runtimeIdentity } from "../version.js";

export const runtimeHealthCheckNames = [
  "config",
  "logging",
  "protocol",
  "sqlite",
  "provider",
] as const;

export type RuntimeHealthCheckName = (typeof runtimeHealthCheckNames)[number];
export type RuntimeHealthStatus = "STARTING" | "READY" | "STOPPED";

export interface RuntimeHealthCheckView {
  readonly name: RuntimeHealthCheckName;
  readonly status: "PASS";
}

export interface RuntimeHealthView {
  readonly status: RuntimeHealthStatus;
  readonly runtimeVersion: string;
  readonly protocolVersion: string;
  readonly checkedAt: string;
  readonly checks: readonly RuntimeHealthCheckView[];
}

export class RuntimeHealthState {
  #status: RuntimeHealthStatus = "STARTING";
  readonly #checkedAt: string;

  public constructor(checkedAt = new Date().toISOString()) {
    this.#checkedAt = checkedAt;
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

  public view(): RuntimeHealthView {
    return {
      status: this.#status,
      runtimeVersion: runtimeIdentity.version,
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      checkedAt: this.#checkedAt,
      checks: runtimeHealthCheckNames.map((name) => ({ name, status: "PASS" })),
    };
  }
}

export function registerHealthRoute(app: FastifyInstance, state: RuntimeHealthState): void {
  app.get("/health", async (_request, reply) => {
    const view = state.view();
    reply.header("Cache-Control", "no-store");
    return reply.code(view.status === "READY" ? 200 : 503).send(view);
  });
}
