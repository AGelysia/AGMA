import { randomBytes, randomUUID } from "node:crypto";

import type { SchemaRegistry } from "../protocol/schema-registry.js";
import type {
  AgentRequestInput,
  AgentRuntimeResponse,
  SessionResumeInput,
  SessionResumeResponse,
} from "../requests/agent-request-service.js";
import type { ToolResultPayload } from "../tools/tool-types.js";
import { SUPPORTED_PROTOCOL_VERSION } from "../version.js";
import { decodeCanonicalBase64Url } from "./handshake-authentication.js";
import type { HandshakeReplayCache } from "./replay-cache.js";
import { parseStrictJson } from "./strict-json.js";

export const APPLICATION_CLOCK_SKEW_MILLISECONDS = 30_000;
export const APPLICATION_MAXIMUM_BYTES = 64 * 1024;

export type ApplicationFailureCode =
  | "APPLICATION_MESSAGE_INVALID"
  | "APPLICATION_MESSAGE_REPLAYED"
  | "APPLICATION_MESSAGE_STALE"
  | "SERVER_ID_MISMATCH"
  | "UNSUPPORTED_MESSAGE_TYPE";

export class ApplicationProtocolFailure extends Error {
  public readonly code: ApplicationFailureCode;

  public constructor(code: ApplicationFailureCode) {
    super(code);
    this.name = "ApplicationProtocolFailure";
    this.code = code;
  }
}

export interface AgentCancelInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly reason:
    | "PLAYER_DISCONNECTED"
    | "PAPER_TIMEOUT"
    | "AGENT_OFFLINE"
    | "RUNTIME_DISCONNECTED";
}

export type PaperApplicationMessage =
  | { readonly type: "agent.request"; readonly request: AgentRequestInput }
  | { readonly type: "agent.cancel"; readonly cancellation: AgentCancelInput }
  | { readonly type: "session.resume"; readonly resume: SessionResumeInput }
  | {
      readonly type: "tool.result";
      readonly requestId: string;
      readonly result: ToolResultPayload;
    }
  | { readonly type: "management.costs.request"; readonly requestId: string };

export interface ManagementUsageWindow {
  readonly period: string;
  readonly admittedRequests: number;
  readonly providerCalls: number;
  readonly reportedProviderCalls: number;
  readonly estimatedProviderCalls: number;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly costMicroUsd: number;
}

export interface ManagementCostsPayload {
  readonly currentDay: ManagementUsageWindow;
  readonly currentMonth: ManagementUsageWindow;
  readonly budget: {
    readonly month: string;
    readonly limitMicroUsd: number;
    readonly settledMicroUsd: number;
    readonly activeReservationsMicroUsd: number;
    readonly remainingMicroUsd: number;
    readonly exhausted: boolean;
  };
}

export type RuntimeApplicationResponse =
  | AgentRuntimeResponse
  | SessionResumeResponse
  | { readonly type: "management.costs.response"; readonly payload: ManagementCostsPayload };

export interface ApplicationEnvelopeProtocolOptions {
  readonly serverId: string;
  readonly schemaRegistry: SchemaRegistry;
  readonly replayCache: HandshakeReplayCache;
  readonly now?: () => Date;
  readonly randomBytes?: (size: number) => Buffer;
  readonly randomUuid?: () => string;
}

interface EnvelopeRecord extends Record<string, unknown> {
  readonly protocolVersion: string;
  readonly messageId: string;
  readonly requestId: string;
  readonly serverId: string;
  readonly type: string;
  readonly timestamp: string;
  readonly nonce: string;
  readonly payload: Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(): ApplicationProtocolFailure {
  return new ApplicationProtocolFailure("APPLICATION_MESSAGE_INVALID");
}

function encodedByteLength(envelope: Record<string, unknown>): number {
  return Buffer.byteLength(JSON.stringify(envelope), "utf8");
}

function envelopeRecord(value: unknown): EnvelopeRecord {
  if (!isRecord(value) || !isRecord(value["payload"])) {
    throw invalid();
  }
  return value as EnvelopeRecord;
}

export class ApplicationEnvelopeProtocol {
  readonly #serverId: string;
  readonly #schemaRegistry: SchemaRegistry;
  readonly #replayCache: HandshakeReplayCache;
  readonly #now: () => Date;
  readonly #randomBytes: (size: number) => Buffer;
  readonly #randomUuid: () => string;

