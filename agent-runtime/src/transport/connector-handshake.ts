import { randomBytes, randomUUID } from "node:crypto";
import { TextDecoder } from "node:util";

import websocket from "@fastify/websocket";
import type { FastifyInstance } from "fastify";
import type { RawData, WebSocket } from "ws";

import type { RuntimeHealthState } from "../health/runtime-health.js";
import type { SchemaRegistry } from "../protocol/schema-registry.js";
import type { AgentRequestInput, AgentRuntimeResponse } from "../requests/agent-request-service.js";
import type {
  ClientToolId,
  CoreToolId,
  ToolCallPayload,
  ToolResultPayload,
  ToolResultSource,
  ToolResultTrust,
} from "../tools/tool-types.js";
import { runtimeIdentity } from "../version.js";
import {
  CONNECTOR_APPLICATION_MAXIMUM_BYTES,
  ConnectorApplicationFailure,
  ConnectorApplicationProtocol,
  type ConnectorApplicationFailureCode,
  type ConnectorApplicationMessage,
  type ConnectorToolCancellationReason,
} from "./connector-application-envelope.js";
import {
  CONNECTOR_KIND,
  CONNECTOR_PROTOCOL_VERSION,
  CONNECTOR_SCHEMA_VERSION,
  createConnectorHandshakeProof,
  type ConnectorCapability,
  type ConnectorHello,
  validateConnectorHelloSemantics,
  verifyConnectorHandshakeProof,
} from "./connector-handshake-authentication.js";
import { HandshakeReplayCache } from "./replay-cache.js";
import { parseStrictJson } from "./strict-json.js";

export const CONNECTOR_HANDSHAKE_MAXIMUM_BYTES = 16 * 1024;
export const CONNECTOR_HANDSHAKE_CLOCK_SKEW_MILLISECONDS = 30_000;
export const CONNECTOR_HANDSHAKE_TIMEOUT_MILLISECONDS = 5_000;
const REPLAY_CACHE_TTL_MILLISECONDS = CONNECTOR_HANDSHAKE_CLOCK_SKEW_MILLISECONDS * 2;
const REPLAY_CACHE_MAXIMUM_ENTRIES = 4096;
const MAXIMUM_PENDING_CONNECTIONS = 8;
const MAXIMUM_SETTLED_TOOL_CALLS = 4096;
const DEFAULT_TOOL_TIMEOUT_MILLISECONDS = 15_000;
const DEFAULT_KEY_ID = "session-key";
const DEFAULT_CAPABILITIES = [
  { id: "client.cancel", version: 1 },
  { id: "client.status", version: 1 },
  { id: "client.text", version: 1 },
] as const satisfies readonly ConnectorCapability[];

type ConnectorHandshakeFailureCode =
  | "AUTHENTICATION_FAILED"
  | "HANDSHAKE_INVALID"
  | "HANDSHAKE_REPLAYED"
  | "HANDSHAKE_STALE"
  | "HANDSHAKE_TIMEOUT"
  | "CONNECTOR_ALREADY_CONNECTED"
  | "PROTOCOL_INCOMPATIBLE"
  | "RUNTIME_NOT_READY"
  | "RUNTIME_STOPPING"
  | "UNSUPPORTED_MESSAGE_TYPE";

export interface ConnectorHandshakeServiceOptions {
  readonly scopeId: string;
  readonly subjectId: string;
  readonly connectorToken: string;
  readonly schemaRegistry: SchemaRegistry;
  readonly health: RuntimeHealthState;
  readonly agentRequests: ConnectorAgentRequests;
  readonly tools: ConnectorToolRegistry;
  readonly componentVersion?: string;
  readonly toolTimeoutMilliseconds?: number;
  readonly keyId?: string;
  readonly capabilities?: readonly ConnectorCapability[];
  readonly now?: () => Date;
  readonly randomBytes?: (size: number) => Buffer;
  readonly randomUuid?: () => string;
  readonly replayCache?: HandshakeReplayCache;
}

