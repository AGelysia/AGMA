import type { RuntimeLogger } from "../observability/runtime-logger.js";
import {
  ConversationOwnershipError,
  type ConversationMessage,
  type ConversationRepository,
} from "../storage/conversation-repository.js";
import type { ToolCallPayload, ToolResultPayload } from "../tools/tool-types.js";
import type { ProviderUsageRecordResult, UsageAccounting } from "../usage/usage-accounting.js";
import type {
  AgentRequestInput,
  AgentRuntimeResponse,
  AgentTerminalResponse,
} from "./agent-request-service.js";
import {
  RequestAdmissionController,
  type RequestAdmissionLimits,
  type RequestAdmissionRejection,
} from "./request-admission.js";

/**
 * Shared bound for every provider generation request. Both audiences must agree
 * on this limit so a provider can never reason about an unbounded response.
 */
export const MAXIMUM_MODEL_OUTPUT_TOKENS = 1024;

const SHUTDOWN_PROVIDER_GRACE_MILLISECONDS = 1_000;

export type UsageFailureCode =
  | "USAGE_ACCOUNTING_FAILED"
  | "USAGE_ADMISSION_FAILED"
  | "USAGE_CLOSE_FAILED";

export interface PendingLifecycleToolCall<Descriptor> {
  readonly descriptor: Descriptor;
  readonly payload: ToolCallPayload;
  readonly resolve: (result: ToolResultPayload) => void;
  readonly reject: (reason: unknown) => void;
  readonly removeAbortListener: () => void;
}

export interface ProviderCostLedger {
  providerCostMicroUsd: number;
  readonly providerUsageKinds: Set<ProviderUsageRecordResult["usageKind"]>;
}

export interface RequestLifecycleRecord<Descriptor> extends ProviderCostLedger {
  readonly input: AgentRequestInput;
  readonly respond: (response: AgentRuntimeResponse) => void;
  readonly controller: AbortController;
  phase: "QUEUED" | "ACTIVE" | "WAITING_TOOL";
  timeout: NodeJS.Timeout | undefined;
  terminalSent: boolean;
  suppressResponse: boolean;
  detached: boolean;
  usageAdmitted: boolean;
  preparedSessionId: string | null;
  executionSessionId: string | null;
  createsSession: boolean;
  pendingTool: PendingLifecycleToolCall<Descriptor> | undefined;
  readonly issuedToolCallIds: Set<string>;
  inventoryAuthorizationUsed: boolean;
}

export interface RequestLifecycleOptions<
  Descriptor,
  Record extends RequestLifecycleRecord<Descriptor>,
> {
  readonly logger: RuntimeLogger;
  readonly conversations: ConversationRepository;
  readonly scopeId: string;
  readonly timeoutMilliseconds: number;
  readonly maximumContextMessages: number;
  readonly admissionLimits: RequestAdmissionLimits;
  readonly now: () => number;
  readonly randomUuid: () => string;
  readonly usage?: UsageAccounting;
  readonly cancelQueuedBeforeActive: boolean;
  readonly usageAdmissionFailureCode: UsageFailureCode;
  readonly usageCloseFailureCode: UsageFailureCode;
  readonly run: (record: Record) => Promise<void>;
  readonly mapRunError: (error: unknown, playerUuid: string) => AgentTerminalResponse;
  readonly validateToolResult: (
    descriptor: Descriptor,
    payload: ToolResultPayload,
    expected: ToolCallPayload,
  ) => boolean;
  readonly createRecord: (base: RequestLifecycleRecord<Descriptor>) => Record;
  readonly usageAdmissionFailedResponse: (playerUuid: string) => AgentTerminalResponse;
  readonly usageBudgetExceededResponse: (playerUuid: string) => AgentTerminalResponse;
  readonly isToolCallIdReserved?: (record: Record, candidate: string) => boolean;
  readonly toolCallIdExhaustedError?: () => Error;
}

