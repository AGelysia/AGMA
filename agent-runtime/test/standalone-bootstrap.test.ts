import { chmod, mkdir, rm } from "node:fs/promises";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { PassThrough } from "node:stream";
import { fileURLToPath } from "node:url";

import { afterEach, describe, expect, it, vi } from "vitest";

import type { ModelProvider } from "../src/providers/model-provider.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import type {
  ModelProviderHealthCheck,
  ModelProviderHealthResult,
} from "../src/health/model-provider.js";
import {
  observeStandaloneManagedParent,
  parseStandaloneCli,
  startStandaloneClient,
  type StartedStandaloneRuntime,
} from "../src/standalone/bootstrap/index.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  validClientRuntimeConfig,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const protocolRoot = fileURLToPath(new URL("../../standalone-client/contracts/", import.meta.url));
const runtimes: StartedStandaloneRuntime[] = [];
const directories: string[] = [];

function provider(): ModelProvider {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
    generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "client only" }),
  };
}

afterEach(async () => {
  await Promise.allSettled(runtimes.splice(0).map((runtime) => runtime.close()));
  await Promise.all(
    directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("standalone-only Runtime bootstrap", () => {
  it("starts the client connector without registering the Paper Agent route", async () => {
    const port = await findAvailablePort();
    const directory = await temporaryRuntimeDirectory();
    directories.push(directory);
    const configPath = await writeRuntimeConfig(directory, validClientRuntimeConfig(port));
    const runtime = await startStandaloneClient({
      configPath,
      environment: runtimeEnvironment(),
      modelProvider: provider(),
      standaloneProtocolRoot: protocolRoot,
      now: () => new Date("2026-07-17T00:00:00Z"),
    });
    runtimes.push(runtime);

    expect(runtime.profile).toBe("client");
    expect((await fetch(`http://127.0.0.1:${String(port)}/health`)).status).toBe(200);
    expect((await fetch(`http://127.0.0.1:${String(port)}/agent`)).status).toBe(404);
  });

  it("purges expired stored conversations using the standalone retention policy", async () => {
    const port = await findAvailablePort();
    const directory = await temporaryRuntimeDirectory();
    directories.push(directory);
    const source = validClientRuntimeConfig(port).replace(
      "storeConversations: false\n  retentionDays: 0",
      "storeConversations: true\n  retentionDays: 2",
    );
    const configPath = await writeRuntimeConfig(directory, source);
    const databasePath = join(directory, "data", "client.db");
    await mkdir(join(directory, "data"), { recursive: true });
    await chmod(join(directory, "data"), 0o700);
    const seeded = new DatabaseSync(databasePath);
    try {
      migrateRuntimeStorage(seeded, "2026-07-10T00:00:00.000Z");
      const insert = seeded.prepare(`
        INSERT INTO sessions (
          id, server_id, player_uuid, status, created_at, updated_at, updated_sequence
        ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
      `);
      insert.run(
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "11111111-1111-4111-8111-111111111111",
        "11111111-1111-4111-8111-111111111111",
        "2026-07-10T00:00:00.000Z",
        "2026-07-10T00:00:00.000Z",
      );
      insert.run(
        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        "11111111-1111-4111-8111-111111111111",
        "11111111-1111-4111-8111-111111111111",
        "2026-07-17T12:00:00.000Z",
        "2026-07-17T12:00:00.000Z",
      );
    } finally {
      seeded.close();
    }
    await chmod(databasePath, 0o600);

    const runtime = await startStandaloneClient({
      configPath,
      environment: runtimeEnvironment(),
      modelProvider: provider(),
      standaloneProtocolRoot: protocolRoot,
      now: () => new Date("2026-07-18T00:00:00Z"),
    });
    await runtime.close();

    const reopened = new DatabaseSync(databasePath, { readOnly: true });
    try {
      expect(reopened.prepare("SELECT id FROM sessions ORDER BY id").all()).toEqual([
        { id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" },
      ]);
    } finally {
      reopened.close();
    }
  });

  it("fails closed when given a version 2 server profile", async () => {
    const directory = await temporaryRuntimeDirectory();
    directories.push(directory);
    const configPath = await writeRuntimeConfig(directory, validRuntimeConfig());

    await expect(
      startStandaloneClient({
        configPath,
        environment: runtimeEnvironment(),
        modelProvider: provider(),
        standaloneProtocolRoot: protocolRoot,
      }),
    ).rejects.toMatchObject({ code: "CONFIG_SCHEMA_INVALID", field: "/configVersion" });
  });

  it("accepts only the fixed --config form with an optional final --managed flag", () => {
    expect(parseStandaloneCli(["--config", "/private/client.yml"])).toEqual({
      configPath: "/private/client.yml",
      managed: false,
    });
    expect(parseStandaloneCli(["--config", "/private/client.yml", "--managed"])).toEqual({
      configPath: "/private/client.yml",
      managed: true,
    });
    expect(() => parseStandaloneCli([])).toThrowError(
      expect.objectContaining({ code: "CONFIG_PATH_INVALID" }),
    );
    expect(() => parseStandaloneCli(["--managed", "--config", "/private/client.yml"])).toThrowError(
      expect.objectContaining({ code: "CONFIG_PATH_INVALID" }),
    );
  });

  it("cancels startup and releases its database lock when managed stdin reaches EOF", async () => {
    const port = await findAvailablePort();
    const directory = await temporaryRuntimeDirectory();
    directories.push(directory);
    const configPath = await writeRuntimeConfig(directory, validClientRuntimeConfig(port));
    const input = new PassThrough();
    const parent = observeStandaloneManagedParent(input);
    let reportStarted: (() => void) | undefined;
    let reportAborted: (() => void) | undefined;
    const started = new Promise<void>((resolve) => {
      reportStarted = resolve;
    });
    const aborted = new Promise<void>((resolve) => {
      reportAborted = resolve;
    });
    const healthCheck: ModelProviderHealthCheck = {
      check: ({ signal }) => {
        reportStarted?.();
        signal.addEventListener("abort", () => reportAborted?.(), { once: true });
        return new Promise<ModelProviderHealthResult>(() => undefined);
      },
    };

    try {
      const start = startStandaloneClient({
        configPath,
        environment: runtimeEnvironment(),
        modelProviderHealthCheck: healthCheck,
        standaloneProtocolRoot: protocolRoot,
        signal: parent.signal,
      });
      await started;
      input.end();

      await expect(start).rejects.toMatchObject({ code: "STARTUP_INTERNAL_ERROR" });
      await aborted;
      expect(parent.signal.aborted).toBe(true);

      const replacement = await startStandaloneClient({
        configPath,
        environment: runtimeEnvironment(),
        modelProvider: provider(),
        standaloneProtocolRoot: protocolRoot,
      });
      runtimes.push(replacement);
    } finally {
      parent.dispose();
      input.destroy();
    }
  });
});
