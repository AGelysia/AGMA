import { rm } from "node:fs/promises";

import { afterEach, describe, expect, it, vi } from "vitest";
import WebSocket, { type RawData } from "ws";

import { startRuntime, type StartRuntimeResult } from "../src/bootstrap/index.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
} from "../src/providers/model-provider.js";
import { startStandaloneClient } from "../src/standalone/bootstrap/index.js";
import {
  CONNECTOR_KIND,
  CONNECTOR_PROTOCOL_VERSION,
  CONNECTOR_SCHEMA_VERSION,
  createConnectorHandshakeProof,
  type ConnectorHello,
  verifyConnectorHandshakeProof,
} from "../src/transport/connector-handshake-authentication.js";
import {
  findAvailablePort,
  runtimeEnvironment,
  temporaryRuntimeDirectory,
  TEST_CONNECTOR_TOKEN,
  validClientRuntimeConfig,
  validRuntimeConfig,
  writeRuntimeConfig,
} from "./helpers/runtime-fixture.js";

const NOW = new Date("2026-07-17T00:00:00.000Z");
const SCOPE_ID = "11111111-1111-4111-8111-111111111111";
const clients = new Set<WebSocket>();
const runtimes: StartRuntimeResult[] = [];
const temporaryDirectories: string[] = [];
let sequence = 1;

interface CloseDetails {
  readonly code: number;
  readonly reason: string;
}

function uuid(): string {
  const suffix = String(sequence).padStart(12, "0");
  sequence += 1;
  return `00000000-0000-4000-8000-${suffix}`;
}

function nonce(): string {
  const value = Buffer.alloc(16, sequence).toString("base64url");
  sequence += 1;
  return value;
}

function provider(
  generate: (request: ModelGenerationRequest) => Promise<ModelGenerationResult>,
): ModelProvider {
  return {
    check: vi.fn().mockResolvedValue({ ok: true }),
    generate: vi.fn(generate),
  };
}

function hello(
  token = TEST_CONNECTOR_TOKEN,
  toolCapabilities: readonly string[] = [],
): ConnectorHello {
  const withoutProof: ConnectorHello = {
    schemaVersion: CONNECTOR_SCHEMA_VERSION,
    connectorKind: CONNECTOR_KIND,
    type: "connector.hello",
    messageId: uuid(),
    requestId: null,
    timestamp: NOW.toISOString(),
    nonce: nonce(),
    component: "standalone_client",
    componentVersion: "0.2.0",
    scopeId: SCOPE_ID,
    supportedProtocolVersions: [CONNECTOR_PROTOCOL_VERSION],
    selectedProtocolVersion: null,
    capabilities: ["client.cancel", "client.status", "client.text", ...toolCapabilities]
      .sort()
      .map((id) => ({ id, version: 1 })),
    authentication: {
      scheme: "hmac-sha256",
      keyId: "session-key",
      challenge: nonce(),
      proof: "A".repeat(43),
    },
  };
  return {
    ...withoutProof,
    authentication: {
      ...withoutProof.authentication,
      proof: createConnectorHandshakeProof(token, withoutProof),
    },
  };
}

function application(
  type:
    | "client.request"
    | "client.cancel"
    | "client.status.request"
    | "client.tool.result"
    | "client.tool.error",
  payload: Readonly<Record<string, unknown>>,
  requestId: string | null = null,
): Record<string, unknown> {
  return {
    schemaVersion: "1.0",
    connectorKind: "standalone_client",
    protocolVersion: "client-1.0",
    messageId: uuid(),
    requestId,
    scopeId: SCOPE_ID,
    type,
    timestamp: NOW.toISOString(),
    nonce: nonce(),
    payload,
  };
}

