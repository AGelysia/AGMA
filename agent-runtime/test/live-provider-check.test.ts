import { createHmac } from "node:crypto";

import { describe, expect, it, vi } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import type { RuntimeConfig } from "../src/config/runtime-config.js";
import type {
  ModelGenerationRequest,
  ModelGenerationResult,
  ModelProvider,
} from "../src/providers/model-provider.js";
import {
  formatLiveProviderCheckReport,
  runLiveProviderCheck,
  runLiveProviderCheckCli,
} from "../src/validation/live-provider-check.js";

const API_KEY = "live-provider-secret-api-key";
const MODEL = "live-provider-secret-model";
const BASE_URL = "https://live-provider-secret.example/v1";
const PROMPT_SECRET = "provider-response-secret";
const CALL_ID = "provider-call-secret";
const SERVER_TOKEN = "validation-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const MODEL_HMAC = createHmac("sha256", SERVER_TOKEN)
  .update("agma/live-provider-check/model/v1\0", "utf8")
  .update(MODEL, "utf8")
  .digest("hex");
const IDENTITY_LINE = `profile=openai-compatible endpoint=CUSTOM model_hmac_sha256=${MODEL_HMAC}`;

function config(timeoutSeconds = 2): RuntimeConfig {
  return {
    configVersion: 2,
    server: { id: "validation-test" },
    transport: {
      host: "127.0.0.1",
      port: 38_127,
      serverToken: SERVER_TOKEN,
    },
    model: {
      provider: "openai-compatible",
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      model: MODEL,
      timeoutSeconds,
      inputMicroUsdPerMillionTokens: 1,
      outputMicroUsdPerMillionTokens: 1,
    },
    storage: { sqlitePath: "data/runtime.db" },
    logging: { directory: "logs", level: "info" },
    limits: {
      maxConcurrentRequests: 1,
      maxQueuedRequests: 0,
      maxToolRounds: 2,
      maxContextMessages: 30,
      maxContextCharacters: 32_768,
      perPlayerCooldownSeconds: 0,
      dailyRequestsPerPlayer: 10,
      monthlyBudgetUsd: 1,
      providerRoundReservationMicroUsd: 1,
    },
    privacy: {
      storeConversations: false,
      retentionDays: 0,
      logMessageContent: false,
      logToolCalls: false,
    },
  };
}

function provider(
  generate: (request: ModelGenerationRequest, sequence: number) => Promise<ModelGenerationResult>,
): ModelProvider & { readonly requests: ModelGenerationRequest[] } {
  const requests: ModelGenerationRequest[] = [];
  return {
    requests,
    check: vi.fn(async () => ({ ok: true as const })),
    generate: vi.fn(async (request) => {
      requests.push(request);
      return generate(request, requests.length - 1);
    }),
  };
}

function successfulProvider(
  missingUsageSequence?: number,
): ModelProvider & { readonly requests: ModelGenerationRequest[] } {
  return provider(async (_request, sequence) => {
    if (sequence === 0) {
      return {
        type: "final",
        fallbackText: PROMPT_SECRET,
        ...(missingUsageSequence === sequence
          ? {}
          : { usage: { inputTokens: 4, outputTokens: 1 } }),
      };
    }
    if (sequence === 1) {
      return {
        type: "tool_call",
        providerCallId: CALL_ID,
        providerName: "agma_validation_echo",
        arguments: { value: "ready" },
        continuation: {
          provider: "openai-compatible",
          items: [{ role: "assistant", content: PROMPT_SECRET }],
        },
        ...(missingUsageSequence === sequence
          ? {}
          : { usage: { inputTokens: 7, outputTokens: 1 } }),
      };
    }
    return {
      type: "final",
      fallbackText: PROMPT_SECRET,
      ...(missingUsageSequence === sequence ? {} : { usage: { inputTokens: 8, outputTokens: 2 } }),
    };
  });
}

