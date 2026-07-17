import {
  normalizeEvidenceClaims,
  type EvidenceClaim,
  type EvidenceDocumentInput,
} from "./evidence-normalizer.js";
import { extractHtmlDocument, extractPlainTextDocument } from "./html-extractor.js";
import { SafeFetchError, SafeHttpFetcher } from "./safe-http-fetcher.js";
import {
  buildControlledSearchQuery,
  type SearchProvider,
  SearchProviderError,
  type WebAuthorization,
  type WebSearchContext,
} from "./search-provider.js";
import type { WebSearchBudget } from "./search-budget.js";

const MAXIMUM_SEARCH_RESULTS = 5;
const MAXIMUM_FETCHED_PAGES = 3;
const MAXIMUM_FETCH_CONCURRENCY = 2;
const DEFAULT_CACHE_TTL_MILLISECONDS = 15 * 60 * 1000;
const DEFAULT_CACHE_MAXIMUM_ENTRIES = 64;

export type WebEvidenceStatus =
  | "disabled"
  | "authorization_required"
  | "budget_exceeded"
  | "search_failed"
  | "no_evidence"
  | "complete"
  | "partial";

export interface WebEvidenceRequest {
  readonly authorization: WebAuthorization;
  readonly question: string;
  readonly context?: WebSearchContext;
  readonly signal: AbortSignal;
}

export interface WebEvidenceResult {
  readonly status: WebEvidenceStatus;
  readonly query: string | null;
  readonly claims: readonly EvidenceClaim[];
  readonly warnings: readonly string[];
  readonly searchCostMicroUsd: number;
}

export interface WebEvidenceCollector {
  collect(request: WebEvidenceRequest): Promise<WebEvidenceResult>;
}

export interface WebPageFetcher {
  fetchPage(value: string, signal: AbortSignal): ReturnType<SafeHttpFetcher["fetchPage"]>;
}

export interface WebEvidencePipelineOptions {
  readonly searchProvider: SearchProvider;
  readonly fetcher?: WebPageFetcher;
  readonly enabled: boolean;
  readonly persistentAuthorizationEnabled: boolean;
  readonly requestCostMicroUsd: number;
  readonly monthlyBudgetMicroUsd: number;
  readonly country: string;
  readonly searchLanguage: string;
  readonly now?: () => Date;
  readonly budget?: WebSearchBudget;
  readonly cacheTtlMilliseconds?: number;
  readonly cacheMaximumEntries?: number;
}

interface CachedEvidenceResult {
  readonly expiresAt: number;
  readonly result: WebEvidenceResult;
}

class MonthlySearchBudget implements WebSearchBudget {
  readonly #requestCostMicroUsd: number;
  readonly #monthlyBudgetMicroUsd: number;
  readonly #now: () => Date;
  #month = "";
  #spentMicroUsd = 0;

  public constructor(requestCostMicroUsd: number, monthlyBudgetMicroUsd: number, now: () => Date) {
    if (
      !Number.isSafeInteger(requestCostMicroUsd) ||
      requestCostMicroUsd < 1 ||
      !Number.isSafeInteger(monthlyBudgetMicroUsd) ||
      monthlyBudgetMicroUsd < requestCostMicroUsd
    ) {
      throw new TypeError("The web Search budget is invalid.");
    }
    this.#requestCostMicroUsd = requestCostMicroUsd;
    this.#monthlyBudgetMicroUsd = monthlyBudgetMicroUsd;
    this.#now = now;
  }

  public charge(): boolean {
    const now = this.#now();
    const month = now.toISOString().slice(0, 7);
    if (month !== this.#month) {
      this.#month = month;
      this.#spentMicroUsd = 0;
    }
    if (this.#spentMicroUsd + this.#requestCostMicroUsd > this.#monthlyBudgetMicroUsd) return false;
    this.#spentMicroUsd += this.#requestCostMicroUsd;
    return true;
  }
}

function warningForFetch(error: unknown): string {
  return error instanceof SafeFetchError
    ? `A search result page was skipped (${error.code}).`
    : "A search result page was skipped because it could not be fetched safely.";
}

export class WebEvidencePipeline implements WebEvidenceCollector {
  readonly #searchProvider: SearchProvider;
  readonly #fetcher: WebPageFetcher;
  readonly #enabled: boolean;
  readonly #persistentAuthorizationEnabled: boolean;
  readonly #requestCostMicroUsd: number;
  readonly #country: string;
  readonly #searchLanguage: string;
  readonly #budget: WebSearchBudget;
  readonly #now: () => Date;
  readonly #cacheTtlMilliseconds: number;
  readonly #cacheMaximumEntries: number;
  readonly #cache = new Map<string, CachedEvidenceResult>();

