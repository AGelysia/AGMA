import { randomBytes, randomUUID } from "node:crypto";

import type { SchemaRegistry } from "../protocol/schema-registry.js";
import type {
  AgentRequestInput,
  AgentTerminalResponse,
} from "../requests/agent-request-service.js";
import type { ClientToolId, ToolCallPayload } from "../tools/tool-types.js";
import type { WebSearchContext } from "../evidence/search-provider.js";
import {
  CONNECTOR_KIND,
  CONNECTOR_PROTOCOL_VERSION,
  CONNECTOR_SCHEMA_VERSION,
  decodeConnectorBase64Url,
} from "./connector-handshake-authentication.js";
import type { HandshakeReplayCache } from "./replay-cache.js";
import { parseStrictJson } from "./strict-json.js";

export const CONNECTOR_APPLICATION_CLOCK_SKEW_MILLISECONDS = 30_000;
export const CONNECTOR_APPLICATION_MAXIMUM_BYTES = 64 * 1024;

export type ConnectorApplicationFailureCode =
  | "APPLICATION_MESSAGE_INVALID"
  | "APPLICATION_MESSAGE_REPLAYED"
  | "APPLICATION_MESSAGE_STALE"
  | "SCOPE_ID_MISMATCH"
  | "UNSUPPORTED_MESSAGE_TYPE";

export class ConnectorApplicationFailure extends Error {
  public readonly code: ConnectorApplicationFailureCode;

  public constructor(code: ConnectorApplicationFailureCode) {
    super(code);
    this.name = "ConnectorApplicationFailure";
    this.code = code;
  }
}

export type ConnectorApplicationMessage =
  | { readonly type: "client.request"; readonly request: AgentRequestInput }
  | {
      readonly type: "client.cancel";
      readonly messageId: string;
      readonly targetRequestId: string;
      readonly reason: "USER_REQUEST" | "CLIENT_SHUTDOWN" | "CONTEXT_CHANGED" | "TIMEOUT";
    }
  | { readonly type: "client.status.request"; readonly messageId: string }
  | {
      readonly type: "client.tool.result";
      readonly requestId: string;
      readonly toolCallId: string;
      readonly subjectId: string;
      readonly tool: ClientToolId;
      readonly sequence: number;
      readonly result: Readonly<Record<string, unknown>>;
    }
  | {
      readonly type: "client.tool.error";
      readonly requestId: string;
      readonly toolCallId: string;
      readonly subjectId: string;
      readonly tool: ClientToolId;
      readonly sequence: number;
      readonly status: "failed" | "rejected";
      readonly code: string;
      readonly message: string;
      readonly retryable: boolean;
    };

export type ConnectorToolCancellationReason =
  | "REQUEST_CANCELLED"
  | "MODEL_TIMEOUT"
  | "TOOL_TIMEOUT"
  | "RUNTIME_SHUTDOWN";

export interface ConnectorStatusPayload {
  readonly state: "READY" | "STOPPING";
  readonly activeRequests: number;
  readonly queuedRequests: number;
}

interface EnvelopeRecord extends Record<string, unknown> {
  readonly schemaVersion: string;
  readonly connectorKind: string;
  readonly protocolVersion: string;
  readonly messageId: string;
  readonly requestId: string | null;
  readonly scopeId: string;
  readonly type: string;
  readonly timestamp: string;
  readonly nonce: string;
  readonly payload: Record<string, unknown>;
}

export interface ConnectorApplicationProtocolOptions {
  readonly scopeId: string;
  readonly subjectId: string;
  readonly schemaRegistry: SchemaRegistry;
  readonly replayCache: HandshakeReplayCache;
  readonly now?: () => Date;
  readonly randomBytes?: (size: number) => Buffer;
  readonly randomUuid?: () => string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(): ConnectorApplicationFailure {
  return new ConnectorApplicationFailure("APPLICATION_MESSAGE_INVALID");
}

function encodedByteLength(envelope: Readonly<Record<string, unknown>>): number {
  return Buffer.byteLength(JSON.stringify(envelope), "utf8");
}

export class ConnectorApplicationProtocol {
  readonly #scopeId: string;
  readonly #subjectId: string;
  readonly #schemaRegistry: SchemaRegistry;
  readonly #replayCache: HandshakeReplayCache;
  readonly #now: () => Date;
  readonly #randomBytes: (size: number) => Buffer;
  readonly #randomUuid: () => string;

  public constructor(options: ConnectorApplicationProtocolOptions) {
    this.#scopeId = options.scopeId;
    this.#subjectId = options.subjectId;
    this.#schemaRegistry = options.schemaRegistry;
    this.#replayCache = options.replayCache;
    this.#now = options.now ?? (() => new Date());
    this.#randomBytes = options.randomBytes ?? randomBytes;
    this.#randomUuid = options.randomUuid ?? randomUUID;
  }

