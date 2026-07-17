import { DatabaseSync } from "node:sqlite";
import { createServer } from "node:net";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { PassThrough } from "node:stream";

import { afterEach, describe, expect, it, vi } from "vitest";

import { bootstrap, observeManagedParent, startRuntime } from "../src/bootstrap/index.js";
import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import { loadRuntimeConfig } from "../src/config/runtime-config.js";
import {
  checkModelProvider,
  type ModelProviderHealthCheck,
  type ModelProviderHealthResult,
} from "../src/health/model-provider.js";
import { RuntimeLogger } from "../src/observability/runtime-logger.js";
import { runtimeIdentity } from "../src/version.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_API_KEY,
  TEST_SERVER_TOKEN,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const temporaryDirectories: string[] = [];

async function fixtureDirectory(): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return directory;
}

function healthyProvider(): ModelProviderHealthCheck {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
  };
}

function capturedLogger(): { readonly lines: string[]; readonly logger: RuntimeLogger } {
  const lines: string[] = [];
  return {
    lines,
    logger: new RuntimeLogger({
      now: () => new Date("2026-07-11T00:00:00.000Z"),
      sink: { write: (line) => lines.push(line) },
    }),
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
  vi.restoreAllMocks();
});

describe("runtime bootstrap", () => {
  it("completes local readiness without opening a network listener", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const provider = healthyProvider();
    const result = await bootstrap({
      configPath,
      environment: runtimeEnvironment(),
      modelProviderHealthCheck: provider,
    });

    try {
      expect(result.identity).toEqual(runtimeIdentity);
      expect(result.app.server.listening).toBe(false);
      expect(result.app.addresses()).toEqual([]);
      expect(provider.check).toHaveBeenCalledTimes(1);

      const response = await result.app.inject({ method: "GET", url: "/health" });
      expect(response.statusCode).toBe(503);
      expect(response.headers["cache-control"]).toBe("no-store");
      expect(response.json()).toMatchObject({
        status: "STARTING",
        runtimeVersion: runtimeIdentity.version,
      });
    } finally {
      await result.app.close();
    }
  });

  it("rejects a second live Runtime owner before recovery or provider access", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const first = await bootstrap({
      configPath,
      environment: runtimeEnvironment(),
      modelProviderHealthCheck: healthyProvider(),
    });
    const blockedProvider = healthyProvider();
    try {
      await expect(
        bootstrap({
          configPath,
          environment: runtimeEnvironment(),
          modelProviderHealthCheck: blockedProvider,
        }),
      ).rejects.toMatchObject({ code: "SQLITE_BUSY" });
      expect(blockedProvider.check).not.toHaveBeenCalled();
    } finally {
      await first.app.close();
    }

    const replacement = await bootstrap({
      configPath,
      environment: runtimeEnvironment(),
      modelProviderHealthCheck: healthyProvider(),
    });
    await replacement.app.close();
  });

  it("binds only after the provider check and serves a cached minimal health view", async () => {
    const directory = await fixtureDirectory();
    const port = await findAvailablePort();
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(port));
    let releaseProvider: (() => void) | undefined;
    let reportProviderStarted: (() => void) | undefined;
    const providerStarted = new Promise<void>((resolve) => {
      reportProviderStarted = resolve;
    });
    const providerGate = new Promise<void>((resolve) => {
      releaseProvider = resolve;
    });
    const provider: ModelProviderHealthCheck = {
      check: vi.fn(async () => {
        reportProviderStarted?.();
        await providerGate;
        return { ok: true };
      }),
    };
    const captured = capturedLogger();

    const start = startRuntime({
      configPath,
      environment: runtimeEnvironment(),
      logger: captured.logger,
      modelProviderHealthCheck: provider,
    });
    await providerStarted;

    await expect(
      fetch(`http://127.0.0.1:${String(port)}/health`, {
        signal: AbortSignal.timeout(200),
      }),
    ).rejects.toThrow();

    releaseProvider?.();
    const runtime = await start;
    try {
      const first = await fetch(`http://127.0.0.1:${String(port)}/health`);
      const second = await fetch(`http://127.0.0.1:${String(port)}/health`);
      const body = await first.text();

      expect(first.status).toBe(200);
      expect(first.headers.get("cache-control")).toBe("no-store");
      expect(JSON.parse(body)).toMatchObject({
        status: "READY",
        protocolVersion: "1.0",
        checks: [
          { name: "config", status: "PASS" },
          { name: "logging", status: "PASS" },
          { name: "protocol", status: "PASS" },
          { name: "sqlite", status: "PASS" },
          { name: "provider", status: "PASS" },
        ],
      });
      expect(second.status).toBe(200);
      expect(provider.check).toHaveBeenCalledTimes(1);
      expect(body).not.toContain(TEST_API_KEY);
      expect(body).not.toContain(TEST_SERVER_TOKEN);

      const absentWebSocketRoute = await fetch(`http://127.0.0.1:${String(port)}/agent`);
      expect(absentWebSocketRoute.status).toBe(404);
    } finally {
      await runtime.close();
    }

    const logs = captured.lines.join("");
    expect(logs).toContain('"event":"runtime.ready"');
    expect(logs).not.toContain(TEST_API_KEY);
    expect(logs).not.toContain(TEST_SERVER_TOKEN);
  });

  it("aborts provider health and releases startup resources when managed parent stdin ends", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const parentInput = new PassThrough();
    const parent = observeManagedParent(parentInput);
    let reportProviderStarted: (() => void) | undefined;
    let reportProviderAborted: (() => void) | undefined;
    const providerStarted = new Promise<void>((resolve) => {
      reportProviderStarted = resolve;
    });
    const providerAborted = new Promise<void>((resolve) => {
      reportProviderAborted = resolve;
    });
    const provider: ModelProviderHealthCheck = {
      check: ({ signal }) => {
        reportProviderStarted?.();
        signal.addEventListener("abort", () => reportProviderAborted?.(), { once: true });
        return new Promise<ModelProviderHealthResult>(() => {
          // A faulty provider adapter may ignore abort; startup cancellation must still win the race.
        });
      },
    };

    try {
      const start = startRuntime({
        configPath,
        environment: runtimeEnvironment(),
        modelProviderHealthCheck: provider,
        signal: parent.signal,
      });
      await providerStarted;

      parentInput.end();

      await expect(start).rejects.toMatchObject({ code: "STARTUP_INTERNAL_ERROR" });
      await providerAborted;
      expect(parent.signal.aborted).toBe(true);

      const replacement = await bootstrap({
        configPath,
        environment: runtimeEnvironment(),
        modelProviderHealthCheck: healthyProvider(),
      });
      await replacement.app.close();
    } finally {
      parent.dispose();
      parentInput.destroy();
    }
  });

  it("does not call the provider or listen when the API key is missing", async () => {
    const directory = await fixtureDirectory();
    const port = await findAvailablePort();
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(port));
    const provider = healthyProvider();

    await expect(
      startRuntime({
        configPath,
        environment: runtimeEnvironment({ OPENAI_API_KEY: undefined }),
        modelProviderHealthCheck: provider,
      }),
    ).rejects.toMatchObject({ code: "API_KEY_MISSING" });
    expect(provider.check).not.toHaveBeenCalled();
    await expect(
      fetch(`http://127.0.0.1:${String(port)}/health`, {
        signal: AbortSignal.timeout(200),
      }),
    ).rejects.toThrow();
  });

  it("does not call the provider or listen when SQLite is not writable", async () => {
    const directory = await fixtureDirectory();
    const port = await findAvailablePort();
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(port));
    const dataDirectory = join(directory, "data");
    const sqlitePath = join(dataDirectory, "runtime.db");
    await mkdir(dataDirectory, { mode: 0o700 });
    await writeFile(sqlitePath, "", { mode: 0o400 });
    await chmod(sqlitePath, 0o400);
    const provider = healthyProvider();

    await expect(
      startRuntime({
        configPath,
        environment: runtimeEnvironment(),
        modelProviderHealthCheck: provider,
      }),
    ).rejects.toMatchObject({ code: "SQLITE_WRITE_FAILED" });
    expect(provider.check).not.toHaveBeenCalled();
    await expect(
      fetch(`http://127.0.0.1:${String(port)}/health`, {
        signal: AbortSignal.timeout(200),
      }),
    ).rejects.toThrow();
  });

  it("maps provider exceptions and logs only a safe diagnostic", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const captured = capturedLogger();
    const provider: ModelProviderHealthCheck = {
      check: vi.fn().mockRejectedValue(new Error(`provider leaked ${TEST_API_KEY}`)),
    };

    let failure: RuntimeStartupError | undefined;
    try {
      await bootstrap({
        configPath,
        environment: runtimeEnvironment(),
        logger: captured.logger,
        modelProviderHealthCheck: provider,
      });
    } catch (error) {
      expect(error).toBeInstanceOf(RuntimeStartupError);
      failure = error as RuntimeStartupError;
      captured.logger.startupFailure(failure);
    }

    expect(failure?.code).toBe("MODEL_HEALTH_FAILED");
    const logs = captured.lines.join("");
    expect(logs).toContain('"code":"MODEL_HEALTH_FAILED"');
    expect(logs).not.toContain(TEST_API_KEY);
    expect(logs).not.toContain(TEST_SERVER_TOKEN);
    expect(logs).not.toContain("provider leaked");
  });

  it("uses the production OpenAI health adapter when no test adapter is injected", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(JSON.stringify({ id: "test-model", object: "model" }), { status: 200 }),
      );

    const result = await bootstrap({ configPath, environment: runtimeEnvironment() });
    try {
      expect(fetchSpy).toHaveBeenCalledOnce();
      expect(fetchSpy.mock.calls[0]?.[0]).toBe("https://api.openai.com/v1/models/test-model");
    } finally {
      await result.app.close();
    }
  });

  it.each([
    ["PROVIDER_AUTH_FAILED", "/model/apiKey"],
    ["PROVIDER_UNAVAILABLE", "/model/provider"],
    ["MODEL_UNAVAILABLE", "/model/model"],
  ] as const)("maps %s to a stable diagnostic field", async (code, field) => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });

    await expect(
      checkModelProvider(loaded.config, {
        check: vi.fn().mockResolvedValue({ ok: false, code }),
      }),
    ).rejects.toMatchObject({ code, field });
  });

  it("aborts and returns a stable provider timeout", async () => {
    const directory = await fixtureDirectory();
    const configPath = await writeRuntimeConfig(directory);
    const loaded = await loadRuntimeConfig({ configPath, environment: runtimeEnvironment() });
    let aborted = false;
    const provider: ModelProviderHealthCheck = {
      check: ({ signal }) =>
        new Promise((resolve) => {
          signal.addEventListener(
            "abort",
            () => {
              aborted = true;
              resolve({ ok: true });
            },
            { once: true },
          );
        }),
    };

    await expect(checkModelProvider(loaded.config, provider, 10)).rejects.toMatchObject({
      code: "PROVIDER_TIMEOUT",
    });
    expect(aborted).toBe(true);
  });

  it("closes resources and never reports READY when the final port bind fails", async () => {
    const directory = await fixtureDirectory();
    const occupied = createServer();
    await new Promise<void>((resolve, reject) => {
      occupied.once("error", reject);
      occupied.listen(0, "127.0.0.1", resolve);
    });
    const address = occupied.address();
    if (address === null || typeof address === "string") {
      throw new Error("Unable to occupy a test port");
    }
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(address.port));
    const captured = capturedLogger();

    try {
      await expect(
        startRuntime({
          configPath,
          environment: runtimeEnvironment(),
          logger: captured.logger,
          modelProviderHealthCheck: healthyProvider(),
        }),
      ).rejects.toMatchObject({ code: "LISTEN_FAILED" });
    } finally {
      await new Promise<void>((resolve, reject) => {
        occupied.close((error) => (error === undefined ? resolve() : reject(error)));
      });
    }

    expect(captured.lines.join("")).not.toContain("runtime.ready");
    const database = new DatabaseSync(join(directory, "data/runtime.db"));
    expect(database.prepare("SELECT 1 AS value").get()?.["value"]).toBe(1);
    database.close();
  });

  it("closes the bound listener when the ready log sink fails", async () => {
    const directory = await fixtureDirectory();
    const port = await findAvailablePort();
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(port));
    const failingLogger = new RuntimeLogger({
      sink: {
        write: () => {
          throw new Error("log sink unavailable");
        },
      },
    });

    await expect(
      startRuntime({
        configPath,
        environment: runtimeEnvironment(),
        logger: failingLogger,
        modelProviderHealthCheck: healthyProvider(),
      }),
    ).rejects.toMatchObject({ code: "STARTUP_INTERNAL_ERROR" });

    const rebound = createServer();
    await new Promise<void>((resolve, reject) => {
      rebound.once("error", reject);
      rebound.listen(port, "127.0.0.1", resolve);
    });
    await new Promise<void>((resolve, reject) => {
      rebound.close((error) => (error === undefined ? resolve() : reject(error)));
    });
  });
});
