import { randomBytes, randomUUID } from "node:crypto";
import { TextDecoder } from "node:util";

import websocket from "@fastify/websocket";
import type { FastifyInstance } from "fastify";
import type { RawData, WebSocket } from "ws";

import type { RuntimeHealthState } from "../health/runtime-health.js";
import type { SchemaRegistry } from "../protocol/schema-registry.js";
import type { AgentRequestService } from "../requests/agent-request-service.js";
import type { UsageAccounting } from "../usage/usage-accounting.js";
import { runtimeIdentity, SUPPORTED_PROTOCOL_VERSION } from "../version.js";
import {
  APPLICATION_MAXIMUM_BYTES,
  ApplicationEnvelopeProtocol,
  ApplicationProtocolFailure,
  type ApplicationFailureCode,
} from "./application-envelope.js";
import {
  createHandshakeProof,
  decodeCanonicalBase64Url,
  verifyHandshakeProof,
  type HandshakeProofFields,
} from "./handshake-authentication.js";
import { HandshakeReplayCache } from "./replay-cache.js";
import { parseStrictJson } from "./strict-json.js";

export const HANDSHAKE_MAXIMUM_BYTES = 16 * 1024;
export const HANDSHAKE_CLOCK_SKEW_MILLISECONDS = 30_000;
export const HANDSHAKE_TIMEOUT_MILLISECONDS = 5_000;
const REPLAY_CACHE_TTL_MILLISECONDS = HANDSHAKE_CLOCK_SKEW_MILLISECONDS * 2;
const REPLAY_CACHE_MAXIMUM_ENTRIES = 4096;
const MAXIMUM_PENDING_CONNECTIONS = 8;

type HandshakeFailureCode =
  | "AUTHENTICATION_FAILED"
  | "HANDSHAKE_INVALID"
  | "HANDSHAKE_REPLAYED"
  | "HANDSHAKE_STALE"
  | "HANDSHAKE_TIMEOUT"
  | "PAPER_ALREADY_CONNECTED"
  | "PROTOCOL_INCOMPATIBLE"
  | "RUNTIME_NOT_READY"
  | "RUNTIME_STOPPING"
  | "UNSUPPORTED_MESSAGE_TYPE";

interface PaperHandshakeEnvelope {
  readonly protocolVersion: typeof SUPPORTED_PROTOCOL_VERSION;
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly type: "paper.hello";
  readonly timestamp: string;
  readonly nonce: string;
  readonly payload: PaperHandshakePayload;
}

interface PaperHandshakePayload {
  readonly component: "paper";
  readonly componentVersion: string;
  readonly supportedProtocolVersions: readonly (typeof SUPPORTED_PROTOCOL_VERSION)[];
  readonly selectedProtocolVersion: null;
  readonly authentication: {
    readonly scheme: "hmac-sha256";
    readonly keyId: string;
    readonly challenge: string;
    readonly proof: string;
  };
}

export interface PaperHandshakeServiceOptions {
  readonly serverId: string;
  readonly serverToken: string;
  readonly schemaRegistry: SchemaRegistry;
  readonly health: RuntimeHealthState;
  readonly agentRequests: AgentRequestService;
  readonly usage: UsageAccounting;
  readonly now?: () => Date;
  readonly randomBytes?: (size: number) => Buffer;
  readonly randomUuid?: () => string;
  readonly replayCache?: HandshakeReplayCache;
}

class HandshakeFailure extends Error {
  public readonly code: HandshakeFailureCode;

  public constructor(code: HandshakeFailureCode) {
    super(code);
    this.name = "HandshakeFailure";
    this.code = code;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rawDataBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) {
    return data;
  }
  if (Array.isArray(data)) {
    return Buffer.concat(data);
  }
  return Buffer.from(data);
}

function closeWithCode(
  socket: WebSocket,
  code: HandshakeFailureCode | ApplicationFailureCode,
): void {
  if (socket.readyState === socket.OPEN) {
    socket.close(1008, code);
  } else if (socket.readyState !== socket.CLOSED) {
    socket.terminate();
  }
}

function proofFields(envelope: PaperHandshakeEnvelope): HandshakeProofFields {
  return {
    serverId: envelope.serverId,
    type: envelope.type,
    timestamp: envelope.timestamp,
    nonce: envelope.nonce,
    component: envelope.payload.component,
    componentVersion: envelope.payload.componentVersion,
    challenge: envelope.payload.authentication.challenge,
  };
}

export class PaperHandshakeService {
  readonly #serverId: string;
  readonly #serverToken: string;
  readonly #schemaRegistry: SchemaRegistry;
  readonly #health: RuntimeHealthState;
  readonly #now: () => Date;
  readonly #randomBytes: (size: number) => Buffer;
  readonly #randomUuid: () => string;
  readonly #replayCache: HandshakeReplayCache;
  readonly #agentRequests: AgentRequestService;
  readonly #usage: UsageAccounting;
  readonly #applicationProtocol: ApplicationEnvelopeProtocol;
  readonly #connections = new Set<WebSocket>();
  #authenticatedSocket: WebSocket | undefined;
  #closed = false;