  public parse(source: string): ConnectorApplicationMessage {
    let document: unknown;
    try {
      document = parseStrictJson(source);
    } catch {
      throw invalid();
    }
    if (!isRecord(document) || !isRecord(document["payload"])) throw invalid();
    const envelope = document as EnvelopeRecord;
    if (
      envelope.schemaVersion !== CONNECTOR_SCHEMA_VERSION ||
      envelope.connectorKind !== CONNECTOR_KIND ||
      envelope.protocolVersion !== CONNECTOR_PROTOCOL_VERSION ||
      !this.#schemaRegistry.validate("connector-envelope.schema.json", envelope).valid
    ) {
      throw invalid();
    }
    if (
      envelope.type !== "client.request" &&
      envelope.type !== "client.cancel" &&
      envelope.type !== "client.status.request" &&
      envelope.type !== "client.tool.result" &&
      envelope.type !== "client.tool.error"
    ) {
      throw new ConnectorApplicationFailure("UNSUPPORTED_MESSAGE_TYPE");
    }
    if (envelope.scopeId !== this.#scopeId) {
      throw new ConnectorApplicationFailure("SCOPE_ID_MISMATCH");
    }
    const now = this.#now();
    const timestamp = Date.parse(envelope.timestamp);
    if (
      !Number.isFinite(timestamp) ||
      Math.abs(now.getTime() - timestamp) > CONNECTOR_APPLICATION_CLOCK_SKEW_MILLISECONDS
    ) {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_STALE");
    }
    if (decodeConnectorBase64Url(envelope.nonce, 16, 64) === undefined) throw invalid();

    const payloadSchema = {
      "client.request": "connector-request.schema.json",
      "client.cancel": "connector-cancel.schema.json",
      "client.status.request": "connector-status-request.schema.json",
      "client.tool.result": "connector-tool-result.schema.json",
      "client.tool.error": "connector-tool-error.schema.json",
    }[envelope.type];
    if (!this.#schemaRegistry.validate(payloadSchema, envelope.payload).valid) throw invalid();

    if (
      (envelope.type === "client.tool.result" || envelope.type === "client.tool.error") &&
      (envelope.requestId !== envelope.payload["requestId"] ||
        envelope.payload["subjectId"] !== this.#subjectId ||
        envelope.messageId === envelope.requestId ||
        envelope.messageId === envelope.payload["toolCallId"])
    ) {
      throw invalid();
    }

    let message: ConnectorApplicationMessage;
    if (envelope.type === "client.request") {
      message = {
        type: "client.request",
        request: {
          requestId: envelope.messageId,
          playerUuid: this.#subjectId,
          sessionId:
            envelope.payload["sessionId"] === null ? null : String(envelope.payload["sessionId"]),
          module: "general",
          message: String(envelope.payload["message"]),
          ...(envelope.payload["localContext"] === undefined
            ? {}
            : {
                localContext: envelope.payload["localContext"] as NonNullable<
                  AgentRequestInput["localContext"]
                >,
              }),
          webAuthorization:
            envelope.payload["webAuthorization"] === undefined
              ? "off"
              : (envelope.payload["webAuthorization"] as "off" | "once" | "persistent"),
          ...(envelope.payload["webContext"] === undefined
            ? {}
            : { webContext: envelope.payload["webContext"] as unknown as WebSearchContext }),
          ...(envelope.payload["inventoryAuthorization"] === undefined
            ? {}
            : {
                inventoryAuthorization: envelope.payload["inventoryAuthorization"] as {
                  readonly authorizationId: string;
                  readonly generationId: string;
                  readonly resourceIds: readonly string[];
                },
              }),
        },
      };
    } else if (envelope.type === "client.cancel") {
      message = {
        type: "client.cancel",
        messageId: envelope.messageId,
        targetRequestId: String(envelope.payload["targetRequestId"]),
        reason: envelope.payload["reason"] as Extract<
          ConnectorApplicationMessage,
          { readonly type: "client.cancel" }
        >["reason"],
      };
    } else if (envelope.type === "client.status.request") {
      message = { type: "client.status.request", messageId: envelope.messageId };
    } else if (envelope.type === "client.tool.result") {
      message = {
        type: "client.tool.result",
        requestId: String(envelope.payload["requestId"]),
        toolCallId: String(envelope.payload["toolCallId"]),
        subjectId: String(envelope.payload["subjectId"]),
        tool: envelope.payload["tool"] as ClientToolId,
        sequence: Number(envelope.payload["sequence"]),
        result: envelope.payload["result"] as Readonly<Record<string, unknown>>,
      };
    } else {
      message = {
        type: "client.tool.error",
        requestId: String(envelope.payload["requestId"]),
        toolCallId: String(envelope.payload["toolCallId"]),
        subjectId: String(envelope.payload["subjectId"]),
        tool: envelope.payload["tool"] as ClientToolId,
        sequence: Number(envelope.payload["sequence"]),
        status: envelope.payload["status"] as "failed" | "rejected",
        code: String(envelope.payload["code"]),
        message: String(envelope.payload["message"]),
        retryable: Boolean(envelope.payload["retryable"]),
      };
    }

    if (!this.#replayCache.accept(envelope.messageId, envelope.nonce, now.getTime())) {
      throw new ConnectorApplicationFailure("APPLICATION_MESSAGE_REPLAYED");
    }
    return message;
  }

  public createTerminalResponse(
    requestId: string,
    response: AgentTerminalResponse,
  ): Record<string, unknown> {
    if (response.type === "agent.complete") {
      if (
        !Number.isSafeInteger(response.payload.costMicroUsd) ||
        Number(response.payload.costMicroUsd) < 0 ||
        (response.payload.costKind !== "reported" &&
          response.payload.costKind !== "estimated" &&
          response.payload.costKind !== "mixed")
      ) {
        throw new Error("Client completion is missing its bounded provider cost summary.");
      }
      return this.#createResponse(requestId, "client.complete", "connector-complete.schema.json", {
        sessionId: response.payload.sessionId,
        text: response.payload.fallbackText,
        costMicroUsd: response.payload.costMicroUsd,
        costKind: response.payload.costKind,
        sources: response.payload.sources ?? [],
      });
    }
    return this.#createResponse(requestId, "client.error", "connector-error.schema.json", {
      code: response.payload.code,
      message: response.payload.fallbackText,
      retryable: response.payload.retryable,
    });
  }

  public createStatusResponse(
    requestId: string,
    payload: ConnectorStatusPayload,
  ): Record<string, unknown> {
    return this.#createResponse(requestId, "client.status", "connector-status.schema.json", {
      ...payload,
    });
  }

  public createToolCall(requestId: string, payload: ToolCallPayload): Record<string, unknown> {
    if (payload.playerUuid !== this.#subjectId) {
      throw new Error("Runtime attempted to send a Tool for another connector subject.");
    }
    return this.#createResponse(requestId, "client.tool.call", "connector-tool-call.schema.json", {
      requestId,
      toolCallId: payload.toolCallId,
      subjectId: this.#subjectId,
      tool: payload.tool,
      sequence: payload.sequence,
      arguments: payload.arguments,
    });
  }

  public createToolCancellation(
    requestId: string,
    payload: ToolCallPayload,
    reason: ConnectorToolCancellationReason,
  ): Record<string, unknown> {
    if (payload.playerUuid !== this.#subjectId) {
      throw new Error("Runtime attempted to cancel a Tool for another connector subject.");
    }
    return this.#createResponse(
      requestId,
      "client.tool.cancel",
      "connector-tool-cancel.schema.json",
      {
        requestId,
        toolCallId: payload.toolCallId,
        subjectId: this.#subjectId,
        tool: payload.tool,
        sequence: payload.sequence,
        reason,
      },
    );
  }

  #createResponse(
    requestId: string,
    type:
      | "client.complete"
      | "client.error"
      | "client.status"
      | "client.tool.call"
      | "client.tool.cancel",
    payloadSchema: string,
    payload: Readonly<Record<string, unknown>>,
  ): Record<string, unknown> {
    if (!this.#schemaRegistry.validate(payloadSchema, payload).valid) {
      throw new Error(`Runtime generated an invalid ${type} payload.`);
    }
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const messageId = this.#randomUuid();
      if (messageId === requestId) continue;
      const now = this.#now();
      const nonce = this.#randomBytes(16).toString("base64url");
      const envelope: Record<string, unknown> = {
        schemaVersion: CONNECTOR_SCHEMA_VERSION,
        connectorKind: CONNECTOR_KIND,
        protocolVersion: CONNECTOR_PROTOCOL_VERSION,
        messageId,
        requestId,
        scopeId: this.#scopeId,
        type,
        timestamp: now.toISOString(),
        nonce,
        payload,
      };
      if (
        !this.#schemaRegistry.validate("connector-envelope.schema.json", envelope).valid ||
        encodedByteLength(envelope) > CONNECTOR_APPLICATION_MAXIMUM_BYTES
      ) {
        throw new Error(`Runtime generated an invalid ${type} envelope.`);
      }
      if (this.#replayCache.accept(messageId, nonce, now.getTime())) return envelope;
    }
    throw new Error("Unable to allocate a unique connector response identity.");
  }
}
