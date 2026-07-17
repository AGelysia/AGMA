import { z } from "zod";

import type {
  ModelProviderHealthRequest,
  ModelProviderHealthResult,
} from "../health/model-provider.js";
import {
  ModelGenerationError,
  type ModelGenerationAccountingDisposition,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
} from "./model-provider.js";
import {
  MAXIMUM_PROVIDER_RESPONSE_BYTES,
  PROVIDER_TOOL_NAME,
  appendEndpoint,
  boundedFallbackText,
  discardBody,
  type FetchImplementation,
  isRecord,
  MAXIMUM_TOOL_ARGUMENT_CHARACTERS,
  readBoundedJson,
  serializeProviderRequest,
  strictToolArguments,
} from "./provider-http.js";

const ANTHROPIC_API_ROOT = "https://api.anthropic.com/v1";
const ANTHROPIC_VERSION = "2023-06-01";
const MAXIMUM_CONTINUATION_ITEMS = 128;
const MAXIMUM_CONTENT_BLOCKS = 64;
const MAXIMUM_TEXT_CHARACTERS = 8192;

export interface AnthropicMessagesProviderOptions {
  readonly baseUrl?: string;
  readonly fetch?: FetchImplementation;
}

const healthResponseSchema = z.object({
  id: z.string().min(1).max(256),
});

const textBlockSchema = z
  .object({
    type: z.literal("text"),
    text: z.string().max(MAXIMUM_TEXT_CHARACTERS),
  })
  .strict();

const toolUseBlockSchema = z
  .object({
    type: z.literal("tool_use"),
    id: z.string().min(1).max(256),
    name: z.string().regex(PROVIDER_TOOL_NAME),
    input: z.custom<Readonly<Record<string, unknown>>>(isRecord),
  })
  .strict();

const assistantContentBlockSchema = z.union([textBlockSchema, toolUseBlockSchema]);

const assistantContinuationSchema = z
  .object({
    role: z.literal("assistant"),
    content: z.array(assistantContentBlockSchema).min(1).max(MAXIMUM_CONTENT_BLOCKS),
  })
  .strict();

const toolResultBlockSchema = z
  .object({
    type: z.literal("tool_result"),
    tool_use_id: z.string().min(1).max(256),
    content: z.string().min(1).max(MAXIMUM_PROVIDER_RESPONSE_BYTES),
  })
  .strict();

const toolResultContinuationSchema = z
  .object({
    role: z.literal("user"),
    content: z.tuple([toolResultBlockSchema]),
  })
  .strict();

const continuationItemSchema = z.union([assistantContinuationSchema, toolResultContinuationSchema]);

const providerResponseSchema = z.object({
  type: z.literal("message"),
  role: z.literal("assistant"),
  content: z.array(assistantContentBlockSchema).min(1).max(MAXIMUM_CONTENT_BLOCKS),
  stop_reason: z.enum([
    "end_turn",
    "max_tokens",
    "stop_sequence",
    "tool_use",
    "pause_turn",
    "refusal",
    "model_context_window_exceeded",
  ]),
  usage: z
    .object({
      input_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
      output_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    })
    .loose()
    .optional(),
});

type AssistantContinuation = z.infer<typeof assistantContinuationSchema>;
type ContinuationItem = z.infer<typeof continuationItemSchema>;

function anthropicHeaders(apiKey: string): Readonly<Record<string, string>> {
  return {
    "x-api-key": apiKey,
    "anthropic-version": ANTHROPIC_VERSION,
  };
}

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

function generationFailure(
  status: number,
  disposition: ModelGenerationAccountingDisposition,
): ModelGenerationError {
  if (status === 401 || status === 403) {
    return new ModelGenerationError("MODEL_AUTHENTICATION_FAILED", disposition);
  }
  if (status === 404) {
    return new ModelGenerationError("MODEL_UNAVAILABLE", disposition);
  }
  if (status === 429) {
    return new ModelGenerationError("MODEL_RATE_LIMITED", disposition);
  }
  if (status === 408 || status >= 500) {
    return new ModelGenerationError("PROVIDER_UNAVAILABLE", disposition);
  }
  return new ModelGenerationError("MODEL_RESPONSE_INVALID", disposition);
}