  public constructor(options: ApplicationEnvelopeProtocolOptions) {
    this.#serverId = options.serverId;
    this.#schemaRegistry = options.schemaRegistry;
    this.#replayCache = options.replayCache;
    this.#now = options.now ?? (() => new Date());
    this.#randomBytes = options.randomBytes ?? randomBytes;
    this.#randomUuid = options.randomUuid ?? randomUUID;
  }

  public parse(source: string): PaperApplicationMessage {
    let document: unknown;
    try {
      document = parseStrictJson(source);
    } catch {
      throw invalid();
    }
    const envelope = envelopeRecord(document);
    if (envelope.protocolVersion !== SUPPORTED_PROTOCOL_VERSION) {
      throw invalid();
    }
    if (
      envelope.type !== "agent.request" &&
      envelope.type !== "agent.cancel" &&
      envelope.type !== "session.resume" &&
      envelope.type !== "tool.result" &&
      envelope.type !== "management.costs.request"
    ) {
      throw new ApplicationProtocolFailure("UNSUPPORTED_MESSAGE_TYPE");
    }
    if (!this.#schemaRegistry.validate("envelope.schema.json", envelope).valid) {
      throw invalid();
    }
    if (envelope.serverId !== this.#serverId) {
      throw new ApplicationProtocolFailure("SERVER_ID_MISMATCH");
    }

    const now = this.#now();
    const timestamp = Date.parse(envelope.timestamp);
    if (
      !Number.isFinite(timestamp) ||
      Math.abs(now.getTime() - timestamp) > APPLICATION_CLOCK_SKEW_MILLISECONDS
    ) {
      throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_STALE");
    }
    const nonce = decodeCanonicalBase64Url(envelope.nonce);
    if (nonce === undefined || nonce.length < 16) {
      throw invalid();
    }

    const payloadSchema =
      envelope.type === "agent.request"
        ? "agent-request.schema.json"
        : envelope.type === "agent.cancel"
          ? "agent-cancel.schema.json"
          : envelope.type === "session.resume"
            ? "session-resume.schema.json"
            : envelope.type === "tool.result"
              ? "tool-result.schema.json"
              : "management-costs-request.schema.json";
    if (!this.#schemaRegistry.validate(payloadSchema, envelope.payload).valid) {
      throw invalid();
    }

    let message: PaperApplicationMessage;
    if (envelope.type === "agent.request") {
      if (envelope.messageId !== envelope.requestId) {
        throw invalid();
      }
      message = {
        type: "agent.request",
        request: {
          requestId: envelope.requestId,
          playerUuid: String(envelope.payload["playerUuid"]),
          sessionId:
            envelope.payload["sessionId"] === null ? null : String(envelope.payload["sessionId"]),
          module: envelope.payload["module"] as AgentRequestInput["module"],
          message: String(envelope.payload["message"]),
        },
      };
    } else if (envelope.type === "agent.cancel") {
      message = {
        type: "agent.cancel",
        cancellation: {
          requestId: envelope.requestId,
          playerUuid: String(envelope.payload["playerUuid"]),
          reason: envelope.payload["reason"] as AgentCancelInput["reason"],
        },
      };
    } else if (envelope.type === "session.resume") {
      if (envelope.messageId !== envelope.requestId) {
        throw invalid();
      }
      message = {
        type: "session.resume",
        resume: {
          requestId: envelope.requestId,
          playerUuid: String(envelope.payload["playerUuid"]),
          sessionId:
            envelope.payload["sessionId"] === null ? null : String(envelope.payload["sessionId"]),
        },
      };
    } else if (envelope.type === "tool.result") {
      if (envelope.messageId === envelope.requestId) {
        throw invalid();
      }
      message = {
        type: "tool.result",
        requestId: envelope.requestId,
        result: {
          toolCallId: String(envelope.payload["toolCallId"]),
          sessionId: String(envelope.payload["sessionId"]),
          playerUuid: String(envelope.payload["playerUuid"]),
          tool: String(envelope.payload["tool"]),
          sequence: Number(envelope.payload["sequence"]),
          status: envelope.payload["status"] as ToolResultPayload["status"],
          source: envelope.payload["source"] as ToolResultPayload["source"],
          trust: envelope.payload["trust"] as ToolResultPayload["trust"],
          result:
            envelope.payload["result"] === null
              ? null
              : (envelope.payload["result"] as Readonly<Record<string, unknown>>),
          error:
            envelope.payload["error"] === null
              ? null
              : (envelope.payload["error"] as ToolResultPayload["error"]),
        },
      };
    } else {
      if (envelope.messageId !== envelope.requestId) {
        throw invalid();
      }
      message = { type: "management.costs.request", requestId: envelope.requestId };
    }

    if (!this.#replayCache.accept(envelope.messageId, envelope.nonce, now.getTime())) {
      throw new ApplicationProtocolFailure("APPLICATION_MESSAGE_REPLAYED");
    }
    return message;
  }

