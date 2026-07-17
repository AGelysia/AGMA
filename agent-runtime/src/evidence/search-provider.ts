export const webAuthorizationModes = ["off", "once", "persistent"] as const;

export type WebAuthorization = (typeof webAuthorizationModes)[number];

export interface WebTargetContext {
  readonly id?: string;
  readonly displayName?: string;
  readonly modId?: string;
  readonly modVersion?: string;
}

export interface WebModPackContext {
  readonly name: string;
  readonly version: string | null;
}

export interface WebSearchContext {
  readonly minecraftVersion: string;
  readonly target?: WebTargetContext;
  readonly modPack?: WebModPackContext;
}

export interface SearchProviderInput {
  readonly query: string;
  readonly count: number;
  readonly country: string;
  readonly searchLanguage: string;
  readonly signal: AbortSignal;
}

export interface SearchProviderResult {
  readonly url: string;
  readonly title: string;
  readonly description: string;
  readonly age: string | null;
}

export interface SearchProviderResponse {
  readonly results: readonly SearchProviderResult[];
}

export interface SearchProvider {
  search(input: SearchProviderInput): Promise<SearchProviderResponse>;
}

export type SearchProviderFailureCode =
  | "SEARCH_AUTHENTICATION_FAILED"
  | "SEARCH_RATE_LIMITED"
  | "SEARCH_TIMEOUT"
  | "SEARCH_RESPONSE_INVALID"
  | "SEARCH_UNAVAILABLE";

export class SearchProviderError extends Error {
  public readonly code: SearchProviderFailureCode;
  public readonly retryable: boolean;

  public constructor(code: SearchProviderFailureCode, retryable: boolean, cause?: unknown) {
    super(code, { cause });
    this.name = "SearchProviderError";
    this.code = code;
    this.retryable = retryable;
  }
}

function cleanQueryPart(value: string): string {
  return value
    .normalize("NFKC")
    .replace(/https?:\/\/\S+/giu, " ")
    .replace(/\p{Cc}/gu, " ")
    .replace(/\s+/gu, " ")
    .trim();
}

export function buildControlledSearchQuery(question: string, context: WebSearchContext): string {
  const parts = [
    context.target?.id,
    context.target?.displayName,
    context.target?.modId,
    context.target?.modVersion,
    context.modPack?.name,
    context.modPack?.version ?? undefined,
    `Minecraft ${context.minecraftVersion}`,
    question,
  ]
    .filter((part): part is string => part !== undefined)
    .map(cleanQueryPart)
    .filter((part) => part.length > 0);
  const words = parts.join(" ").split(/\s+/u).slice(0, 50);
  let query = words.join(" ");
  while ([...query].length > 400) query = [...query].slice(0, 400).join("").trimEnd();
  if (query.length === 0) throw new TypeError("A controlled web search query is empty.");
  return query;
}

export class DisabledSearchProvider implements SearchProvider {
  public async search(): Promise<SearchProviderResponse> {
    throw new SearchProviderError("SEARCH_UNAVAILABLE", false);
  }
}
