import { BraveSearchProvider } from "./brave-search-provider.js";
import type { SafeHttpFetcher } from "./safe-http-fetcher.js";
import type { SearchProvider } from "./search-provider.js";
import type { WebSearchBudget } from "./search-budget.js";
import { WebEvidencePipeline } from "./web-evidence-pipeline.js";

export interface EvidencePipelineOverrides {
  readonly searchProvider?: SearchProvider;
  readonly fetcher?: SafeHttpFetcher;
  readonly now?: () => Date;
  readonly budget?: WebSearchBudget;
}

export interface EvidenceClientConfig {
  readonly profile: "client" | "paper";
  readonly webEvidence?: {
    readonly apiKey: string;
    readonly requestCostMicroUsd: number;
    readonly monthlyBudgetMicroUsd: number;
    readonly persistentAuthorizationEnabled: boolean;
    readonly country: string;
    readonly searchLanguage: string;
  };
}

export function createConfiguredEvidencePipeline(
  config: EvidenceClientConfig,
  overrides: EvidencePipelineOverrides = {},
): WebEvidencePipeline | undefined {
  if (config.profile !== "client" || config.webEvidence === undefined) return undefined;
  const evidence = config.webEvidence;
  return new WebEvidencePipeline({
    searchProvider:
      overrides.searchProvider ?? new BraveSearchProvider({ apiKey: evidence.apiKey }),
    ...(overrides.fetcher === undefined ? {} : { fetcher: overrides.fetcher }),
    enabled: true,
    persistentAuthorizationEnabled: evidence.persistentAuthorizationEnabled,
    requestCostMicroUsd: evidence.requestCostMicroUsd,
    monthlyBudgetMicroUsd: evidence.monthlyBudgetMicroUsd,
    country: evidence.country,
    searchLanguage: evidence.searchLanguage,
    ...(overrides.budget === undefined ? {} : { budget: overrides.budget }),
    ...(overrides.now === undefined ? {} : { now: overrides.now }),
  });
}