export interface ConnectorAgentRequests {
  readonly activeCount: number;
  readonly queuedCount: number;
  submit(input: AgentRequestInput, respond: (response: AgentRuntimeResponse) => void): void;
  cancel(requestId: string, subjectId: string): boolean;
  acceptToolResult(
    requestId: string,
    payload: ToolResultPayload,
  ): "accepted" | "ignored" | "violation";
  cancelAll(): void;
  close(): Promise<void>;
}

export interface ConnectorToolRegistry {
  configuredClientTools(): readonly ClientToolId[];
  activateClientCapabilities(capabilityIds: readonly string[]): void;
  clearClientCapabilities(): void;
  isClientToolActive(id: ClientToolId): boolean;
  byId(id: CoreToolId):
    | {
        readonly execution: string;
        readonly source: ToolResultSource;
        readonly trust: ToolResultTrust;
      }
    | undefined;
}

interface PendingConnectorTool {
  readonly requestId: string;
  readonly payload: ToolCallPayload;
  readonly timeout: NodeJS.Timeout;
}

class ConnectorHandshakeFailure extends Error {
  public readonly code: ConnectorHandshakeFailureCode;

  public constructor(code: ConnectorHandshakeFailureCode) {
    super(code);
    this.name = "ConnectorHandshakeFailure";
    this.code = code;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rawDataBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  return Buffer.from(data);
}

function closeWithCode(
  socket: WebSocket,
  code: ConnectorHandshakeFailureCode | ConnectorApplicationFailureCode,
): void {
  if (socket.readyState === socket.OPEN) socket.close(1008, code);
  else if (socket.readyState !== socket.CLOSED) socket.terminate();
}

export class ConnectorHandshakeService {
  readonly #scopeId: string;
  readonly #componentVersion: string;
  readonly #subjectId: string;
  readonly #connectorToken: string;
  readonly #schemaRegistry: SchemaRegistry;
  readonly #health: RuntimeHealthState;
  readonly #agentRequests: ConnectorAgentRequests;
  readonly #tools: ConnectorToolRegistry;
  readonly #toolTimeoutMilliseconds: number;
  readonly #keyId: string;
  readonly #capabilities: readonly ConnectorCapability[];
  readonly #now: () => Date;
  readonly #randomBytes: (size: number) => Buffer;
  readonly #randomUuid: () => string;
  readonly #replayCache: HandshakeReplayCache;
  readonly #applicationProtocol: ConnectorApplicationProtocol;
  readonly #connections = new Set<WebSocket>();
  readonly #pendingTools = new Map<string, PendingConnectorTool>();
  readonly #settledToolCalls = new Map<string, number>();
  #authenticatedSocket: WebSocket | undefined;
  #closed = false;

  public constructor(options: ConnectorHandshakeServiceOptions) {
    this.#scopeId = options.scopeId;
    this.#componentVersion = options.componentVersion ?? runtimeIdentity.version;
    if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u.test(this.#componentVersion)) {
      throw new TypeError("Connector component version is invalid.");
    }
    this.#subjectId = options.subjectId;
    this.#connectorToken = options.connectorToken;
    this.#schemaRegistry = options.schemaRegistry;
    this.#health = options.health;
    this.#agentRequests = options.agentRequests;
    this.#tools = options.tools;
    this.#toolTimeoutMilliseconds =
      options.toolTimeoutMilliseconds ?? DEFAULT_TOOL_TIMEOUT_MILLISECONDS;
    if (
      !Number.isSafeInteger(this.#toolTimeoutMilliseconds) ||
      this.#toolTimeoutMilliseconds < 1 ||
      this.#toolTimeoutMilliseconds > 120_000
    ) {
      throw new TypeError("Connector Tool timeout is outside the supported range.");
    }
    this.#keyId = options.keyId ?? DEFAULT_KEY_ID;
    const supportedCapabilities = [
      ...DEFAULT_CAPABILITIES,
      ...options.tools.configuredClientTools().map((id) => ({ id, version: 1 })),
    ].sort((left, right) => (left.id < right.id ? -1 : left.id > right.id ? 1 : 0));
    const supportedIds = new Set<string>(supportedCapabilities.map((capability) => capability.id));
    this.#capabilities = (options.capabilities ?? supportedCapabilities)
      .filter((capability) => supportedIds.has(capability.id) && capability.version === 1)
      .sort((left, right) => (left.id < right.id ? -1 : left.id > right.id ? 1 : 0));
    this.#now = options.now ?? (() => new Date());
    this.#randomBytes = options.randomBytes ?? randomBytes;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    this.#replayCache =
      options.replayCache ??
      new HandshakeReplayCache({
        ttlMilliseconds: REPLAY_CACHE_TTL_MILLISECONDS,
        maximumEntries: REPLAY_CACHE_MAXIMUM_ENTRIES,
      });
    this.#applicationProtocol = new ConnectorApplicationProtocol({
      scopeId: options.scopeId,
      subjectId: options.subjectId,
      schemaRegistry: options.schemaRegistry,
      replayCache: this.#replayCache,
      ...(options.now === undefined ? {} : { now: options.now }),
      ...(options.randomBytes === undefined ? {} : { randomBytes: options.randomBytes }),
      ...(options.randomUuid === undefined ? {} : { randomUuid: options.randomUuid }),
    });
  }

