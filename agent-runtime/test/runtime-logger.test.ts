import { readFile, readdir, rm, stat } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import {
  RuntimeLogger,
  silentLogSink,
  type RuntimeLogSink,
} from "../src/observability/runtime-logger.js";
import { temporaryRuntimeDirectory } from "./helpers/runtime-fixture.js";

const temporaryDirectories: string[] = [];

function capturingSink(lines: string[]): RuntimeLogSink {
  return { write: (line) => lines.push(line) };
}

function fixedNow(): () => Date {
  return () => new Date("2026-07-11T00:00:00.000Z");
}

function parseLines(lines: readonly string[]): Record<string, unknown>[] {
  return lines.map((line) => JSON.parse(line) as Record<string, unknown>);
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("runtime logger", () => {
  it("emits structured single-line events to the configured sink", () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({ now: fixedNow(), sink: capturingSink(lines) });

    logger.ready(38_127);

    expect(lines).toHaveLength(1);
    expect((lines[0] ?? "").trimEnd().split("\n")).toHaveLength(1);
    expect(parseLines(lines)[0]).toEqual({
      timestamp: "2026-07-11T00:00:00.000Z",
      level: "info",
      event: "runtime.ready",
      host: "127.0.0.1",
      port: 38_127,
    });
  });

  it("filters events below the configured level", () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({
      level: "warn",
      now: fixedNow(),
      sink: capturingSink(lines),
    });

    logger.ready(38_127);
    logger.configWarning({ code: "MODEL_CUSTOM_BASE_URL", field: "/model/baseUrl" });
    logger.stopped();
    logger.startupFailure(
      new RuntimeStartupError({
        code: "LOG_DIRECTORY_UNAVAILABLE",
        stage: "logging",
        safeMessage: "Runtime log directory is not writable.",
      }),
    );

    expect(parseLines(lines).map((record) => record["event"])).toEqual([
      "runtime.config.warning",
      "runtime.startup.failed",
    ]);
  });

  it("applies a level selected after construction", () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({ now: fixedNow(), sink: capturingSink(lines) });

    logger.ready(38_127);
    logger.setLevel("error");
    logger.stopped();
    logger.configWarning({ code: "CONFIG_FILE_PERMISSIONS_WIDE" });
    logger.startupFailure(
      new RuntimeStartupError({
        code: "LISTEN_FAILED",
        stage: "listen",
        safeMessage: "Runtime could not bind its local listening port.",
      }),
    );

    expect(parseLines(lines).map((record) => record["event"])).toEqual([
      "runtime.ready",
      "runtime.startup.failed",
    ]);
  });

  it("records runtime errors as bounded metadata without message content", () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({
      level: "error",
      now: fixedNow(),
      sink: capturingSink(lines),
    });
    const failure = new Error("boom".repeat(256));
    failure.stack = [
      "Error: boom",
      "    at first (/runtime/src/requests/agent-request-service.ts:100:15)",
      "    at second (/runtime/src/bootstrap/index.ts:200:9)",
      "    at third (/runtime/src/requests/client-agent-request-service.ts:300:5)",
      "    at fourth (/runtime/src/standalone/bootstrap/index.ts:40:3)",
      "    at fifth (/runtime/src/observability/runtime-logger.ts:50:7)",
    ].join("\n");

    logger.runtimeError("RUNTIME_INTERNAL_ERROR", "33333333-3333-4333-8333-333333333333", failure);
    logger.runtimeError("USAGE_CLOSE_FAILED", "44444444-4444-4444-8444-444444444444", "plain text");

    expect(lines).toHaveLength(2);
    expect((lines[0] ?? "").trimEnd().split("\n")).toHaveLength(1);
    const first = JSON.parse(lines[0] ?? "") as {
      level: string;
      event: string;
      code: string;
      requestId: string;
      error: { name: string; message: string; stack?: string[] };
    };
    expect(first.level).toBe("error");
    expect(first.event).toBe("runtime.error");
    expect(first.code).toBe("RUNTIME_INTERNAL_ERROR");
    expect(first.requestId).toBe("33333333-3333-4333-8333-333333333333");
    expect(first.error.name).toBe("Error");
    expect(first.error.message.length).toBe(512 + "[truncated]".length);
    expect(first.error.message.endsWith("[truncated]")).toBe(true);
    expect(first.error.stack).toHaveLength(4);
    expect(first.error.stack?.[0]).toContain("at first");
    const second = JSON.parse(lines[1] ?? "") as {
      error: { name: string; message: string; stack?: string[] };
    };
    expect(second.error).toEqual({ name: "string", message: "plain text" });
  });

  it("appends structured events to runtime.log inside the log directory", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const logger = new RuntimeLogger({ file: { directory }, sink: silentLogSink, now: fixedNow() });

    logger.configWarning({ code: "CONFIG_FILE_PERMISSIONS_WIDE" });
    logger.runtimeError("TRANSPORT_RESPONSE_FAILED", "req-0001", new Error("socket closed"));
    logger.stopped();

    const content = await readFile(join(directory, "runtime.log"), "utf8");
    expect(content.trim().split("\n")).toHaveLength(3);
    expect(parseLines(content.trim().split("\n")).map((record) => record["event"])).toEqual([
      "runtime.config.warning",
      "runtime.error",
      "runtime.stopped",
    ]);
    expect(content).toContain('"requestId":"req-0001"');
  });

  it("routes events to a log directory selected after construction", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const lines: string[] = [];
    const logger = new RuntimeLogger({ now: fixedNow(), sink: capturingSink(lines) });

    logger.useLogDirectory(directory);
    logger.stopped();

    const content = await readFile(join(directory, "runtime.log"), "utf8");
    expect(content).toContain('"event":"runtime.stopped"');
    expect(lines.join("")).toContain('"event":"runtime.stopped"');
  });

  it("rotates runtime.log at the size cap and keeps a single older generation", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    let tick = 0;
    const logger = new RuntimeLogger({
      file: { directory, maximumBytes: 300 },
      sink: silentLogSink,
      now: () => new Date(Date.UTC(2026, 6, 10, 20, 0, 0, tick * 1_000)),
    });

    for (let index = 0; index < 12; index += 1) {
      logger.ready(38_127);
      tick += 1;
    }

    expect((await readdir(directory)).sort()).toEqual(["runtime.log", "runtime.log.1"]);
    expect((await stat(join(directory, "runtime.log"))).size).toBeLessThanOrEqual(300);

    const rotatedLines = (await readFile(join(directory, "runtime.log.1"), "utf8"))
      .trim()
      .split("\n");
    const activeLines = (await readFile(join(directory, "runtime.log"), "utf8")).trim().split("\n");
    expect(rotatedLines.length).toBeGreaterThanOrEqual(1);
    expect(activeLines.length).toBeGreaterThanOrEqual(1);
    expect(rotatedLines.length + activeLines.length).toBeLessThan(12);
    const lastRotated = parseLines([rotatedLines[0] ?? ""])[0]?.["timestamp"];
    const lastActive = parseLines([activeLines[activeLines.length - 1] ?? ""])[0]?.["timestamp"];
    expect(typeof lastRotated).toBe("string");
    expect(typeof lastActive).toBe("string");
    expect(String(lastActive) > String(lastRotated)).toBe(true);
  });

  it("does not create the log file when every event is filtered", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const logger = new RuntimeLogger({
      level: "error",
      file: { directory },
      sink: silentLogSink,
      now: fixedNow(),
    });

    logger.ready(38_127);
    logger.stopped();

    expect(await readdir(directory)).toEqual([]);
    await expect(readFile(join(directory, "runtime.log"), "utf8")).rejects.toMatchObject({
      code: "ENOENT",
    });
  });
});
