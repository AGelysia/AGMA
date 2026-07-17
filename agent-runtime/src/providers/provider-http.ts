import { TextDecoder } from "node:util";

import { parseStrictJson } from "../transport/strict-json.js";
import { ModelGenerationError } from "./model-provider.js";

export const MAXIMUM_PROVIDER_RESPONSE_BYTES = 1024 * 1024;
export const MAXIMUM_FALLBACK_TEXT_LENGTH = 8192;
export const MAXIMUM_TOOL_ARGUMENT_CHARACTERS = 16 * 1024;
export const PROVIDER_TOOL_NAME = /^[A-Za-z0-9_-]{1,64}$/u;

export type FetchImplementation = (
  input: string | URL | globalThis.Request,
  init?: RequestInit,
) => Promise<Response>;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function bearerAuthorization(apiKey: string): string {
  return `Bearer ${apiKey}`;
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
