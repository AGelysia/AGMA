import { randomUUID } from "node:crypto";

import type { RuntimeConfig } from "../config/runtime-config.js";
import {
  containsSensitiveExactClaim,
  hasApplicableConflictFreeEvidence,
  renderUntrustedEvidenceChannel,
  type EvidenceClaim,
} from "../evidence/evidence-normalizer.js";
import type { WebAuthorization, WebSearchContext } from "../evidence/search-provider.js";
import type { WebEvidenceCollector } from "../evidence/web-evidence-pipeline.js";
import { type ModuleId, ModuleRegistry } from "../modules/module-manifest.js";
import {
  ModelGenerationError,
  type ModelGenerationContinuation,
  type ModelGenerationFailureCode,
  type ModelGenerationResult,
  type ModelProvider,
  type ModelToolOutput,
} from "../providers/model-provider.js";
import { buildContextWindow } from "../sessions/context-window.js";
import {
  ConversationOwnershipError,
  DisabledConversationRepository,
  type ConversationOwner,
  type ConversationRepository,
} from "../storage/conversation-repository.js";
import { type CoreToolDescriptor, ToolRegistry } from "../tools/tool-registry.js";
import {
  type LocalToolExecution,
  UnavailableLocalToolExecutor,
} from "../tools/local-tool-executor.js";
import type {
  ToolCallPayload,
  ToolExecutionResult,
  ToolResultPayload,
} from "../tools/tool-types.js";
import type { ProviderUsageRecordResult, UsageAccounting } from "../usage/usage-accounting.js";
import { RequestAdmissionController, type RequestAdmissionRejection } from "./request-admission.js";
import {
  createAuthoritativeRecipePresentation,
  type RecipeViewContentV2,
  type RecipeViewPresentation,
  uncheckedRecipeFallback,
  unavailableRecipeFallback,
} from "./recipe-presentation.js";

const MAXIMUM_MODEL_OUTPUT_TOKENS = 1024;
const SHUTDOWN_PROVIDER_GRACE_MILLISECONDS = 1_000;

type VerifiedProjectRevisions = Map<string, number>;

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function updateVerifiedProject(
  verified: VerifiedProjectRevisions,
  descriptor: CoreToolDescriptor,
  argumentsValue: Readonly<Record<string, unknown>>,
  result: ToolExecutionResult,
): void {
  if (descriptor.id !== "project.read") {
    return;
  }
  const requestedProjectId = argumentsValue["projectId"];
  if (typeof requestedProjectId === "string") {
    verified.delete(requestedProjectId);
  }
  if (result.status !== "succeeded" || !isRecord(result.result)) {
    return;
  }
  const project = result.result["project"];
  if (!isRecord(project)) {
    return;
  }
  const projectId = project["projectId"];
  const revision = project["revision"];
  if (typeof projectId === "string" && Number.isSafeInteger(revision) && Number(revision) >= 1) {
    verified.set(projectId, Number(revision));
  }
}

function matchesVerifiedProject(
  verified: VerifiedProjectRevisions,
  argumentsValue: Readonly<Record<string, unknown>>,
): boolean {
  const projectId = argumentsValue["projectId"];
  const revision = argumentsValue["revision"];
  return (
    typeof projectId === "string" &&
    Number.isSafeInteger(revision) &&
    verified.get(projectId) === Number(revision)
  );
}

type ProjectMutationKind = "project.create" | "project.update";

function permitsProjectMutation(message: string, kind: ProjectMutationKind): boolean {
  const normalized = message.normalize("NFKC").trim().toLowerCase();
  const isQuestionOrHypothetical =
    /[?？]/u.test(normalized) ||
    /^(?:how|what|when|where|why|who|which|can|could|would|should|do|does|did|is|are|may|might|if|suppose|imagine)\b/u.test(
      normalized,
    ) ||
    /\b(?:how\s+to|tell\s+me\s+how|explain\s+how|hypothetically)\b|如何|怎么|怎样|是否|能否|可否|为什么|假如|假设/u.test(
      normalized,
    );
  if (isQuestionOrHypothetical) {
    return false;
  }

  const verbs =
    kind === "project.create"
      ? "save|store|persist|create|record|remember"
      : "update|edit|rename|revise|modify|change";
  const chineseVerbs =
    kind === "project.create" ? "保存|存储|新建|创建|记录|记住" : "更新|修改|编辑|重命名|变更";
  const negated =
    new RegExp(
      `\\b(?:do\\s+not|don't|dont|never|avoid|not\\s+to)\\s+(?:\\w+\\s+){0,3}(?:${verbs})\\b`,
      "u",
    ).test(normalized) ||
    new RegExp(
      `(?:不要|别|禁止|避免|无需|不用|不想|不能|不可)[^\\r\\n]{0,12}(?:${chineseVerbs})`,
      "u",
    ).test(normalized);
  if (negated) {
    return false;
  }

  const directEnglish = new RegExp(
    `^(?:please(?:\\s+|,\\s*))?(?:${verbs})\\b[^\\r\\n]{0,160}\\b(?:project|plan)\\b`,
    "u",
  );
  const directChinese = new RegExp(
    `^(?:(?:请|麻烦|请帮我|帮我)[，,\\s]*)?(?:(?:${chineseVerbs})[^\\r\\n]{0,80}(?:项目|计划)|(?:把|将)[^\\r\\n]{0,60}(?:${chineseVerbs})[^\\r\\n]{0,60}(?:项目|计划)|(?:把|将)[^\\r\\n]{0,60}(?:项目|计划)[^\\r\\n]{0,60}(?:${chineseVerbs})|(?:项目|计划)[^\\r\\n]{0,60}(?:${chineseVerbs}))`,
    "u",
  );
  return directEnglish.test(normalized) || directChinese.test(normalized);
}

