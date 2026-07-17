import { z } from "zod";

import type {
  ModelProviderHealthRequest,
  ModelProviderHealthResult,
} from "../health/model-provider.js";
import { parseStrictJson } from "../transport/strict-json.js";
import {
  ModelGenerationError,
  type ModelGenerationAccountingDisposition,
  type ModelGenerationRequest,
  type ModelGenerationResult,
  type ModelProvider,
} from "./model-provider.js";
import {
  appendEndpoint,
  boundedFallbackText,
  discardBody,
  type FetchImplementation,
  isRecord,
  MAXIMUM_PROVIDER_RESPONSE_BYTES,
  MAXIMUM_TOOL_ARGUMENT_CHARACTERS,
  PROVIDER_TOOL_NAME,
  readBoundedJson,
  serializeProviderRequest,
  strictToolArguments,
} from "./provider-http.js";

const GEMINI_API_ROOT = "https://generativelanguage.googleapis.com/v1beta";
const MAXIMUM_PROVIDER_IDENTIFIER_LENGTH = 256;
const MAXIMUM_CONTENT_PARTS = 64;
const MAXIMUM_CONTINUATION_ITEMS = 64;

export interface GeminiGenerateContentProviderOptions {
  readonly baseUrl?: string;
  readonly fetch?: FetchImplementation;
}

const healthResponseSchema = z
  .object({
    name: z.string().min(1).max(512),
    supportedGenerationMethods: z.array(z.string().min(1).max(128)).max(64),
  })
  .loose();

const functionCallSchema = z
  .object({
    id: z.string().min(1).max(MAXIMUM_PROVIDER_IDENTIFIER_LENGTH).optional(),
    name: z.string().regex(PROVIDER_TOOL_NAME),
    args: z.record(z.string(), z.unknown()).optional(),
  })
  .strict();

const textPartSchema = z
  .object({
    text: z.string().max(MAXIMUM_PROVIDER_RESPONSE_BYTES),
    thought: z.boolean().optional(),
    thoughtSignature: z.string().max(MAXIMUM_PROVIDER_RESPONSE_BYTES).optional(),
  })
  .strict();

const functionCallPartSchema = z
  .object({
    functionCall: functionCallSchema,
    thought: z.boolean().optional(),
    thoughtSignature: z.string().max(MAXIMUM_PROVIDER_RESPONSE_BYTES).optional(),
  })
  .strict();

const modelPartSchema = z.union([textPartSchema, functionCallPartSchema]);

const modelContentSchema = z
  .object({
    role: z.literal("model"),
    parts: z.array(modelPartSchema).min(1).max(MAXIMUM_CONTENT_PARTS),
  })
  .strict();

const functionResponseSchema = z
  .object({
    id: z.string().min(1).max(MAXIMUM_PROVIDER_IDENTIFIER_LENGTH).optional(),
    name: z.string().regex(PROVIDER_TOOL_NAME),
    response: z.record(z.string(), z.unknown()),
  })
  .strict();

const functionResponseContentSchema = z
  .object({
    role: z.literal("user"),
    parts: z
      .array(
        z
          .object({
            functionResponse: functionResponseSchema,
          })
          .strict(),
      )
      .length(1),
  })
  .strict();

const continuationContentSchema = z.union([modelContentSchema, functionResponseContentSchema]);

const safetyRatingSchema = z
  .object({
    blocked: z.boolean().optional(),
  })
  .loose();

const candidateSchema = z
  .object({
    content: modelContentSchema,
    finishReason: z.string().min(1).max(128),
    safetyRatings: z.array(safetyRatingSchema).max(64).optional(),
  })
  .loose();

