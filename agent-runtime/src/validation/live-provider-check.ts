import { createHmac } from "node:crypto";

import { isMainModule } from "../bootstrap/main-module.js";
import { RuntimeStartupError, type RuntimeStartupErrorCode } from "../bootstrap/startup-error.js";
import {
  loadRuntimeConfig,
  type LoadRuntimeConfigOptions,
  type RuntimeConfig,
} from "../config/runtime-config.js";
import type { ModelProviderFailureCode } from "../health/model-provider.js";
import {
  ModelGenerationError,
  type ModelGenerationFailureCode,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelGenerationUsage,
  type ModelProvider,
  type ModelToolDefinition,
} from "../providers/model-provider.js";
import { createProductionModelProvider } from "../providers/provider-factory.js";

const MAXIMUM_VALIDATION_OUTPUT_TOKENS = 1024;
const VALIDATION_TOOL_NAME = "agma_validation_echo";
const VALIDATION_TOOL_VALUE = "ready";

const validationTool: ModelToolDefinition = {
  id: "validation.echo",
  providerName: VALIDATION_TOOL_NAME,
  description: "Returns a deterministic validation acknowledgement.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      value: { type: "string", enum: [VALIDATION_TOOL_VALUE] },
    },
    required: ["value"],
  },
};

const textInstructions =
  "This is a provider validation request. Return a short plain-text acknowledgement.";
const textInput = [{ role: "user", content: "Return a short acknowledgement." }] as const;
const toolInstructions =
  "This is a provider validation request. Call the provided function exactly once. After its result is supplied, return a short plain-text acknowledgement without calling a function again.";
const toolInput = [
  {
    role: "user",
    content: `Call ${VALIDATION_TOOL_NAME} with value set to ${VALIDATION_TOOL_VALUE}.`,
  },
] as const;

export const liveProviderCheckFailureCodes = [
  "BILLING_CONFIRMATION_REQUIRED",
  "CLI_ARGUMENTS_INVALID",
  "TEXT_RESULT_INVALID",
  "TOOL_CALL_INVALID",
  "CONTINUATION_RESULT_INVALID",
  "CONFIG_PRIVACY_UNSAFE",
  "CONFIG_WARNING_UNSAFE",
  "USAGE_INVALID",
  "USAGE_MISSING",
  "VALIDATION_INTERNAL_ERROR",
] as const;

export type LiveProviderCheckFailureCode =
  | (typeof liveProviderCheckFailureCodes)[number]
  | RuntimeStartupErrorCode
  | ModelProviderFailureCode
  | ModelGenerationFailureCode;

export type LiveProviderCheckStepName =
  | "confirmation"
  | "config"
  | "health"
  | "text"
  | "tool_call"
  | "continuation";

export type LiveProviderUsageStatus = "REPORTED" | "MISSING" | "INVALID";

export interface LiveProviderCheckIdentity {
  readonly profile: RuntimeConfig["model"]["provider"];
  readonly endpoint: "DEFAULT" | "CUSTOM";
  readonly modelHmacSha256: string;
}

export interface LiveProviderCheckStep {
  readonly name: LiveProviderCheckStepName;
  readonly status: "PASS" | "FAIL";
  readonly usage?: LiveProviderUsageStatus;
  readonly code?: LiveProviderCheckFailureCode;
}

export interface LiveProviderCheckReport {
  readonly status: "PASS" | "FAIL";
  readonly steps: readonly LiveProviderCheckStep[];
  readonly identity?: LiveProviderCheckIdentity;
  readonly code?: LiveProviderCheckFailureCode;
}

export interface LiveProviderCheckOptions {
  readonly confirmedBillable: boolean;
  readonly configPath?: string;
  readonly environment?: Readonly<Record<string, string | undefined>>;
  readonly workingDirectory?: string;
  readonly config?: RuntimeConfig;
  readonly provider?: ModelProvider;
  readonly loadConfig?: typeof loadRuntimeConfig;
  readonly createProvider?: typeof createProductionModelProvider;
}