async function startClient(
  adapter: ModelProvider,
  configSource: (port: number) => string = validClientRuntimeConfig,
  toolTimeoutMilliseconds?: number,
): Promise<{
  readonly runtime: StartRuntimeResult;
  readonly port: number;
  readonly url: string;
}> {
  const port = await findAvailablePort();
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  const configPath = await writeRuntimeConfig(directory, configSource(port));
  const runtime = await startStandaloneClient({
    configPath,
    environment: runtimeEnvironment(),
    modelProvider: adapter,
    now: () => NOW,
    ...(toolTimeoutMilliseconds === undefined
      ? {}
      : { connectorToolTimeoutMilliseconds: toolTimeoutMilliseconds }),
  });
  runtimes.push(runtime);
  return { runtime, port, url: `ws://127.0.0.1:${String(port)}/connector` };
}

function clientConfigWithTools(...allowed: readonly string[]): (port: number) => string {
  return (port) =>
    validClientRuntimeConfig(port).replace("  allowed: []", `  allowed: [${allowed.join(", ")}]`);
}

function toolTerminal(
  call: Readonly<Record<string, unknown>>,
  type: "client.tool.result" | "client.tool.error",
  terminal: Readonly<Record<string, unknown>>,
): Record<string, unknown> {
  const requestId = String(call["requestId"]);
  const payload = call["payload"] as Readonly<Record<string, unknown>>;
  return application(
    type,
    {
      requestId,
      toolCallId: payload["toolCallId"],
      subjectId: payload["subjectId"],
      tool: payload["tool"],
      sequence: payload["sequence"],
      ...terminal,
    },
    requestId,
  );
}

const EMPTY_SEARCH_RESULT = {
  generationId: "generation-001",
  visibility: "no_world",
  completeness: "unavailable",
  candidates: [],
  ambiguous: false,
  truncated: false,
  warnings: [],
} as const;

async function startPaper(adapter: ModelProvider): Promise<{
  readonly runtime: StartRuntimeResult;
  readonly port: number;
}> {
  const port = await findAvailablePort();
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  const configPath = await writeRuntimeConfig(directory, validRuntimeConfig(port));
  const runtime = await startRuntime({
    configPath,
    environment: runtimeEnvironment(),
    modelProvider: adapter,
    now: () => NOW,
  });
  runtimes.push(runtime);
  return { runtime, port };
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

function nextMessage(socket: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.once("message", (data: RawData, isBinary: boolean) => {
      if (isBinary) {
        reject(new Error("Expected a text WebSocket message"));
        return;
      }
      resolve(
        JSON.parse(Buffer.isBuffer(data) ? data.toString("utf8") : String(data)) as Record<
          string,
          unknown
        >,
      );
    });
  });
}

function nextMessages(
  socket: WebSocket,
  count: number,
): Promise<readonly Record<string, unknown>[]> {
  return new Promise((resolve, reject) => {
    const messages: Record<string, unknown>[] = [];
    const onError = (error: Error): void => {
      socket.off("message", onMessage);
      reject(error);
    };
    const onMessage = (data: RawData, isBinary: boolean): void => {
      if (isBinary) {
        socket.off("error", onError);
        socket.off("message", onMessage);
        reject(new Error("Expected a text WebSocket message"));
        return;
      }
      messages.push(
        JSON.parse(Buffer.isBuffer(data) ? data.toString("utf8") : String(data)) as Record<
          string,
          unknown
        >,
      );
      if (messages.length === count) {
        socket.off("error", onError);
        socket.off("message", onMessage);
        resolve(messages);
      }
    };
    socket.on("error", onError);
    socket.on("message", onMessage);
  });
}

function nextClose(socket: WebSocket): Promise<CloseDetails> {
  return new Promise((resolve) => {
    socket.once("close", (code, reason) => resolve({ code, reason: reason.toString("utf8") }));
  });
}

async function exchange(
  socket: WebSocket,
  message: Readonly<Record<string, unknown>> | ConnectorHello,
): Promise<Record<string, unknown>> {
  const response = nextMessage(socket);
  socket.send(JSON.stringify(message));
  return response;
}

