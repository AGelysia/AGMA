import { z } from "zod";

import type {
  ModelProviderHealthRequest,
  ModelProviderHealthResult,
} from "../health/model-provider.js";
import {
  ModelGenerationError,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
  type ModelProviderId,
} from "./model-provider.js";
import {
  appendEndpoint,
  bearerAuthorization,
  boundedFallbackText,
  discardBody,
  distinctProviderTools,
  type FetchImplementation,
  fetchProviderResponse,
  generationFailure,
  healthFailure,
  MAXIMUM_PROVIDER_RESPONSE_BYTES,
  MAXIMUM_TOOL_ARGUMENT_CHARACTERS,
  PROVIDER_TOOL_NAME,
  type ProviderHttpResilience,
  providerHttpResilience,
  type ProviderHttpResilienceOptions,
  readBoundedJson,
  serializeProviderRequest,
  strictToolArguments,
} from "./provider-http.js";

const DEEPSEEK_API_ROOT = "https://api.deepseek.com";
const KIMI_API_ROOT = "https://api.moonshot.cn";
const GLM_API_ROOT = "https://open.bigmodel.cn/api/paas/v4";
const MAXIMUM_CONTINUATION_ITEMS = 64;
// Long answers are truncated to MAXIMUM_FALLBACK_TEXT_LENGTH downstream, so
// the schema only rejects responses that are unreasonably large overall.
const MAXIMUM_ASSISTANT_MESSAGE_CHARACTERS = 32768;
type ChatProviderId = Extract<ModelProviderId, "deepseek" | "kimi" | "glm" | "openai-compatible">;

const PROVIDER_API_ROOTS: Readonly<Record<Exclude<ChatProviderId, "openai-compatible">, string>> = {
  deepseek: DEEPSEEK_API_ROOT,
  kimi: KIMI_API_ROOT,
  glm: GLM_API_ROOT,
};

export interface OpenAiChatCompletionsProviderOptions extends ProviderHttpResilienceOptions {
  readonly provider: ChatProviderId;
  readonly baseUrl?: string;
  readonly fetch?: FetchImplementation;
}

const modelListSchema = z
  .object({
    data: z
      .array(
        z
          .object({
            id: z.string().min(1).max(256),
          })
          .loose(),
      )
      .max(4096),
  })
  .loose();

const chatToolCallSchema = z
  .object({
    id: z.string().min(1).max(256),
    type: z.literal("function"),
    function: z
      .object({
        name: z.string().regex(PROVIDER_TOOL_NAME),
        arguments: z.string().max(MAXIMUM_TOOL_ARGUMENT_CHARACTERS),
      })
      .loose(),
  })
  .loose();

const assistantContinuationSchema = z
  .object({
    role: z.literal("assistant"),
    content: z.string().max(MAXIMUM_ASSISTANT_MESSAGE_CHARACTERS).nullable(),
    tool_calls: z.array(chatToolCallSchema).length(1),
  })
  .strict();

const toolContinuationSchema = z
  .object({
    role: z.literal("tool"),
    tool_call_id: z.string().min(1).max(256),
    content: z.string().min(1).max(MAXIMUM_PROVIDER_RESPONSE_BYTES),
  })
  .strict();

const continuationItemSchema = z.union([assistantContinuationSchema, toolContinuationSchema]);
type ContinuationItem = z.infer<typeof continuationItemSchema>;

const providerResponseSchema = z
  .object({
    choices: z
      .array(
        z
          .object({
            index: z.number().int().nonnegative(),
            finish_reason: z.string().min(1).max(64),
            message: z
              .object({
                role: z.literal("assistant"),
                content: z.string().max(MAXIMUM_ASSISTANT_MESSAGE_CHARACTERS).nullish(),
                // Endpoints may fan out parallel calls even when asked not to;
                // only the first call is processed and the rest are dropped.
                tool_calls: z.array(chatToolCallSchema).max(8).optional(),
              })
              .loose(),
          })
          .loose(),
      )
      .max(4),
    usage: z
      .object({
        prompt_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
        completion_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
      })
      .loose()
      .optional(),
  })
  .loose();

function continuationItems(
  request: ModelGenerationRequest,
  provider: ChatProviderId,
): readonly Readonly<Record<string, unknown>>[] {
  const continuation = request.continuation;
  const toolOutput = request.toolOutput;
  if ((continuation === undefined) !== (toolOutput === undefined)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  if (continuation === undefined || toolOutput === undefined) {
    return [];
  }
  if (
    continuation.provider !== provider ||
    continuation.items.length === 0 ||
    continuation.items.length > MAXIMUM_CONTINUATION_ITEMS ||
    continuation.items.length % 2 === 0 ||
    toolOutput.output.length === 0 ||
    Buffer.byteLength(toolOutput.output, "utf8") > MAXIMUM_PROVIDER_RESPONSE_BYTES
  ) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }

  const parsed: ContinuationItem[] = [];
  const callIds = new Set<string>();
  let precedingCallId: string | undefined;
  for (let index = 0; index < continuation.items.length; index += 1) {
    const result = continuationItemSchema.safeParse(continuation.items[index]);
    if (!result.success || (index % 2 === 0) !== (result.data.role === "assistant")) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    if (result.data.role === "assistant") {
      const call = result.data.tool_calls[0];
      if (call === undefined || callIds.has(call.id)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
      }
      callIds.add(call.id);
      precedingCallId = call.id;
    } else if (result.data.tool_call_id !== precedingCallId) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    parsed.push(result.data);
  }
  if (precedingCallId !== toolOutput.providerCallId) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }

  return [
    ...parsed,
    {
      role: "tool",
      tool_call_id: toolOutput.providerCallId,
      content: toolOutput.output,
    },
  ];
}

