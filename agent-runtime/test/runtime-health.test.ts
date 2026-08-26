import Fastify from "fastify";
import { describe, expect, it, vi } from "vitest";

import {
  checkModelProvider,
  type ModelProviderHealthResult,
  type ProviderHealthProbe,
} from "../src/health/model-provider.js";
import {
  DEFAULT_PROVIDER_PROBE_INTERVAL_MILLISECONDS,
  registerHealthRoute,
  RuntimeHealthState,
} from "../src/health/runtime-health.js";

function clock(start: number): { now(): number; advance(milliseconds: number): void } {
  let current = start;
  return {
    now: () => current,
    advance: (milliseconds) => {
      current += milliseconds;
    },
  };
}

function probe(run: ProviderHealthProbe["run"], timeoutMilliseconds = 25): ProviderHealthProbe {
  return { timeoutMilliseconds, run };
}

function healthyRun() {
  return vi.fn(async (): Promise<ModelProviderHealthResult> => ({ ok: true }));
}

function healthyCheck() {
  return vi.fn(async (): Promise<ModelProviderHealthResult> => ({ ok: true }));
}

describe("runtime health view", () => {
  it("refreshes checkedAt every time a view is built", () => {
    const time = clock(1_000_000);
    const state = new RuntimeHealthState("2026-08-26T00:00:00.000Z", "1.0", { now: time.now });
    state.markReady();

    const first = state.view();
    expect(first.checkedAt).toBe(new Date(1_000_000).toISOString());
    time.advance(5_000);
    const second = state.view();
    expect(second.checkedAt).toBe(new Date(1_005_000).toISOString());
    expect(first.checkedAt).not.toBe(second.checkedAt);
  });

  it("serves the cached provider result between probes and re-probes after the interval", async () => {
    const time = clock(1_000_000);
    const run = healthyRun().mockResolvedValueOnce({ ok: true }).mockResolvedValueOnce({
      ok: false,
      code: "PROVIDER_UNAVAILABLE",
    });
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
      providerProbe: probe(run),
      probeIntervalMilliseconds: 30_000,
    });
    state.markReady();

    await state.refresh();
    expect(run).toHaveBeenCalledOnce();
    expect(state.view()).toMatchObject({ status: "READY" });
    expect(state.view().checks).toContainEqual({ name: "provider", status: "PASS" });

    time.advance(29_999);
    await state.refresh();
    expect(run).toHaveBeenCalledOnce();

    time.advance(1);
    await state.refresh();
    expect(run).toHaveBeenCalledTimes(2);
    const degraded = state.view();
    expect(degraded.status).toBe("DEGRADED");
    expect(degraded.checks).toContainEqual({ name: "provider", status: "FAIL" });
  });

  it("recovers to READY once the provider answers again", async () => {
    const time = clock(1_000_000);
    const run = healthyRun()
      .mockResolvedValueOnce({ ok: false, code: "PROVIDER_UNAVAILABLE" })
      .mockResolvedValueOnce({ ok: true });
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
      providerProbe: probe(run),
      probeIntervalMilliseconds: 30_000,
    });
    state.markReady();

    await state.refresh();
    expect(state.view().status).toBe("DEGRADED");

    time.advance(30_000);
    await state.refresh();
    expect(state.view()).toMatchObject({ status: "READY" });
    expect(state.view().checks).toContainEqual({ name: "provider", status: "PASS" });
  });

  it("keeps STOPPED terminal even when the provider probe fails", async () => {
    const time = clock(1_000_000);
    const run = healthyRun().mockResolvedValue({ ok: false, code: "PROVIDER_UNAVAILABLE" });
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
      providerProbe: probe(run),
      probeIntervalMilliseconds: 30_000,
    });
    state.markReady();

    await state.refresh();
    expect(state.view().status).toBe("DEGRADED");

    state.markStopped();
    expect(state.view().status).toBe("STOPPED");
  });

  it("refreshes in the background from synchronous views without duplicate probes", async () => {
    const time = clock(1_000_000);
    let releaseProbe: (() => void) | undefined;
    const probeGate = new Promise<void>((resolve) => {
      releaseProbe = resolve;
    });
    const run = vi.fn(
      (): Promise<ModelProviderHealthResult> =>
        probeGate.then(
          (): ModelProviderHealthResult => ({
            ok: false,
            code: "PROVIDER_UNAVAILABLE",
          }),
        ),
    );
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
      providerProbe: probe(run),
      probeIntervalMilliseconds: 30_000,
    });
    state.markReady();

    const stale = state.view();
    expect(stale).toMatchObject({ status: "READY" });
    state.view();
    expect(run).toHaveBeenCalledOnce();

    releaseProbe?.();
    await state.refresh();
    expect(state.view().status).toBe("DEGRADED");
  });

  it("captures the startup provider registration without extra bootstrap wiring", async () => {
    const time = clock(Date.now());
    const check = healthyCheck();
    await checkModelProvider(
      {
        model: {
          provider: "openai",
          model: "gpt-test",
          apiKey: "private-test-key",
          timeoutSeconds: 5,
        },
      },
      { check },
    );

    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
    });
    state.markReady();

    await state.refresh();
    expect(check).toHaveBeenCalledOnce();
    expect(state.view()).toMatchObject({
      status: "READY",
      checks: [
        { name: "config", status: "PASS" },
        { name: "logging", status: "PASS" },
        { name: "protocol", status: "PASS" },
        { name: "sqlite", status: "PASS" },
        { name: "provider", status: "PASS" },
      ],
    });

    time.advance(DEFAULT_PROVIDER_PROBE_INTERVAL_MILLISECONDS + 1_000);
    await state.refresh();
    expect(check).toHaveBeenCalledTimes(2);
    expect(state.view()).toMatchObject({ status: "READY" });
  });

  it("stops reporting READY on /health once the provider dies", async () => {
    const time = clock(1_000_000);
    const run = healthyRun().mockResolvedValue({ ok: false, code: "PROVIDER_UNAVAILABLE" });
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
      providerProbe: probe(run),
      probeIntervalMilliseconds: 30_000,
    });
    state.markReady();
    const app = Fastify({ logger: false });
    registerHealthRoute(app, state);

    const response = await app.inject({ method: "GET", url: "/health" });
    expect(response.statusCode).toBe(503);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(response.json()).toMatchObject({
      status: "DEGRADED",
      checks: [
        { name: "config", status: "PASS" },
        { name: "logging", status: "PASS" },
        { name: "protocol", status: "PASS" },
        { name: "sqlite", status: "PASS" },
        { name: "provider", status: "FAIL" },
      ],
    });
    await app.close();
  });

  it("serves 200 with a cached healthy provider between probes", async () => {
    const time = clock(Date.now());
    const check = healthyCheck();
    await checkModelProvider(
      {
        model: {
          provider: "openai",
          model: "gpt-test",
          apiKey: "private-test-key",
          timeoutSeconds: 5,
        },
      },
      { check },
    );
    const state = new RuntimeHealthState(new Date(time.now()).toISOString(), "1.0", {
      now: time.now,
    });
    state.markReady();
    const app = Fastify({ logger: false });
    registerHealthRoute(app, state);

    const response = await app.inject({ method: "GET", url: "/health" });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ status: "READY" });
    await app.close();
  });
});