function providerTools(request: ModelGenerationRequest): readonly Record<string, unknown>[] {
  const names = new Set<string>();
  return request.tools.map((tool) => {
    if (
      !PROVIDER_TOOL_NAME.test(tool.providerName) ||
      names.has(tool.providerName) ||
      !isRecord(tool.parameters)
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    names.add(tool.providerName);
    return {
      name: tool.providerName,
      description: tool.description,
      input_schema: tool.parameters,
      strict: true,
    };
  });
}

function toolUse(message: AssistantContinuation): z.infer<typeof toolUseBlockSchema> | undefined {
  const calls = message.content.filter((block) => block.type === "tool_use");
  return calls.length === 1 ? calls[0] : undefined;
}

function strictFunctionArguments(
  input: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  let source: string;
  try {
    source = JSON.stringify(input);
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (source.length > MAXIMUM_TOOL_ARGUMENT_CHARACTERS) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return strictToolArguments(source);
}

function continuationItems(request: ModelGenerationRequest): readonly ContinuationItem[] {
  const continuation = request.continuation;
  const output = request.toolOutput;
  if ((continuation === undefined) !== (output === undefined)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  if (continuation === undefined || output === undefined) {
    return [];
  }
  if (
    continuation.provider !== "anthropic" ||
    continuation.items.length === 0 ||
    continuation.items.length > MAXIMUM_CONTINUATION_ITEMS ||
    continuation.items.length % 2 === 0 ||
    output.output.length === 0 ||
    Buffer.byteLength(output.output, "utf8") > MAXIMUM_PROVIDER_RESPONSE_BYTES
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
      const call = toolUse(result.data);
      if (call === undefined || callIds.has(call.id)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
      }
      callIds.add(call.id);
      precedingCallId = call.id;
    } else if (result.data.content[0].tool_use_id !== precedingCallId) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    parsed.push(result.data);
  }

  if (precedingCallId !== output.providerCallId) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }

  return [
    ...parsed,
    {
      role: "user",
      content: [
        {
          type: "tool_result",
          tool_use_id: output.providerCallId,
          content: output.output,
        },
      ],
    },
  ];
}

async function generationPayload(response: Response, signal: AbortSignal): Promise<unknown> {
  try {
    return await readBoundedJson(response, signal);
  } catch (error) {
    if (signal.aborted) {
      throw signal.reason;
    }
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw new ModelGenerationError("PROVIDER_UNAVAILABLE");
  }
}

export class AnthropicMessagesProvider implements ModelProvider {
  readonly #baseUrl: string;
  readonly #fetch: FetchImplementation;
  readonly #officialEndpoint: boolean;

  public constructor(options: AnthropicMessagesProviderOptions = {}) {
    this.#baseUrl = options.baseUrl ?? ANTHROPIC_API_ROOT;
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#officialEndpoint = options.baseUrl === undefined;
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    if (request.provider !== "anthropic") {
      return { ok: false, code: "PROVIDER_UNSUPPORTED" };
    }

    let response: Response;
    try {
      response = await this.#fetch(
        appendEndpoint(this.#baseUrl, `models/${encodeURIComponent(request.model)}`),
        {
          method: "GET",
          headers: anthropicHeaders(request.apiKey),
          redirect: "error",
          signal: request.signal,
        },
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
      const parsed = healthResponseSchema.safeParse(
        await readBoundedJson(response, request.signal),
      );
      return parsed.success ? { ok: true } : { ok: false, code: "MODEL_HEALTH_FAILED" };
    } catch {
      if (request.signal.aborted) {
        throw request.signal.reason;
      }
      return { ok: false, code: "MODEL_HEALTH_FAILED" };
    }
  }

  public async generate(request: ModelGenerationRequest): Promise<ModelGenerationResult> {
    if (request.provider !== "anthropic") {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }

    const tools = providerTools(request);
    const allowedToolNames = new Set(tools.map((tool) => String(tool["name"])));
    const priorItems = continuationItems(request);
    const toolConfiguration =
      tools.length === 0
        ? {}
        : {
            tools,
            tool_choice: { type: "auto", disable_parallel_tool_use: true },
          };
    const body = serializeProviderRequest({
      model: request.model,
      max_tokens: request.maxOutputTokens,
      system: request.instructions,
      messages: [...request.input, ...priorItems],
      ...toolConfiguration,
    });

    let response: Response;
    try {
      response = await this.#fetch(appendEndpoint(this.#baseUrl, "messages"), {
        method: "POST",
        headers: {
          ...anthropicHeaders(request.apiKey),
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
      throw generationFailure(
        response.status,
        this.#officialEndpoint ? "NOT_BILLABLE" : "BILLABILITY_UNKNOWN",
      );
    }

    const parsed = providerResponseSchema.safeParse(
      await generationPayload(response, request.signal),
    );
    if (!parsed.success) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const calls = parsed.data.content.filter((block) => block.type === "tool_use");
    if (
      calls.length > 1 ||
      (calls.length === 1) !== (parsed.data.stop_reason === "tool_use") ||
      parsed.data.stop_reason === "pause_turn" ||
      parsed.data.stop_reason === "max_tokens" ||
      parsed.data.stop_reason === "model_context_window_exceeded" ||
      (tools.length === 0 && calls.length !== 0)
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const usage = parsed.data.usage;
    const generationUsage =
      usage === undefined
        ? {}
        : {
            usage: {
              inputTokens: usage.input_tokens,
              outputTokens: usage.output_tokens,
            },
          };
    const call = calls[0];
    if (call !== undefined) {
      const reusesPriorCallId = priorItems.some(
        (item) =>
          item.role === "assistant" &&
          item.content.some((block) => block.type === "tool_use" && block.id === call.id),
      );
      if (!allowedToolNames.has(call.name) || reusesPriorCallId) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      return {
        type: "tool_call",
        providerCallId: call.id,
        providerName: call.name,
        arguments: strictFunctionArguments(call.input),
        continuation: {
          provider: "anthropic",
          items: [
            ...priorItems,
            {
              role: "assistant",
              content: parsed.data.content,
            },
          ],
        },
        ...generationUsage,
      };
    }

    return {
      type: "final",
      fallbackText: boundedFallbackText(
        parsed.data.content
          .filter((block) => block.type === "text")
          .map((block) => block.text)
          .join(""),
      ),
      ...generationUsage,
    };
  }
}