  public accept(socket: WebSocket): void {
    if (this.#closed) {
      closeWithCode(socket, "RUNTIME_STOPPING");
      return;
    }
    if (this.#health.view().status !== "READY") {
      closeWithCode(socket, "RUNTIME_NOT_READY");
      return;
    }
    if (this.#connections.size >= MAXIMUM_PENDING_CONNECTIONS) {
      closeWithCode(socket, "CONNECTOR_ALREADY_CONNECTED");
      return;
    }

    this.#connections.add(socket);
    let handshakeAttempted = false;
    let authenticated = false;
    const timeout = setTimeout(
      () => closeWithCode(socket, "HANDSHAKE_TIMEOUT"),
      CONNECTOR_HANDSHAKE_TIMEOUT_MILLISECONDS,
    );
    timeout.unref();

    socket.once("close", () => {
      clearTimeout(timeout);
      this.#connections.delete(socket);
      if (this.#authenticatedSocket === socket) {
        this.#authenticatedSocket = undefined;
        this.#clearPendingTools();
        this.#tools.clearClientCapabilities();
        this.#agentRequests.cancelAll();
      }
    });
    socket.on("message", (data, isBinary) => {
      if (authenticated) {
        this.#handleApplicationMessage(socket, data, isBinary);
        return;
      }
      if (handshakeAttempted) {
        closeWithCode(socket, "HANDSHAKE_INVALID");
        return;
      }
      handshakeAttempted = true;
      clearTimeout(timeout);
      try {
        if (isBinary) throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
        const response = this.#authenticate(rawDataBuffer(data), socket);
        authenticated = true;
        socket.send(JSON.stringify(response), (error) => {
          if (error !== undefined && error !== null) socket.terminate();
        });
      } catch (error) {
        closeWithCode(
          socket,
          error instanceof ConnectorHandshakeFailure ? error.code : "HANDSHAKE_INVALID",
        );
      }
    });
  }