async function authenticate(socket: WebSocket, request = hello()): Promise<ConnectorHello> {
  const response = (await exchange(socket, request)) as unknown as ConnectorHello;
  expect(response).toMatchObject({
    schemaVersion: "1.0",
    connectorKind: "standalone_client",
    type: "connector.hello",
    requestId: request.messageId,
    scopeId: SCOPE_ID,
    component: "runtime",
    selectedProtocolVersion: "client-1.0",
  });
  expect(response.nonce).not.toBe(request.nonce);
  expect(response.authentication.challenge).toBe(request.authentication.challenge);
  expect(verifyConnectorHandshakeProof(TEST_CONNECTOR_TOKEN, response)).toBe(true);
  return response;
}

afterEach(async () => {
  for (const client of clients) client.terminate();
  clients.clear();
  await Promise.allSettled(runtimes.splice(0).map((runtime) => runtime.close()));
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
  sequence = 1;
  vi.restoreAllMocks();
});

describe("standalone connector WebSocket", () => {
  it("registers exactly one profile route and reports the selected health protocol", async () => {
    const adapter = provider(async () => ({ type: "final", fallbackText: "ok" }));
    const client = await startClient(adapter);
    const paper = await startPaper(adapter);

    expect(client.runtime.profile).toBe("client");
    expect(paper.runtime.profile).toBe("paper");
    const health = await fetch(`http://127.0.0.1:${String(client.port)}/health`);
    expect(await health.json()).toMatchObject({ protocolVersion: "client-1.0" });
    expect((await fetch(`http://127.0.0.1:${String(client.port)}/agent`)).status).toBe(404);
    expect((await fetch(`http://127.0.0.1:${String(paper.port)}/connector`)).status).toBe(404);
  });

  it("authenticates, rejects a wrong token, and rejects a replayed hello", async () => {
    const adapter = provider(async () => ({ type: "final", fallbackText: "ok" }));
    const { url } = await startClient(adapter);

    const bad = await openClient(url);
    const badClose = nextClose(bad);
    bad.send(JSON.stringify(hello("wrong-token-value-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ")));
    await expect(badClose).resolves.toEqual({ code: 1008, reason: "AUTHENTICATION_FAILED" });

    const replayed = hello();
    const first = await openClient(url);
    await authenticate(first, replayed);
    const firstClosed = nextClose(first);
    first.close();
    await firstClosed;

    const second = await openClient(url);
    const replayClose = nextClose(second);
    second.send(JSON.stringify(replayed));
    await expect(replayClose).resolves.toEqual({ code: 1008, reason: "HANDSHAKE_REPLAYED" });
  });

  it("completes a general text request with no Paper tools and returns bounded status", async () => {
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (request) => {
      generated.push(request);
      return {
        type: "final",
        fallbackText: "local standalone answer",
        usage: { inputTokens: 7, outputTokens: 3 },
      };
    });
    const { url } = await startClient(adapter);
    const socket = await openClient(url);
    await authenticate(socket);

    const request = application("client.request", {
      sessionId: null,
      message: "What can I do from the main menu?",
    });
    const completion = await exchange(socket, request);
    expect(completion).toMatchObject({
      requestId: request["messageId"],
      scopeId: SCOPE_ID,
      type: "client.complete",
      payload: {
        sessionId: null,
        text: "Unknown:\n- Select an exact local catalog target before requesting process facts.",
        costMicroUsd: 19,
        costKind: "reported",
        sources: [],
      },
    });
    expect(generated).toHaveLength(1);
    expect(generated[0]?.tools).toEqual([]);
    expect(generated[0]?.instructions).not.toContain("server tools");

    const statusRequest = application("client.status.request", {});
    const status = await exchange(socket, statusRequest);
    expect(status).toMatchObject({
      requestId: statusRequest["messageId"],
      type: "client.status",
      payload: { state: "READY", activeRequests: 0, queuedRequests: 0 },
    });
  });

  it("exposes only the configured and negotiated client Tool capability intersection", async () => {
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (request) => {
      generated.push(request);
      return { type: "final", fallbackText: "bounded" };
    });
    const { url } = await startClient(
      adapter,
      clientConfigWithTools("game.resource.search", "game.process.plan"),
    );
    const socket = await openClient(url);
    const handshake = await authenticate(
      socket,
      hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]),
    );
    expect(handshake.capabilities).toContainEqual({ id: "game.resource.search", version: 1 });
    expect(handshake.capabilities).not.toContainEqual({ id: "game.process.plan", version: 1 });

    await exchange(
      socket,
      application("client.request", { sessionId: null, message: "search locally" }),
    );
    expect(generated[0]?.tools.map((tool) => tool.providerName)).toEqual(["game_resource_search"]);
    expect(generated[0]?.tools.some((tool) => tool.providerName.startsWith("server_"))).toBe(false);
  });

  it("proxies one bound client Tool call and renders its validated result deterministically", async () => {
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (request) => {
      generated.push(request);
      if (generated.length === 1) {
        return {
          type: "tool_call",
          providerCallId: "provider-search-1",
          providerName: "game_resource_search",
          arguments: { query: "iron pickaxe", limit: 5 },
          continuation: { provider: "openai", items: [] },
          usage: { inputTokens: 2, outputTokens: 1 },
        };
      }
      return { type: "final", fallbackText: "The local catalog is not ready." };
    });
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));

    const request = application("client.request", { sessionId: null, message: "find a pickaxe" });
    const call = await exchange(socket, request);
    expect(call).toMatchObject({
      requestId: request["messageId"],
      type: "client.tool.call",
      payload: {
        requestId: request["messageId"],
        subjectId: SCOPE_ID,
        tool: "game.resource.search",
        sequence: 0,
        arguments: { query: "iron pickaxe", limit: 5 },
      },
    });

    const completion = await exchange(
      socket,
      toolTerminal(call, "client.tool.result", { result: EMPTY_SEARCH_RESULT }),
    );
    expect(completion).toMatchObject({
      requestId: request["messageId"],
      type: "client.complete",
      payload: {
        text: "Local catalog result:\n- No client-visible match was found.",
        costMicroUsd: 6,
        costKind: "reported",
      },
    });
    expect(generated).toHaveLength(1);
  });

  it("passes a bounded client Tool error to the model without trusting client provenance", async () => {
    const generated: ModelGenerationRequest[] = [];
    const adapter = provider(async (request) => {
      generated.push(request);
      return generated.length === 1
        ? {
            type: "tool_call",
            providerCallId: "provider-search-1",
            providerName: "game_resource_search",
            arguments: { query: "iron", limit: 5 },
            continuation: { provider: "openai", items: [] },
          }
        : { type: "final", fallbackText: "Try again after the catalog reload." };
    });
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    const call = await exchange(
      socket,
      application("client.request", { sessionId: null, message: "find iron" }),
    );

    const completion = await exchange(
      socket,
      toolTerminal(call, "client.tool.error", {
        status: "failed",
        code: "CATALOG_RELOADING",
        message: "The local catalog is reloading.",
        retryable: true,
      }),
    );
    expect(completion["type"]).toBe("client.complete");
    expect(JSON.parse(generated[1]?.toolOutput?.output ?? "null")).toMatchObject({
      status: "failed",
      source: "client_catalog",
      trust: "client_visible",
      error: { code: "CATALOG_RELOADING", retryable: true },
    });
  });

  it("times out a pending client Tool, sends cancellation, and cleans up before continuing", async () => {
    let generation = 0;
    const adapter = provider(async () => {
      generation += 1;
      return generation === 1
        ? {
            type: "tool_call",
            providerCallId: "provider-search-1",
            providerName: "game_resource_search",
            arguments: { query: "iron", limit: 5 },
            continuation: { provider: "openai", items: [] },
          }
        : { type: "final", fallbackText: "The local lookup timed out." };
    });
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"), 25);
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    await exchange(
      socket,
      application("client.request", { sessionId: null, message: "find iron" }),
    );

    const messages = await nextMessages(socket, 2);
    expect(messages[0]).toMatchObject({
      type: "client.tool.cancel",
      payload: { reason: "TOOL_TIMEOUT" },
    });
    expect(messages[1]).toMatchObject({
      type: "client.complete",
      payload: {
        text: "Unknown:\n- Select an exact local catalog target before requesting process facts.",
        costMicroUsd: 100_000,
        costKind: "estimated",
      },
    });
  });

  it("propagates request cancellation to a pending client Tool and keeps the connector usable", async () => {
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-search-1",
      providerName: "game_resource_search",
      arguments: { query: "iron", limit: 5 },
      continuation: { provider: "openai", items: [] },
    }));
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    const request = application("client.request", { sessionId: null, message: "find iron" });
    await exchange(socket, request);

    const cancellation = await exchange(
      socket,
      application("client.cancel", {
        targetRequestId: request["messageId"],
        reason: "USER_REQUEST",
      }),
    );
    expect(cancellation).toMatchObject({
      requestId: request["messageId"],
      type: "client.tool.cancel",
      payload: { reason: "REQUEST_CANCELLED" },
    });
    const status = await exchange(socket, application("client.status.request", {}));
    expect(status).toMatchObject({
      type: "client.status",
      payload: { activeRequests: 0, queuedRequests: 0 },
    });
  });

  it.each([
    ["unknown Tool", { tool: "paper.command" }],
    ["cross-request injection", { requestId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }],
  ])("closes on %s while a client Tool is pending", async (_label, mutation) => {
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-search-1",
      providerName: "game_resource_search",
      arguments: { query: "iron", limit: 5 },
      continuation: { provider: "openai", items: [] },
    }));
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    const call = await exchange(
      socket,
      application("client.request", { sessionId: null, message: "find iron" }),
    );
    const terminal = toolTerminal(call, "client.tool.result", {
      result: EMPTY_SEARCH_RESULT,
      ...mutation,
    });
    if (mutation.requestId !== undefined) terminal["requestId"] = mutation.requestId;

    const closed = nextClose(socket);
    socket.send(JSON.stringify(terminal));
    await expect(closed).resolves.toEqual({
      code: 1008,
      reason: "APPLICATION_MESSAGE_INVALID",
    });
  });

  it("rejects a second terminal for the same Tool call even with a fresh envelope identity", async () => {
    let generation = 0;
    const adapter = provider(async () => {
      generation += 1;
      return {
        type: "tool_call",
        providerCallId: "provider-search-1",
        providerName: "game_resource_search",
        arguments: { query: "iron", limit: 5 },
        continuation: { provider: "openai", items: [] },
      };
    });
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const socket = await openClient(url);
    await authenticate(socket, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    const call = await exchange(
      socket,
      application("client.request", { sessionId: null, message: "find iron" }),
    );
    const completion = nextMessage(socket);
    socket.send(
      JSON.stringify(toolTerminal(call, "client.tool.result", { result: EMPTY_SEARCH_RESULT })),
    );
    await expect(completion).resolves.toMatchObject({ type: "client.complete" });
    expect(generation).toBe(1);

    const closed = nextClose(socket);
    socket.send(
      JSON.stringify(toolTerminal(call, "client.tool.result", { result: EMPTY_SEARCH_RESULT })),
    );
    await expect(closed).resolves.toEqual({
      code: 1008,
      reason: "APPLICATION_MESSAGE_INVALID",
    });
  });

  it("cleans pending client Tool ownership on connector loss", async () => {
    const adapter = provider(async () => ({
      type: "tool_call",
      providerCallId: "provider-search-1",
      providerName: "game_resource_search",
      arguments: { query: "iron", limit: 5 },
      continuation: { provider: "openai", items: [] },
    }));
    const { url } = await startClient(adapter, clientConfigWithTools("game.resource.search"));
    const first = await openClient(url);
    await authenticate(first, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    await exchange(first, application("client.request", { sessionId: null, message: "find iron" }));
    const firstClosed = nextClose(first);
    first.close();
    await firstClosed;

    const second = await openClient(url);
    await authenticate(second, hello(TEST_CONNECTOR_TOKEN, ["game.resource.search"]));
    const status = await exchange(second, application("client.status.request", {}));
    expect(status).toMatchObject({
      type: "client.status",
      payload: { activeRequests: 0, queuedRequests: 0 },
    });
  });

  it("closes the connector when an authenticated application envelope is replayed", async () => {
    const adapter = provider(async () => ({ type: "final", fallbackText: "ok" }));
    const { url } = await startClient(adapter);
    const socket = await openClient(url);
    await authenticate(socket);
    const statusRequest = application("client.status.request", {});
    const statusResponse = await exchange(socket, statusRequest);
    const replayedNonce = application("client.status.request", {});
    replayedNonce["nonce"] = statusResponse["nonce"];

    const closed = nextClose(socket);
    socket.send(JSON.stringify(replayedNonce));
    await expect(closed).resolves.toEqual({
      code: 1008,
      reason: "APPLICATION_MESSAGE_REPLAYED",
    });
  });

  it("cancels an active request without closing the authenticated connector", async () => {
    let started: (() => void) | undefined;
    let aborted = false;
    const providerStarted = new Promise<void>((resolve) => {
      started = resolve;
    });
    const adapter = provider(
      (request) =>
        new Promise<ModelGenerationResult>((_resolve, reject) => {
          started?.();
          request.signal.addEventListener(
            "abort",
            () => {
              aborted = true;
              reject(new Error("cancelled"));
            },
            { once: true },
          );
        }),
    );
    const { url } = await startClient(adapter);
    const socket = await openClient(url);
    await authenticate(socket);
    const request = application("client.request", { sessionId: null, message: "wait" });
    socket.send(JSON.stringify(request));
    await providerStarted;

    socket.send(
      JSON.stringify(
        application("client.cancel", {
          targetRequestId: request["messageId"],
          reason: "USER_REQUEST",
        }),
      ),
    );
    await vi.waitFor(() => expect(aborted).toBe(true));

    const statusRequest = application("client.status.request", {});
    const status = await exchange(socket, statusRequest);
    expect(status).toMatchObject({
      type: "client.status",
      payload: { activeRequests: 0, queuedRequests: 0 },
    });
  });

  it("maps an offline Provider to a recoverable standalone error", async () => {
    const adapter = provider(async () => {
      throw new ModelGenerationError("MODEL_UNAVAILABLE", "NOT_BILLABLE");
    });
    const { url } = await startClient(adapter);
    const socket = await openClient(url);
    await authenticate(socket);
    const request = application("client.request", { sessionId: null, message: "offline" });

    const response = await exchange(socket, request);
    expect(response).toMatchObject({
      requestId: request["messageId"],
      type: "client.error",
      payload: {
        code: "MODEL_UNAVAILABLE",
        retryable: true,
      },
    });
  });

  it("returns a recoverable timeout and aborts the Provider operation", async () => {
    let aborted = false;
    const adapter = provider(
      (request) =>
        new Promise<ModelGenerationResult>((_resolve, reject) => {
          request.signal.addEventListener(
            "abort",
            () => {
              aborted = true;
              reject(new Error("timed out"));
            },
            { once: true },
          );
        }),
    );
    const { url } = await startClient(adapter, (port) =>
      validClientRuntimeConfig(port).replace("timeoutSeconds: 2", "timeoutSeconds: 1"),
    );
    const socket = await openClient(url);
    await authenticate(socket);
    const request = application("client.request", { sessionId: null, message: "timeout" });

    const response = await exchange(socket, request);
    expect(response).toMatchObject({
      requestId: request["messageId"],
      type: "client.error",
      payload: { code: "MODEL_TIMEOUT", retryable: true },
    });
    expect(aborted).toBe(true);
  });
});