export interface LiveProviderCheckCliOptions {
  readonly arguments?: readonly string[];
  readonly environment?: Readonly<Record<string, string | undefined>>;
  readonly workingDirectory?: string;
  readonly writeLine?: (line: string) => void;
  readonly loadConfig?: typeof loadRuntimeConfig;
  readonly createProvider?: typeof createProductionModelProvider;
}

interface ParsedArguments {
  readonly confirmedBillable: boolean;
  readonly configPath?: string;
}

class ValidationFailure extends Error {
  public readonly code: LiveProviderCheckFailureCode;

  public constructor(code: LiveProviderCheckFailureCode) {
    super(code);
    this.name = "ValidationFailure";
    this.code = code;
  }
}

function failureCode(error: unknown): LiveProviderCheckFailureCode {
  if (
    error instanceof ValidationFailure ||
    error instanceof RuntimeStartupError ||
    error instanceof ModelGenerationError
  ) {
    return error.code;
  }
  return "VALIDATION_INTERNAL_ERROR";
}

function usageStatus(usage: ModelGenerationUsage | undefined): LiveProviderUsageStatus {
  return usage === undefined ? "MISSING" : "REPORTED";
}

function usageIsPositive(usage: ModelGenerationUsage): boolean {
  return usage.inputTokens > 0 && usage.outputTokens > 0;
}

function checkIdentity(config: RuntimeConfig): LiveProviderCheckIdentity {
  return {
    profile: config.model.provider,
    endpoint: config.model.baseUrl === undefined ? "DEFAULT" : "CUSTOM",
    modelHmacSha256: createHmac("sha256", config.transport.serverToken)
      .update("agma/live-provider-check/model/v1\0", "utf8")
      .update(config.model.model, "utf8")
      .digest("hex"),
  };
}

function failedReport(
  steps: readonly LiveProviderCheckStep[],
  name: LiveProviderCheckStepName,
  code: LiveProviderCheckFailureCode,
  usage?: LiveProviderUsageStatus,
  identity?: LiveProviderCheckIdentity,
): LiveProviderCheckReport {
  return {
    status: "FAIL",
    steps: [...steps, { name, status: "FAIL", ...(usage === undefined ? {} : { usage }), code }],
    ...(identity === undefined ? {} : { identity }),
    code,
  };
}

async function withConfiguredTimeout<Result>(
  timeoutMilliseconds: number,
  operation: (signal: AbortSignal) => Promise<Result>,
): Promise<Result> {
  const controller = new AbortController();
  let timeoutHandle: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timeoutHandle = setTimeout(() => {
      const failure = new ValidationFailure("PROVIDER_TIMEOUT");
      reject(failure);
      controller.abort(failure);
    }, timeoutMilliseconds);
  });

  try {
    return await Promise.race([
      Promise.resolve().then(() => operation(controller.signal)),
      timeout,
    ]);
  } finally {
    if (timeoutHandle !== undefined) {
      clearTimeout(timeoutHandle);
    }
  }
}

function generationRequest(
  config: RuntimeConfig,
  signal: AbortSignal,
  kind: "text" | "tool",
  additions: Partial<Pick<ModelGenerationRequest, "continuation" | "toolOutput">> = {},
): ModelGenerationRequest {
  const usesTool = kind === "tool";
  return {
    provider: config.model.provider,
    model: config.model.model,
    apiKey: config.model.apiKey,
    instructions: usesTool ? toolInstructions : textInstructions,
    input: usesTool ? toolInput : textInput,
    tools: usesTool ? [validationTool] : [],
    ...additions,
    maxOutputTokens: MAXIMUM_VALIDATION_OUTPUT_TOKENS,
    signal,
  };
}

function exactValidationArguments(arguments_: Readonly<Record<string, unknown>>): boolean {
  const keys = Object.keys(arguments_);
  return keys.length === 1 && keys[0] === "value" && arguments_["value"] === VALIDATION_TOOL_VALUE;
}

