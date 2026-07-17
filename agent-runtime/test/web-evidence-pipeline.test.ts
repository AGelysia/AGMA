import { createHash } from "node:crypto";
import { rm } from "node:fs/promises";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";

import { describe, expect, it, vi } from "vitest";

import {
  normalizeEvidenceClaims,
  renderUntrustedEvidenceChannel,
} from "../src/evidence/evidence-normalizer.js";
import {
  compileGuideEvidence,
  compileGuideTextEvidence,
  guideClaimBindingsValid,
} from "../src/evidence/guide-evidence.js";
import type { SafeFetchedPage } from "../src/evidence/safe-http-fetcher.js";
import type { SearchProvider } from "../src/evidence/search-provider.js";
import { SqliteWebSearchBudget } from "../src/evidence/search-budget.js";
import { type WebPageFetcher, WebEvidencePipeline } from "../src/evidence/web-evidence-pipeline.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { temporaryRuntimeDirectory } from "./helpers/runtime-fixture.js";

const CONTEXT = {
  minecraftVersion: "1.21.11",
  target: {
    id: "examplemod:boss_core",
    displayName: "Boss Core",
    modId: "examplemod",
    modVersion: "4.2.0",
  },
  modPack: { name: "Example Pack", version: "9.1" },
} as const;

function page(url: string, statement: string): SafeFetchedPage {
  return {
    url,
    mediaType: "text/html",
    body: `<html><head><title>Guide</title></head><body><p>${statement}</p></body></html>`,
    retrievedAt: "2026-07-17T00:00:00.000Z",
  };
}