const providerResponseSchema = z
  .object({
    candidates: z.array(candidateSchema).max(64),
    promptFeedback: z
      .object({
        blockReason: z.string().min(1).max(128).optional(),
        safetyRatings: z.array(safetyRatingSchema).max(64).optional(),
      })
      .loose()
      .optional(),
    usageMetadata: z
      .object({
        promptTokenCount: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
        candidatesTokenCount: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
        toolUsePromptTokenCount: z
          .number()
          .int()
          .nonnegative()
          .max(Number.MAX_SAFE_INTEGER)
          .optional(),
        thoughtsTokenCount: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),
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

function invalidRequest(): ModelGenerationError {
  return new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
}

interface SchemaConversionState {
  nodes: number;
  readonly ancestors: Set<object>;
}

function geminiSchemaValue(value: unknown, state: SchemaConversionState, depth = 0): unknown {
  state.nodes += 1;
  if (depth > 32 || state.nodes > 4096) {
    throw invalidRequest();
  }
  if (
    value === null ||
    typeof value === "string" ||
    (typeof value === "number" && Number.isFinite(value)) ||
    typeof value === "boolean"
  ) {
    return value;
  }
  if (typeof value !== "object") {
    throw invalidRequest();
  }
  if (state.ancestors.has(value)) {
    throw invalidRequest();
  }
  state.ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      return value.map((item) => geminiSchemaValue(item, state, depth + 1));
    }
    if (!isRecord(value)) {
      throw invalidRequest();
    }

    const transformed: Record<string, unknown> = Object.create(null) as Record<string, unknown>;
    for (const [key, child] of Object.entries(value)) {
      transformed[key] = geminiSchemaValue(child, state, depth + 1);
    }
    return transformed;
  } finally {
    state.ancestors.delete(value);
  }
}

function geminiToolSchema(
  schema: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  const converted = geminiSchemaValue(schema, { nodes: 0, ancestors: new Set() });
  if (!isRecord(converted)) {
    throw invalidRequest();
  }
  return converted;
}

function providerTools(request: ModelGenerationRequest): readonly Record<string, unknown>[] {
  const names = new Set<string>();
  const declarations = (request.tools ?? []).map((tool) => {
    if (!PROVIDER_TOOL_NAME.test(tool.providerName) || names.has(tool.providerName)) {
      throw invalidRequest();
    }
    names.add(tool.providerName);
    return {
      name: tool.providerName,
      description: tool.description,
      parametersJsonSchema: geminiToolSchema(tool.parameters),
    };
  });
  return declarations.length === 0 ? [] : [{ functionDeclarations: declarations }];
}

function parsedToolOutput(source: string): Readonly<Record<string, unknown>> {
  if (
    source.length > MAXIMUM_PROVIDER_RESPONSE_BYTES ||
    Buffer.byteLength(source, "utf8") > MAXIMUM_PROVIDER_RESPONSE_BYTES
  ) {
    throw invalidRequest();
  }
  try {
    const parsed = parseStrictJson(source, { maximumDepth: 16, maximumTokens: 2048 });
    if (!isRecord(parsed)) {
      throw invalidRequest();
    }
    return parsed;
  } catch (error) {
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw invalidRequest();
  }
}

function strictFunctionArguments(
  argumentsValue: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  let source: string;
  try {
    source = JSON.stringify(argumentsValue);
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (source.length > MAXIMUM_TOOL_ARGUMENT_CHARACTERS) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return strictToolArguments(source);
}

function tokenSum(...values: readonly number[]): number {
  const total = values.reduce((sum, value) => sum + value, 0);
  if (!Number.isSafeInteger(total)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return total;
}

function modelFunctionCall(
  content: z.infer<typeof modelContentSchema>,
): z.infer<typeof functionCallSchema> {
  const calls = content.parts.filter((part) => "functionCall" in part);
  if (calls.length !== 1) {
    throw invalidRequest();
  }
  const call = calls[0];
  if (call === undefined) {
    throw invalidRequest();
  }
  return call.functionCall;
}

function providerCallId(call: z.infer<typeof functionCallSchema>, sequence: number): string {
  return call.id ?? `gemini-call-${String(sequence)}-${call.name}`;
}

function continuationContents(
  request: ModelGenerationRequest,
): readonly Readonly<Record<string, unknown>>[] {
  const continuation = request.continuation;
  const toolOutput = request.toolOutput;
  if ((continuation === undefined) !== (toolOutput === undefined)) {
    throw invalidRequest();
  }
  if (continuation === undefined || toolOutput === undefined) {
    return [];
  }
  if (
    continuation.provider !== "gemini" ||
    continuation.items.length === 0 ||
    continuation.items.length > MAXIMUM_CONTINUATION_ITEMS
  ) {
    throw invalidRequest();
  }

  const parsedItems = continuation.items.map((item) => continuationContentSchema.safeParse(item));
  if (parsedItems.some((item) => !item.success)) {
    throw invalidRequest();
  }
  const contents = parsedItems.map((item) => {
    if (!item.success) {
      throw invalidRequest();
    }
    return item.data;
  });

  const callIds = new Set<string>();
  for (let index = 0; index < contents.length; index += 1) {
    const content = contents[index];
    if (content === undefined) {
      throw invalidRequest();
    }
    if (index % 2 === 0) {
      if (content.role !== "model") {
        throw invalidRequest();
      }
      const call = modelFunctionCall(content);
      const callId = providerCallId(call, Math.floor(index / 2));
      if (callIds.has(callId)) {
        throw invalidRequest();
      }
      callIds.add(callId);
      const responseContent = contents[index + 1];
      if (responseContent !== undefined) {
        if (responseContent.role !== "user") {
          throw invalidRequest();
        }
        const response = responseContent.parts[0]?.functionResponse;
        if (response === undefined || response.id !== call.id || response.name !== call.name) {
          throw invalidRequest();
        }
      }
    } else if (content.role !== "user") {
      throw invalidRequest();
    }
  }

  const pendingContent = contents.at(-1);
  if (pendingContent?.role !== "model") {
    throw invalidRequest();
  }
  const pendingCall = modelFunctionCall(pendingContent);
  const pendingSequence = Math.floor((contents.length - 1) / 2);
  if (providerCallId(pendingCall, pendingSequence) !== toolOutput.providerCallId) {
    throw invalidRequest();
  }

  return [
    ...contents,
    {
      role: "user",
      parts: [
        {
          functionResponse: {
            ...(pendingCall.id === undefined ? {} : { id: pendingCall.id }),
            name: pendingCall.name,
            response: parsedToolOutput(toolOutput.output),
          },
        },
      ],
    },
  ];
}

async function generationResponse(response: Response, signal: AbortSignal): Promise<unknown> {
  try {
    return await readBoundedJson(response, signal);
  } catch (error) {
    if (signal.aborted) {
      throw signal.reason;
    }
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
}

export class GeminiGenerateContentProvider implements ModelProvider {
  readonly #baseUrl: string;
  readonly #fetch: FetchImplementation;
  readonly #officialEndpoint: boolean;

  public constructor(options: GeminiGenerateContentProviderOptions = {}) {
    this.#baseUrl = options.baseUrl ?? GEMINI_API_ROOT;
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#officialEndpoint = options.baseUrl === undefined;
  }

  public async check(request: ModelProviderHealthRequest): Promise<ModelProviderHealthResult> {
    if (request.provider !== "gemini") {
      return { ok: false, code: "PROVIDER_UNSUPPORTED" };
    }

    let response: Response;
    try {
      response = await this.#fetch(
        appendEndpoint(this.#baseUrl, `models/${encodeURIComponent(request.model)}`),
        {
          method: "GET",
          headers: { "x-goog-api-key": request.apiKey },
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
      return parsed.success && parsed.data.supportedGenerationMethods.includes("generateContent")
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
    if (request.provider !== "gemini") {
      throw invalidRequest();
    }

    const priorContents = continuationContents(request);
    const tools = providerTools(request);
    const body = serializeProviderRequest({
      systemInstruction: { parts: [{ text: request.instructions }] },
      contents: [
        ...request.input.map((message) => ({
          role: message.role === "assistant" ? "model" : "user",
          parts: [{ text: message.content }],
        })),
        ...priorContents,
      ],
      generationConfig: { maxOutputTokens: request.maxOutputTokens },
      store: false,
      tools,
      ...(tools.length === 0 ? {} : { toolConfig: { functionCallingConfig: { mode: "AUTO" } } }),
    });

    let response: Response;
    try {
      response = await this.#fetch(
        appendEndpoint(
          this.#baseUrl,
          `models/${encodeURIComponent(request.model)}:generateContent`,
        ),
        {
          method: "POST",
          headers: {
            "x-goog-api-key": request.apiKey,
            "Content-Type": "application/json",
          },
          redirect: "error",
          signal: request.signal,
          body,
        },
      );
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
      await generationResponse(response, request.signal),
    );
    if (!parsed.success || parsed.data.candidates.length !== 1) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    if (
      parsed.data.promptFeedback?.blockReason !== undefined &&
      parsed.data.promptFeedback.blockReason !== "BLOCK_REASON_UNSPECIFIED"
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    if (
      parsed.data.promptFeedback?.safetyRatings?.some((rating) => rating.blocked === true) === true
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const candidate = parsed.data.candidates[0];
    if (
      candidate === undefined ||
      candidate.finishReason !== "STOP" ||
      candidate.safetyRatings?.some((rating) => rating.blocked === true) === true
    ) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }

    const calls = candidate.content.parts.filter((part) => "functionCall" in part);
    if (calls.length > 1 || (tools.length === 0 && calls.length !== 0)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
    }
    const usage = parsed.data.usageMetadata;
    const generationUsage =
      usage === undefined
        ? {}
        : {
            usage: {
              inputTokens: tokenSum(usage.promptTokenCount, usage.toolUsePromptTokenCount ?? 0),
              outputTokens: tokenSum(usage.candidatesTokenCount, usage.thoughtsTokenCount ?? 0),
            },
          };

    const call = calls[0]?.functionCall;
    if (call !== undefined) {
      const allowedNames = new Set((request.tools ?? []).map((tool) => tool.providerName));
      if (!allowedNames.has(call.name)) {
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      return {
        type: "tool_call",
        providerCallId: providerCallId(
          call,
          priorContents.filter((content) => content["role"] === "model").length,
        ),
        providerName: call.name,
        arguments: strictFunctionArguments(call.args ?? {}),
        continuation: {
          provider: "gemini",
          items: [...priorContents, candidate.content],
        },
        ...generationUsage,
      };
    }

    const fallbackText = boundedFallbackText(
      candidate.content.parts
        .filter((part) => "text" in part && part.thought !== true)
        .map((part) => ("text" in part ? part.text : ""))
        .join(""),
    );
    return {
      type: "final",
      fallbackText,
      ...generationUsage,
    };
  }
}