  public constructor(options: PaperHandshakeServiceOptions) {
    this.#serverId = options.serverId;
    this.#serverToken = options.serverToken;
    this.#schemaRegistry = options.schemaRegistry;
    this.#health = options.health;
    this.#now = options.now ?? (() => new Date());
    this.#randomBytes = options.randomBytes ?? randomBytes;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    this.#replayCache =
      options.replayCache ??
      new HandshakeReplayCache({
        ttlMilliseconds: REPLAY_CACHE_TTL_MILLISECONDS,
        maximumEntries: REPLAY_CACHE_MAXIMUM_ENTRIES,
      });
    this.#agentRequests = options.agentRequests;
    this.#usage = options.usage;
    this.#applicationProtocol = new ApplicationEnvelopeProtocol({
      serverId: options.serverId,
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
      closeWithCode(socket, "PAPER_ALREADY_CONNECTED");
      return;
    }

    this.#connections.add(socket);
    let handshakeAttempted = false;
    let authenticated = false;
    const timeout = setTimeout(() => {
      closeWithCode(socket, "HANDSHAKE_TIMEOUT");
    }, HANDSHAKE_TIMEOUT_MILLISECONDS);
    timeout.unref();

    socket.once("close", () => {
      clearTimeout(timeout);
      this.#connections.delete(socket);
      if (this.#authenticatedSocket === socket) {
        this.#authenticatedSocket = undefined;
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
        if (isBinary) {
          throw new HandshakeFailure("HANDSHAKE_INVALID");
        }
        const response = this.#authenticate(rawDataBuffer(data), socket);
        authenticated = true;
        socket.send(JSON.stringify(response), (error) => {
          // Node's write callback can supply null at runtime even though ws types it as undefined.
          if (error !== undefined && error !== null) {
            socket.terminate();
          }
        });
      } catch (error) {
        closeWithCode(socket, error instanceof HandshakeFailure ? error.code : "HANDSHAKE_INVALID");
      }
    });
  }

  public close(): void {
    if (this.#closed) {
      return;
    }
    this.#closed = true;
    for (const socket of this.#connections) {
      socket.terminate();
    }
    this.#connections.clear();
    this.#authenticatedSocket = undefined;
    this.#agentRequests.close();
  }

  public get authenticated(): boolean {
    return this.#authenticatedSocket !== undefined;
  }

  #handleApplicationMessage(socket: WebSocket, data: RawData, isBinary: boolean): void {
    try {
      if (isBinary || this.#authenticatedSocket !== socket) {
        throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_INVALID");
      }
      const bytes = rawDataBuffer(data);
      if (bytes.length > APPLICATION_MAXIMUM_BYTES) {
        throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_INVALID");
      }
      let source: string;
      try {
        source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      } catch {
        throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_INVALID");
      }

      const message = this.#applicationProtocol.parse(source);
      if (message.type === "management.costs.request") {
        const snapshot = this.#usage.snapshot(this.#now().getTime());
        this.#sendApplicationResponse(socket, message.requestId, {
          type: "management.costs.response",
          payload: {
            currentDay: snapshot.currentDay,
            currentMonth: snapshot.currentMonth,
            budget: snapshot.budget,
          },
        });
        return;
      }
      if (message.type === "agent.cancel") {
        this.#agentRequests.cancel(message.cancellation.requestId, message.cancellation.playerUuid);
        return;
      }
      if (message.type === "tool.result") {
        const outcome = this.#agentRequests.acceptToolResult(message.requestId, message.result);
        if (outcome === "violation") {
          throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_INVALID");
        }
        return;
      }
      const requestId =
        message.type === "session.resume" ? message.resume.requestId : message.request.requestId;
      const respond = (terminal: Parameters<ApplicationEnvelopeProtocol["createResponse"]>[1]) => {
        this.#sendApplicationResponse(socket, requestId, terminal);
      };
      if (message.type === "session.resume") {
        this.#agentRequests.resume(message.resume, respond);
      } else {
        this.#agentRequests.submit(message.request, respond);
      }
    } catch (error) {
      closeWithCode(
        socket,
        error instanceof ApplicationProtocolFailure ? error.code : "APPLICATION_MESSAGE_INVALID",
      );
    }
  }

  #sendApplicationResponse(
    socket: WebSocket,
    requestId: string,
    terminal: Parameters<ApplicationEnvelopeProtocol["createResponse"]>[1],
  ): void {
    if (this.#authenticatedSocket !== socket || socket.readyState !== socket.OPEN) {
      return;
    }
    let response: Record<string, unknown>;
    try {
      response = this.#applicationProtocol.createResponse(requestId, terminal);
    } catch {
      socket.terminate();
      return;
    }
    try {
      socket.send(JSON.stringify(response), (error) => {
        if (error !== undefined && error !== null) {
          socket.terminate();
        }
      });
    } catch {
      socket.terminate();
    }
  }

  #authenticate(bytes: Buffer, socket: WebSocket): Record<string, unknown> {
    if (bytes.length > HANDSHAKE_MAXIMUM_BYTES) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    let source: string;
    try {
      source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    let document: unknown;
    try {
      document = parseStrictJson(source);
    } catch {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }
    if (!isRecord(document)) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    if (document["protocolVersion"] !== SUPPORTED_PROTOCOL_VERSION) {
      throw new HandshakeFailure("PROTOCOL_INCOMPATIBLE");
    }
    if (document["type"] !== "paper.hello") {
      throw new HandshakeFailure("UNSUPPORTED_MESSAGE_TYPE");
    }
    if (!this.#schemaRegistry.validate("envelope.schema.json", document).valid) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    const payloadValue = document["payload"];
    if (!isRecord(payloadValue)) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }
    const advertisedVersions = payloadValue["supportedProtocolVersions"];
    if (
      !Array.isArray(advertisedVersions) ||
      !advertisedVersions.includes(SUPPORTED_PROTOCOL_VERSION)
    ) {
      throw new HandshakeFailure("PROTOCOL_INCOMPATIBLE");
    }
    if (!this.#schemaRegistry.validate("handshake.schema.json", payloadValue).valid) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    const envelope = document as unknown as PaperHandshakeEnvelope;
    if (
      envelope.payload.component !== "paper" ||
      envelope.payload.selectedProtocolVersion !== null ||
      envelope.messageId !== envelope.requestId
    ) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }
    if (
      envelope.serverId !== this.#serverId ||
      envelope.payload.authentication.keyId !== this.#serverId
    ) {
      throw new HandshakeFailure("AUTHENTICATION_FAILED");
    }
    const nonce = decodeCanonicalBase64Url(envelope.nonce);
    const challenge = decodeCanonicalBase64Url(envelope.payload.authentication.challenge);
    if (
      nonce === undefined ||
      nonce.length < 16 ||
      challenge === undefined ||
      challenge.length < 16
    ) {
      throw new HandshakeFailure("HANDSHAKE_INVALID");
    }

    const now = this.#now();
    const timestamp = Date.parse(envelope.timestamp);
    if (
      !Number.isFinite(timestamp) ||
      Math.abs(now.getTime() - timestamp) > HANDSHAKE_CLOCK_SKEW_MILLISECONDS
    ) {
      throw new HandshakeFailure("HANDSHAKE_STALE");
    }
    if (
      !verifyHandshakeProof(
        this.#serverToken,
        proofFields(envelope),
        envelope.payload.authentication.proof,
      )
    ) {
      throw new HandshakeFailure("AUTHENTICATION_FAILED");
    }
    if (!this.#replayCache.accept(envelope.messageId, envelope.nonce, now.getTime())) {
      throw new HandshakeFailure("HANDSHAKE_REPLAYED");
    }
    if (this.#authenticatedSocket !== undefined) {
      throw new HandshakeFailure("PAPER_ALREADY_CONNECTED");
    }

    this.#authenticatedSocket = socket;
    const responseTimestamp = now.toISOString();
    const responseNonce = this.#randomBytes(16).toString("base64url");
    const responseFields: HandshakeProofFields = {
      serverId: this.#serverId,
      type: "runtime.hello",
      timestamp: responseTimestamp,
      nonce: responseNonce,
      component: "runtime",
      componentVersion: runtimeIdentity.version,
      challenge: envelope.payload.authentication.challenge,
    };

    return {
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId: this.#randomUuid(),
      requestId: envelope.requestId,
      serverId: this.#serverId,
      type: "runtime.hello",
      timestamp: responseTimestamp,
      nonce: responseNonce,
      payload: {
        component: "runtime",
        componentVersion: runtimeIdentity.version,
        supportedProtocolVersions: [SUPPORTED_PROTOCOL_VERSION],
        selectedProtocolVersion: SUPPORTED_PROTOCOL_VERSION,
        authentication: {
          scheme: "hmac-sha256",
          keyId: this.#serverId,
          challenge: envelope.payload.authentication.challenge,
          proof: createHandshakeProof(this.#serverToken, responseFields),
        },
      },
    };
  }
}

export async function registerPaperHandshakeRoute(
  app: FastifyInstance,
  options: PaperHandshakeServiceOptions,
): Promise<PaperHandshakeService> {
  const service = new PaperHandshakeService(options);
  await app.register(websocket, {
    options: {
      maxPayload: APPLICATION_MAXIMUM_BYTES,
      perMessageDeflate: false,
    },
  });
  app.get("/agent", { websocket: true }, (socket) => {
    service.accept(socket);
  });
  app.addHook("onClose", async () => {
    service.close();
  });
  return service;
}