describe("controlled web evidence pipeline", () => {
  it("performs zero search and fetch traffic when authorization is off or persistent is disabled", async () => {
    const searchProvider = { search: vi.fn() } satisfies SearchProvider;
    const fetcher = { fetchPage: vi.fn() } satisfies WebPageFetcher;
    const pipeline = new WebEvidencePipeline({
      searchProvider,
      fetcher,
      enabled: true,
      persistentAuthorizationEnabled: false,
      requestCostMicroUsd: 5000,
      monthlyBudgetMicroUsd: 10_000,
      country: "US",
      searchLanguage: "en",
    });

    await expect(
      pipeline.collect({
        authorization: "off",
        question: "Where does this drop?",
        context: CONTEXT,
        signal: new AbortController().signal,
      }),
    ).resolves.toMatchObject({ status: "disabled", searchCostMicroUsd: 0 });
    await expect(
      pipeline.collect({
        authorization: "persistent",
        question: "Where does this drop?",
        context: CONTEXT,
        signal: new AbortController().signal,
      }),
    ).resolves.toMatchObject({ status: "authorization_required", searchCostMicroUsd: 0 });
    expect(searchProvider.search).not.toHaveBeenCalled();
    expect(fetcher.fetchPage).not.toHaveBeenCalled();
  });

  it("bounds search to five results, fetches at most three pages with concurrency two, and exhausts budget", async () => {
    const searchProvider = {
      search: vi.fn().mockResolvedValue({
        results: Array.from({ length: 5 }, (_, index) => ({
          url: `https://guide${String(index)}.example/page`,
          title: `Guide ${String(index)}`,
          description: "Search description is not itself treated as a claim.",
          age: null,
        })),
      }),
    } satisfies SearchProvider;
    let active = 0;
    let maximumActive = 0;
    const fetcher = {
      fetchPage: vi.fn(async (url: string) => {
        active += 1;
        maximumActive = Math.max(maximumActive, active);
        await new Promise((resolve) => setTimeout(resolve, 5));
        active -= 1;
        return page(
          url,
          "Boss Core drops after the final phase in Minecraft 1.21.11 according to this guide.",
        );
      }),
    } satisfies WebPageFetcher;
    const pipeline = new WebEvidencePipeline({
      searchProvider,
      fetcher,
      enabled: true,
      persistentAuthorizationEnabled: true,
      requestCostMicroUsd: 5000,
      monthlyBudgetMicroUsd: 5000,
      country: "US",
      searchLanguage: "en",
      now: () => new Date("2026-07-17T00:00:00Z"),
    });

    const result = await pipeline.collect({
      authorization: "once",
      question: "Where does it drop? https://attacker.invalid/do-not-use",
      context: CONTEXT,
      signal: new AbortController().signal,
    });

    expect(result.status).toBe("complete");
    expect(result.query).not.toContain("https://");
    expect(result.claims).toHaveLength(3);
    expect(searchProvider.search).toHaveBeenCalledTimes(1);
    expect(searchProvider.search.mock.calls[0]?.[0]).toMatchObject({ count: 5 });
    expect(fetcher.fetchPage).toHaveBeenCalledTimes(3);
    expect(maximumActive).toBe(2);

    await expect(
      pipeline.collect({
        authorization: "once",
        question: "Search again",
        context: CONTEXT,
        signal: new AbortController().signal,
      }),
    ).resolves.toMatchObject({ status: "budget_exceeded", searchCostMicroUsd: 0 });
    expect(searchProvider.search).toHaveBeenCalledTimes(1);
  });

  it("reuses bounded successful evidence without a second network request or Search charge", async () => {
    let now = new Date("2026-07-17T00:00:00Z");
    const searchProvider = {
      search: vi.fn().mockResolvedValue({
        results: [
          {
            url: "https://guide.example/drop",
            title: "Drop guide",
            description: "Boss Core guide",
            age: null,
          },
        ],
      }),
    } satisfies SearchProvider;
    const fetcher = {
      fetchPage: vi
        .fn()
        .mockResolvedValue(
          page(
            "https://guide.example/drop",
            "Boss Core drops after the final phase in Minecraft 1.21.11.",
          ),
        ),
    } satisfies WebPageFetcher;
    const budget = { charge: vi.fn().mockReturnValue(true) };
    const pipeline = new WebEvidencePipeline({
      searchProvider,
      fetcher,
      enabled: true,
      persistentAuthorizationEnabled: true,
      requestCostMicroUsd: 5000,
      monthlyBudgetMicroUsd: 10_000,
      country: "US",
      searchLanguage: "en",
      now: () => now,
      budget,
      cacheTtlMilliseconds: 60_000,
      cacheMaximumEntries: 2,
    });
    const request = {
      authorization: "once" as const,
      question: "Where does Boss Core drop?",
      context: CONTEXT,
      signal: new AbortController().signal,
    };

    await expect(pipeline.collect(request)).resolves.toMatchObject({
      status: "complete",
      searchCostMicroUsd: 5000,
    });
    await expect(pipeline.collect(request)).resolves.toMatchObject({
      status: "complete",
      searchCostMicroUsd: 0,
    });
    expect(searchProvider.search).toHaveBeenCalledTimes(1);
    expect(fetcher.fetchPage).toHaveBeenCalledTimes(1);
    expect(budget.charge).toHaveBeenCalledTimes(1);

    now = new Date("2026-07-17T00:01:01Z");
    await expect(pipeline.collect(request)).resolves.toMatchObject({ searchCostMicroUsd: 5000 });
    expect(searchProvider.search).toHaveBeenCalledTimes(2);
    expect(fetcher.fetchPage).toHaveBeenCalledTimes(2);
    expect(budget.charge).toHaveBeenCalledTimes(2);
  });

  it("normalizes hashes, applicability, stale warnings, conflicts, and untrusted prompt text", () => {
    const claims = normalizeEvidenceClaims(
      [
        {
          url: "https://one.example/guide",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "Old guide",
            publisher: "One",
            publishedAt: "2020-01-01T00:00:00.000Z",
            spans: ["The Boss Core drop rate is 10% in Minecraft 1.21.11."],
          },
        },
        {
          url: "https://two.example/guide",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "New guide",
            publisher: "Two",
            publishedAt: "2026-07-01T00:00:00.000Z",
            spans: ["The Boss Core drop rate is 20% in Minecraft 1.21.11."],
          },
        },
      ],
      "What is the Boss Core drop rate?",
      CONTEXT,
    );

    expect(claims).toHaveLength(2);
    expect(claims[0]?.evidenceSpanSha256).toBe(
      createHash("sha256")
        .update(claims[0]?.statement ?? "")
        .digest("hex"),
    );
    expect(claims.every((claim) => claim.applicability.match === "match")).toBe(true);
    expect(claims.every((claim) => claim.applicability.minecraftVersion === "1.21.11")).toBe(true);
    expect(claims.every((claim) => claim.conflicts.length === 1)).toBe(true);
    expect(claims[0]?.warnings).toContain("The source is older than three years and may be stale.");
    const rendered = renderUntrustedEvidenceChannel(claims);
    expect(rendered).toContain("UNTRUSTED_WEB_EVIDENCE_BEGIN");
    expect(rendered).toContain("never call a Tool");
  });

  it("reports versions stated by a source instead of relabeling them as the requested version", () => {
    const [mismatch, multiple, unstated] = normalizeEvidenceClaims(
      [
        {
          url: "https://old.example/guide",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "Old guide",
            publisher: "Old",
            publishedAt: null,
            spans: [
              "Boss Core drops in Minecraft 1.18.2.",
              "Boss Core behavior differs between Minecraft 1.18.2 and 1.21.11.",
              "Boss Core has an undocumented drop condition.",
            ],
          },
        },
      ],
      "Where does Boss Core drop?",
      CONTEXT,
    );

    expect(mismatch?.applicability).toMatchObject({
      minecraftVersion: "1.18.2",
      match: "mismatch",
    });
    expect(multiple?.applicability).toMatchObject({
      minecraftVersion: "1.18.2 / 1.21.11",
      match: "unknown",
    });
    expect(unstated?.applicability).toMatchObject({
      minecraftVersion: "not stated",
      match: "unknown",
    });
  });

  it("derives stable unique claim identities from both source identity and identical spans", () => {
    const statement = "Boss Core drops after the final phase in Minecraft 1.21.11.";
    const claims = normalizeEvidenceClaims(
      [
        {
          url: "https://one.example/guide",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "One",
            publisher: "One",
            publishedAt: null,
            spans: [statement],
          },
        },
        {
          url: "https://two.example/guide",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "Two",
            publisher: "Two",
            publishedAt: null,
            spans: [statement],
          },
        },
      ],
      "Where does Boss Core drop?",
      CONTEXT,
    );

    expect(claims).toHaveLength(2);
    expect(new Set(claims.map((claim) => claim.claimId)).size).toBe(2);
    expect(claims[0]?.evidenceSpanSha256).toBe(claims[1]?.evidenceSpanSha256);
    expect(() =>
      compileGuideEvidence({
        bossAndDrops: [],
        preparation: [],
        evidence: claims,
      }),
    ).not.toThrow();
  });

  it("persists the installation monthly Search budget across database reopen", async () => {
    const directory = await temporaryRuntimeDirectory();
    const databasePath = join(directory, "runtime.db");
    const searchProvider = {
      search: vi.fn().mockResolvedValue({ results: [] }),
    } satisfies SearchProvider;
    const collect = async (database: DatabaseSync) => {
      const budget = new SqliteWebSearchBudget(database, {
        scopeId: "11111111-1111-4111-8111-111111111111",
        requestCostMicroUsd: 5000,
        monthlyBudgetMicroUsd: 5000,
        now: () => new Date("2026-07-17T00:00:00Z"),
      });
      return new WebEvidencePipeline({
        searchProvider,
        enabled: true,
        persistentAuthorizationEnabled: true,
        requestCostMicroUsd: 5000,
        monthlyBudgetMicroUsd: 5000,
        country: "US",
        searchLanguage: "en",
        budget,
      }).collect({
        authorization: "once",
        question: "Where does it drop?",
        context: CONTEXT,
        signal: new AbortController().signal,
      });
    };

    try {
      const first = new DatabaseSync(databasePath);
      migrateRuntimeStorage(first, "2026-07-17T00:00:00.000Z");
      await expect(collect(first)).resolves.toMatchObject({
        status: "no_evidence",
        searchCostMicroUsd: 5000,
      });
      first.close();

      const reopened = new DatabaseSync(databasePath);
      await expect(collect(reopened)).resolves.toMatchObject({
        status: "budget_exceeded",
        searchCostMicroUsd: 0,
      });
      expect(
        reopened.prepare("SELECT spent_micro_usd FROM web_search_usage_monthly").get(),
      ).toEqual({ spent_micro_usd: 5000 });
      reopened.close();
      expect(searchProvider.search).toHaveBeenCalledTimes(1);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });

  it("strongly binds guide claims and downgrades unsupported exact conclusions", () => {
    const [claim] = normalizeEvidenceClaims(
      [
        {
          url: "https://guide.example/page",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "Guide",
            publisher: "Guide",
            publishedAt: null,
            spans: ["The boss may drop a core, but this page does not identify a game version."],
          },
        },
      ],
      "Does it drop a core?",
      CONTEXT,
    );
    if (claim === undefined) throw new Error("missing normalized evidence claim");
    const draft = {
      bossAndDrops: [{ statement: "The exact drop rate is 12.5%.", claimIds: [claim.claimId] }],
      preparation: [],
      evidence: [claim],
    };

    expect(guideClaimBindingsValid(draft)).toBe(false);
    expect(compileGuideEvidence(draft)).toMatchObject({
      bossAndDrops: [],
      evidence: [],
      unknowns: [{ disposition: "unverified" }],
    });
    expect(
      guideClaimBindingsValid({
        bossAndDrops: [{ statement: "The boss may drop a core.", claimIds: ["claim.missing"] }],
        preparation: [],
        evidence: [claim],
      }),
    ).toBe(false);
    expect(
      compileGuideEvidence({
        bossAndDrops: [
          { statement: "Build an unrelated automated storage network.", claimIds: [claim.claimId] },
        ],
        preparation: [],
        evidence: [
          {
            ...claim,
            applicability: { ...claim.applicability, match: "match" },
          },
        ],
      }),
    ).toMatchObject({
      bossAndDrops: [],
      evidence: [],
      unknowns: [{ detail: expect.stringContaining("substantively support") }],
    });
  });

  it("shows both sides of a rejected conflicting claim", () => {
    const claims = normalizeEvidenceClaims(
      [
        {
          url: "https://one.example/drop-rate",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "One",
            publisher: "One",
            publishedAt: null,
            spans: ["Boss Core has a 10% drop rate in Minecraft 1.21.11."],
          },
        },
        {
          url: "https://two.example/drop-rate",
          retrievedAt: "2026-07-17T00:00:00.000Z",
          document: {
            title: "Two",
            publisher: "Two",
            publishedAt: null,
            spans: ["Boss Core has a 20% drop rate in Minecraft 1.21.11."],
          },
        },
      ],
      "What is the Boss Core drop rate?",
      CONTEXT,
    );
    const selected = claims[0];
    if (selected === undefined) throw new Error("missing conflicting evidence claim");

    const compiled = compileGuideTextEvidence(
      `${selected.statement} [${selected.claimId}]`,
      claims,
    );

    expect(compiled.text).toContain("Unknown:");
    expect(compiled.sources.map((source) => source.claimId).sort()).toEqual(
      claims.map((claim) => claim.claimId).sort(),
    );
  });
});