function completedProjectMutation(
  descriptor: CoreToolDescriptor,
  result: ToolExecutionResult,
): boolean {
  if (
    (descriptor.id !== "project.create" && descriptor.id !== "project.update") ||
    result.status !== "succeeded" ||
    !isRecord(result.result)
  ) {
    return false;
  }
  const outcome = result.result["outcome"];
  return outcome === "CREATED" || outcome === "UPDATED";
}

function authoritativeBuildFallback(result: ToolExecutionResult): string | undefined {
  if (
    result.status !== "succeeded" ||
    result.source !== "paper_api" ||
    result.trust !== "authoritative" ||
    !isRecord(result.result) ||
    result.result["previewStatus"] !== "server_validated" ||
    result.result["worldWriteEnabled"] !== false
  ) {
    return undefined;
  }
  const previewId = result.result["previewId"];
  const projectId = result.result["projectId"];
  const revision = result.result["revision"];
  const changeCount = result.result["changeCount"];
  if (
    typeof previewId !== "string" ||
    typeof projectId !== "string" ||
    !Number.isSafeInteger(revision) ||
    !Number.isSafeInteger(changeCount)
  ) {
    return undefined;
  }
  return `Paper generated server-validated build preview ${previewId} for project ${projectId} revision ${String(revision)} with ${String(changeCount)} changes. No blocks were changed; loading the preview is an explicit client action.`;
}

export interface AgentRequestInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly sessionId: string | null;
  readonly module: ModuleId;
  readonly message: string;
  readonly localContext?: {
    readonly minecraftVersion: string;
    readonly catalogGenerationId: string;
    readonly target: {
      readonly id: string;
      readonly displayName?: string;
      readonly modId?: string;
      readonly modVersion?: string;
    };
  };
  readonly webAuthorization?: WebAuthorization;
  readonly webContext?: WebSearchContext;
  readonly inventoryAuthorization?: {
    readonly authorizationId: string;
    readonly generationId: string;
    readonly resourceIds: readonly string[];
  };
}

export interface AgentCompletionPayload {
  readonly sessionId: string | null;
  readonly playerUuid: string;
  readonly fallbackText: string;
  readonly structuredViews: readonly AgentStructuredView[];
  readonly costMicroUsd?: number;
  readonly costKind?: "reported" | "estimated" | "mixed";
  readonly sources?: readonly AgentCompletionSource[];
}

export interface AgentCompletionSource {
  readonly claimId: string;
  readonly title: string;
  readonly url: string;
  readonly publisher: string;
  readonly retrievedAt: string;
  readonly applicability: {
    readonly minecraftVersion: string;
    readonly modVersions: Readonly<Record<string, string>>;
    readonly modpackVersion: string | null;
    readonly match: "match" | "mismatch" | "unknown";
  };
  readonly warnings: readonly string[];
}

export interface StructuredTextView {
  readonly viewSchemaVersion: "1.0";
  readonly viewId: string;
  readonly requestId: string;
  readonly viewType: "text";
  readonly revision: 1;
  readonly title: string;
  readonly fallbackText: string;
  readonly pinnable: true;
  readonly content: {
    readonly text: string;
  };
}

export interface StructuredRecipeView {
  readonly viewSchemaVersion: "1.0";
  readonly viewId: string;
  readonly requestId: string;
  readonly viewType: "recipe";
  readonly revision: 1;
  readonly title: string;
  readonly fallbackText: string;
  readonly pinnable: true;
  readonly content: RecipeViewContentV2;
}

export type AgentStructuredView = StructuredTextView | StructuredRecipeView;

export type AgentErrorCode =
  | "MODEL_TIMEOUT"
  | "MODEL_UNAVAILABLE"
  | "MODEL_AUTHENTICATION_FAILED"
  | "MODEL_RESPONSE_INVALID"
  | "REQUEST_CANCELLED"
  | "REQUEST_LIMITED"
  | "BUDGET_EXCEEDED"
  | "SESSION_NOT_FOUND"
  | "CONVERSATION_STORAGE_DISABLED"
  | "TOOL_REJECTED"
  | "TOOL_ROUND_LIMIT"
  | "RUNTIME_INTERNAL_ERROR";

export interface AgentErrorPayload {
  readonly playerUuid: string;
  readonly code: AgentErrorCode;
  readonly fallbackText: string;
  readonly retryable: boolean;
}