function parseArguments(arguments_: readonly string[]): ParsedArguments {
  let confirmedBillable = false;
  let configPath: string | undefined;

  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--confirm-billable" && !confirmedBillable) {
      confirmedBillable = true;
      continue;
    }
    if (argument === "--config" && configPath === undefined) {
      const value = arguments_[index + 1];
      if (value === undefined || value.length === 0) {
        throw new ValidationFailure("CLI_ARGUMENTS_INVALID");
      }
      configPath = value;
      index += 1;
      continue;
    }
    throw new ValidationFailure("CLI_ARGUMENTS_INVALID");
  }

  return {
    confirmedBillable,
    ...(configPath === undefined ? {} : { configPath }),
  };
}

export async function runLiveProviderCheck(
  options: LiveProviderCheckOptions,
): Promise<LiveProviderCheckReport> {
  if (!options.confirmedBillable) {
    return failedReport([], "confirmation", "BILLING_CONFIRMATION_REQUIRED");
  }

  let config: RuntimeConfig;
  try {
    if (options.config !== undefined) {
      config = options.config;
    } else {
      const loadOptions: LoadRuntimeConfigOptions = {
        ...(options.configPath === undefined ? {} : { configPath: options.configPath }),
        ...(options.environment === undefined ? {} : { environment: options.environment }),
        ...(options.workingDirectory === undefined
          ? {}
          : { workingDirectory: options.workingDirectory }),
      };
      const loaded = await (options.loadConfig ?? loadRuntimeConfig)(loadOptions);
      if (loaded.warnings.some((warning) => warning.code !== "MODEL_CUSTOM_BASE_URL")) {
        throw new ValidationFailure("CONFIG_WARNING_UNSAFE");
      }
      config = loaded.config;
    }
    if (config.privacy.logMessageContent || config.privacy.logToolCalls) {
      throw new ValidationFailure("CONFIG_PRIVACY_UNSAFE");
    }
  } catch (error) {
    return failedReport([], "config", failureCode(error));
  }

  const identity = checkIdentity(config);
  const failAfterConfig = (
    steps: readonly LiveProviderCheckStep[],
    name: LiveProviderCheckStepName,
    code: LiveProviderCheckFailureCode,
    usage?: LiveProviderUsageStatus,
  ): LiveProviderCheckReport => failedReport(steps, name, code, usage, identity);

  let provider: ModelProvider;
  try {
    provider =
      options.provider ?? (options.createProvider ?? createProductionModelProvider)(config.model);
  } catch (error) {
    return failAfterConfig([], "config", failureCode(error));
  }

  const timeoutMilliseconds = config.model.timeoutSeconds * 1000;
  const steps: LiveProviderCheckStep[] = [];

  try {
    const health = await withConfiguredTimeout(timeoutMilliseconds, (signal) =>
      provider.check({
        provider: config.model.provider,
        model: config.model.model,
        apiKey: config.model.apiKey,
        signal,
      }),
    );
    if (!health.ok) {
      return failAfterConfig(steps, "health", health.code);
    }
    steps.push({ name: "health", status: "PASS" });
  } catch (error) {
    return failAfterConfig(steps, "health", failureCode(error));
  }

  let textResult: ModelGenerationResult;
  try {
    textResult = await withConfiguredTimeout(timeoutMilliseconds, (signal) =>
      provider.generate(generationRequest(config, signal, "text")),
    );
  } catch (error) {
    return failAfterConfig(steps, "text", failureCode(error));
  }
  if (textResult.type !== "final") {
    return failAfterConfig(steps, "text", "TEXT_RESULT_INVALID", usageStatus(textResult.usage));
  }
  if (textResult.usage === undefined) {
    return failAfterConfig(steps, "text", "USAGE_MISSING", "MISSING");
  }
  if (!usageIsPositive(textResult.usage)) {
    return failAfterConfig(steps, "text", "USAGE_INVALID", "INVALID");
  }
  steps.push({ name: "text", status: "PASS", usage: usageStatus(textResult.usage) });

  let toolResult: ModelGenerationResult;
  try {
    toolResult = await withConfiguredTimeout(timeoutMilliseconds, (signal) =>
      provider.generate(generationRequest(config, signal, "tool")),
    );
  } catch (error) {
    return failAfterConfig(steps, "tool_call", failureCode(error));
  }
  if (
    toolResult.type !== "tool_call" ||
    toolResult.providerName !== VALIDATION_TOOL_NAME ||
    !exactValidationArguments(toolResult.arguments)
  ) {
    return failAfterConfig(steps, "tool_call", "TOOL_CALL_INVALID", usageStatus(toolResult.usage));
  }
  if (toolResult.usage === undefined) {
    return failAfterConfig(steps, "tool_call", "USAGE_MISSING", "MISSING");
  }
  if (!usageIsPositive(toolResult.usage)) {
    return failAfterConfig(steps, "tool_call", "USAGE_INVALID", "INVALID");
  }
  steps.push({ name: "tool_call", status: "PASS", usage: usageStatus(toolResult.usage) });

  let continuationResult: ModelGenerationResult;
  try {
    continuationResult = await withConfiguredTimeout(timeoutMilliseconds, (signal) =>
      provider.generate(
        generationRequest(config, signal, "tool", {
          continuation: toolResult.continuation,
          toolOutput: {
            providerCallId: toolResult.providerCallId,
            output: '{"accepted":true}',
          },
        }),
      ),
    );
  } catch (error) {
    return failAfterConfig(steps, "continuation", failureCode(error));
  }
  if (continuationResult.type !== "final") {
    return failAfterConfig(
      steps,
      "continuation",
      "CONTINUATION_RESULT_INVALID",
      usageStatus(continuationResult.usage),
    );
  }
  if (continuationResult.usage === undefined) {
    return failAfterConfig(steps, "continuation", "USAGE_MISSING", "MISSING");
  }
  if (!usageIsPositive(continuationResult.usage)) {
    return failAfterConfig(steps, "continuation", "USAGE_INVALID", "INVALID");
  }
  steps.push({
    name: "continuation",
    status: "PASS",
    usage: usageStatus(continuationResult.usage),
  });

  return { status: "PASS", steps, identity };
}