export type AgentErrorTerminalResponse = Extract<
  AgentTerminalResponse,
  { readonly type: "agent.error" }
>;

export function requestLimitedResponse(
  playerUuid: string,
  reason: RequestAdmissionRejection,
): AgentErrorTerminalResponse {
  const fallbackText =
    reason === "PLAYER_BUSY"
      ? "You already have an AI request in progress."
      : "Too many AI requests. Try again shortly.";
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "REQUEST_LIMITED",
      fallbackText,
      retryable: true,
    },
  };
}

export function runtimeStoppingResponse(playerUuid: string): AgentErrorTerminalResponse {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "RUNTIME_INTERNAL_ERROR",
      fallbackText: "The AI Runtime is stopping.",
      retryable: true,
    },
  };
}

export function runtimeInternalErrorResponse(playerUuid: string): AgentErrorTerminalResponse {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "RUNTIME_INTERNAL_ERROR",
      fallbackText: "The AI request failed. Try again later.",
      retryable: true,
    },
  };
}

export function modelTimeoutResponse(playerUuid: string): AgentErrorTerminalResponse {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "MODEL_TIMEOUT",
      fallbackText: "The AI request timed out. Try again.",
      retryable: true,
    },
  };
}

export function recordProviderCost(
  record: ProviderCostLedger,
  usage: ProviderUsageRecordResult,
): void {
  const total = record.providerCostMicroUsd + usage.costMicroUsd;
  if (!Number.isSafeInteger(total) || total < 0) {
    throw new Error("Provider request cost exceeds its bounded total.");
  }
  record.providerCostMicroUsd = total;
  record.providerUsageKinds.add(usage.usageKind);
}

export function providerCostKind(
  usageKinds: ReadonlySet<ProviderUsageRecordResult["usageKind"]>,
): "reported" | "estimated" | "mixed" {
  if (usageKinds.has("REPORTED") && usageKinds.has("ESTIMATED")) return "mixed";
  return usageKinds.has("REPORTED") ? "reported" : "estimated";
}

/**
 * Audience-independent request lifecycle: admission, timeouts, cancellation,
 * Tool-call arbitration, usage admission, and terminal response delivery. The
 * audience service supplies only its run strategy and response vocabulary.
 */
export class RequestLifecycle<Descriptor, Record extends RequestLifecycleRecord<Descriptor>> {
  readonly #options: RequestLifecycleOptions<Descriptor, Record>;
  readonly #admission: RequestAdmissionController;
  readonly #conversations: ConversationRepository;
  readonly #logger: RuntimeLogger;
  readonly #usage: UsageAccounting | undefined;
  readonly #now: () => number;
  readonly #randomUuid: () => string;
  readonly #timeoutMilliseconds: number;
  readonly #scopeId: string;
  readonly #maximumContextMessages: number;
  readonly #toolCallIdReserved: (record: Record, candidate: string) => boolean;
  readonly #requests = new Map<string, Record>();
  readonly #runs = new Set<Promise<void>>();
  #closed = false;
  #usageHealthy = true;

  public constructor(options: RequestLifecycleOptions<Descriptor, Record>) {
    if (!Number.isSafeInteger(options.timeoutMilliseconds) || options.timeoutMilliseconds < 1) {
      throw new TypeError("The Agent request timeout must be a positive bounded integer.");
    }
    this.#options = options;
    this.#conversations = options.conversations;
    this.#logger = options.logger;
    this.#usage = options.usage;
    this.#now = options.now;
    this.#randomUuid = options.randomUuid;
    this.#timeoutMilliseconds = options.timeoutMilliseconds;
    this.#scopeId = options.scopeId;
    this.#maximumContextMessages = options.maximumContextMessages;
    this.#toolCallIdReserved =
      options.isToolCallIdReserved ??
      ((record: Record, candidate: string) =>
        candidate === record.input.requestId || candidate === record.executionSessionId);
    this.#admission = new RequestAdmissionController(options.admissionLimits, options.now);
  }

