import { DatabaseSync } from "node:sqlite";
import { chmod, rm } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import {
  calculateUsageCostMicroUsd,
  SqliteUsageAccounting,
  usdToMicroUsd,
  type UsageAccountingOptions,
} from "../src/usage/usage-accounting.js";
import { temporaryRuntimeDirectory } from "./helpers/runtime-fixture.js";

const NOW = Date.parse("2026-07-14T03:04:05.000Z");
const LATER = Date.parse("2026-07-14T03:05:05.000Z");
const PLAYER_ONE = "11111111-1111-4111-8111-111111111111";
const PLAYER_TWO = "22222222-2222-4222-8222-222222222222";
const REQUEST_ONE = "33333333-3333-4333-8333-333333333333";
const REQUEST_TWO = "44444444-4444-4444-8444-444444444444";
const temporaryDirectories: string[] = [];

function options(
  overrides: Partial<UsageAccountingOptions["limits"]> = {},
): UsageAccountingOptions {
  return {
    serverId: "test-server",
    provider: "openai",
    model: "test-model",
    pricing: {
      inputMicroUsdPerMillionTokens: 1_000_000,
      outputMicroUsdPerMillionTokens: 4_000_000,
    },
    limits: {
      dailyRequestsPerPlayer: 100,
      monthlyBudgetMicroUsd: 1_000_000,
      providerRoundReservationMicroUsd: 60,
      ...overrides,
    },
  };
}

function migratedDatabase(path = ":memory:"): DatabaseSync {
  const database = new DatabaseSync(path);
  migrateRuntimeStorage(database, new Date(NOW).toISOString());
  return database;
}

function request(requestId: string, playerUuid = PLAYER_ONE, timestamp = NOW) {
  return { requestId, playerUuid, timestamp };
}

