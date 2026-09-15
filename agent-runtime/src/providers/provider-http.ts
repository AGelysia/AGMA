import { TextDecoder } from "node:util";

import type { ModelProviderHealthResult } from "../health/model-provider.js";
import { parseStrictJson } from "../transport/strict-json.js";
import {
  ModelGenerationError,
  type ModelGenerationAccountingDisposition,
  type ModelGenerationRequest,
  type ModelToolDefinition,
} from "./model-provider.js";
import { isRecord } from "../shared/predicates.js";
export { isRecord };

export const MAXIMUM_PROVIDER_RESPONSE_BYTES = 1024 * 1024;
export const MAXIMUM_FALLBACK_TEXT_LENGTH = 8192;
export const MAXIMUM_TOOL_ARGUMENT_CHARACTERS = 16 * 1024;
export const PROVIDER_TOOL_NAME = /^[A-Za-z0-9_-]{1,64}$/u;
/**
 * Hard ceiling on the wall-clock delay a single retry may add to one provider
 * round. The caller's deadline is not visible here, so the retry budget stays
 * bounded by this cap instead of an unknown remaining request budget.
 */
export const MAXIMUM_PROVIDER_RETRY_DELAY_MILLISECONDS = 2000;
/** Per-round bound used when a provider is constructed without an explicit timeout. */
export const DEFAULT_PROVIDER_ROUND_TIMEOUT_MILLISECONDS = 120000;

export type FetchImplementation = (
  input: string | URL | globalThis.Request,
  init?: RequestInit,
) => Promise<Response>;

export function bearerAuthorization(apiKey: string): string {
  return `Bearer ${apiKey}`;
}

export interface ProviderHttpResilienceOptions {
  /** Bound applied to every individual HTTP round (fetch call). */
  readonly timeoutMilliseconds?: number;
  /** Ceiling for the jittered delay before the single retry; 0 disables retries. */
  readonly retryDelayMilliseconds?: number;
}

export interface ProviderHttpResilience {
  readonly timeoutMilliseconds: number;
  readonly retryDelayMilliseconds: number;
}

export function providerHttpResilience(
  options: ProviderHttpResilienceOptions = {},
): ProviderHttpResilience {
  const timeoutMilliseconds =
    options.timeoutMilliseconds ?? DEFAULT_PROVIDER_ROUND_TIMEOUT_MILLISECONDS;
  if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 1) {
    throw new TypeError("The provider round timeout must be a positive bounded integer.");
  }
  const retryDelayMilliseconds =
    options.retryDelayMilliseconds ?? MAXIMUM_PROVIDER_RETRY_DELAY_MILLISECONDS;
  if (!Number.isSafeInteger(retryDelayMilliseconds) || retryDelayMilliseconds < 0) {
    throw new TypeError("The provider retry delay must be a non-negative bounded integer.");
  }
  return { timeoutMilliseconds, retryDelayMilliseconds };
}

export interface ProviderFetchOptions {
  /** The caller's signal; cancellation always wins over retrying. */
  readonly signal: AbortSignal;
  readonly timeoutMilliseconds?: number;
  readonly retryDelayMilliseconds?: number;
}

function isTransientProviderStatus(status: number): boolean {
  return status === 429 || status === 502 || status === 503 || status === 504;
}

function retryDelayMilliseconds(cap: number): number {
  // Full jitter in [cap / 2, cap] keeps the single retry below the hard ceiling.
  return Math.round(cap * (0.5 + Math.random() / 2));
}