describe("live provider validation", () => {
  it("requires explicit billing confirmation before loading config or contacting a provider", async () => {
    const loadConfig = vi.fn();
    const createProvider = vi.fn();

    const report = await runLiveProviderCheck({
      confirmedBillable: false,
      loadConfig,
      createProvider,
    });

    expect(loadConfig).not.toHaveBeenCalled();
    expect(createProvider).not.toHaveBeenCalled();
    expect(formatLiveProviderCheckReport(report)).toEqual([
      "confirmation=FAIL code=BILLING_CONFIRMATION_REQUIRED",
      "result=FAIL code=BILLING_CONFIRMATION_REQUIRED",
    ]);
  });

  it("checks health, text, one function call, and its continuation without exposing values", async () => {
    const fakeProvider = successfulProvider();

    const report = await runLiveProviderCheck({
      confirmedBillable: true,
      config: config(),
      provider: fakeProvider,
    });
    const lines = formatLiveProviderCheckReport(report);

    expect(lines).toEqual([
      IDENTITY_LINE,
      "health=PASS",
      "text=PASS usage=REPORTED",
      "tool_call=PASS usage=REPORTED",
      "continuation=PASS usage=REPORTED",
      "result=PASS",
    ]);
    expect(fakeProvider.requests).toHaveLength(3);
    expect(fakeProvider.requests[0]).toMatchObject({ tools: [] });
    expect(fakeProvider.requests[1]?.tools).toEqual([
      expect.objectContaining({ providerName: "agma_validation_echo" }),
    ]);
    expect(fakeProvider.requests[2]).toMatchObject({
      continuation: { provider: "openai-compatible" },
      toolOutput: { providerCallId: CALL_ID, output: '{"accepted":true}' },
    });
    expect(fakeProvider.requests.every((request) => request.signal instanceof AbortSignal)).toBe(
      true,
    );

    const output = lines.join("\n");
    for (const secret of [
      API_KEY,
      MODEL,
      BASE_URL,
      PROMPT_SECRET,
      CALL_ID,
      SERVER_TOKEN,
      "ready",
    ]) {
      expect(output).not.toContain(secret);
    }
  });

  it.each([
    [0, "text", [IDENTITY_LINE, "health=PASS", "text=FAIL usage=MISSING code=USAGE_MISSING"]],
    [
      1,
      "tool_call",
      [
        IDENTITY_LINE,
        "health=PASS",
        "text=PASS usage=REPORTED",
        "tool_call=FAIL usage=MISSING code=USAGE_MISSING",
      ],
    ],
    [
      2,
      "continuation",
      [
        IDENTITY_LINE,
        "health=PASS",
        "text=PASS usage=REPORTED",
        "tool_call=PASS usage=REPORTED",
        "continuation=FAIL usage=MISSING code=USAGE_MISSING",
      ],
    ],
  ] as const)(
    "fails when generation sequence %i omits usage at %s",
    async (sequence, _step, prefix) => {
      const report = await runLiveProviderCheck({
        confirmedBillable: true,
        config: config(),
        provider: successfulProvider(sequence),
      });

      expect(formatLiveProviderCheckReport(report)).toEqual([
        ...prefix,
        "result=FAIL code=USAGE_MISSING",
      ]);
    },
  );

  it("stops at a health failure and emits only the provider failure code", async () => {
    const fakeProvider = successfulProvider();
    fakeProvider.check = vi.fn(async () => ({ ok: false, code: "PROVIDER_AUTH_FAILED" }));

    const report = await runLiveProviderCheck({
      confirmedBillable: true,
      config: config(),
      provider: fakeProvider,
    });

    expect(fakeProvider.requests).toHaveLength(0);
    expect(formatLiveProviderCheckReport(report)).toEqual([
      IDENTITY_LINE,
      "health=FAIL code=PROVIDER_AUTH_FAILED",
      "result=FAIL code=PROVIDER_AUTH_FAILED",
    ]);
  });

  it("maps config and unexpected provider errors to safe codes without their messages", async () => {
    const configFailure = await runLiveProviderCheck({
      confirmedBillable: true,
      loadConfig: async () => {
        throw new RuntimeStartupError({
          code: "API_KEY_MISSING",
          stage: "config",
          safeMessage: API_KEY,
        });
      },
    });
    const unsafeProvider = provider(async () => {
      throw new Error(`${API_KEY} ${MODEL} ${BASE_URL}`);
    });
    const providerFailure = await runLiveProviderCheck({
      confirmedBillable: true,
      config: config(),
      provider: unsafeProvider,
    });
    const invalidUsage = await runLiveProviderCheck({
      confirmedBillable: true,
      config: config(),
      provider: provider(async () => ({
        type: "final",
        fallbackText: PROMPT_SECRET,
        usage: { inputTokens: 0, outputTokens: 1 },
      })),
    });

    expect(formatLiveProviderCheckReport(configFailure)).toEqual([
      "config=FAIL code=API_KEY_MISSING",
      "result=FAIL code=API_KEY_MISSING",
    ]);
    expect(formatLiveProviderCheckReport(providerFailure)).toEqual([
      IDENTITY_LINE,
      "health=PASS",
      "text=FAIL code=VALIDATION_INTERNAL_ERROR",
      "result=FAIL code=VALIDATION_INTERNAL_ERROR",
    ]);
    expect(formatLiveProviderCheckReport(invalidUsage)).toEqual([
      IDENTITY_LINE,
      "health=PASS",
      "text=FAIL usage=INVALID code=USAGE_INVALID",
      "result=FAIL code=USAGE_INVALID",
    ]);
    expect(
      [
        ...formatLiveProviderCheckReport(configFailure),
        ...formatLiveProviderCheckReport(providerFailure),
        ...formatLiveProviderCheckReport(invalidUsage),
      ].join("\n"),
    ).not.toContain(API_KEY);
  });

  it("aborts a timed-out generation using the configured timeout", async () => {
    vi.useFakeTimers();
    try {
      let signal: AbortSignal | undefined;
      const fakeProvider = provider(async (request) => {
        signal = request.signal;
        return await new Promise<ModelGenerationResult>(() => undefined);
      });

      const result = runLiveProviderCheck({
        confirmedBillable: true,
        config: config(1),
        provider: fakeProvider,
      });
      await vi.advanceTimersByTimeAsync(1000);
      const report = await result;

      expect(signal?.aborted).toBe(true);
      expect(formatLiveProviderCheckReport(report)).toEqual([
        IDENTITY_LINE,
        "health=PASS",
        "text=FAIL code=PROVIDER_TIMEOUT",
        "result=FAIL code=PROVIDER_TIMEOUT",
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("runs the injectable CLI offline and rejects unknown arguments safely", async () => {
    const output: string[] = [];
    const loaded = {
      config: config(),
      paths: {
        configFile: "/private/config.local.yml",
        rootDirectory: "/private",
        sqlite: "/private/data/runtime.db",
        logDirectory: "/private/logs",
        knowledgeRoots: [],
      },
      warnings: [],
    };

    const exitCode = await runLiveProviderCheckCli({
      arguments: ["--confirm-billable", "--config", "/private/config.local.yml"],
      writeLine: (line) => output.push(line),
      loadConfig: vi.fn(async () => loaded),
      createProvider: vi.fn(() => successfulProvider()),
    });
    expect(exitCode).toBe(0);
    expect(output.at(-1)).toBe("result=PASS");

    output.length = 0;
    const createProvider = vi.fn(() => successfulProvider());
    const unsafeConfigExitCode = await runLiveProviderCheckCli({
      arguments: ["--confirm-billable", "--config", "/private/config.local.yml"],
      writeLine: (line) => output.push(line),
      loadConfig: vi.fn(async () => ({
        ...loaded,
        warnings: [{ code: "CONFIG_FILE_PERMISSIONS_WIDE" }] as const,
      })),
      createProvider,
    });
    expect(unsafeConfigExitCode).toBe(1);
    expect(createProvider).not.toHaveBeenCalled();
    expect(output).toEqual([
      "config=FAIL code=CONFIG_WARNING_UNSAFE",
      "result=FAIL code=CONFIG_WARNING_UNSAFE",
    ]);

    output.length = 0;
    const privateCreateProvider = vi.fn(() => successfulProvider());
    const unsafePrivacyExitCode = await runLiveProviderCheckCli({
      arguments: ["--confirm-billable", "--config", "/private/config.local.yml"],
      writeLine: (line) => output.push(line),
      loadConfig: vi.fn(async () => ({
        ...loaded,
        config: {
          ...loaded.config,
          privacy: { ...loaded.config.privacy, logToolCalls: true },
        },
      })),
      createProvider: privateCreateProvider,
    });
    expect(unsafePrivacyExitCode).toBe(1);
    expect(privateCreateProvider).not.toHaveBeenCalled();
    expect(output).toEqual([
      "config=FAIL code=CONFIG_PRIVACY_UNSAFE",
      "result=FAIL code=CONFIG_PRIVACY_UNSAFE",
    ]);

    output.length = 0;
    const invalidExitCode = await runLiveProviderCheckCli({
      arguments: ["--config"],
      writeLine: (line) => output.push(line),
    });
    expect(invalidExitCode).toBe(1);
    expect(output).toEqual([
      "confirmation=FAIL code=CLI_ARGUMENTS_INVALID",
      "result=FAIL code=CLI_ARGUMENTS_INVALID",
    ]);
  });
});