export type AgentTerminalResponse =
  | { readonly type: "agent.complete"; readonly payload: AgentCompletionPayload }
  | { readonly type: "agent.error"; readonly payload: AgentErrorPayload };

export type AgentRuntimeResponse =
  | AgentTerminalResponse
  | { readonly type: "tool.call"; readonly payload: ToolCallPayload };

export interface SessionResumeInput {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly sessionId: string | null;
}

export interface SessionResumedPayload {
  readonly playerUuid: string;
  readonly sessionId: string;
}

export type SessionResumeResponse =
  | { readonly type: "session.resumed"; readonly payload: SessionResumedPayload }
  | { readonly type: "agent.error"; readonly payload: AgentErrorPayload };

export type RequestAudience = "paper" | "client";

export interface AgentRequestServiceOptions {
  readonly provider: ModelProvider;
  readonly config: RuntimeConfig;
  readonly timeoutMilliseconds?: number;
  readonly now?: () => number;
  readonly randomUuid?: () => string;
  readonly conversations?: ConversationRepository;
  readonly modules?: ModuleRegistry;
  readonly tools: ToolRegistry;
  readonly localTools?: LocalToolExecution;
  readonly usage?: UsageAccounting;
  readonly audience?: RequestAudience;
  readonly webEvidence?: WebEvidenceCollector;
}

interface PendingToolCall {
  readonly payload: ToolCallPayload;
  readonly descriptor: CoreToolDescriptor;
  readonly resolve: (payload: ToolResultPayload) => void;
  readonly reject: (reason: unknown) => void;
  readonly removeAbortListener: () => void;
}

interface RequestRecord {
  readonly input: AgentRequestInput;
  readonly respond: (response: AgentRuntimeResponse) => void;
  readonly controller: AbortController;
  phase: "QUEUED" | "ACTIVE" | "WAITING_TOOL";
  timeout: NodeJS.Timeout | undefined;
  terminalSent: boolean;
  suppressResponse: boolean;
  detached: boolean;
  preparedSessionId: string | null;
  executionSessionId: string | null;
  createsSession: boolean;
  pendingTool: PendingToolCall | undefined;
  clientGenerationId: string | undefined;
  usageAdmitted: boolean;
  readonly issuedToolCallIds: Set<string>;
  inventoryAuthorizationUsed: boolean;
  providerCostMicroUsd: number;
  readonly providerUsageKinds: Set<ProviderUsageRecordResult["usageKind"]>;
}

function recordProviderCost(
  record: Pick<RequestRecord, "providerCostMicroUsd" | "providerUsageKinds">,
  usage: ProviderUsageRecordResult,
): void {
  const total = record.providerCostMicroUsd + usage.costMicroUsd;
  if (!Number.isSafeInteger(total) || total < 0) {
    throw new Error("Provider request cost exceeds its bounded total.");
  }
  record.providerCostMicroUsd = total;
  record.providerUsageKinds.add(usage.usageKind);
}

function providerCostKind(
  usageKinds: ReadonlySet<ProviderUsageRecordResult["usageKind"]>,
): "reported" | "estimated" | "mixed" {
  if (usageKinds.has("REPORTED") && usageKinds.has("ESTIMATED")) return "mixed";
  return usageKinds.has("REPORTED") ? "reported" : "estimated";
}

class ToolLoopError extends Error {
  public readonly code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT";

  public constructor(code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT") {
    super(code);
    this.name = "ToolLoopError";
    this.code = code;
  }
}

class UsageBudgetError extends Error {
  public constructor() {
    super("MONTHLY_BUDGET_EXCEEDED");
    this.name = "UsageBudgetError";
  }
}

function limitedError(
  playerUuid: string,
  reason: RequestAdmissionRejection,
): AgentTerminalResponse {
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

function budgetError(playerUuid: string, audience: RequestAudience): AgentTerminalResponse {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code: "BUDGET_EXCEEDED",
      fallbackText:
        audience === "client"
          ? "The monthly AI budget has been reached. Review the standalone client budget settings."
          : "The monthly AI budget has been reached. Ask an administrator to review it.",
      retryable: false,
    },
  };
}

function providerError(
  playerUuid: string,
  code: ModelGenerationFailureCode,
  audience: RequestAudience,
): AgentTerminalResponse {
  switch (code) {
    case "MODEL_AUTHENTICATION_FAILED":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_AUTHENTICATION_FAILED",
          fallbackText:
            audience === "client"
              ? "Model authentication failed. Review the standalone client Provider settings."
              : "The AI model is unavailable. Ask an administrator to check it.",
          retryable: false,
        },
      };
    case "MODEL_UNAVAILABLE":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_UNAVAILABLE",
          fallbackText: "The AI model is unavailable. Try again later.",
          retryable: true,
        },
      };
    case "MODEL_RATE_LIMITED":
    case "PROVIDER_UNAVAILABLE":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_UNAVAILABLE",
          fallbackText: "The AI model is temporarily unavailable. Try again later.",
          retryable: true,
        },
      };
    case "MODEL_RESPONSE_INVALID":
      return {
        type: "agent.error",
        payload: {
          playerUuid,
          code: "MODEL_RESPONSE_INVALID",
          fallbackText: "The AI returned an unusable response. Try again.",
          retryable: true,
        },
      };
  }
}