function indexedUuid(index: number): string {
  return `aaaaaaaa-aaaa-4aaa-8aaa-${index.toString(16).padStart(12, "0")}`;
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("durable usage accounting", () => {
  it("uses integer micro-USD with conservative per-event rounding", () => {
    expect(usdToMicroUsd(10.123456)).toBe(10_123_456);
    expect(() => usdToMicroUsd(0.0000001)).toThrow(/six decimal/u);
    expect(
      calculateUsageCostMicroUsd(
        { inputTokens: 1, outputTokens: 1 },
        {
          inputMicroUsdPerMillionTokens: 150_000,
          outputMicroUsdPerMillionTokens: 600_000,
        },
      ),
    ).toBe(1);
    expect(
      calculateUsageCostMicroUsd(
        { inputTokens: 2_000_000, outputTokens: 500_000 },
        {
          inputMicroUsdPerMillionTokens: 150_000,
          outputMicroUsdPerMillionTokens: 600_000,
        },
      ),
    ).toBe(600_000);
  });

  it("settles reported and missing usage idempotently into UTC aggregates", () => {
    const database = migratedDatabase();
    try {
      const usage = new SqliteUsageAccounting(database, options());
      expect(usage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
      expect(usage.markProviderRoundStarted(REQUEST_ONE, 0, NOW)).toBe(true);

      const reported = usage.recordProviderUsage({
        ...request(REQUEST_ONE),
        providerRound: 0,
        usage: { inputTokens: 10, outputTokens: 2 },
      });
      expect(reported).toEqual({ inserted: true, usageKind: "REPORTED", costMicroUsd: 18 });
      expect(
        usage.recordProviderUsage({
          ...request(REQUEST_ONE, PLAYER_ONE, LATER),
          providerRound: 0,
          usage: { inputTokens: 10, outputTokens: 2 },
        }),
      ).toEqual({ inserted: false, usageKind: "REPORTED", costMicroUsd: 18 });

      expect(usage.reserveProviderRound(REQUEST_ONE, 1, LATER)).toEqual({ accepted: true });
      expect(usage.markProviderRoundStarted(REQUEST_ONE, 1, LATER)).toBe(true);
      expect(
        usage.recordProviderUsage({
          ...request(REQUEST_ONE, PLAYER_ONE, LATER),
          providerRound: 1,
        }),
      ).toEqual({ inserted: true, usageKind: "ESTIMATED", costMicroUsd: 60 });
      expect(usage.closeRequest(REQUEST_ONE, LATER)).toBe(true);

      const snapshot = usage.snapshot(LATER);
      expect(snapshot.currentDay).toEqual({
        period: "2026-07-14",
        admittedRequests: 1,
        providerCalls: 2,
        reportedProviderCalls: 1,
        estimatedProviderCalls: 1,
        inputTokens: 10,
        outputTokens: 2,
        costMicroUsd: 78,
      });
      expect(snapshot.currentMonth).toMatchObject({ period: "2026-07", costMicroUsd: 78 });
      expect(snapshot.budget).toMatchObject({
        settledMicroUsd: 78,
        activeReservationsMicroUsd: 0,
        remainingMicroUsd: 999_922,
        exhausted: false,
      });
      expect(
        database.prepare("SELECT COUNT(*) AS count FROM provider_usage_events").get()?.["count"],
      ).toBe(2);
      expect(() =>
        usage.recordProviderUsage({
          ...request(REQUEST_ONE),
          providerRound: 0,
          usage: { inputTokens: 11, outputTokens: 2 },
        }),
      ).toThrow(/conflicting/u);
    } finally {
      database.close();
    }
  });

  it("reserves budget atomically across concurrent requests and releases unused exposure", () => {
    const database = migratedDatabase();
    try {
      const usage = new SqliteUsageAccounting(
        database,
        options({ monthlyBudgetMicroUsd: 100, providerRoundReservationMicroUsd: 60 }),
      );
      expect(usage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
      expect(usage.admitRequest(request(REQUEST_TWO, PLAYER_TWO))).toEqual({
        accepted: false,
        reason: "MONTHLY_BUDGET_EXCEEDED",
      });
      expect(usage.snapshot(NOW).budget.activeReservationsMicroUsd).toBe(60);

      expect(usage.closeRequest(REQUEST_ONE, LATER)).toBe(true);
      expect(usage.admitRequest(request(REQUEST_TWO, PLAYER_TWO, LATER))).toEqual({
        accepted: true,
      });
      expect(usage.snapshot(LATER).budget.activeReservationsMicroUsd).toBe(60);
    } finally {
      database.close();
    }
  });

  it("keeps daily and monthly admission stable across reopen and clears abandoned reservations", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const path = join(directory, "usage.db");
    const first = migratedDatabase(path);
    const firstUsage = new SqliteUsageAccounting(
      first,
      options({ dailyRequestsPerPlayer: 1, monthlyBudgetMicroUsd: 100 }),
    );
    expect(firstUsage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
    first.close();
    await chmod(path, 0o600);

    const reopened = migratedDatabase(path);
    try {
      const usage = new SqliteUsageAccounting(
        reopened,
        options({ dailyRequestsPerPlayer: 1, monthlyBudgetMicroUsd: 100 }),
      );
      expect(usage.recoverAbandonedRequests(LATER)).toBe(1);
      expect(usage.snapshot(LATER).budget.activeReservationsMicroUsd).toBe(0);
      expect(usage.admitRequest(request(REQUEST_TWO, PLAYER_ONE, LATER))).toEqual({
        accepted: false,
        reason: "PLAYER_DAILY_LIMIT",
      });
      expect(
        usage.admitRequest(request(REQUEST_TWO, PLAYER_ONE, Date.parse("2026-07-15T00:00:00Z"))),
      ).toEqual({
        accepted: true,
      });
    } finally {
      reopened.close();
    }
  });

  it("enforces settled monthly cost after a process reopen", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const path = join(directory, "monthly-usage.db");
    const first = migratedDatabase(path);
    const firstUsage = new SqliteUsageAccounting(
      first,
      options({ monthlyBudgetMicroUsd: 100, providerRoundReservationMicroUsd: 60 }),
    );
    expect(firstUsage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
    expect(firstUsage.markProviderRoundStarted(REQUEST_ONE, 0, NOW)).toBe(true);
    expect(
      firstUsage.recordProviderUsage({
        ...request(REQUEST_ONE),
        providerRound: 0,
        usage: { inputTokens: 50, outputTokens: 0 },
      }),
    ).toMatchObject({ costMicroUsd: 50 });
    expect(firstUsage.closeRequest(REQUEST_ONE, LATER)).toBe(true);
    first.close();
    await chmod(path, 0o600);

    const reopened = migratedDatabase(path);
    try {
      const usage = new SqliteUsageAccounting(
        reopened,
        options({ monthlyBudgetMicroUsd: 100, providerRoundReservationMicroUsd: 60 }),
      );
      expect(usage.snapshot(LATER).currentMonth.costMicroUsd).toBe(50);
      expect(usage.admitRequest(request(REQUEST_TWO, PLAYER_TWO, LATER))).toEqual({
        accepted: false,
        reason: "MONTHLY_BUDGET_EXCEEDED",
      });
    } finally {
      reopened.close();
    }
  });

  it("records a returned late result after cancellation without retaining the reservation", () => {
    const database = migratedDatabase();
    try {
      const usage = new SqliteUsageAccounting(database, options());
      expect(usage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
      expect(usage.markProviderRoundStarted(REQUEST_ONE, 0, NOW)).toBe(true);
      expect(usage.closeRequest(REQUEST_ONE, LATER)).toBe(true);
      expect(
        usage.recordProviderUsage({
          ...request(REQUEST_ONE, PLAYER_ONE, LATER),
          providerRound: 0,
          usage: { inputTokens: 4, outputTokens: 1 },
        }),
      ).toEqual({ inserted: false, usageKind: "REPORTED", costMicroUsd: 8 });
      expect(usage.snapshot(LATER).budget).toMatchObject({
        settledMicroUsd: 8,
        activeReservationsMicroUsd: 0,
      });
    } finally {
      database.close();
    }
  });

  it("charges a started abandoned round conservatively during recovery", () => {
    const database = migratedDatabase();
    try {
      const usage = new SqliteUsageAccounting(database, options());
      expect(usage.admitRequest(request(REQUEST_ONE))).toEqual({ accepted: true });
      expect(usage.markProviderRoundStarted(REQUEST_ONE, 0, NOW)).toBe(true);

      expect(usage.recoverAbandonedRequests(LATER)).toBe(1);
      expect(usage.snapshot(LATER)).toMatchObject({
        currentMonth: {
          providerCalls: 1,
          estimatedProviderCalls: 1,
          costMicroUsd: 60,
        },
        budget: { settledMicroUsd: 60, activeReservationsMicroUsd: 0 },
      });
    } finally {
      database.close();
    }
  });

  it("settles a month-boundary response into the reservation month", () => {
    const database = migratedDatabase();
    try {
      const june = Date.parse("2026-06-30T23:59:59.000Z");
      const july = Date.parse("2026-07-01T00:00:01.000Z");
      const usage = new SqliteUsageAccounting(
        database,
        options({ monthlyBudgetMicroUsd: 100, providerRoundReservationMicroUsd: 60 }),
      );
      expect(usage.admitRequest(request(REQUEST_ONE, PLAYER_ONE, june))).toEqual({
        accepted: true,
      });
      expect(usage.markProviderRoundStarted(REQUEST_ONE, 0, june)).toBe(true);
      expect(usage.admitRequest(request(REQUEST_TWO, PLAYER_TWO, july))).toEqual({
        accepted: true,
      });
      expect(usage.markProviderRoundStarted(REQUEST_TWO, 0, july)).toBe(true);

      expect(
        usage.recordProviderUsage({
          ...request(REQUEST_ONE, PLAYER_ONE, july),
          providerRound: 0,
          usage: { inputTokens: 60, outputTokens: 0 },
        }),
      ).toMatchObject({ costMicroUsd: 60 });
      expect(
        usage.recordProviderUsage({
          ...request(REQUEST_TWO, PLAYER_TWO, july),
          providerRound: 0,
          usage: { inputTokens: 60, outputTokens: 0 },
        }),
      ).toMatchObject({ costMicroUsd: 60 });

      expect(usage.snapshot(july).currentMonth.costMicroUsd).toBe(60);
      expect(usage.snapshot(june).currentMonth.costMicroUsd).toBe(60);
    } finally {
      database.close();
    }
  });

  it("bounds management history to the 31 most recent UTC days", () => {
    const database = migratedDatabase();
    try {
      const usage = new SqliteUsageAccounting(
        database,
        options({ providerRoundReservationMicroUsd: 1 }),
      );
      for (let index = 0; index < 35; index += 1) {
        const timestamp = Date.parse("2026-01-01T00:00:00.000Z") + index * 86_400_000;
        const requestId = indexedUuid(index);
        expect(usage.admitRequest(request(requestId, PLAYER_ONE, timestamp))).toEqual({
          accepted: true,
        });
        expect(usage.closeRequest(requestId, timestamp)).toBe(true);
      }
      const snapshot = usage.snapshot(Date.parse("2026-02-04T12:00:00.000Z"));
      expect(snapshot.recentDays).toHaveLength(31);
      expect(snapshot.recentDays[0]?.period).toBe("2026-02-04");
      expect(snapshot.recentDays.at(-1)?.period).toBe("2026-01-05");
    } finally {
      database.close();
    }
  });
});