  public constructor(options: WebEvidencePipelineOptions) {
    this.#searchProvider = options.searchProvider;
    this.#fetcher = options.fetcher ?? new SafeHttpFetcher();
    this.#enabled = options.enabled;
    this.#persistentAuthorizationEnabled = options.persistentAuthorizationEnabled;
    this.#requestCostMicroUsd = options.requestCostMicroUsd;
    this.#country = options.country;
    this.#searchLanguage = options.searchLanguage;
    const now = options.now ?? (() => new Date());
    const cacheTtlMilliseconds = options.cacheTtlMilliseconds ?? DEFAULT_CACHE_TTL_MILLISECONDS;
    const cacheMaximumEntries = options.cacheMaximumEntries ?? DEFAULT_CACHE_MAXIMUM_ENTRIES;
    if (!Number.isSafeInteger(cacheTtlMilliseconds) || cacheTtlMilliseconds < 1) {
      throw new TypeError("The web evidence cache TTL is invalid.");
    }
    if (!Number.isSafeInteger(cacheMaximumEntries) || cacheMaximumEntries < 1) {
      throw new TypeError("The web evidence cache size is invalid.");
    }
    this.#now = now;
    this.#cacheTtlMilliseconds = cacheTtlMilliseconds;
    this.#cacheMaximumEntries = cacheMaximumEntries;
    this.#budget =
      options.budget ??
      new MonthlySearchBudget(options.requestCostMicroUsd, options.monthlyBudgetMicroUsd, now);
  }

  public async collect(request: WebEvidenceRequest): Promise<WebEvidenceResult> {
    if (!this.#enabled || request.authorization === "off") {
      return {
        status: "disabled",
        query: null,
        claims: [],
        warnings: [],
        searchCostMicroUsd: 0,
      };
    }
    if (
      request.context === undefined ||
      (request.authorization === "persistent" && !this.#persistentAuthorizationEnabled)
    ) {
      return {
        status: "authorization_required",
        query: null,
        claims: [],
        warnings: ["Web evidence authorization or bounded version context is unavailable."],
        searchCostMicroUsd: 0,
      };
    }
    const query = buildControlledSearchQuery(request.question, request.context);
    const cacheKey = JSON.stringify({
      query,
      question: request.question.normalize("NFKC"),
      context: request.context,
      country: this.#country,
      searchLanguage: this.#searchLanguage,
    });
    const cached = this.#cached(cacheKey);
    if (cached !== undefined) {
      return { ...cached, searchCostMicroUsd: 0 };
    }
    if (!this.#budget.charge()) {
      return {
        status: "budget_exceeded",
        query,
        claims: [],
        warnings: ["The configured monthly web Search budget is exhausted."],
        searchCostMicroUsd: 0,
      };
    }

    let searchResults;
    try {
      searchResults = await this.#searchProvider.search({
        query,
        count: MAXIMUM_SEARCH_RESULTS,
        country: this.#country,
        searchLanguage: this.#searchLanguage,
        signal: request.signal,
      });
    } catch (error) {
      if (request.signal.aborted) throw request.signal.reason;
      const warning =
        error instanceof SearchProviderError
          ? `Web Search failed (${error.code}).`
          : "Web Search failed without returning usable evidence.";
      return {
        status: "search_failed",
        query,
        claims: [],
        warnings: [warning],
        searchCostMicroUsd: this.#requestCostMicroUsd,
      };
    }

    const candidates = searchResults.results.slice(0, MAXIMUM_FETCHED_PAGES);
    const documents: Array<EvidenceDocumentInput | undefined> = Array.from({
      length: candidates.length,
    });
    const fetchWarnings: Array<string | undefined> = Array.from({ length: candidates.length });
    let cursor = 0;
    const worker = async (): Promise<void> => {
      while (cursor < candidates.length) {
        const index = cursor;
        cursor += 1;
        const result = candidates[index];
        if (result === undefined) continue;
        try {
          const page = await this.#fetcher.fetchPage(result.url, request.signal);
          const document =
            page.mediaType === "text/plain"
              ? extractPlainTextDocument(page.body, page.url)
              : extractHtmlDocument(page.body, page.url);
          if (document.spans.length > 0) {
            documents[index] = { url: page.url, retrievedAt: page.retrievedAt, document };
          }
        } catch (error) {
          if (request.signal.aborted) throw request.signal.reason;
          fetchWarnings[index] = warningForFetch(error);
        }
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(MAXIMUM_FETCH_CONCURRENCY, candidates.length) }, worker),
    );
    const boundedDocuments = documents.filter(
      (document): document is EvidenceDocumentInput => document !== undefined,
    );
    const warnings = fetchWarnings.filter((warning): warning is string => warning !== undefined);
    const claims = normalizeEvidenceClaims(boundedDocuments, request.question, request.context);
    const result: WebEvidenceResult = {
      status: claims.length === 0 ? "no_evidence" : warnings.length === 0 ? "complete" : "partial",
      query,
      claims,
      warnings,
      searchCostMicroUsd: this.#requestCostMicroUsd,
    };
    this.#putCached(cacheKey, result);
    return result;
  }

  #cached(cacheKey: string): WebEvidenceResult | undefined {
    const cached = this.#cache.get(cacheKey);
    if (cached === undefined) return undefined;
    if (cached.expiresAt <= this.#now().getTime()) {
      this.#cache.delete(cacheKey);
      return undefined;
    }
    this.#cache.delete(cacheKey);
    this.#cache.set(cacheKey, cached);
    return cached.result;
  }

  #putCached(cacheKey: string, result: WebEvidenceResult): void {
    this.#cache.delete(cacheKey);
    this.#cache.set(cacheKey, {
      expiresAt: this.#now().getTime() + this.#cacheTtlMilliseconds,
      result,
    });
    while (this.#cache.size > this.#cacheMaximumEntries) {
      const oldest = this.#cache.keys().next().value as string | undefined;
      if (oldest === undefined) return;
      this.#cache.delete(oldest);
    }
  }
}