  public close(): void {
    if (this.#closed) return;
    this.#closed = true;
    const authenticatedSocket = this.#authenticatedSocket;
    if (authenticatedSocket !== undefined) {
      this.#cancelPendingTools(authenticatedSocket, undefined, "RUNTIME_SHUTDOWN");
    }
    for (const socket of this.#connections) socket.terminate();
    this.#connections.clear();
    this.#authenticatedSocket = undefined;
    this.#clearPendingTools();
    this.#tools.clearClientCapabilities();
    void this.#agentRequests.close();
  }

  public get authenticated(): boolean {
    return this.#authenticatedSocket !== undefined;
  }

  #handleApplicationMessage(socket: WebSocket, data: RawData, isBinary: boolean): void {
    try {
      if (isBinary || this.#authenticatedSocket !== socket) {
        throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
      }
      const bytes = rawDataBuffer(data);
      if (bytes.length > CONNECTOR_APPLICATION_MAXIMUM_BYTES) {
        throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
      }
      let source: string;
      try {
        source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      } catch {
        throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
      }
      const message = this.#applicationProtocol.parse(source);
      if (message.type === "client.cancel") {
        this.#cancelPendingTools(socket, message.targetRequestId, "REQUEST_CANCELLED");
        this.#agentRequests.cancel(message.targetRequestId, this.#subjectId);
        return;
      }
      if (message.type === "client.status.request") {
        this.#sendApplicationResponse(
          socket,
          this.#applicationProtocol.createStatusResponse(message.messageId, {
            state: "READY",
            activeRequests: this.#agentRequests.activeCount,
            queuedRequests: this.#agentRequests.queuedCount,
          }),
        );
        return;
      }
      if (message.type === "client.tool.result" || message.type === "client.tool.error") {
        this.#acceptToolTerminal(socket, message);
        return;
      }
      this.#agentRequests.submit(message.request, (response) => {
        try {
          if (response.type === "tool.call") {
            this.#sendToolCall(socket, message.request.requestId, response.payload);
            return;
          }
          this.#cancelPendingTools(
            socket,
            message.request.requestId,
            response.type === "agent.error" && response.payload.code === "MODEL_TIMEOUT"
              ? "MODEL_TIMEOUT"
              : "REQUEST_CANCELLED",
          );
          this.#sendApplicationResponse(
            socket,
            this.#applicationProtocol.createTerminalResponse(message.request.requestId, response),
          );
        } catch {
          socket.terminate();
        }
      });
    } catch (error) {
      closeWithCode(
        socket,
        error instanceof ConnectorApplicationFailure ? error.code : "APPLICATION_MESSAGE_INVALID",
      );
    }
  }

  #sendToolCall(socket: WebSocket, requestId: string, payload: ToolCallPayload): void {
    const descriptor = this.#tools.byId(payload.tool);
    if (
      descriptor?.execution !== "connector_remote" ||
      !this.#tools.isClientToolActive(payload.tool as ClientToolId) ||
      this.#pendingTools.has(payload.toolCallId) ||
      this.#settledToolCalls.has(payload.toolCallId) ||
      [...this.#pendingTools.values()].some((pending) => pending.requestId === requestId)
    ) {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
    }
    const timeout = setTimeout(
      () => this.#expireToolCall(socket, payload.toolCallId),
      this.#toolTimeoutMilliseconds,
    );
    timeout.unref();
    this.#pendingTools.set(payload.toolCallId, { requestId, payload, timeout });
    try {
      this.#sendApplicationResponse(
        socket,
        this.#applicationProtocol.createToolCall(requestId, payload),
      );
    } catch (error) {
      clearTimeout(timeout);
      this.#pendingTools.delete(payload.toolCallId);
      throw error;
    }
  }

  #acceptToolTerminal(
    socket: WebSocket,
    message:
      | Extract<ConnectorApplicationMessage, { readonly type: "client.tool.result" }>
      | Extract<ConnectorApplicationMessage, { readonly type: "client.tool.error" }>,
  ): void {
    const pending = this.#pendingTools.get(message.toolCallId);
    if (
      pending === undefined ||
      this.#settledToolCalls.has(message.toolCallId) ||
      pending.requestId !== message.requestId ||
      pending.payload.playerUuid !== message.subjectId ||
      pending.payload.tool !== message.tool ||
      pending.payload.sequence !== message.sequence
    ) {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
    }
    const descriptor = this.#tools.byId(pending.payload.tool);
    if (descriptor?.execution !== "connector_remote") {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
    }

    clearTimeout(pending.timeout);
    this.#pendingTools.delete(message.toolCallId);
    this.#rememberSettled(message.toolCallId);
    const result: ToolResultPayload =
      message.type === "client.tool.result"
        ? {
            toolCallId: pending.payload.toolCallId,
            sessionId: pending.payload.sessionId,
            playerUuid: pending.payload.playerUuid,
            tool: pending.payload.tool,
            sequence: pending.payload.sequence,
            status: "succeeded",
            source: descriptor.source,
            trust: descriptor.trust,
            result: message.result,
            error: null,
          }
        : {
            toolCallId: pending.payload.toolCallId,
            sessionId: pending.payload.sessionId,
            playerUuid: pending.payload.playerUuid,
            tool: pending.payload.tool,
            sequence: pending.payload.sequence,
            status: message.status,
            source: message.status === "rejected" ? "client_policy" : descriptor.source,
            trust: message.status === "rejected" ? "client_visible" : descriptor.trust,
            result: null,
            error: {
              code: message.code,
              message: message.message,
              retryable: message.retryable,
            },
          };
    if (this.#agentRequests.acceptToolResult(message.requestId, result) !== "accepted") {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
    }
  }

  #expireToolCall(socket: WebSocket, toolCallId: string): void {
    try {
      const pending = this.#pendingTools.get(toolCallId);
      if (pending === undefined) return;
      this.#pendingTools.delete(toolCallId);
      clearTimeout(pending.timeout);
      this.#rememberSettled(toolCallId);
      this.#sendApplicationResponse(
        socket,
        this.#applicationProtocol.createToolCancellation(
          pending.requestId,
          pending.payload,
          "TOOL_TIMEOUT",
        ),
      );
      const descriptor = this.#tools.byId(pending.payload.tool);
      if (
        descriptor === undefined ||
        this.#agentRequests.acceptToolResult(pending.requestId, {
          toolCallId: pending.payload.toolCallId,
          sessionId: pending.payload.sessionId,
          playerUuid: pending.payload.playerUuid,
          tool: pending.payload.tool,
          sequence: pending.payload.sequence,
          status: "failed",
          source: descriptor?.source ?? "client_policy",
          trust: descriptor?.trust ?? "client_visible",
          result: null,
          error: {
            code: "CLIENT_TOOL_TIMEOUT",
            message: "The local client Tool did not finish before its deadline.",
            retryable: true,
          },
        }) !== "accepted"
      ) {
        closeWithCode(socket, "APPLICATION_MESSAGE_INVALID");
      }
    } catch {
      closeWithCode(socket, "APPLICATION_MESSAGE_INVALID");
    }
  }

  #cancelPendingTools(
    socket: WebSocket,
    requestId: string | undefined,
    reason: ConnectorToolCancellationReason,
  ): void {
    for (const [toolCallId, pending] of this.#pendingTools) {
      if (requestId !== undefined && pending.requestId !== requestId) continue;
      clearTimeout(pending.timeout);
      this.#pendingTools.delete(toolCallId);
      this.#rememberSettled(toolCallId);
      this.#sendApplicationResponse(
        socket,
        this.#applicationProtocol.createToolCancellation(
          pending.requestId,
          pending.payload,
          reason,
        ),
      );
    }
  }

  #clearPendingTools(): void {
    for (const pending of this.#pendingTools.values()) clearTimeout(pending.timeout);
    this.#pendingTools.clear();
    this.#settledToolCalls.clear();
  }

  #rememberSettled(toolCallId: string): void {
    this.#settledToolCalls.set(toolCallId, this.#now().getTime());
    while (this.#settledToolCalls.size > MAXIMUM_SETTLED_TOOL_CALLS) {
      const oldest = this.#settledToolCalls.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.#settledToolCalls.delete(oldest);
    }
  }

  #sendApplicationResponse(socket: WebSocket, response: Record<string, unknown>): void {
    if (this.#authenticatedSocket !== socket || socket.readyState !== socket.OPEN) return;
    try {
      socket.send(JSON.stringify(response), (error) => {
        if (error !== undefined && error !== null) socket.terminate();
      });
    } catch {
      socket.terminate();
    }
  }

  #authenticate(bytes: Buffer, socket: WebSocket): ConnectorHello {
    if (bytes.length > CONNECTOR_HANDSHAKE_MAXIMUM_BYTES) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    let source: string;
    try {
      source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    let document: unknown;
    try {
      document = parseStrictJson(source);
    } catch {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    if (!isRecord(document)) throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    if (document["type"] !== "connector.hello") {
      throw new ConnectorHandshakeFailure("UNSUPPORTED_MESSAGE_TYPE");
    }
    if (!this.#schemaRegistry.validate("connector-hello.schema.json", document).valid) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    const hello = document as unknown as ConnectorHello;
    if (
      !validateConnectorHelloSemantics(hello) ||
      hello.component !== "standalone_client" ||
      hello.supportedProtocolVersions[0] !== CONNECTOR_PROTOCOL_VERSION
    ) {
      throw new ConnectorHandshakeFailure("PROTOCOL_INCOMPATIBLE");
    }
    if (
      hello.scopeId !== this.#scopeId ||
      hello.authentication.keyId !== this.#keyId ||
      !verifyConnectorHandshakeProof(this.#connectorToken, hello)
    ) {
      throw new ConnectorHandshakeFailure("AUTHENTICATION_FAILED");
    }
    const now = this.#now();
    const timestamp = Date.parse(hello.timestamp);
    if (
      !Number.isFinite(timestamp) ||
      Math.abs(now.getTime() - timestamp) > CONNECTOR_HANDSHAKE_CLOCK_SKEW_MILLISECONDS
    ) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_STALE");
    }
    if (!this.#replayCache.accept(hello.messageId, hello.nonce, now.getTime())) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_REPLAYED");
    }
    if (this.#authenticatedSocket !== undefined) {
      throw new ConnectorHandshakeFailure("CONNECTOR_ALREADY_CONNECTED");
    }

    const responseMessageId = this.#distinctUuid(hello.messageId);
    const responseNonce = this.#distinctNonce(hello.nonce);
    const advertised = new Map(hello.capabilities.map((capability) => [capability.id, capability]));
    const negotiated = this.#capabilities.filter(
      (capability) => advertised.get(capability.id)?.version === capability.version,
    );
    const responseWithoutProof: ConnectorHello = {
      schemaVersion: CONNECTOR_SCHEMA_VERSION,
      connectorKind: CONNECTOR_KIND,
      type: "connector.hello",
      messageId: responseMessageId,
      requestId: hello.messageId,
      timestamp: now.toISOString(),
      nonce: responseNonce,
      component: "runtime",
      componentVersion: this.#componentVersion,
      scopeId: this.#scopeId,
      supportedProtocolVersions: [CONNECTOR_PROTOCOL_VERSION],
      selectedProtocolVersion: CONNECTOR_PROTOCOL_VERSION,
      capabilities: negotiated,
      authentication: {
        scheme: "hmac-sha256",
        keyId: this.#keyId,
        challenge: hello.authentication.challenge,
        proof: "A".repeat(43),
      },
    };
    const response: ConnectorHello = {
      ...responseWithoutProof,
      authentication: {
        ...responseWithoutProof.authentication,
        proof: createConnectorHandshakeProof(this.#connectorToken, responseWithoutProof),
      },
    };
    if (
      !validateConnectorHelloSemantics(response) ||
      !this.#schemaRegistry.validate("connector-hello.schema.json", response).valid
    ) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    if (!this.#replayCache.accept(response.messageId, response.nonce, now.getTime())) {
      throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
    }
    this.#tools.activateClientCapabilities(negotiated.map((capability) => capability.id));
    this.#authenticatedSocket = socket;
    return response;
  }

  #distinctUuid(requestId: string): string {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = this.#randomUuid();
      if (candidate !== requestId) return candidate;
    }
    throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
  }

  #distinctNonce(requestNonce: string): string {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = this.#randomBytes(16).toString("base64url");
      if (candidate !== requestNonce) return candidate;
    }
    throw new ConnectorHandshakeFailure("HANDSHAKE_INVALID");
  }
}

export async function registerConnectorHandshakeRoute(
  app: FastifyInstance,
  options: ConnectorHandshakeServiceOptions,
): Promise<ConnectorHandshakeService> {
  const service = new ConnectorHandshakeService(options);
  await app.register(websocket, {
    options: {
      maxPayload: CONNECTOR_APPLICATION_MAXIMUM_BYTES,
      perMessageDeflate: false,
    },
  });
  app.get("/connector", { websocket: true }, (socket) => service.accept(socket));
  app.addHook("onClose", async () => service.close());
  return service;
}
