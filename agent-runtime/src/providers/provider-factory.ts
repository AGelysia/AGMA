import type { RuntimeConfig } from "../config/runtime-config.js";
import { AnthropicMessagesProvider } from "./anthropic-messages-provider.js";
import { GeminiGenerateContentProvider } from "./gemini-generate-content-provider.js";
import type { ModelProvider } from "./model-provider.js";
import { OpenAiChatCompletionsProvider } from "./openai-chat-completions-provider.js";
import { OpenAiResponsesProvider } from "./openai-responses-provider.js";

export function createProductionModelProvider(model: RuntimeConfig["model"]): ModelProvider {
  const endpoint = model.baseUrl === undefined ? {} : { baseUrl: model.baseUrl };
  switch (model.provider) {
    case "openai":
      return new OpenAiResponsesProvider(endpoint);
    case "anthropic":
      return new AnthropicMessagesProvider(endpoint);
    case "deepseek":
      return new OpenAiChatCompletionsProvider({
        provider: "deepseek",
        ...endpoint,
      });
    case "gemini":
      return new GeminiGenerateContentProvider(endpoint);
    case "openai-compatible":
      if (model.baseUrl === undefined) {
        throw new TypeError("The openai-compatible provider requires a base URL.");
      }
      return new OpenAiChatCompletionsProvider({
        provider: "openai-compatible",
        baseUrl: model.baseUrl,
      });
  }
}