async function delayOrAbort(milliseconds: number, signal: AbortSignal): Promise<void> {
  await new Promise<void>((resolve) => {
    if (signal.aborted || milliseconds <= 0) {
      resolve();
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    const onAbort = (): void => {
      clearTimeout(timer);
      resolve();
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Performs one provider HTTP round with an independent timeout, retrying at
 * most once for transient outcomes (429/502/503/504 or a network-level fetch
 * failure that was not a cancellation). The retry delay is jittered and capped
 * so it cannot consume the caller's request budget, and the caller's signal
 * aborts the delay and is honored before every retry.
 */
export async function fetchProviderResponse(
  fetchImplementation: FetchImplementation,
  input: string | URL | globalThis.Request,
  init: RequestInit,
  options: ProviderFetchOptions,
): Promise<Response> {
  const timeoutMilliseconds =
    options.timeoutMilliseconds ?? DEFAULT_PROVIDER_ROUND_TIMEOUT_MILLISECONDS;
  const retryDelayCap = options.retryDelayMilliseconds ?? MAXIMUM_PROVIDER_RETRY_DELAY_MILLISECONDS;
  const firstRoundSignal = AbortSignal.any([
    options.signal,
    AbortSignal.timeout(timeoutMilliseconds),
  ]);

  let response: Response | undefined;
  let failure: unknown;
  try {
    response = await fetchImplementation(input, { ...init, signal: firstRoundSignal });
  } catch (error) {
    failure = error;
  }

  const transient =
    response !== undefined
      ? isTransientProviderStatus(response.status)
      : !options.signal.aborted && !firstRoundSignal.aborted;
  if (!transient || retryDelayCap <= 0 || options.signal.aborted) {
    if (response !== undefined) {
      return response;
    }
    throw failure;
  }

  if (response !== undefined) {
    await discardBody(response);
  }
  await delayOrAbort(retryDelayMilliseconds(retryDelayCap), options.signal);
  if (options.signal.aborted) {
    throw options.signal.reason;
  }

  const retryRoundSignal = AbortSignal.any([
    options.signal,
    AbortSignal.timeout(timeoutMilliseconds),
  ]);
  try {
    return await fetchImplementation(input, { ...init, signal: retryRoundSignal });
  } catch (error) {
    if (options.signal.aborted) {
      throw options.signal.reason;
    }
    throw error;
  }
}

export async function discardBody(response: Response): Promise<void> {
  await response.body?.cancel().catch(() => undefined);
}

export async function readBoundedJson(response: Response, signal?: AbortSignal): Promise<unknown> {
  if (response.body === null) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }

  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength !== null &&
    Number.isFinite(Number(declaredLength)) &&
    Number(declaredLength) > MAXIMUM_PROVIDER_RESPONSE_BYTES
  ) {
    await discardBody(response);
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) {
        break;
      }
      byteLength += result.value.byteLength;
      if (byteLength > MAXIMUM_PROVIDER_RESPONSE_BYTES) {
        await reader.cancel();
        throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
      }
      chunks.push(result.value);
    }
  } catch (error) {
    if (signal?.aborted === true) {
      throw signal.reason;
    }
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return parseStrictJson(source);
  } catch (error) {
    if (error instanceof ModelGenerationError) {
      throw error;
    }
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
}

export function boundedFallbackText(value: string): string {
  let text = value.trim();
  if (hasUnpairedSurrogate(text)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (text.length > MAXIMUM_FALLBACK_TEXT_LENGTH) {
    text = text.slice(0, MAXIMUM_FALLBACK_TEXT_LENGTH);
    const last = text.charCodeAt(text.length - 1);
    if (last >= 0xd800 && last <= 0xdbff) {
      text = text.slice(0, -1);
    }
    text = text.trimEnd();
  }
  if (text.length === 0) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return text;
}

export function strictToolArguments(source: string): Readonly<Record<string, unknown>> {
  let parsed: unknown;
  try {
    parsed = parseStrictJson(source, { maximumDepth: 16, maximumTokens: 2048 });
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (!isRecord(parsed)) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return parsed;
}

export function appendEndpoint(baseUrl: string, endpoint: string): string {
  return `${baseUrl.replace(/\/+$/u, "")}/${endpoint.replace(/^\/+/u, "")}`;
}

export function serializeProviderRequest(value: Readonly<Record<string, unknown>>): string {
  let source: string;
  try {
    source = JSON.stringify(value);
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  if (Buffer.byteLength(source, "utf8") > MAXIMUM_PROVIDER_RESPONSE_BYTES) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
  }
  return source;
}

export function strictFunctionArguments(
  value: Readonly<Record<string, unknown>>,
): Readonly<Record<string, unknown>> {
  let source: string;
  try {
    source = JSON.stringify(value);
  } catch {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  if (source.length > MAXIMUM_TOOL_ARGUMENT_CHARACTERS) {
    throw new ModelGenerationError("MODEL_RESPONSE_INVALID");
  }
  return strictToolArguments(source);
}

export function distinctProviderTools(
  request: ModelGenerationRequest,
): readonly ModelToolDefinition[] {
  const names = new Set<string>();
  return (request.tools ?? []).map((tool) => {
    if (!PROVIDER_TOOL_NAME.test(tool.providerName) || names.has(tool.providerName)) {
      throw new ModelGenerationError("MODEL_RESPONSE_INVALID", "NOT_BILLABLE");
    }
    names.add(tool.providerName);
    return tool;
  });
}

export function healthFailure(status: number): ModelProviderHealthResult {
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

export function generationFailure(
  status: number,
  disposition: ModelGenerationAccountingDisposition = "BILLABILITY_UNKNOWN",
): ModelGenerationError {
  if (status === 401 || status === 403) {
    return new ModelGenerationError("MODEL_AUTHENTICATION_FAILED", disposition);
  }
  if (status === 404) {
    return new ModelGenerationError("MODEL_NOT_FOUND", disposition);
  }
  if (status === 429) {
    return new ModelGenerationError("MODEL_RATE_LIMITED", disposition);
  }
  if (status === 408 || status >= 500) {
    return new ModelGenerationError("PROVIDER_UNAVAILABLE", disposition);
  }
  return new ModelGenerationError("MODEL_RESPONSE_INVALID", disposition);
}

function hasUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (index + 1 >= value.length || next < 0xdc00 || next > 0xdfff) {
        return true;
      }
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return true;
    }
  }
  return false;
}
