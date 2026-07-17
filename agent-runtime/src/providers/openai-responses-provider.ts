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

const OPENAI_API_ROOT = "https://api.openai.com/v1";
const MAXIMUM_CONTINUATION_ITEMS = 1024;

export interface OpenAiResponsesProviderOptions {
  readonly fetch?: FetchImplementation;
  readonly baseUrl?: string;
}

const healthResponseSchema = z
  .object({
    id: z.string().min(1).max(256),
  })
  .loose();

const assistantMessageSchema = z
  .object({
    id: z.string().min(1).max(256).optional(),
    type: z.literal("message"),
    role: z.literal("assistant"),
    content: z
      .array(
        z
          .object({
            type: z.string().min(1).max(64),
            text: z.string().max(8192).optional(),
          })
          .loose(),
      )
      .max(64),
  })
  .loose();

const functionCallSchema = z
  .object({
    id: z.string().min(1).max(256).optional(),
    type: z.literal("function_call"),
    call_id: z.string().min(1).max(256),
    name: z.string().regex(PROVIDER_TOOL_NAME),
    arguments: z.string().max(MAXIMUM_TOOL_ARGUMENT_CHARACTERS),
    status: z.enum(["in_progress", "completed", "incomplete"]).optional(),
  })
  .loose();

const reasoningItemSchema = z
  .object({
    id: z.string().min(1).max(256),
    type: z.literal("reasoning"),
    encrypted_content: z.string().max(MAXIMUM_PROVIDER_RESPONSE_BYTES).optional(),
    summary: z
      .array(z.object({ type: z.literal("summary_text"), text: z.string().max(8192) }).strict())
      .max(16),
  })
  .loose();

const functionCallOutputSchema = z
  .object({
    type: z.literal("function_call_output"),
    call_id: z.string().min(1).max(256),
    output: z.string().min(1).max(MAXIMUM_PROVIDER_RESPONSE_BYTES),
  })
  .strict();

const continuationItemSchema = z.union([
  assistantMessageSchema,
  functionCallSchema,
  functionCallOutputSchema,
  reasoningItemSchema,
]);

const providerResponseSchema = z
  .object({
    status: z.literal("completed"),
    output: z
      .array(z.union([assistantMessageSchema, functionCallSchema, reasoningItemSchema]))
      .max(64),
    usage: z
      .object({
        input_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
        output_tokens: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
      })
      .loose()
      .optional(),
  })
  .loose();

function continuationItems(
  request: ModelGenerationRequest,
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
    continuation.provider !== "openai" ||
    continuation.items.length === 0 ||
    continuation.items.length > MAXIMUM_CONTINUATION_ITEMS ||
    toolOutput.output.length === 0 ||
    Buffer.byteLength(toolOutput.output, "utf8") > MAXIMUM_PROVIDER_RESPONSE_BYTES
  ) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }

  const parsedItems = continuation.items.map((item) => continuationItemSchema.safeParse(item));
  if (parsedItems.some((item) => !item.success)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  const items = parsedItems.map((item) => {
    if (!item.success) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    return item.data;
  });
  const calls = items.filter((item) => item.type === "function_call");
  const outputs = items.filter((item) => item.type === "function_call_output");
  const callIds = new Set(calls.map((call) => call.call_id));
  const outputIds = new Set(outputs.map((output) => output.call_id));
  const pending = calls.at(-1);
  if (
    pending?.call_id !== toolOutput.providerCallId ||
    callIds.size !== calls.length ||
    outputIds.size !== outputs.length ||
    calls.some((call) => call.status !== undefined && call.status !== "completed") ||
    outputs.some((output) => !callIds.has(output.call_id)) ||
    calls.some((call, index) =>
      index === calls.length - 1 ? outputIds.has(call.call_id) : !outputIds.has(call.call_id),
    )
  ) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  return [
    ...items,
    {
      type: "function_call_output",
      call_id: toolOutput.providerCallId,
      output: toolOutput.output,
    },
  ];
}