function isUsableFallbackText(value: unknown): value is string {
  if (typeof value !== "string" || value.trim().length === 0) {
    return false;
  }
  let codePoints = 0;
  for (const character of value) {
    codePoints += 1;
    if (codePoints > 8192) {
      return false;
    }
    const codePoint = character.codePointAt(0);
    if (
      codePoint === undefined ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
      ((codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) &&
        codePoint !== 0x09 &&
        codePoint !== 0x0a) ||
      codePoint === 0x061c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069)
    ) {
      return false;
    }
  }
  return true;
}

function sessionError(
  playerUuid: string,
  code: "SESSION_NOT_FOUND" | "CONVERSATION_STORAGE_DISABLED",
  audience: RequestAudience,
): { readonly type: "agent.error"; readonly payload: AgentErrorPayload } {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code,
      fallbackText:
        code === "SESSION_NOT_FOUND"
          ? "That conversation is not available."
          : audience === "client"
            ? "Conversation history is disabled in the standalone client."
            : "Conversation history is disabled on this server.",
      retryable: false,
    },
  };
}

function internalError(playerUuid: string): {
  readonly type: "agent.error";
  readonly payload: AgentErrorPayload;
} {
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

function toolLoopError(
  playerUuid: string,
  code: "TOOL_REJECTED" | "TOOL_ROUND_LIMIT",
  audience: RequestAudience,
): { readonly type: "agent.error"; readonly payload: AgentErrorPayload } {
  return {
    type: "agent.error",
    payload: {
      playerUuid,
      code,
      fallbackText:
        code === "TOOL_REJECTED"
          ? audience === "client"
            ? "The requested local capability was not allowed."
            : "The requested server lookup was not allowed."
          : audience === "client"
            ? "The AI used too many local lookups. Try a more specific question."
            : "The AI used too many server lookups. Try a more specific question.",
      retryable: code === "TOOL_ROUND_LIMIT",
    },
  };
}

export class AgentRequestService {
  readonly #provider: ModelProvider;
  readonly #config: RuntimeConfig;
  readonly #timeoutMilliseconds: number;
  readonly #admission: RequestAdmissionController;
  readonly #now: () => number;
  readonly #randomUuid: () => string;
  readonly #conversations: ConversationRepository;
  readonly #modules: ModuleRegistry;
  readonly #tools: ToolRegistry;
  readonly #localTools: LocalToolExecution;
  readonly #usage: UsageAccounting | undefined;
  readonly #audience: RequestAudience;
  readonly #webEvidence: WebEvidenceCollector | undefined;
  readonly #requests = new Map<string, RequestRecord>();
  readonly #runs = new Set<Promise<void>>();
  #closed = false;
  #usageHealthy = true;

  public constructor(options: AgentRequestServiceOptions) {
    const timeoutMilliseconds =
      options.timeoutMilliseconds ?? options.config.model.timeoutSeconds * 1000;
    if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 1) {
      throw new TypeError("The Agent request timeout must be a positive bounded integer.");
    }
    this.#provider = options.provider;
    this.#config = options.config;
    this.#now = options.now ?? Date.now;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    this.#conversations = options.conversations ?? new DisabledConversationRepository();
    this.#modules = options.modules ?? new ModuleRegistry();
    this.#tools = options.tools;
    this.#localTools = options.localTools ?? new UnavailableLocalToolExecutor();
    this.#usage = options.usage;
    this.#audience = options.audience ?? "paper";
    this.#webEvidence = options.webEvidence;
    this.#timeoutMilliseconds = timeoutMilliseconds;
    this.#admission = new RequestAdmissionController(
      {
        maximumConcurrent: options.config.limits.maxConcurrentRequests,
        maximumQueued: options.config.limits.maxQueuedRequests,
        perPlayerCooldownMilliseconds: options.config.limits.perPlayerCooldownSeconds * 1000,
        dailyRequestsPerPlayer: options.config.limits.dailyRequestsPerPlayer,
      },
      this.#now,
    );
  }

  public submit(input: AgentRequestInput, respond: (response: AgentRuntimeResponse) => void): void {
    if (this.#closed) {
      this.#safeRespond(respond, {
        type: "agent.error",
        payload: {
          playerUuid: input.playerUuid,
          code: "RUNTIME_INTERNAL_ERROR",
          fallbackText: "The AI Runtime is stopping.",
          retryable: true,
        },
      });
      return;
    }
    if (this.#requests.has(input.requestId)) {
      this.#safeRespond(respond, limitedError(input.playerUuid, "PLAYER_BUSY"));
      return;
    }
    if (this.#usage !== undefined && !this.#usageHealthy) {
      this.#safeRespond(respond, internalError(input.playerUuid));
      return;
    }

    let usageAdmitted = false;
    if (this.#usage !== undefined) {
      try {
        const usageDecision = this.#usageOperation(() =>
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
          this.#safeRespond(
            respond,
            usageDecision.reason === "MONTHLY_BUDGET_EXCEEDED"
              ? budgetError(input.playerUuid, this.#audience)
              : limitedError(input.playerUuid, "PLAYER_DAILY_LIMIT"),
          );
          return;
        }
        usageAdmitted = true;
      } catch {
        this.#safeRespond(respond, internalError(input.playerUuid));
        return;
      }
    }

    const record: RequestRecord = {
      input,
      respond,
      controller: new AbortController(),
      phase: "QUEUED",
      timeout: undefined,
      terminalSent: false,
      suppressResponse: false,
      detached: false,
      preparedSessionId: null,
      executionSessionId: null,
      createsSession: false,
      pendingTool: undefined,
      clientGenerationId: undefined,
      usageAdmitted,
      issuedToolCallIds: new Set(),
      inventoryAuthorizationUsed: false,
      providerCostMicroUsd: 0,
      providerUsageKinds: new Set(),
    };
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
          this.#usageOperation(() => {
            if (this.#usage?.rollbackAdmission(input.requestId) !== true) {
              throw new Error("Durable usage admission could not be rolled back.");
            }
          });
          record.usageAdmitted = false;
        }
        this.#safeRespond(respond, limitedError(input.playerUuid, decision.reason));
      } catch {
        this.#safeRespond(respond, internalError(input.playerUuid));
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
      !this.#validToolResult(pending.descriptor, payload)
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
    for (const record of records.filter((candidate) => candidate.phase === "QUEUED")) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
    for (const record of records.filter((candidate) => candidate.phase !== "QUEUED")) {
      this.cancel(record.input.requestId, record.input.playerUuid);
    }
  }

  #validToolResult(descriptor: CoreToolDescriptor, payload: ToolResultPayload): boolean {
    if (payload.status === "succeeded") {
      return this.#tools.validateResult(descriptor, payload);
    }
    if (
      payload.result !== null ||
      payload.error === null ||
      !/^[A-Z][A-Z0-9_]{0,63}$/u.test(payload.error.code) ||
      payload.error.message.length < 1 ||
      payload.error.message.length > 1024
    ) {
      return false;
    }
    if (payload.status === "rejected") {
      return descriptor.execution === "connector_remote"
        ? payload.source === "client_policy" && payload.trust === "client_visible"
        : payload.source === "paper_policy" && payload.trust === "authoritative";
    }
    return payload.source === descriptor.source && payload.trust === descriptor.trust;
  }

  public resume(
    input: SessionResumeInput,
    respond: (response: SessionResumeResponse) => void,
  ): void {
    if (this.#closed) {
      this.#safeRespond(respond, internalError(input.playerUuid));
      return;
    }
    if (!this.#conversations.enabled) {
      this.#safeRespond(
        respond,
        sessionError(input.playerUuid, "CONVERSATION_STORAGE_DISABLED", this.#audience),
      );
      return;
    }
    try {
      const owner = this.#owner(input.playerUuid);
      const session =
        input.sessionId === null
          ? this.#conversations.findLatestOwned(owner)
          : this.#conversations.findOwned(input.sessionId, owner);
      this.#safeRespond(
        respond,
        session === undefined
          ? sessionError(input.playerUuid, "SESSION_NOT_FOUND", this.#audience)
          : {
              type: "session.resumed",
              payload: { playerUuid: input.playerUuid, sessionId: session.id },
            },
      );
    } catch {
      this.#safeRespond(respond, internalError(input.playerUuid));
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

  public get activeCount(): number {
    return this.#admission.activeCount;
  }

  public get queuedCount(): number {
    return this.#admission.queuedCount;
  }

  #start(record: RequestRecord): void {
    record.phase = "ACTIVE";
    const run = this.#run(record)
      .catch((error: unknown) => {
        if (record.terminalSent || record.suppressResponse) {
          return;
        }
        record.terminalSent = true;
        this.#safeRespond(
          record.respond,
          error instanceof ModelGenerationError
            ? providerError(record.input.playerUuid, error.code, this.#audience)
            : error instanceof ConversationOwnershipError
              ? sessionError(record.input.playerUuid, "SESSION_NOT_FOUND", this.#audience)
              : error instanceof ToolLoopError
                ? toolLoopError(record.input.playerUuid, error.code, this.#audience)
                : error instanceof UsageBudgetError
                  ? budgetError(record.input.playerUuid, this.#audience)
                  : internalError(record.input.playerUuid),
        );
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

  async #run(record: RequestRecord): Promise<void> {
    const manifest = this.#modules.get(record.input.module);
    const history = this.#prepareConversation(record);
    let evidenceClaims: readonly EvidenceClaim[] = [];
    const authorization = record.input.webAuthorization ?? "off";
    let evidenceChannel = "";
    if (this.#audience === "client" && this.#webEvidence !== undefined) {
      const evidence = await this.#webEvidence.collect({
        authorization,
        question: record.input.message,
        ...(record.input.webContext === undefined ? {} : { context: record.input.webContext }),
        signal: record.controller.signal,
      });
      evidenceClaims = evidence.claims;
      evidenceChannel = renderUntrustedEvidenceChannel(evidence.claims);
    }
    const modelMessage =
      evidenceChannel.length === 0
        ? record.input.message
        : `${record.input.message}\n\n${evidenceChannel}`;
    const input = buildContextWindow(history, modelMessage, {
      maximumMessages: this.#config.limits.maxContextMessages,
      maximumCharacters: this.#config.limits.maxContextCharacters,
    });
    const allowedTools =
      evidenceChannel.length === 0 ? this.#tools.forAllowlist(manifest.toolAllowlist) : [];
    const allowedToolIds = new Set(allowedTools.map((tool) => tool.id));
    const inventoryAuthorization = record.input.inventoryAuthorization;
    const instructions =
      inventoryAuthorization === undefined
        ? manifest.instructions
        : `${manifest.instructions}\nTrusted single-use inventory authorization: call game_inventory_snapshot at most once, using exactly authorizationId=${inventoryAuthorization.authorizationId}, generationId=${inventoryAuthorization.generationId}, and resourceIds=${JSON.stringify(inventoryAuthorization.resourceIds)}. Do not reveal the authorization ID.`;
    let sequence = 0;
    let continuation: ModelGenerationContinuation | undefined;
    let toolOutput: ModelToolOutput | undefined;
    let recipeToolAttempted = false;
    let authoritativeRecipe: ReturnType<typeof createAuthoritativeRecipePresentation> = undefined;
    let buildFallback: string | undefined;
    let buildToolAttempted = false;
    const verifiedProjects: VerifiedProjectRevisions = new Map();
    let projectMutationCompleted = false;

    while (!record.controller.signal.aborted) {
      const toolsAvailable = sequence < this.#config.limits.maxToolRounds;
      if (this.#usage !== undefined && !this.#usageHealthy) {
        throw new Error("Usage accounting is unavailable.");
      }
      if (sequence > 0 && this.#usage !== undefined) {
        const reservation = this.#usageOperation(() =>
          this.#usage?.reserveProviderRound(record.input.requestId, sequence, this.#now()),
        );
        if (reservation === undefined) {
          throw new Error("Usage accounting disappeared during reservation.");
        }
        if (!reservation.accepted) {
          if (reservation.reason === "MONTHLY_BUDGET_EXCEEDED") {
            throw new UsageBudgetError();
          }
          throw new Error("Usage request closed before its next provider round.");
        }
      }
      if (this.#usage !== undefined) {
        this.#usageOperation(() => {
          if (
            this.#usage?.markProviderRoundStarted(record.input.requestId, sequence, this.#now()) !==
            true
          ) {
            throw new Error("Provider round could not be durably marked as started.");
          }
        });
      }
      let result: ModelGenerationResult;
      try {
        result = await this.#provider.generate({
          provider: this.#config.model.provider,
          model: this.#config.model.model,
          apiKey: this.#config.model.apiKey,
          instructions,
          input,
          tools: toolsAvailable ? allowedTools : [],
          ...(continuation === undefined ? {} : { continuation }),
          ...(toolOutput === undefined ? {} : { toolOutput }),
          maxOutputTokens: MAXIMUM_MODEL_OUTPUT_TOKENS,
          signal: record.controller.signal,
        });
      } catch (error) {
        if (this.#usage !== undefined) {
          this.#usageOperation(() => {
            if (
              error instanceof ModelGenerationError &&
              error.accountingDisposition === "NOT_BILLABLE" &&
              !record.detached
            ) {
              if (
                this.#usage?.releaseProviderRound(record.input.requestId, sequence, this.#now()) !==
                true
              ) {
                throw new Error("Non-billable provider round could not be released.");
              }
              return;
            }
            this.#usage?.recordProviderUsage({
              requestId: record.input.requestId,
              playerUuid: record.input.playerUuid,
              providerRound: sequence,
              timestamp: this.#now(),
            });
          });
        }
        if (
          !toolsAvailable &&
          error instanceof ModelGenerationError &&
          error.code === "MODEL_RESPONSE_INVALID"
        ) {
          throw new ToolLoopError("TOOL_ROUND_LIMIT");
        }
        throw error;
      }
      if (this.#usage !== undefined) {
        const usage = this.#usageOperation(() =>
          this.#usage?.recordProviderUsage({
            requestId: record.input.requestId,
            playerUuid: record.input.playerUuid,
            providerRound: sequence,
            timestamp: this.#now(),
            ...(result.usage === undefined ? {} : { usage: result.usage }),
          }),
        );
        if (usage !== undefined) recordProviderCost(record, usage);
      } else if (this.#audience === "client") {
        record.providerUsageKinds.add(result.usage === undefined ? "ESTIMATED" : "REPORTED");
      }
      if (result.type === "final") {
        if (authoritativeRecipe !== undefined) {
          this.#complete(record, authoritativeRecipe.fallbackText, authoritativeRecipe.view);
        } else if (buildFallback !== undefined) {
          this.#complete(record, buildFallback);
        } else if (buildToolAttempted) {
          this.#complete(
            record,
            "Paper could not produce a server-validated build preview. No blocks were changed.",
          );
        } else {
          const fallbackText =
            authorization !== "off" &&
            containsSensitiveExactClaim(result.fallbackText) &&
            !hasApplicableConflictFreeEvidence(evidenceClaims)
              ? "I could not verify an exact drop rate, coordinate, or version conclusion from applicable conflict-free web evidence."
              : result.fallbackText;
          this.#complete(
            record,
            recipeToolAttempted
              ? unavailableRecipeFallback()
              : record.input.module === "recipe"
                ? uncheckedRecipeFallback()
                : fallbackText,
          );
        }
        return;
      }
      if (!toolsAvailable) {
        throw new ToolLoopError("TOOL_ROUND_LIMIT");
      }
      const descriptor = this.#tools.byProviderName(result.providerName);
      if (
        descriptor === undefined ||
        !allowedToolIds.has(descriptor.id) ||
        !this.#tools.validateArguments(descriptor, result.arguments)
      ) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      if (descriptor.execution === "connector_remote") {
        const requestedGeneration = result.arguments["generationId"];
        if (
          record.clientGenerationId !== undefined &&
          requestedGeneration !== undefined &&
          requestedGeneration !== record.clientGenerationId
        ) {
          throw new ToolLoopError("TOOL_REJECTED");
        }
      }
      if (descriptor.id === "game.inventory.snapshot") {
        const authorization = record.input.inventoryAuthorization;
        if (
          authorization === undefined ||
          record.inventoryAuthorizationUsed ||
          result.arguments["authorizationId"] !== authorization.authorizationId ||
          result.arguments["generationId"] !== authorization.generationId ||
          JSON.stringify(result.arguments["resourceIds"]) !==
            JSON.stringify(authorization.resourceIds)
        ) {
          throw new ToolLoopError("TOOL_REJECTED");
        }
        record.inventoryAuthorizationUsed = true;
      }
      if (
        descriptor.id === "build.preview.create" &&
        !matchesVerifiedProject(verifiedProjects, result.arguments)
      ) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      if (
        (descriptor.id === "project.create" || descriptor.id === "project.update") &&
        (projectMutationCompleted || !permitsProjectMutation(record.input.message, descriptor.id))
      ) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      const toolCallId = this.#allocateToolCallId(record);
      let toolResult: ToolExecutionResult;
      if (descriptor.execution === "runtime_local") {
        try {
          toolResult = await this.#localTools.execute({
            descriptor,
            serverId: this.#config.server.id,
            playerUuid: record.input.playerUuid,
            requestId: record.input.requestId,
            toolCallId,
            arguments: result.arguments,
            now: this.#now(),
            signal: record.controller.signal,
          });
        } catch (error) {
          if (record.controller.signal.aborted) {
            throw error;
          }
          throw new ToolLoopError("TOOL_REJECTED");
        }
        if (!this.#validLocalToolResult(descriptor, toolResult)) {
          throw new ToolLoopError("TOOL_REJECTED");
        }
        updateVerifiedProject(verifiedProjects, descriptor, result.arguments, toolResult);
        projectMutationCompleted ||= completedProjectMutation(descriptor, toolResult);
      } else {
        const executionSessionId = record.executionSessionId;
        if (executionSessionId === null) {
          throw new Error("Tool execution session was not prepared.");
        }
        const payload: ToolCallPayload = {
          toolCallId,
          sessionId: executionSessionId,
          playerUuid: record.input.playerUuid,
          module: record.input.module,
          tool: descriptor.id,
          arguments: result.arguments,
          sequence,
        };
        toolResult = await this.#awaitToolResult(record, descriptor, payload);
      }
      if (descriptor.execution === "connector_remote" && toolResult.status === "succeeded") {
        const generationId = toolResult.result?.["generationId"];
        if (
          typeof generationId !== "string" ||
          (record.clientGenerationId !== undefined && generationId !== record.clientGenerationId)
        ) {
          throw new ToolLoopError("TOOL_REJECTED");
        }
        record.clientGenerationId = generationId;
        if (
          descriptor.id === "game.inventory.snapshot" &&
          toolResult.result?.["authorizationId"] !== result.arguments["authorizationId"]
        ) {
          throw new ToolLoopError("TOOL_REJECTED");
        }
      }
      if (toolResult.status === "rejected") {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      if (descriptor.id === "server.recipe.lookup" || descriptor.id === "server.recipe.uses") {
        recipeToolAttempted = true;
        authoritativeRecipe = createAuthoritativeRecipePresentation(descriptor.id, toolResult);
      }
      if (descriptor.id === "build.preview.create") {
        buildToolAttempted = true;
        buildFallback = authoritativeBuildFallback(toolResult);
      }
      const providerOutput = JSON.stringify({
        status: toolResult.status,
        source: toolResult.source,
        trust: toolResult.trust,
        result: toolResult.result,
        error: toolResult.error,
      });
      if (providerOutput.length > 64 * 1024) {
        throw new ToolLoopError("TOOL_REJECTED");
      }
      continuation = result.continuation;
      toolOutput = { providerCallId: result.providerCallId, output: providerOutput };
      sequence += 1;
    }
  }

  #complete(
    record: RequestRecord,
    fallbackText: string,
    recipeView?: RecipeViewPresentation,
  ): void {
    if (record.terminalSent || record.suppressResponse) {
      return;
    }
    if (!isUsableFallbackText(fallbackText)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    if (record.preparedSessionId !== null) {
      this.#conversations.commitExchange({
        ...this.#owner(record.input.playerUuid),
        sessionId: record.preparedSessionId,
        createSession: record.createsSession,
        requestId: record.input.requestId,
        module: record.input.module,
        userContent: record.input.message,
        assistantContent: fallbackText,
        createdAt: new Date(this.#now()).toISOString(),
      });
    }
    record.terminalSent = true;
    const viewId = this.#randomUuid();
    const structuredViews: AgentStructuredView[] =
      recipeView === undefined
        ? [
            {
              viewSchemaVersion: "1.0",
              viewId,
              requestId: record.input.requestId,
              viewType: "text",
              revision: 1,
              title: "Agent response",
              fallbackText,
              pinnable: true,
              content: { text: fallbackText },
            },
          ]
        : [
            {
              viewSchemaVersion: "1.0",
              viewId,
              requestId: record.input.requestId,
              viewType: "recipe",
              revision: 1,
              title: recipeView.title,
              fallbackText,
              pinnable: true,
              content: recipeView.content,
            },
          ];
    this.#safeRespond(record.respond, {
      type: "agent.complete",
      payload: {
        sessionId: record.preparedSessionId,
        playerUuid: record.input.playerUuid,
        fallbackText,
        structuredViews,
        ...(this.#audience === "client"
          ? {
              costMicroUsd: record.providerCostMicroUsd,
              costKind: providerCostKind(record.providerUsageKinds),
            }
          : {}),
      },
    });
  }

  #awaitToolResult(
    record: RequestRecord,
    descriptor: CoreToolDescriptor,
    payload: ToolCallPayload,
  ): Promise<ToolResultPayload> {
    return new Promise<ToolResultPayload>((resolve, reject) => {
      const onAbort = (): void => reject(record.controller.signal.reason);
      const pending: PendingToolCall = {
        payload,
        descriptor,
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
      this.#safeRespond(record.respond, { type: "tool.call", payload });
    }).finally(() => {
      record.phase = "ACTIVE";
    });
  }

  #validLocalToolResult(descriptor: CoreToolDescriptor, payload: ToolExecutionResult): boolean {
    if (payload.status === "succeeded") {
      return this.#tools.validateResult(descriptor, payload);
    }
    return (
      payload.status === "failed" &&
      payload.source === descriptor.source &&
      payload.trust === descriptor.trust &&
      payload.result === null &&
      payload.error !== null
    );
  }

  #owner(playerUuid: string): ConversationOwner {
    return { serverId: this.#config.server.id, playerUuid };
  }

  #allocateToolCallId(record: RequestRecord): string {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const candidate = this.#randomUuid();
      if (
        candidate !== record.input.requestId &&
        candidate !== record.executionSessionId &&
        !record.issuedToolCallIds.has(candidate)
      ) {
        record.issuedToolCallIds.add(candidate);
        return candidate;
      }
    }
    throw new Error("Unable to allocate a unique Tool call ID.");
  }

  #prepareConversation(record: RequestRecord) {
    if (!this.#conversations.enabled) {
      if (record.input.sessionId !== null) {
        throw new ConversationOwnershipError();
      }
      record.executionSessionId = this.#randomUuid();
      return [];
    }

    const owner = this.#owner(record.input.playerUuid);
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
    const maximumHistory = Math.max(
      0,
      Math.floor((this.#config.limits.maxContextMessages - 1) / 2) * 2,
    );
    return this.#conversations.loadRecentOwned(session.id, owner, maximumHistory);
  }

  #timeout(record: RequestRecord): void {
    if (record.terminalSent || record.suppressResponse) {
      return;
    }
    record.terminalSent = true;
    this.#detach(record);
    record.controller.abort(new Error("MODEL_TIMEOUT"));
    this.#safeRespond(record.respond, {
      type: "agent.error",
      payload: {
        playerUuid: record.input.playerUuid,
        code: "MODEL_TIMEOUT",
        fallbackText: "The AI request timed out. Try again.",
        retryable: true,
      },
    });
  }

  #clearTimeout(record: RequestRecord): void {
    if (record.timeout !== undefined) {
      clearTimeout(record.timeout);
      record.timeout = undefined;
    }
  }

  #detach(record: RequestRecord): void {
    if (record.detached) {
      return;
    }
    record.detached = true;
    if (record.usageAdmitted) {
      record.usageAdmitted = false;
      try {
        this.#usageOperation(() => {
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

  #usageOperation<Result>(operation: () => Result): Result {
    if (!this.#usageHealthy) {
      throw new Error("Usage accounting is unavailable.");
    }
    try {
      return operation();
    } catch (error) {
      this.#usageHealthy = false;
      throw error;
    }
  }

  #safeRespond<Response>(responder: (response: Response) => void, response: Response): void {
    try {
      responder(response);
    } catch {
      // A failed transport must not leak a rejection or retain admission state.
    }
  }
}