function providerTools(request: ModelGenerationRequest): readonly Record<string, unknown>[] {
  return distinctProviderTools(request).map((tool) => ({
    type: "function",
    function: {
      name: tool.providerName,
      description: tool.description,
      parameters: tool.parameters,
    },
  }));
}

export class OpenAiChatCompletionsProvider implements ModelProvider {
  readonly #provider: ChatProviderId;
  readonly #baseUrl: string;
  readonly #fetch: FetchImplementation;
  readonly #resilience: ProviderHttpResilience;

  public constructor(options: OpenAiChatCompletionsProviderOptions) {
    this.#provider = options.provider;
    this.#baseUrl =
      options.baseUrl ??
      (options.provider === "openai-compatible"
        ? (() => {
            throw new TypeError("The openai-compatible provider requires a base URL.");
          })()
        : PROVIDER_API_ROOTS[options.provider]);
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#resilience = providerHttpResilience(options);
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    if (request.provider !== this.#provider) {
      return { ok: false, code: "PROVIDER_UNSUPPORTED" };
    }

    let response: Response;
    try {
      response = await fetchProviderResponse(
        this.#fetch,
        appendEndpoint(this.#baseUrl, "models"),
        {
          method: "GET",
          headers: { Authorization: bearerAuthorization(request.apiKey) },
          redirect: "error",
        },
        { signal: request.signal, ...this.#resilience },
      );
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      return { ok: false, code: "PROVIDER_UNAVAILABLE" };
    }

    if (!response.ok) {
      await discardBody(response);
      return healthFailure(response.status);
    }
    try {
      const parsed = modelListSchema.safeParse(await readBoundedJson(response, request.signal));
      return parsed.success && parsed.data.data.some((model) => model.id === request.model)
        ? { ok: true }
        : { ok: false, code: "MODEL_HEALTH_FAILED" };
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      return { ok: false, code: "MODEL_HEALTH_FAILED" };
    }
  }

  public async generate(request: ModelGenerationRequest): Promise<ModelGenerationResult> {
    if (request.provider !== this.#provider) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    const priorItems = continuationItems(request, this.#provider);
    const tools = providerTools(request);
    const body = serializeProviderRequest({
      model: request.model,
      messages: [
        { role: "system", content: request.instructions },
        ...request.input,
        ...priorItems,
      ],
      max_tokens: request.maxOutputTokens,
      stream: false,
      tools,
      tool_choice: tools.length === 0 ? "none" : "auto",
      parallel_tool_calls: false,
      // GLM-4.5/4.6 default to thinking mode, which burns max_tokens on
      // reasoning and can return an empty content truncated by length.
      ...(this.#provider === "deepseek" || this.#provider === "glm"
        ? { thinking: { type: "disabled" } }
        : {}),
    });
    let response: Response;
    try {
      response = await fetchProviderResponse(
        this.#fetch,
        appendEndpoint(this.#baseUrl, "chat/completions"),
        {
          method: "POST",
          headers: {
            Authorization: bearerAuthorization(request.apiKey),
            "Content-Type": "application/json",
          },
          redirect: "error",
          body,
        },
        { signal: request.signal, ...this.#resilience },
      );
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      throw new ModelGenerationError("PROVIDER_UNAVAILABLE");
    }

    if (!response.ok) {
      await discardBody(response);
      throw generationFailure(response.status);
    }
    const parsed = providerResponseSchema.safeParse(
      await readBoundedJson(response, request.signal),
    );
    if (
      !parsed.success ||
      parsed.data.choices.length !== 1 ||
      parsed.data.choices[0]?.index !== 0
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const choice = parsed.data.choices[0];
    const calls = choice.message.tool_calls ?? [];
    if (tools.length === 0 && calls.length !== 0) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    const usage = parsed.data.usage;
    const generationUsage =
      usage === undefined
        ? {}
        : {
            usage: {
              inputTokens: usage.prompt_tokens,
              outputTokens: usage.completion_tokens,
            },
          };
    const call = calls[0];
    if (call !== undefined) {
      if (choice.finish_reason !== "tool_calls") {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const allowedNames = new Set(
        tools.map((tool) => String((tool["function"] as Record<string, unknown>)["name"])),
      );
      if (!allowedNames.has(call.function.name)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const currentAssistant = {
        role: "assistant",
        content: choice.message.content ?? null,
        tool_calls: [
          {
            id: call.id,
            type: "function",
            function: {
              name: call.function.name,
              arguments: call.function.arguments,
            },
          },
        ],
      } as const;
      return {
        type: "tool_call",
        providerCallId: call.id,
        providerName: call.function.name,
        arguments: strictToolArguments(call.function.arguments),
        continuation: {
          provider: this.#provider,
          items: [...priorItems, currentAssistant],
        },
        ...generationUsage,
      };
    }

    if (choice.finish_reason !== "stop" && choice.finish_reason !== "length") {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    // A plain-text answer truncated by max_tokens is still usable fallback text;
    // only tool calls are unsafe to accept when truncated (see above).
    const content = choice.message.content ?? "";
    if (content.trim().length === 0 && choice.finish_reason === "length") {
      // All output tokens went to reasoning (or nowhere), so retrying the same
      // request would truncate identically; surface an actionable error instead.
      throw new ModelGenerationError("MODEL_OUTPUT_TRUNCATED");
    }
    return {
      type: "final",
      fallbackText: boundedFallbackText(content),
      ...generationUsage,
    };
  }
}