export function formatLiveProviderCheckReport(report: LiveProviderCheckReport): readonly string[] {
  const lines =
    report.identity === undefined
      ? []
      : [
          `profile=${report.identity.profile} endpoint=${report.identity.endpoint} model_hmac_sha256=${report.identity.modelHmacSha256}`,
        ];
  lines.push(
    ...report.steps.map((step) =>
      [
        `${step.name}=${step.status}`,
        ...(step.usage === undefined ? [] : [`usage=${step.usage}`]),
        ...(step.code === undefined ? [] : [`code=${step.code}`]),
      ].join(" "),
    ),
  );
  lines.push(
    report.status === "PASS"
      ? "result=PASS"
      : `result=FAIL code=${report.code ?? "VALIDATION_INTERNAL_ERROR"}`,
  );
  return lines;
}

export async function runLiveProviderCheckCli(
  options: LiveProviderCheckCliOptions = {},
): Promise<number> {
  let parsed: ParsedArguments;
  try {
    parsed = parseArguments(options.arguments ?? process.argv.slice(2));
  } catch (error) {
    const report = failedReport([], "confirmation", failureCode(error));
    for (const line of formatLiveProviderCheckReport(report)) {
      (options.writeLine ?? ((value) => process.stdout.write(`${value}\n`)))(line);
    }
    return 1;
  }

  const report = await runLiveProviderCheck({
    confirmedBillable: parsed.confirmedBillable,
    ...(parsed.configPath === undefined ? {} : { configPath: parsed.configPath }),
    ...(options.environment === undefined ? {} : { environment: options.environment }),
    ...(options.workingDirectory === undefined
      ? {}
      : { workingDirectory: options.workingDirectory }),
    ...(options.loadConfig === undefined ? {} : { loadConfig: options.loadConfig }),
    ...(options.createProvider === undefined ? {} : { createProvider: options.createProvider }),
  });
  const writeLine = options.writeLine ?? ((value: string) => process.stdout.write(`${value}\n`));
  for (const line of formatLiveProviderCheckReport(report)) {
    writeLine(line);
  }
  return report.status === "PASS" ? 0 : 1;
}

if (isMainModule(process.argv[1], import.meta.url)) {
  process.exitCode = await runLiveProviderCheckCli();
}