function providerTools(request: ModelGenerationRequest): readonly Record<string, unknown>[] {
  const names = new Set<string>();
  return (request.tools ?? []).map((tool) => {
    if (!PROVIDER_TOOL_NAME.test(tool.providerName) || names.has(tool.providerName)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    names.add(tool.providerName);
    return {
      type: "function",
      name: tool.providerName,
      description: tool.description,
      parameters: tool.parameters,
      strict: true,
    };
  });
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

export class OpenAiResponsesProvider implements ModelProvider {
  readonly #fetch: FetchImplementation;
  readonly #baseUrl: string;
  readonly #officialEndpoint: boolean;

  public constructor(options: OpenAiResponsesProviderOptions = {}) {
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#baseUrl = options.baseUrl ?? OPENAI_API_ROOT;
    this.#officialEndpoint = options.baseUrl === undefined;
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    if (request.provider !== "openai") {
      return { ok: false, code: "PROVIDER_UNSUPPORTED" };
    }
    let response: Response;
    try {
      response = await this.#fetch(
        appendEndpoint(this.#baseUrl, `models/${encodeURIComponent(request.model)}`),
        {
          method: "GET",
          headers: { Authorization: bearerAuthorization(request.apiKey) },
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
      return parsed.success && parsed.data.id === request.model
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
    if (request.provider !== "openai") {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    let response: Response;
    const priorItems = continuationItems(request);
    const tools = providerTools(request);
    const body = serializeProviderRequest({
      model: request.model,
      instructions: request.instructions,
      input: [...request.input, ...priorItems],
      max_output_tokens: request.maxOutputTokens,
      store: false,
      tools,
      tool_choice: tools.length === 0 ? "none" : "auto",
      parallel_tool_calls: false,
      include: ["reasoning.encrypted_content"],
    });
    try {
      response = await this.#fetch(appendEndpoint(this.#baseUrl, "responses"), {
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
      throw generationFailure(
        response.status,
        this.#officialEndpoint ? "NOT_BILLABLE" : "BILLABILITY_UNKNOWN",
      );
    }

    const parsed = providerResponseSchema.safeParse(
      await readBoundedJson(response, request.signal),
    );
    if (!parsed.success) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const calls = parsed.data.output.filter((item) => item.type === "function_call");
    if (calls.length > 1 || (tools.length === 0 && calls.length !== 0)) {
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
      if (call.status !== undefined && call.status !== "completed") {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const allowedNames = new Set(tools.map((tool) => String(tool["name"])));
      if (!allowedNames.has(call.name)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      const currentContinuation = parsed.data.output.map((item) => {
        if (item.type === "function_call") {
          return {
            ...(item.id === undefined ? {} : { id: item.id }),
            type: item.type,
            call_id: item.call_id,
            name: item.name,
            arguments: item.arguments,
            ...(item.status === undefined ? {} : { status: item.status }),
          };
        }
        if (item.type === "reasoning") {
          return {
            id: item.id,
            type: item.type,
            summary: item.summary,
            ...(item.encrypted_content === undefined
              ? {}
              : { encrypted_content: item.encrypted_content }),
          };
        }
        return {
          ...(item.id === undefined ? {} : { id: item.id }),
          type: item.type,
          role: item.role,
          content: item.content.map((content) => ({
            type: content.type,
            ...(content.text === undefined ? {} : { text: content.text }),
          })),
        };
      });
      return {
        type: "tool_call",
        providerCallId: call.call_id,
        providerName: call.name,
        arguments: strictToolArguments(call.arguments),
        continuation: {
          provider: "openai",
          items: [...priorItems, ...currentContinuation],
        },
        ...generationUsage,
      };
    }

    const fallbackText = boundedFallbackText(
      parsed.data.output
        .flatMap((item) => (item.type === "message" ? item.content : []))
        .filter((content) => content.type === "output_text")
        .map((content) => content.text ?? "")
        .join(""),
    );
    return {
      type: "final",
      fallbackText,
      ...generationUsage,
    };
  }
}
