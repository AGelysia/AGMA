import { parseStrictJson } from "../transport/strict-json.js";
import {
  type SearchProvider,
  SearchProviderError,
  type SearchProviderInput,
  type SearchProviderResponse,
  type SearchProviderResult,
} from "./search-provider.js";

export const BRAVE_WEB_SEARCH_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";
const BRAVE_RESPONSE_MAXIMUM_BYTES = 256 * 1024;
const BRAVE_TIMEOUT_MILLISECONDS = 5_000;

export interface BraveSearchProviderOptions {
  readonly apiKey: string;
  readonly fetch?: typeof fetch;
  readonly endpoint?: typeof BRAVE_WEB_SEARCH_ENDPOINT;
  readonly timeoutMilliseconds?: number;
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function readBoundedBody(response: Response): Promise<string> {
  const declared = response.headers.get("content-length");
  if (declared !== null && Number(declared) > BRAVE_RESPONSE_MAXIMUM_BYTES) {
    throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
  }
  if (response.body === null) throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.length;
      if (length > BRAVE_RESPONSE_MAXIMUM_BYTES) {
        throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (error) {
    throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false, error);
  }
}

function boundedString(value: unknown, maximum: number): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.normalize("NFKC").replace(/\s+/gu, " ").trim();
  return normalized.length >= 1 && normalized.length <= maximum ? normalized : undefined;
}

function parseResult(value: unknown): SearchProviderResult | undefined {
  if (!isRecord(value)) return undefined;
  const title = boundedString(value["title"], 512);
  const url = boundedString(value["url"], 2048);
  const description = boundedString(value["description"], 4096);
  if (title === undefined || url === undefined || description === undefined) return undefined;
  const ageValue = value["age"];
  const age = ageValue === undefined ? null : (boundedString(ageValue, 128) ?? null);
  return { title, url, description, age };
}

export class BraveSearchProvider implements SearchProvider {
  readonly #apiKey: string;
  readonly #fetch: typeof fetch;
  readonly #endpoint: typeof BRAVE_WEB_SEARCH_ENDPOINT;
  readonly #timeoutMilliseconds: number;

  public constructor(options: BraveSearchProviderOptions) {
    if (options.apiKey.trim().length === 0)
      throw new TypeError("A Brave Search API key is required.");
    this.#apiKey = options.apiKey;
    this.#fetch = options.fetch ?? fetch;
    this.#endpoint = options.endpoint ?? BRAVE_WEB_SEARCH_ENDPOINT;
    this.#timeoutMilliseconds = options.timeoutMilliseconds ?? BRAVE_TIMEOUT_MILLISECONDS;
    if (
      !Number.isSafeInteger(this.#timeoutMilliseconds) ||
      this.#timeoutMilliseconds < 1 ||
      this.#timeoutMilliseconds > 30_000
    ) {
      throw new TypeError("The Brave Search timeout is outside the supported range.");
    }
  }

  public async search(input: SearchProviderInput): Promise<SearchProviderResponse> {
    if (
      [...input.query].length < 1 ||
      [...input.query].length > 400 ||
      input.query.split(/\s+/u).length > 50 ||
      !Number.isSafeInteger(input.count) ||
      input.count < 1 ||
      input.count > 5
    ) {
      throw new TypeError("The Brave Search request is outside the controlled bounds.");
    }
    const url = new URL(this.#endpoint);
    url.searchParams.set("q", input.query);
    url.searchParams.set("count", String(input.count));
    url.searchParams.set("country", input.country);
    url.searchParams.set("search_lang", input.searchLanguage);
    url.searchParams.set("safesearch", "strict");
    url.searchParams.set("spellcheck", "false");

    const timeoutSignal = AbortSignal.timeout(this.#timeoutMilliseconds);
    const signal = AbortSignal.any([input.signal, timeoutSignal]);
    let response: Response;
    try {
      response = await this.#fetch(url, {
        method: "GET",
        redirect: "error",
        headers: {
          accept: "application/json",
          "x-subscription-token": this.#apiKey,
        },
        signal,
      });
    } catch (error) {
      if (signal.aborted) throw new SearchProviderError("SEARCH_TIMEOUT", true, error);
      throw new SearchProviderError("SEARCH_UNAVAILABLE", true, error);
    }
    if (response.status === 401 || response.status === 403) {
      throw new SearchProviderError("SEARCH_AUTHENTICATION_FAILED", false);
    }
    if (response.status === 429) throw new SearchProviderError("SEARCH_RATE_LIMITED", true);
    if (!response.ok) throw new SearchProviderError("SEARCH_UNAVAILABLE", response.status >= 500);
    if (response.headers.get("content-type")?.split(";", 1)[0]?.trim() !== "application/json") {
      throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
    }

    let document: unknown;
    try {
      document = parseStrictJson(await readBoundedBody(response));
    } catch (error) {
      if (error instanceof SearchProviderError) throw error;
      throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false, error);
    }
    if (!isRecord(document)) throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
    const web = document["web"];
    const rawResults = isRecord(web) ? web["results"] : undefined;
    if (rawResults === undefined) return { results: [] };
    if (!Array.isArray(rawResults) || rawResults.length > 20) {
      throw new SearchProviderError("SEARCH_RESPONSE_INVALID", false);
    }
    return {
      results: rawResults
        .flatMap((result) => {
          const parsed = parseResult(result);
          return parsed === undefined ? [] : [parsed];
        })
        .slice(0, input.count),
    };
  }
}
