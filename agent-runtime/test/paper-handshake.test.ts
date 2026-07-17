import { rm } from "node:fs/promises";

import { afterEach, describe, expect, it, vi } from "vitest";
import WebSocket, { type RawData } from "ws";

import {
  bootstrap,
  startRuntime,
  type BootstrapResult,
  type StartRuntimeResult,
} from "../src/bootstrap/index.js";
import type { ModelProviderHealthCheck } from "../src/health/model-provider.js";
import { RuntimeLogger } from "../src/observability/runtime-logger.js";
import { SchemaRegistry } from "../src/protocol/schema-registry.js";
import type { ModelProvider } from "../src/providers/model-provider.js";
import {
  createHandshakeProof,
  verifyHandshakeProof,
  type HandshakeProofFields,
} from "../src/transport/handshake-authentication.js";
import { APPLICATION_MAXIMUM_BYTES } from "../src/transport/application-envelope.js";
import { HANDSHAKE_MAXIMUM_BYTES } from "../src/transport/paper-handshake.js";
import { runtimeIdentity } from "../src/version.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_SERVER_TOKEN,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const NOW = new Date("2026-07-12T00:00:00.000Z");
const clients = new Set<WebSocket>();
const runtimes: (BootstrapResult | StartRuntimeResult)[] = [];
const temporaryDirectories: string[] = [];
let nextUuidSuffix = 1;

interface PaperHelloOptions {
  readonly token?: string;
  readonly timestamp?: string;
  readonly messageId?: string;
  readonly requestId?: string;
  readonly nonce?: string;
  readonly challenge?: string;
}

interface CloseDetails {
  readonly code: number;
  readonly reason: string;
}

interface StartFixtureOptions {
  readonly logger?: RuntimeLogger;
  readonly modelProvider?: ModelProvider;
  readonly configSource?: (port: number) => string;
}

function healthyProvider(): ModelProviderHealthCheck {
  return { check: vi.fn().mockResolvedValue({ ok: true }) };
}

function uuid(): string {
  const suffix = String(nextUuidSuffix).padStart(12, "0");
  nextUuidSuffix += 1;
  return `00000000-0000-4000-8000-${suffix}`;
}

function paperHello(options: PaperHelloOptions = {}): Record<string, unknown> {
  const messageId = options.messageId ?? uuid();
  const timestamp = options.timestamp ?? NOW.toISOString();
  const nonce = options.nonce ?? Buffer.alloc(16, nextUuidSuffix).toString("base64url");
  const challenge = options.challenge ?? Buffer.alloc(16, 0x21).toString("base64url");
  const fields: HandshakeProofFields = {
    serverId: "test-server",
    type: "paper.hello",
    timestamp,
    nonce,
    component: "paper",
    componentVersion: "0.1.0",
    challenge,
  };

  return {
    protocolVersion: "1.0",
    messageId,
    requestId: options.requestId ?? messageId,
    serverId: "test-server",
    type: "paper.hello",
    timestamp,
    nonce,
    payload: {
      component: "paper",
      componentVersion: "0.1.0",
      supportedProtocolVersions: ["1.0"],
      selectedProtocolVersion: null,
      authentication: {
        scheme: "hmac-sha256",
        keyId: "test-server",
        challenge,
        proof: createHandshakeProof(options.token ?? TEST_SERVER_TOKEN, fields),
      },
    },
  };
}

function agentRequest(
  message: string,
  playerUuid = "44444444-4444-4444-8444-444444444444",
): Record<string, unknown> {
  const requestId = uuid();
  return {
    protocolVersion: "1.0",
    messageId: requestId,
    requestId,
    serverId: "test-server",
    type: "agent.request",
    timestamp: NOW.toISOString(),
    nonce: Buffer.alloc(16, nextUuidSuffix).toString("base64url"),
    payload: {
      sessionId: null,
      playerUuid,
      module: "general",
      message,
      clientCapabilities: {
        connected: false,
        clientProtocolVersion: null,
        features: {
          overlay: 0,
          itemIcons: 0,
          recipeView: 0,
          litematicaPreview: 0,
          litematicaMaterialList: 0,
        },
      },
    },
  };
}

function agentCancel(request: Record<string, unknown>): Record<string, unknown> {
  return {
    protocolVersion: "1.0",
    messageId: uuid(),
    requestId: request["requestId"],
    serverId: "test-server",
    type: "agent.cancel",
    timestamp: NOW.toISOString(),
    nonce: Buffer.alloc(16, nextUuidSuffix).toString("base64url"),
    payload: {
      playerUuid: asRecord(request["payload"])["playerUuid"],
      reason: "PLAYER_DISCONNECTED",
    },
  };
}