  public createResponse(
    requestId: string,
    response: RuntimeApplicationResponse,
  ): Record<string, unknown> {
    const payloadSchema =
      response.type === "agent.complete"
        ? "agent-complete.schema.json"
        : response.type === "agent.error"
          ? "agent-error.schema.json"
          : response.type === "session.resumed"
            ? "session-resumed.schema.json"
            : response.type === "tool.call"
              ? "tool-call.schema.json"
              : "management-costs-response.schema.json";
    if (!this.#schemaRegistry.validate(payloadSchema, response.payload).valid) {
      throw new Error(`Runtime generated an invalid ${response.type} payload.`);
    }

    let messageId: string | undefined;
    for (let attempt = 0; attempt < 8 && messageId === undefined; attempt += 1) {
      const candidate = this.#randomUuid();
      if (candidate !== requestId) {
        messageId = candidate;
      }
    }
    if (messageId === undefined) {
      throw new Error("Unable to allocate a distinct response message ID.");
    }

    const responseTimestamp = this.#now().toISOString();
    const responseFields = {
      protocolVersion: SUPPORTED_PROTOCOL_VERSION,
      messageId,
      requestId,
      serverId: this.#serverId,
      type: response.type,
      timestamp: responseTimestamp,
      nonce: this.#randomBytes(16).toString("base64url"),
    };
    let envelope: Record<string, unknown> = {
      ...responseFields,
      payload: response.payload,
    };
    if (!this.#schemaRegistry.validate("envelope.schema.json", envelope).valid) {
      throw new Error(`Runtime generated an invalid ${response.type} envelope.`);
    }

    if (
      encodedByteLength(envelope) > APPLICATION_MAXIMUM_BYTES &&
      response.type === "agent.complete" &&
      response.payload.structuredViews.length > 0
    ) {
      const fallbackPayload = { ...response.payload, structuredViews: [] };
      if (!this.#schemaRegistry.validate(payloadSchema, fallbackPayload).valid) {
        throw new Error(`Runtime generated an invalid ${response.type} fallback payload.`);
      }
      envelope = { ...responseFields, payload: fallbackPayload };
      if (!this.#schemaRegistry.validate("envelope.schema.json", envelope).valid) {
        throw new Error(`Runtime generated an invalid ${response.type} fallback envelope.`);
      }
    }
    if (encodedByteLength(envelope) > APPLICATION_MAXIMUM_BYTES) {
      throw new Error(`Runtime generated an oversized ${response.type} envelope.`);
    }
    return envelope;
  }
}
