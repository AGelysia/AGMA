import type { ModelProviderHealthCheck } from "../health/model-provider.js";

export const modelProviderIds = [
  "openai",
  "anthropic",
  "deepseek",
  "gemini",
  "openai-compatible",
] as const;

export type ModelProviderId = (typeof modelProviderIds)[number];

export const modelGenerationFailureCodes = [
  "MODEL_AUTHENTICATION_FAILED",
  "MODEL_UNAVAILABLE",
  "MODEL_RATE_LIMITED",
  "MODEL_RESPONSE_INVALID",
  "PROVIDER_UNAVAILABLE",
] as const;

export type ModelGenerationFailureCode = (typeof modelGenerationFailureCodes)[number];

export interface ModelInputMessage {
  readonly role: "user" | "assistant";
  readonly content: string;
}

export interface ModelToolDefinition {
  readonly id: string;
  readonly providerName: string;
  readonly description: string;
  readonly parameters: Readonly<Record<string, unknown>>;
}

export interface ModelToolOutput {
  readonly providerCallId: string;
  readonly output: string;
}

export interface ModelGenerationContinuation {
  readonly provider: ModelProviderId;
  readonly items: readonly Readonly<Record<string, unknown>>[];
}

export interface ModelGenerationRequest {
  readonly provider: ModelProviderId;
  readonly model: string;
  readonly apiKey: string;
  readonly instructions: string;
  readonly input: readonly ModelInputMessage[];
  readonly tools: readonly ModelToolDefinition[];
  readonly continuation?: ModelGenerationContinuation;
  readonly toolOutput?: ModelToolOutput;
  readonly maxOutputTokens: number;
  readonly signal: AbortSignal;
}

export interface ModelGenerationUsage {
  readonly inputTokens: number;
  readonly outputTokens: number;
}

export interface ModelFinalResult {
  readonly type: "final";
  readonly fallbackText: string;
  readonly usage?: ModelGenerationUsage;
}

export interface ModelToolCallResult {
  readonly type: "tool_call";
  readonly providerCallId: string;
  readonly providerName: string;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly continuation: ModelGenerationContinuation;
  readonly usage?: ModelGenerationUsage;
}

export type ModelGenerationResult = ModelFinalResult | ModelToolCallResult;

export interface ModelProvider extends ModelProviderHealthCheck {
  generate(request: ModelGenerationRequest): Promise<ModelGenerationResult>;
}

export type ModelGenerationAccountingDisposition = "NOT_BILLABLE" | "BILLABILITY_UNKNOWN";

export class ModelGenerationError extends Error {
  public readonly code: ModelGenerationFailureCode;
  public readonly accountingDisposition: ModelGenerationAccountingDisposition;

  public constructor(
    code: ModelGenerationFailureCode,
    accountingDisposition: ModelGenerationAccountingDisposition = "BILLABILITY_UNKNOWN",
  ) {
    super(code);
    this.name = "ModelGenerationError";
    this.code = code;
    this.accountingDisposition = accountingDisposition;
  }
}

export class UnsupportedModelProvider implements ModelProvider {
  public async check(): Promise<{ readonly ok: false; readonly code: "PROVIDER_UNSUPPORTED" }> {
    return Promise.resolve({ ok: false, code: "PROVIDER_UNSUPPORTED" });
  }

  public async generate(): Promise<ModelGenerationResult> {
    throw new ModelGenerationError("PROVIDER_UNAVAILABLE", "NOT_BILLABLE");
  }
}