function sessionResume(
  sessionId: string | null,
  playerUuid = "44444444-4444-4444-8444-444444444444",
): Record<string, unknown> {
  const requestId = uuid();
  return {
    protocolVersion: "1.0",
    messageId: requestId,
    requestId,
    serverId: "test-server",
    type: "session.resume",
    timestamp: NOW.toISOString(),
    nonce: Buffer.alloc(16, nextUuidSuffix).toString("base64url"),
    payload: { playerUuid, sessionId },
  };
}

function managementCostsRequest(): Record<string, unknown> {
  const requestId = uuid();
  return {
    protocolVersion: "1.0",
    messageId: requestId,
    requestId,
    serverId: "test-server",
    type: "management.costs.request",
    timestamp: NOW.toISOString(),
    nonce: Buffer.alloc(16, nextUuidSuffix).toString("base64url"),
    payload: {},
  };
}

function toolResult(callEnvelope: Record<string, unknown>): Record<string, unknown> {
  const call = asRecord(callEnvelope["payload"]);
  return {
    protocolVersion: "1.0",
    messageId: uuid(),
    requestId: callEnvelope["requestId"],
    serverId: "test-server",
    type: "tool.result",
    timestamp: NOW.toISOString(),
    nonce: Buffer.alloc(16, nextUuidSuffix).toString("base64url"),
    payload: {
      toolCallId: call["toolCallId"],
      sessionId: call["sessionId"],
      playerUuid: call["playerUuid"],
      tool: call["tool"],
      sequence: call["sequence"],
      status: "succeeded",
      source: "paper_api",
      trust: "authoritative",
      result: {
        serverName: "Paper",
        minecraftVersion: "1.21.11",
        serverVersion: "1.21.11-132",
        onlinePlayers: 1,
        maxPlayers: 20,
        viewDistance: 10,
        simulationDistance: 10,
      },
      error: null,
    },
  };
}

async function createConfig(port: number, source = validRuntimeConfig(port)): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return writeRuntimeConfig(directory, source);
}

async function startFixture(options: StartFixtureOptions = {}): Promise<{
  readonly runtime: StartRuntimeResult;
  readonly url: string;
}> {
  const port = await findAvailablePort();
  const configPath = await createConfig(
    port,
    options.configSource?.(port) ?? validRuntimeConfig(port),
  );
  const runtime = await startRuntime({
    configPath,
    environment: runtimeEnvironment(),
    ...(options.modelProvider === undefined
      ? { modelProviderHealthCheck: healthyProvider() }
      : { modelProvider: options.modelProvider }),
    now: () => NOW,
    ...(options.logger === undefined ? {} : { logger: options.logger }),
  });
  runtimes.push(runtime);
  return { runtime, url: `ws://127.0.0.1:${String(port)}/agent` };
}

async function openClient(url: string): Promise<WebSocket> {
  const socket = new WebSocket(url, { perMessageDeflate: false });
  clients.add(socket);
  socket.once("close", () => clients.delete(socket));
  await new Promise<void>((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  return socket;
}

function nextMessage(socket: WebSocket): Promise<unknown> {
  return new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.once("message", (data: RawData, isBinary: boolean) => {
      if (isBinary) {
        reject(new Error("Expected a text WebSocket message"));
        return;
      }
      resolve(JSON.parse(Buffer.isBuffer(data) ? data.toString("utf8") : String(data)) as unknown);
    });
  });
}

function nextClose(socket: WebSocket): Promise<CloseDetails> {
  return new Promise((resolve) => {
    socket.once("close", (code, reason) => {
      resolve({ code, reason: reason.toString("utf8") });
    });
  });
}

async function exchange(socket: WebSocket, message: unknown): Promise<unknown> {
  const response = nextMessage(socket);
  socket.send(JSON.stringify(message));
  return response;
}

async function sendAndClose(
  socket: WebSocket,
  message: string | Buffer,
  binary = false,
): Promise<CloseDetails> {
  const closed = nextClose(socket);
  socket.send(message, { binary });
  return closed;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError("Expected an object");
  }
  return value as Record<string, unknown>;
}

