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
  type FetchImplementation,
  MAXIMUM_PROVIDER_RESPONSE_BYTES,
  MAXIMUM_TOOL_ARGUMENT_CHARACTERS,
  PROVIDER_TOOL_NAME,
  readBoundedJson,
  serializeProviderRequest,
  strictToolArguments,
} from "./provider-http.js";

const DEEPSEEK_API_ROOT = "https://api.deepseek.com";
const MAXIMUM_CONTINUATION_ITEMS = 64;
type ChatProviderId = Extract<ModelProviderId, "deepseek" | "openai-compatible">;

export interface OpenAiChatCompletionsProviderOptions {
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
    content: z.string().max(8192).nullable(),
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
                content: z.string().max(8192).nullable(),
                tool_calls: z.array(chatToolCallSchema).max(2).optional(),
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

function healthFailure(status: number): ModelProviderHealthResult {
  if (status === 401 || status === 403) {
    return { ok: false, code: "PROVIDER_AUTH_FAILED" };
  }
  if (status === 404) {
    return { ok: false, code: "MODEL_UNAVAILABLE" };
  }
  if (status === 408 || status === 429 || status >= 500) {
    return { ok: false, code: "PROVIDER_UNAVAILABLE" };
  }
  return { ok: false, code: "MODEL_HEALTH_FAILED" };
}

function generationFailure(status: number): ModelGenerationError {
  if (status === 401 || status === 403) {
    return new ModelGenerationError("MODEL_AUTHENTICATION_FAILED");
  }
  if (status === 404) {
    return new ModelGenerationError("MODEL_UNAVAILABLE");
  }
  if (status === 429) {
    return new ModelGenerationError("MODEL_RATE_LIMITED");
  }
  if (status === 408 || status >= 500) {
    return new ModelGenerationError("PROVIDER_UNAVAILABLE");
  }
  return new ModelGenerationError("MODEL_RESPONSE_INVALID");
}

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
  const names = new Set<string>();
  return request.tools.map((tool) => {
    if (!PROVIDER_TOOL_NAME.test(tool.providerName) || names.has(tool.providerName)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    names.add(tool.providerName);
    return {
      type: "function",
      function: {
        name: tool.providerName,
        description: tool.description,
        parameters: tool.parameters,
      },
    };
  });
}

export class OpenAiChatCompletionsProvider implements ModelProvider {
  readonly #provider: ChatProviderId;
  readonly #baseUrl: string;
  readonly #fetch: FetchImplementation;

  public constructor(options: OpenAiChatCompletionsProviderOptions) {
    this.#provider = options.provider;
    this.#baseUrl =
      options.baseUrl ??
      (options.provider === "deepseek"
        ? DEEPSEEK_API_ROOT
        : (() => {
            throw new TypeError("The openai-compatible provider requires a base URL.");
          })());
    this.#fetch = options.fetch ?? globalThis.fetch;
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    if (request.provider !== this.#provider) {
      return { ok: false, code: "PROVIDER_UNSUPPORTED" };
    }

    let response: Response;
    try {
      response = await this.#fetch(appendEndpoint(this.#baseUrl, "models"), {
        method: "GET",
        headers: { Authorization: bearerAuthorization(request.apiKey) },
        redirect: "error",
        signal: request.signal,
      });
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
      ...(this.#provider === "deepseek" ? { thinking: { type: "disabled" } } : {}),
    });
    let response: Response;
    try {
      response = await this.#fetch(appendEndpoint(this.#baseUrl, "chat/completions"), {
        method: "POST",
        headers: {
          Authorization: bearerAuthorization(request.apiKey),
          "Content-Type": "application/json",
        },
        redirect: "error",
        signal: request.signal,
        body,
      });
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
    if (calls.length > 1 || (tools.length === 0 && calls.length !== 0)) {
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
        content: choice.message.content,
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

    if (choice.finish_reason !== "stop") {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    return {
      type: "final",
      fallbackText: boundedFallbackText(choice.message.content ?? ""),
      ...generationUsage,
    };
  }
}