  public get activeCount(): number {
    return this.#admission.activeCount;
  }

  public get queuedCount(): number {
    return this.#admission.queuedCount;
  }

  public get usageConfigured(): boolean {
    return this.#usage !== undefined;
  }

  public get closed(): boolean {
    return this.#closed;
  }

  public get usageHealthy(): boolean {
    return this.#usageHealthy;
  }

  public submit(input: AgentRequestInput, respond: (response: AgentRuntimeResponse) => void): void {
    if (this.#closed) {
      this.safeRespond(respond, runtimeStoppingResponse(input.playerUuid), input.requestId);
      return;
    }
    if (this.#requests.has(input.requestId)) {
      this.safeRespond(
        respond,
        requestLimitedResponse(input.playerUuid, "PLAYER_BUSY"),
        input.requestId,
      );
      return;
    }
    if (this.#usage !== undefined && !this.#usageHealthy) {
      this.safeRespond(
        respond,
        this.#options.usageAdmissionFailedResponse(input.playerUuid),
        input.requestId,
      );
      return;
    }

    let usageAdmitted = false;
    if (this.#usage !== undefined) {
      try {
        const usageDecision = this.usageOperation(
          input.requestId,
          this.#options.usageAdmissionFailureCode,
          () =>
            this.#usage?.admitRequest({
              requestId: input.requestId,
              playerUuid: input.playerUuid,
              timestamp: this.#now(),
            }),
        );
        if (usageDecision === undefined) {
          throw new Error("Usage accounting disappeared during admission.");
        }
        if (!usageDecision.accepted) {
          this.safeRespond(
            respond,
            usageDecision.reason === "MONTHLY_BUDGET_EXCEEDED"
              ? this.#options.usageBudgetExceededResponse(input.playerUuid)
              : requestLimitedResponse(input.playerUuid, "PLAYER_DAILY_LIMIT"),
            input.requestId,
          );
          return;
        }
        usageAdmitted = true;
      } catch {
        this.safeRespond(
          respond,
          this.#options.usageAdmissionFailedResponse(input.playerUuid),
          input.requestId,
        );
        return;
      }
    }

    const record = this.#options.createRecord({
      input,
      respond,
      controller: new AbortController(),
      phase: "QUEUED",
      timeout: undefined,
      terminalSent: false,
      suppressResponse: false,
      detached: false,
      usageAdmitted,
      preparedSessionId: null,
      executionSessionId: null,
      createsSession: false,
      pendingTool: undefined,
      issuedToolCallIds: new Set(),
      inventoryAuthorizationUsed: false,
      providerCostMicroUsd: 0,
      providerUsageKinds: new Set(),
    });
    this.#requests.set(input.requestId, record);
    const decision = this.#admission.admit({
      requestId: input.requestId,
      playerUuid: input.playerUuid,
      start: () => this.#start(record),
    });
    if (!decision.accepted) {
      this.#requests.delete(input.requestId);
      try {
        if (record.usageAdmitted) {
          this.usageOperation(input.requestId, this.#options.usageAdmissionFailureCode, () => {
            if (this.#usage?.rollbackAdmission(input.requestId) !== true) {
              throw new Error("Durable usage admission could not be rolled back.");
            }
          });
          record.usageAdmitted = false;
        }
        this.safeRespond(
          respond,
          requestLimitedResponse(input.playerUuid, decision.reason),
          input.requestId,
        );
      } catch {
        this.safeRespond(
          respond,
          this.#options.usageAdmissionFailedResponse(input.playerUuid),
          input.requestId,
        );
      }
      return;
    }

    record.phase = decision.queued ? "QUEUED" : "ACTIVE";
    record.timeout = setTimeout(() => this.#timeout(record), this.#timeoutMilliseconds);
    record.timeout.unref();
  }

  public cancel(requestId: string, playerUuid: string): boolean {
    const record = this.#requests.get(requestId);
    if (record === undefined || record.input.playerUuid !== playerUuid) {
      return false;
    }
    record.suppressResponse = true;
    this.#detach(record);
    record.controller.abort(new Error("REQUEST_CANCELLED"));
    return true;
  }

  public acceptToolResult(
    requestId: string,
    payload: ToolResultPayload,
  ): "accepted" | "ignored" | "violation" {
    const record = this.#requests.get(requestId);
    if (record === undefined || record.terminalSent || record.suppressResponse) {
      return "ignored";
    }
    const pending = record.pendingTool;
    if (pending === undefined) {
      return "violation";
    }
    const expected = pending.payload;
    if (
      payload.toolCallId !== expected.toolCallId ||
      payload.sessionId !== expected.sessionId ||
      payload.playerUuid !== expected.playerUuid ||
      payload.tool !== expected.tool ||
      payload.sequence !== expected.sequence ||
      !this.#options.validateToolResult(pending.descriptor, payload, expected)
    ) {
      return "violation";
    }
    record.pendingTool = undefined;
    pending.removeAbortListener();
    pending.resolve(payload);
    return "accepted";
  }

  public cancelAll(): void {
    const records = [...this.#requests.values()];
    if (!this.#options.cancelQueuedBeforeActive) {
      for (const record of records) {
        this.cancel(record.input.requestId, record.input.playerUuid);
      }
      return;
    }
    for (const record of records.filter((candidate) => candidate.phase === "QUEUED")) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
    for (const record of records.filter((candidate) => candidate.phase !== "QUEUED")) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
  }

  public async close(): Promise<void> {
    this.#closed = true;
    this.cancelAll();
    if (this.#runs.size === 0) {
      return;
    }
    let graceTimer: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        Promise.allSettled([...this.#runs]),
        new Promise<void>((resolve) => {
          graceTimer = setTimeout(resolve, SHUTDOWN_PROVIDER_GRACE_MILLISECONDS);
        }),
      ]);
    } finally {
      if (graceTimer !== undefined) {
        clearTimeout(graceTimer);
      }
    }
  }

  public prepareConversation(record: Record): readonly ConversationMessage[] {
    if (!this.#conversations.enabled) {
      if (record.input.sessionId !== null) {
        throw new ConversationOwnershipError();
      }
      record.executionSessionId = this.#randomUuid();
      return [];
    }

    const owner = { serverId: this.#scopeId, playerUuid: record.input.playerUuid };
    if (record.input.sessionId === null) {
      record.preparedSessionId = this.#randomUuid();
      record.executionSessionId = record.preparedSessionId;
      record.createsSession = true;
      return [];
    }
    const session = this.#conversations.findOwned(record.input.sessionId, owner);
    if (session === undefined) {
      throw new ConversationOwnershipError();
    }
    record.preparedSessionId = session.id;
    record.executionSessionId = session.id;
    record.createsSession = false;
    const maximumHistory = Math.max(0, Math.floor((this.#maximumContextMessages - 1) / 2) * 2);
    return this.#conversations.loadRecentOwned(session.id, owner, maximumHistory);
  }

  public awaitToolResult(
    record: Record,
    descriptor: Descriptor,
    payload: ToolCallPayload,
  ): Promise<ToolResultPayload> {
    return new Promise<ToolResultPayload>((resolve, reject) => {
      const onAbort = (): void => reject(record.controller.signal.reason);
      const pending: PendingLifecycleToolCall<Descriptor> = {
        descriptor,
        payload,
        resolve,
        reject,
        removeAbortListener: () => record.controller.signal.removeEventListener("abort", onAbort),
      };
      record.pendingTool = pending;
      record.phase = "WAITING_TOOL";
      record.controller.signal.addEventListener("abort", onAbort, { once: true });
      if (record.controller.signal.aborted) {
        onAbort();
        return;
      }
      this.safeRespond(record.respond, { type: "tool.call", payload }, record.input.requestId);
    }).finally(() => {
      record.phase = "ACTIVE";
    });
  }

  public allocateToolCallId(record: Record): string {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = this.#randomUuid();
      if (
        !this.#toolCallIdReserved(record, candidate) &&
        !record.issuedToolCallIds.has(candidate)
      ) {
        record.issuedToolCallIds.add(candidate);
        return candidate;
      }
    }
    throw (
      this.#options.toolCallIdExhaustedError?.() ??
      new Error("Unable to allocate a unique Tool call ID.")
    );
  }

  public usageOperation<Result>(
    requestId: string,
    code: UsageFailureCode,
    operation: () => Result,
  ): Result {
    if (!this.#usageHealthy) {
      throw new Error("Usage accounting is unavailable.");
    }
    try {
      return operation();
    } catch (error) {
      this.#usageHealthy = false;
      this.#logger.runtimeError(code, requestId, error);
      throw error;
    }
  }

  public safeRespond<Response>(
    responder: (response: Response) => void,
    response: Response,
    requestId: string,
  ): void {
    try {
      responder(response);
    } catch (error) {
      // A failed transport must not leak a rejection or retain admission state.
      this.#logger.runtimeError("TRANSPORT_RESPONSE_FAILED", requestId, error);
    }
  }

  #start(record: Record): void {
    record.phase = "ACTIVE";
    const run = this.#options
      .run(record)
      .catch((error: unknown) => {
        if (record.terminalSent || record.suppressResponse) {
          return;
        }
        record.terminalSent = true;
        const response = this.#options.mapRunError(error, record.input.playerUuid);
        if (response.type === "agent.error" && response.payload.code === "RUNTIME_INTERNAL_ERROR") {
          this.#logger.runtimeError("RUNTIME_INTERNAL_ERROR", record.input.requestId, error);
        }
        this.safeRespond(record.respond, response, record.input.requestId);
      })
      .finally(() => {
        this.#detach(record);
      });
    this.#runs.add(run);
    void run
      .finally(() => {
        this.#runs.delete(run);
      })
      .catch(() => undefined);
  }

  #timeout(record: Record): void {
    if (record.terminalSent || record.suppressResponse) {
      return;
    }
    record.terminalSent = true;
    this.#detach(record);
    record.controller.abort(new Error("MODEL_TIMEOUT"));
    this.safeRespond(
      record.respond,
      modelTimeoutResponse(record.input.playerUuid),
      record.input.requestId,
    );
  }

  #clearTimeout(record: Record): void {
    if (record.timeout !== undefined) {
      clearTimeout(record.timeout);
      record.timeout = undefined;
    }
  }

  #detach(record: Record): void {
    if (record.detached) {
      return;
    }
    record.detached = true;
    if (record.usageAdmitted) {
      record.usageAdmitted = false;
      try {
        this.usageOperation(record.input.requestId, this.#options.usageCloseFailureCode, () => {
          if (this.#usage?.closeRequest(record.input.requestId, this.#now()) !== true) {
            throw new Error("Durable usage admission could not be closed.");
          }
        });
      } catch {
        // A terminal request must still release in-memory admission after a storage failure.
      }
    }
    const pending = record.pendingTool;
    if (pending !== undefined) {
      record.pendingTool = undefined;
      pending.removeAbortListener();
      pending.reject(new Error("REQUEST_DETACHED"));
    }
    this.#clearTimeout(record);
    if (this.#requests.get(record.input.requestId) === record) {
      this.#requests.delete(record.input.requestId);
    }
    if (record.phase === "QUEUED") {
      this.#admission.cancelQueued(record.input.requestId, record.input.playerUuid);
    } else {
      this.#admission.releaseActive(record.input.requestId);
    }
  }
}
