import { describe, expect, it } from "vitest";

import type { RuntimeConfig } from "../src/config/runtime-config.js";
import { AnthropicMessagesProvider } from "../src/providers/anthropic-messages-provider.js";
import { GeminiGenerateContentProvider } from "../src/providers/gemini-generate-content-provider.js";
import { OpenAiChatCompletionsProvider } from "../src/providers/openai-chat-completions-provider.js";
import { OpenAiResponsesProvider } from "../src/providers/openai-responses-provider.js";
import { createProductionModelProvider } from "../src/providers/provider-factory.js";

function model(
  provider: RuntimeConfig["model"]["provider"],
  baseUrl?: string,
): RuntimeConfig["model"] {
  return {
    provider,
    ...(baseUrl === undefined ? {} : { baseUrl }),
    apiKey: "test-key-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    model: "test-model",
    timeoutSeconds: 30,
    inputMicroUsdPerMillionTokens: 1_000_000,
    outputMicroUsdPerMillionTokens: 4_000_000,
  };
}

describe("production model provider factory", () => {
  it.each([
    ["openai", OpenAiResponsesProvider],
    ["anthropic", AnthropicMessagesProvider],
    ["deepseek", OpenAiChatCompletionsProvider],
    ["gemini", GeminiGenerateContentProvider],
    ["openai-compatible", OpenAiChatCompletionsProvider],
  ] as const)("selects the %s production adapter", (provider, expectedType) => {
    const configured = model(
      provider,
      provider === "openai-compatible" ? "https://models.example.test/v1" : undefined,
    );

    expect(createProductionModelProvider(configured)).toBeInstanceOf(expectedType);
  });

  it("fails closed if an unvalidated compatible config reaches the factory", () => {
    expect(() => createProductionModelProvider(model("openai-compatible"))).toThrow(TypeError);
  });
});