afterEach(async () => {
  for (const client of clients) {
    client.terminate();
  }
  clients.clear();
  await Promise.allSettled(runtimes.splice(0).map((runtime) => runtime.app.close()));
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
  vi.restoreAllMocks();
});

describe("Paper WebSocket handshake", () => {
  it("returns an authenticated runtime.hello only after Runtime readiness", async () => {
    const lines: string[] = [];
    const logger = new RuntimeLogger({
      now: () => NOW,
      sink: { write: (line) => lines.push(line) },
    });
    const { runtime, url } = await startFixture({ logger });
    const socket = await openClient(url);
    const hello = paperHello();
    const response = asRecord(await exchange(socket, hello));
    const payload = asRecord(response["payload"]);
    const authentication = asRecord(payload["authentication"]);
    const requestPayload = asRecord(asRecord(hello["payload"])["authentication"]);

    const registry = await SchemaRegistry.load();
    expect(registry.validate("envelope.schema.json", response).valid).toBe(true);
    expect(registry.validate("handshake.schema.json", payload).valid).toBe(true);
    expect(response).toMatchObject({
      protocolVersion: "1.0",
      requestId: hello["requestId"],
      serverId: "test-server",
      type: "runtime.hello",
    });
    expect(payload).toMatchObject({
      component: "runtime",
      componentVersion: runtimeIdentity.version,
      supportedProtocolVersions: ["1.0"],
      selectedProtocolVersion: "1.0",
    });
    expect(authentication["challenge"]).toBe(requestPayload["challenge"]);
    expect(
      verifyHandshakeProof(
        TEST_SERVER_TOKEN,
        {
          serverId: String(response["serverId"]),
          type: "runtime.hello",
          timestamp: String(response["timestamp"]),
          nonce: String(response["nonce"]),
          component: "runtime",
          componentVersion: String(payload["componentVersion"]),
          challenge: String(authentication["challenge"]),
        },
        String(authentication["proof"]),
      ),
    ).toBe(true);

    const health = await fetch(
      `http://127.0.0.1:${String(runtime.listenAddress.port)}/health`,
    ).then((responseValue) => responseValue.text());
    const diagnostics = `${health}\n${lines.join("\n")}`;
    expect(diagnostics).not.toContain(TEST_SERVER_TOKEN);
    expect(diagnostics).not.toContain(String(authentication["proof"]));
  });

  it("allows one authenticated Paper and retains replay state across connections", async () => {
    const { url } = await startFixture();
    const firstHello = paperHello();
    const first = await openClient(url);
    await exchange(first, firstHello);

    const competing = await openClient(url);
    expect(await sendAndClose(competing, JSON.stringify(paperHello()))).toEqual({
      code: 1008,
      reason: "PAPER_ALREADY_CONNECTED",
    });

    const firstClosed = nextClose(first);
    first.close();
    await firstClosed;

    const replay = await openClient(url);
    expect(await sendAndClose(replay, JSON.stringify(firstHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_REPLAYED",
    });

    const replacement = await openClient(url);
    expect(asRecord(await exchange(replacement, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("rejects invalid handshakes without occupying the authenticated slot", async () => {
    const { url } = await startFixture();

    const wrongToken = await openClient(url);
    expect(
      await sendAndClose(
        wrongToken,
        JSON.stringify(paperHello({ token: "wrong-public-test-token-with-32-characters" })),
      ),
    ).toEqual({ code: 1008, reason: "AUTHENTICATION_FAILED" });

    const stale = await openClient(url);
    expect(
      await sendAndClose(
        stale,
        JSON.stringify(paperHello({ timestamp: "2026-07-11T23:59:29.999Z" })),
      ),
    ).toEqual({ code: 1008, reason: "HANDSHAKE_STALE" });

    const incompatibleHello = paperHello();
    incompatibleHello["protocolVersion"] = "2.0";
    const incompatible = await openClient(url);
    expect(await sendAndClose(incompatible, JSON.stringify(incompatibleHello))).toEqual({
      code: 1008,
      reason: "PROTOCOL_INCOMPATIBLE",
    });

    const unrelatedHello = paperHello({ requestId: uuid() });
    const unrelated = await openClient(url);
    expect(await sendAndClose(unrelated, JSON.stringify(unrelatedHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const selectedHello = paperHello();
    asRecord(selectedHello["payload"])["selectedProtocolVersion"] = "1.0";
    const selected = await openClient(url);
    expect(await sendAndClose(selected, JSON.stringify(selectedHello))).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const unsupportedHello = paperHello();
    unsupportedHello["type"] = "health.ping";
    const unsupported = await openClient(url);
    expect(await sendAndClose(unsupported, JSON.stringify(unsupportedHello))).toEqual({
      code: 1008,
      reason: "UNSUPPORTED_MESSAGE_TYPE",
    });

    const valid = await openClient(url);
    expect(asRecord(await exchange(valid, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("rejects duplicate-key JSON, binary input, and bounded handshake payloads", async () => {
    const { url } = await startFixture();
    const source = JSON.stringify(paperHello());
    const duplicateSource = source.replace(
      '{"protocolVersion":"1.0",',
      '{"protocolVersion":"1.0","protocolVersion":"1.0",',
    );

    const duplicate = await openClient(url);
    expect(await sendAndClose(duplicate, duplicateSource)).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const binary = await openClient(url);
    expect(await sendAndClose(binary, Buffer.from(source), true)).toEqual({
      code: 1008,
      reason: "HANDSHAKE_INVALID",
    });

    const invalidUtf8 = await openClient(url);
    expect((await sendAndClose(invalidUtf8, Buffer.from([0xc3, 0x28]))).code).toBe(1007);

    const oversized = await openClient(url);
    const oversizedClose = await sendAndClose(
      oversized,
      `"${"x".repeat(HANDSHAKE_MAXIMUM_BYTES)}"`,
    );
    expect(oversizedClose).toEqual({ code: 1008, reason: "HANDSHAKE_INVALID" });

    const transportOversized = await openClient(url);
    expect(
      (await sendAndClose(transportOversized, `"${"x".repeat(APPLICATION_MAXIMUM_BYTES)}"`)).code,
    ).toBe(1009);

    const valid = await openClient(url);
    expect(asRecord(await exchange(valid, paperHello()))["type"]).toBe("runtime.hello");
  });

  it("closes the authenticated connection when the Runtime stops", async () => {
    const { runtime, url } = await startFixture();
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    const closed = nextClose(socket);

    await runtime.close();

    expect((await closed).code).not.toBe(1006);
    expect(socket.readyState).toBe(WebSocket.CLOSED);
    expect(runtime.health.view().status).toBe("STOPPED");
  });

  it("keeps /agent closed while the app is still STARTING", async () => {
    const port = await findAvailablePort();
    const configPath = await createConfig(port);
    const result = await bootstrap({
      configPath,
      environment: runtimeEnvironment(),
      modelProviderHealthCheck: healthyProvider(),
      now: () => NOW,
    });
    runtimes.push(result);
    await result.app.listen(result.listenAddress);

    const socket = await openClient(`ws://127.0.0.1:${String(port)}/agent`);
    expect(await nextClose(socket)).toEqual({ code: 1008, reason: "RUNTIME_NOT_READY" });
  });

  it("serves multiple private Agent requests over one authenticated connection", async () => {
    const generate = vi
      .fn()
      .mockImplementation(async ({ input }: Parameters<ModelProvider["generate"]>[0]) => ({
        type: "final" as const,
        fallbackText: `answer:${input.at(-1)?.content ?? "missing"}`,
      }));
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate,
    };
    const { url } = await startFixture({ modelProvider });
    const socket = await openClient(url);
    await exchange(socket, paperHello());

    const firstRequest = agentRequest("first private prompt");
    const first = asRecord(await exchange(socket, firstRequest));
    const firstPayload = asRecord(first["payload"]);
    const secondRequest = agentRequest(
      "second private prompt",
      "55555555-5555-4555-8555-555555555555",
    );
    const second = asRecord(await exchange(socket, secondRequest));

    const registry = await SchemaRegistry.load();
    expect(registry.validate("envelope.schema.json", first).valid).toBe(true);
    expect(registry.validate("agent-complete.schema.json", firstPayload).valid).toBe(true);
    expect(first).toMatchObject({
      requestId: firstRequest["requestId"],
      serverId: "test-server",
      type: "agent.complete",
    });
    expect(first["messageId"]).not.toBe(firstRequest["requestId"]);
    expect(firstPayload).toEqual({
      sessionId: expect.any(String),
      playerUuid: "44444444-4444-4444-8444-444444444444",
      fallbackText: "answer:first private prompt",
      structuredViews: [
        {
          viewSchemaVersion: "1.0",
          viewId: expect.any(String),
          requestId: firstRequest["requestId"],
          viewType: "text",
          revision: 1,
          title: "Agent response",
          fallbackText: "answer:first private prompt",
          pinnable: true,
          content: { text: "answer:first private prompt" },
        },
      ],
    });
    expect(second).toMatchObject({
      requestId: secondRequest["requestId"],
      type: "agent.complete",
      payload: { fallbackText: "answer:second private prompt" },
    });
    expect(generate).toHaveBeenCalledTimes(2);
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("serves the bounded durable costs snapshot over the authenticated channel", async () => {
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({
        type: "final",
        fallbackText: "accounted answer",
        usage: { inputTokens: 5, outputTokens: 4 },
      }),
    };
    const { url } = await startFixture({ modelProvider });
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    expect(asRecord(await exchange(socket, agentRequest("account this")))["type"]).toBe(
      "agent.complete",
    );

    const request = managementCostsRequest();
    const response = asRecord(await exchange(socket, request));
    const responsePayload = asRecord(response["payload"]);
    expect(response).toMatchObject({
      requestId: request["requestId"],
      serverId: "test-server",
      type: "management.costs.response",
      payload: {
        currentDay: {
          period: "2026-07-12",
          admittedRequests: 1,
          providerCalls: 1,
          reportedProviderCalls: 1,
          estimatedProviderCalls: 0,
          inputTokens: 5,
          outputTokens: 4,
          costMicroUsd: 21,
        },
        currentMonth: {
          period: "2026-07",
          admittedRequests: 1,
          providerCalls: 1,
          costMicroUsd: 21,
        },
        budget: {
          month: "2026-07",
          limitMicroUsd: 10_000_000,
          settledMicroUsd: 21,
          activeReservationsMicroUsd: 0,
          remainingMicroUsd: 9_999_979,
          exhausted: false,
        },
      },
    });
    const registry = await SchemaRegistry.load();
    expect(registry.validate("envelope.schema.json", response).valid).toBe(true);
    expect(registry.validate("management-costs-response.schema.json", responsePayload).valid).toBe(
      true,
    );
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("returns a budget error without closing the authenticated channel", async () => {
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "must not run" }),
    };
    const { url } = await startFixture({
      modelProvider,
      configSource: (port) =>
        validRuntimeConfig(port).replace("monthlyBudgetUsd: 10", "monthlyBudgetUsd: 0"),
    });
    const socket = await openClient(url);
    await exchange(socket, paperHello());

    const request = agentRequest("blocked by budget");
    const response = asRecord(await exchange(socket, request));

    expect(response).toMatchObject({
      requestId: request["requestId"],
      type: "agent.error",
      payload: { code: "BUDGET_EXCEEDED", retryable: false },
    });
    expect(modelProvider.generate).not.toHaveBeenCalled();
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("round-trips a correlated typed Tool call and result before completion", async () => {
    let generation = 0;
    const generate = vi.fn(async () => {
      generation += 1;
      return generation === 1
        ? {
            type: "tool_call" as const,
            providerCallId: "provider-call-1",
            providerName: "server_info_read",
            arguments: {},
            continuation: { provider: "openai" as const, items: [] },
          }
        : { type: "final" as const, fallbackText: "One player is online." };
    });
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate,
    };
    const { url } = await startFixture({ modelProvider });
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    const request = agentRequest("How many players are online?");
    const call = asRecord(await exchange(socket, request));

    expect(call).toMatchObject({
      requestId: request["requestId"],
      type: "tool.call",
      payload: {
        playerUuid: "44444444-4444-4444-8444-444444444444",
        tool: "server.info.read",
        sequence: 0,
      },
    });
    const registry = await SchemaRegistry.load();
    expect(registry.validate("tool-call.schema.json", asRecord(call["payload"])).valid).toBe(true);

    const completion = asRecord(await exchange(socket, toolResult(call)));
    expect(completion).toMatchObject({
      requestId: request["requestId"],
      type: "agent.complete",
      payload: { fallbackText: "One player is online." },
    });
    expect(generate).toHaveBeenCalledTimes(2);
    expect(generate.mock.calls[1]?.[0].toolOutput.output).toContain('"source":"paper_api"');
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("cancels an active request, releases it, and keeps the application channel open", async () => {
    let generation = 0;
    let reportStarted: (() => void) | undefined;
    let reportAborted: (() => void) | undefined;
    const started = new Promise<void>((resolve) => {
      reportStarted = resolve;
    });
    const aborted = new Promise<void>((resolve) => {
      reportAborted = resolve;
    });
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn(async ({ signal }) => {
        generation += 1;
        if (generation > 1) {
          return { type: "final", fallbackText: "replacement answer" };
        }
        reportStarted?.();
        return new Promise((_resolve, reject) => {
          signal.addEventListener(
            "abort",
            () => {
              reportAborted?.();
              reject(signal.reason);
            },
            { once: true },
          );
        });
      }),
    };
    const { url } = await startFixture({ modelProvider });
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    const pending = agentRequest("cancel me");
    socket.send(JSON.stringify(pending));
    await started;

    socket.send(JSON.stringify(agentCancel(pending)));
    await aborted;
    const replacement = agentRequest("replacement", "55555555-5555-4555-8555-555555555555");
    const response = asRecord(await exchange(socket, replacement));

    expect(response).toMatchObject({
      requestId: replacement["requestId"],
      type: "agent.complete",
      payload: { fallbackText: "replacement answer" },
    });
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("resumes only sessions owned by the authenticated server player", async () => {
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "stored answer" }),
    };
    const { url } = await startFixture({ modelProvider });
    const socket = await openClient(url);
    await exchange(socket, paperHello());
    const completion = asRecord(await exchange(socket, agentRequest("persist me")));
    const sessionId = String(asRecord(completion["payload"])["sessionId"]);

    expect(asRecord(await exchange(socket, sessionResume(sessionId)))).toMatchObject({
      type: "session.resumed",
      payload: {
        playerUuid: "44444444-4444-4444-8444-444444444444",
        sessionId,
      },
    });
    expect(
      asRecord(
        await exchange(socket, sessionResume(sessionId, "55555555-5555-4555-8555-555555555555")),
      ),
    ).toMatchObject({
      type: "agent.error",
      payload: { code: "SESSION_NOT_FOUND" },
    });
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it("closes on replayed and binary messages while accepting display-only capabilities", async () => {
    const modelProvider: ModelProvider = {
      check: vi.fn().mockResolvedValue({ ok: true }),
      generate: vi.fn().mockResolvedValue({ type: "final", fallbackText: "answer" }),
    };

    const replayFixture = await startFixture({ modelProvider });
    const replaySocket = await openClient(replayFixture.url);
    await exchange(replaySocket, paperHello());
    const repeated = agentRequest("one request");
    expect(asRecord(await exchange(replaySocket, repeated))["type"]).toBe("agent.complete");
    expect(await sendAndClose(replaySocket, JSON.stringify(repeated))).toEqual({
      code: 1008,
      reason: "APPLICATION_MESSAGE_REPLAYED",
    });

    const binaryFixture = await startFixture({ modelProvider });
    const binarySocket = await openClient(binaryFixture.url);
    await exchange(binarySocket, paperHello());
    expect(
      await sendAndClose(binarySocket, Buffer.from(JSON.stringify(agentRequest("binary"))), true),
    ).toEqual({ code: 1008, reason: "APPLICATION_MESSAGE_INVALID" });

    const capabilityFixture = await startFixture({ modelProvider });
    const capabilitySocket = await openClient(capabilityFixture.url);
    await exchange(capabilitySocket, paperHello());
    const unsupportedCapabilities = agentRequest("future client");
    const capabilities = asRecord(
      asRecord(unsupportedCapabilities["payload"])["clientCapabilities"],
    );
    capabilities["connected"] = true;
    capabilities["clientProtocolVersion"] = "1.1";
    const features = asRecord(capabilities["features"]);
    features["overlay"] = 1;
    features["itemIcons"] = 1;
    features["recipeView"] = 1;
    const capabilityResponse = asRecord(await exchange(capabilitySocket, unsupportedCapabilities));
    expect(capabilityResponse).toMatchObject({
      type: "agent.complete",
      payload: { fallbackText: "answer" },
    });
    expect(capabilitySocket.readyState).toBe(WebSocket.OPEN);
    expect(modelProvider.generate).toHaveBeenCalledTimes(2);
  });

  it("keeps legacy health-only injection fail closed without an implicit provider fetch", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const { url } = await startFixture();
    const socket = await openClient(url);
    await exchange(socket, paperHello());

    const response = asRecord(await exchange(socket, agentRequest("must not leave runtime")));

    expect(response).toMatchObject({
      type: "agent.error",
      payload: { code: "MODEL_UNAVAILABLE", retryable: true },
    });
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
