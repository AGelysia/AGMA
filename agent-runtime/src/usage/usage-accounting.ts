import type { DatabaseSync } from "node:sqlite";

import type { ModelGenerationUsage } from "../providers/model-provider.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const SERVER_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/u;
const PROVIDER_ID = /^[a-z0-9][a-z0-9._-]{0,31}$/u;
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/u;
const MICRO_USD_PER_USD = 1_000_000;
const MAXIMUM_PROVIDER_ROUND = 64;
const MAXIMUM_RECENT_DAYS = 31;
const DETAIL_RETENTION_DAYS = 32;
const MAXIMUM_SAFE_INTEGER = BigInt(Number.MAX_SAFE_INTEGER);

export interface UsagePricing {
  readonly inputMicroUsdPerMillionTokens: number;
  readonly outputMicroUsdPerMillionTokens: number;
}

export interface UsageAccountingLimits {
  readonly dailyRequestsPerPlayer: number;
  readonly monthlyBudgetMicroUsd: number;
  readonly providerRoundReservationMicroUsd: number;
}

export interface UsageAccountingOptions {
  readonly serverId: string;
  readonly provider: string;
  readonly model: string;
  readonly pricing: UsagePricing;
  readonly limits: UsageAccountingLimits;
}

export interface UsageRequestIdentity {
  readonly requestId: string;
  readonly playerUuid: string;
  readonly timestamp: number;
}

export type UsageAdmissionRejection =
  | "PLAYER_DAILY_LIMIT"
  | "MONTHLY_BUDGET_EXCEEDED"
  | "DUPLICATE_REQUEST";

export type UsageAdmissionDecision =
  | { readonly accepted: true }
  | { readonly accepted: false; readonly reason: UsageAdmissionRejection };

export type UsageRoundReservationDecision =
  | { readonly accepted: true }
  | {
      readonly accepted: false;
      readonly reason: "MONTHLY_BUDGET_EXCEEDED" | "REQUEST_NOT_ACTIVE";
    };

export interface ProviderUsageEventInput extends UsageRequestIdentity {
  readonly providerRound: number;
  readonly usage?: ModelGenerationUsage;
}

export interface ProviderUsageRecordResult {
  readonly inserted: boolean;
  readonly usageKind: "REPORTED" | "ESTIMATED";
  readonly costMicroUsd: number;
}

export interface UsageAggregateSnapshot {
  readonly period: string;
  readonly admittedRequests: number;
  readonly providerCalls: number;
  readonly reportedProviderCalls: number;
  readonly estimatedProviderCalls: number;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly costMicroUsd: number;
}

export interface ManagementCostSnapshot {
  readonly generatedAt: string;
  readonly currency: "USD";
  readonly accountingUnit: "micro_usd";
  readonly serverId: string;
  readonly provider: string;
  readonly model: string;
  readonly pricing: UsagePricing;
  readonly budget: {
    readonly month: string;
    readonly limitMicroUsd: number;
    readonly settledMicroUsd: number;
    readonly activeReservationsMicroUsd: number;
    readonly remainingMicroUsd: number;
    readonly exhausted: boolean;
  };
  readonly currentDay: UsageAggregateSnapshot;
  readonly currentMonth: UsageAggregateSnapshot;
  readonly recentDays: readonly UsageAggregateSnapshot[];
}

export interface UsageAccounting {
  admitRequest(input: UsageRequestIdentity): UsageAdmissionDecision;
  rollbackAdmission(requestId: string): boolean;
  reserveProviderRound(
    requestId: string,
    providerRound: number,
    timestamp: number,
  ): UsageRoundReservationDecision;
  markProviderRoundStarted(requestId: string, providerRound: number, timestamp: number): boolean;
  releaseProviderRound(requestId: string, providerRound: number, timestamp: number): boolean;
  recordProviderUsage(input: ProviderUsageEventInput): ProviderUsageRecordResult;
  closeRequest(requestId: string, timestamp: number): boolean;
  snapshot(timestamp: number): ManagementCostSnapshot;
}

interface UsagePeriod {
  readonly iso: string;
  readonly day: string;
  readonly month: string;
}

function usagePeriod(timestamp: number): UsagePeriod {
  if (!Number.isFinite(timestamp)) {
    throw new TypeError("Usage timestamp is invalid.");
  }
  const iso = new Date(timestamp).toISOString();
  return { iso, day: iso.slice(0, 10), month: iso.slice(0, 7) };
}

function boundedInteger(value: number, field: string, maximum = Number.MAX_SAFE_INTEGER): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > maximum) {
    throw new TypeError(`${field} must be a bounded non-negative integer.`);
  }
}

function assertRequestId(requestId: string): void {
  if (!UUID.test(requestId)) {
    throw new TypeError("requestId must be a canonical UUID.");
  }
}

function assertPlayerUuid(playerUuid: string): void {
  if (!UUID.test(playerUuid)) {
    throw new TypeError("playerUuid must be a canonical UUID.");
  }
}

function assertProviderRound(providerRound: number): void {
  boundedInteger(providerRound, "providerRound", MAXIMUM_PROVIDER_ROUND);
}

function rowInteger(row: Record<string, unknown>, field: string): number {
  const value = Number(row[field]);
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("Usage storage returned an invalid integer.");
  }
  return value;
}

function rowString(row: Record<string, unknown>, field: string): string {
  const value = row[field];
  if (typeof value !== "string") {
    throw new Error("Usage storage returned invalid text.");
  }
  return value;
}

function optionalRowInteger(row: Record<string, unknown> | undefined, field: string): number {
  return row === undefined ? 0 : rowInteger(row, field);
}

function safeIntegerSum(field: string, ...values: number[]): number {
  const total = values.reduce((sum, value) => sum + BigInt(value), 0n);
  if (total > MAXIMUM_SAFE_INTEGER) {
    throw new Error(`${field} exceeds the supported accounting range.`);
  }
  return Number(total);
}

export function usdToMicroUsd(usd: number): number {
  const result = usd * MICRO_USD_PER_USD;
  if (!Number.isSafeInteger(result) || result < 0) {
    throw new TypeError("USD amount must have at most six decimal places and be bounded.");
  }
  return result;
}

export function calculateUsageCostMicroUsd(
  usage: ModelGenerationUsage,
  pricing: UsagePricing,
): number {
  boundedInteger(usage.inputTokens, "inputTokens");
  boundedInteger(usage.outputTokens, "outputTokens");
  boundedInteger(pricing.inputMicroUsdPerMillionTokens, "input token price");
  boundedInteger(pricing.outputMicroUsdPerMillionTokens, "output token price");

  const numerator =
    BigInt(usage.inputTokens) * BigInt(pricing.inputMicroUsdPerMillionTokens) +
    BigInt(usage.outputTokens) * BigInt(pricing.outputMicroUsdPerMillionTokens);
  const cost = (numerator + BigInt(MICRO_USD_PER_USD - 1)) / BigInt(MICRO_USD_PER_USD);
  if (cost > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new RangeError("Provider usage cost exceeds the supported accounting range.");
  }
  return Number(cost);
}

function aggregate(
  row: Record<string, unknown> | undefined,
  period: string,
): UsageAggregateSnapshot {
  return {
    period,
    admittedRequests: optionalRowInteger(row, "admitted_requests"),
    providerCalls: optionalRowInteger(row, "provider_calls"),
    reportedProviderCalls: optionalRowInteger(row, "reported_provider_calls"),
    estimatedProviderCalls: optionalRowInteger(row, "estimated_provider_calls"),
    inputTokens: optionalRowInteger(row, "input_tokens"),
    outputTokens: optionalRowInteger(row, "output_tokens"),
    costMicroUsd: optionalRowInteger(row, "cost_micro_usd"),
  };
}

export class SqliteUsageAccounting implements UsageAccounting {
  readonly #database: DatabaseSync;
  readonly #serverId: string;
  readonly #provider: string;
  readonly #model: string;
  readonly #pricing: UsagePricing;
  readonly #limits: UsageAccountingLimits;

  public constructor(database: DatabaseSync, options: UsageAccountingOptions) {
    if (!SERVER_ID.test(options.serverId)) {
      throw new TypeError("Usage serverId is invalid.");
    }
    if (!PROVIDER_ID.test(options.provider)) {
      throw new TypeError("Usage provider is invalid.");
    }
    if (!MODEL_ID.test(options.model)) {
      throw new TypeError("Usage model is invalid.");
    }
    boundedInteger(options.pricing.inputMicroUsdPerMillionTokens, "input token price");
    boundedInteger(options.pricing.outputMicroUsdPerMillionTokens, "output token price");
    boundedInteger(options.limits.dailyRequestsPerPlayer, "daily request limit");
    if (options.limits.dailyRequestsPerPlayer < 1) {
      throw new TypeError("Daily request limit must be positive.");
    }
    boundedInteger(options.limits.monthlyBudgetMicroUsd, "monthly budget");
    boundedInteger(options.limits.providerRoundReservationMicroUsd, "provider reservation");
    if (options.limits.providerRoundReservationMicroUsd < 1) {
      throw new TypeError("Provider reservation must be positive.");
    }

    this.#database = database;
    this.#serverId = options.serverId;
    this.#provider = options.provider;
    this.#model = options.model;
    this.#pricing = { ...options.pricing };
    this.#limits = { ...options.limits };
  }

  public admitRequest(input: UsageRequestIdentity): UsageAdmissionDecision {
    this.#assertIdentity(input);
    const period = usagePeriod(input.timestamp);
    return this.#transaction(() => {
      const existing = this.#database
        .prepare(
          "SELECT player_uuid FROM usage_request_admissions WHERE server_id = ? AND request_id = ?",
        )
        .get(this.#serverId, input.requestId);
      if (existing !== undefined) {
        if (rowString(existing, "player_uuid") !== input.playerUuid) {
          throw new Error("Usage request ID collided with another player.");
        }
        return { accepted: false, reason: "DUPLICATE_REQUEST" };
      }

      const player = this.#database
        .prepare(
          `SELECT admitted_requests FROM usage_player_daily
           WHERE server_id = ? AND player_uuid = ? AND usage_day = ?`,
        )
        .get(this.#serverId, input.playerUuid, period.day);
      if (optionalRowInteger(player, "admitted_requests") >= this.#limits.dailyRequestsPerPlayer) {
        return { accepted: false, reason: "PLAYER_DAILY_LIMIT" };
      }
      if (!this.#hasBudget(period.month, this.#limits.providerRoundReservationMicroUsd)) {
        return { accepted: false, reason: "MONTHLY_BUDGET_EXCEEDED" };
      }

      this.#database
        .prepare(
          `INSERT INTO usage_request_admissions
             (server_id, request_id, player_uuid, usage_day, usage_month, state, admitted_at, closed_at)
           VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)`,
        )
        .run(
          this.#serverId,
          input.requestId,
          input.playerUuid,
          period.day,
          period.month,
          period.iso,
        );
      this.#insertRoundReservation(input.requestId, 0, period);
      this.#database
        .prepare(
          `INSERT INTO usage_player_daily
             (server_id, player_uuid, usage_day, admitted_requests)
           VALUES (?, ?, ?, 1)
           ON CONFLICT(server_id, player_uuid, usage_day) DO UPDATE SET
             admitted_requests = admitted_requests + 1`,
        )
        .run(this.#serverId, input.playerUuid, period.day);
      this.#incrementAdmissionAggregate("usage_daily", "usage_day", period.day);
      this.#incrementAdmissionAggregate("usage_monthly", "usage_month", period.month);
      return { accepted: true };
    });
  }

  public rollbackAdmission(requestId: string): boolean {
    assertRequestId(requestId);
    return this.#transaction(() => {
      const admission = this.#database
        .prepare(
          `SELECT player_uuid, usage_day, usage_month, state
           FROM usage_request_admissions WHERE server_id = ? AND request_id = ?`,
        )
        .get(this.#serverId, requestId);
      if (admission === undefined || rowString(admission, "state") !== "ACTIVE") {
        return false;
      }
      const event = this.#database
        .prepare(
          "SELECT 1 AS present FROM provider_usage_events WHERE server_id = ? AND request_id = ? LIMIT 1",
        )
        .get(this.#serverId, requestId);
      if (event !== undefined) {
        throw new Error("A started usage admission cannot be rolled back.");
      }

      const playerUuid = rowString(admission, "player_uuid");
      const day = rowString(admission, "usage_day");
      const month = rowString(admission, "usage_month");
      this.#decrementAdmissionAggregate("usage_player_daily", "usage_day", day, playerUuid);
      this.#decrementAdmissionAggregate("usage_daily", "usage_day", day);
      this.#decrementAdmissionAggregate("usage_monthly", "usage_month", month);
      this.#database
        .prepare("DELETE FROM usage_request_admissions WHERE server_id = ? AND request_id = ?")
        .run(this.#serverId, requestId);
      return true;
    });
  }

  public reserveProviderRound(
    requestId: string,
    providerRound: number,
    timestamp: number,
  ): UsageRoundReservationDecision {
    assertRequestId(requestId);
    assertProviderRound(providerRound);
    const period = usagePeriod(timestamp);
    return this.#transaction(() => {
      const admission = this.#database
        .prepare(
          "SELECT state FROM usage_request_admissions WHERE server_id = ? AND request_id = ?",
        )
        .get(this.#serverId, requestId);
      if (admission === undefined || rowString(admission, "state") !== "ACTIVE") {
        return { accepted: false, reason: "REQUEST_NOT_ACTIVE" };
      }
      const existing = this.#database
        .prepare(
          `SELECT state FROM usage_round_reservations
           WHERE server_id = ? AND request_id = ? AND provider_round = ?`,
        )
        .get(this.#serverId, requestId, providerRound);
      if (existing !== undefined) {
        return rowString(existing, "state") === "ACTIVE"
          ? { accepted: true }
          : { accepted: false, reason: "REQUEST_NOT_ACTIVE" };
      }
      if (!this.#hasBudget(period.month, this.#limits.providerRoundReservationMicroUsd)) {
        return { accepted: false, reason: "MONTHLY_BUDGET_EXCEEDED" };
      }
      this.#insertRoundReservation(requestId, providerRound, period);
      return { accepted: true };
    });
  }

  public markProviderRoundStarted(
    requestId: string,
    providerRound: number,
    timestamp: number,
  ): boolean {
    assertRequestId(requestId);
    assertProviderRound(providerRound);
    const { iso } = usagePeriod(timestamp);
    return this.#transaction(() => {
      const reservation = this.#database
        .prepare(
          `SELECT state, started_at FROM usage_round_reservations
           WHERE server_id = ? AND request_id = ? AND provider_round = ?`,
        )
        .get(this.#serverId, requestId, providerRound);
      if (reservation === undefined || rowString(reservation, "state") !== "ACTIVE") {
        return false;
      }
      const startedAt = reservation["started_at"];
      if (startedAt !== null) {
        if (typeof startedAt !== "string" || !Number.isFinite(Date.parse(startedAt))) {
          throw new Error("Usage storage returned an invalid provider start timestamp.");
        }
        return true;
      }
      const updated = this.#database
        .prepare(
          `UPDATE usage_round_reservations SET started_at = ?
           WHERE server_id = ? AND request_id = ? AND provider_round = ?
             AND state = 'ACTIVE' AND started_at IS NULL`,
        )
        .run(iso, this.#serverId, requestId, providerRound);
      return updated.changes === 1;
    });
  }

  public releaseProviderRound(
    requestId: string,
    providerRound: number,
    timestamp: number,
  ): boolean {
    assertRequestId(requestId);
    assertProviderRound(providerRound);
    const { iso } = usagePeriod(timestamp);
    return this.#transaction(() => {
      const updated = this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'RELEASED', settled_at = ?
           WHERE server_id = ? AND request_id = ? AND provider_round = ?
             AND state = 'ACTIVE'`,
        )
        .run(iso, this.#serverId, requestId, providerRound);
      return updated.changes === 1;
    });
  }

  public recordProviderUsage(input: ProviderUsageEventInput): ProviderUsageRecordResult {
    this.#assertIdentity(input);
    assertProviderRound(input.providerRound);
    const occurred = usagePeriod(input.timestamp);
    const usageKind = input.usage === undefined ? "ESTIMATED" : "REPORTED";
    const inputTokens = input.usage?.inputTokens ?? 0;
    const outputTokens = input.usage?.outputTokens ?? 0;
    if (input.usage !== undefined) {
      calculateUsageCostMicroUsd(input.usage, this.#pricing);
    }

    return this.#transaction(() => {
      const reservation = this.#database
        .prepare(
          `SELECT r.reserved_micro_usd, r.state, r.reserved_at, r.started_at, r.usage_month,
                  a.player_uuid
           FROM usage_round_reservations r
           JOIN usage_request_admissions a
             ON a.server_id = r.server_id AND a.request_id = r.request_id
           WHERE r.server_id = ? AND r.request_id = ? AND r.provider_round = ?`,
        )
        .get(this.#serverId, input.requestId, input.providerRound);
      if (reservation === undefined) {
        throw new Error("Provider usage has no matching durable reservation.");
      }
      if (rowString(reservation, "player_uuid") !== input.playerUuid) {
        throw new Error("Provider usage player does not own the reservation.");
      }
      const reservedAt = rowString(reservation, "reserved_at");
      const reservedPeriod = usagePeriod(Date.parse(reservedAt));
      if (reservedPeriod.month !== rowString(reservation, "usage_month")) {
        throw new Error("Provider reservation period is inconsistent.");
      }
      const costMicroUsd =
        input.usage === undefined
          ? rowInteger(reservation, "reserved_micro_usd")
          : calculateUsageCostMicroUsd(input.usage, this.#pricing);

      const existing = this.#database
        .prepare(
          `SELECT player_uuid, provider, model, usage_kind, input_tokens, output_tokens,
                  cost_micro_usd, occurred_at, usage_day, usage_month
           FROM provider_usage_events
           WHERE server_id = ? AND request_id = ? AND provider_round = ?`,
        )
        .get(this.#serverId, input.requestId, input.providerRound);
      if (existing !== undefined) {
        const identityConflict =
          rowString(existing, "player_uuid") !== input.playerUuid ||
          rowString(existing, "provider") !== this.#provider ||
          rowString(existing, "model") !== this.#model;
        if (identityConflict) {
          throw new Error("Provider usage idempotency key has conflicting data.");
        }
        if (
          rowString(existing, "usage_kind") !== usageKind ||
          rowInteger(existing, "input_tokens") !== inputTokens ||
          rowInteger(existing, "output_tokens") !== outputTokens ||
          rowInteger(existing, "cost_micro_usd") !== costMicroUsd
        ) {
          if (
            rowString(existing, "usage_kind") === "ESTIMATED" &&
            usageKind === "REPORTED" &&
            rowInteger(existing, "input_tokens") === 0 &&
            rowInteger(existing, "output_tokens") === 0
          ) {
            const previousCostMicroUsd = rowInteger(existing, "cost_micro_usd");
            const usageDay = rowString(existing, "usage_day");
            const usageMonth = rowString(existing, "usage_month");
            this.#replaceEstimatedUsageAggregate("usage_daily", "usage_day", usageDay, {
              inputTokens,
              outputTokens,
              previousCostMicroUsd,
              costMicroUsd,
            });
            this.#replaceEstimatedUsageAggregate("usage_monthly", "usage_month", usageMonth, {
              inputTokens,
              outputTokens,
              previousCostMicroUsd,
              costMicroUsd,
            });
            const corrected = this.#database
              .prepare(
                `UPDATE provider_usage_events
                 SET usage_kind = 'REPORTED', input_tokens = ?, output_tokens = ?,
                     cost_micro_usd = ?, occurred_at = ?
                 WHERE server_id = ? AND request_id = ? AND provider_round = ?
                   AND usage_kind = 'ESTIMATED'`,
              )
              .run(
                inputTokens,
                outputTokens,
                costMicroUsd,
                occurred.iso,
                this.#serverId,
                input.requestId,
                input.providerRound,
              );
            if (corrected.changes !== 1) {
              throw new Error("Estimated provider usage could not be corrected.");
            }
            return { inserted: false, usageKind, costMicroUsd };
          }
          throw new Error("Provider usage idempotency key has conflicting data.");
        }
        return { inserted: false, usageKind, costMicroUsd };
      }

      if (
        rowString(reservation, "state") !== "ACTIVE" ||
        typeof reservation["started_at"] !== "string"
      ) {
        throw new Error("Provider usage reservation was not durably started.");
      }

      this.#database
        .prepare(
          `INSERT INTO provider_usage_events
             (server_id, request_id, provider_round, player_uuid, provider, model, usage_kind,
              input_tokens, output_tokens, cost_micro_usd, occurred_at, usage_day, usage_month)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          this.#serverId,
          input.requestId,
          input.providerRound,
          input.playerUuid,
          this.#provider,
          this.#model,
          usageKind,
          inputTokens,
          outputTokens,
          costMicroUsd,
          occurred.iso,
          reservedPeriod.day,
          reservedPeriod.month,
        );
      const updated = this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'SETTLED', settled_at = ?
           WHERE server_id = ? AND request_id = ? AND provider_round = ?
             AND state = 'ACTIVE' AND started_at IS NOT NULL`,
        )
        .run(occurred.iso, this.#serverId, input.requestId, input.providerRound);
      if (updated.changes !== 1) {
        throw new Error("Provider usage reservation could not be settled.");
      }
      this.#incrementUsageAggregate("usage_daily", "usage_day", reservedPeriod.day, {
        usageKind,
        inputTokens,
        outputTokens,
        costMicroUsd,
      });
      this.#incrementUsageAggregate("usage_monthly", "usage_month", reservedPeriod.month, {
        usageKind,
        inputTokens,
        outputTokens,
        costMicroUsd,
      });
      return { inserted: true, usageKind, costMicroUsd };
    });
  }

  public closeRequest(requestId: string, timestamp: number): boolean {
    assertRequestId(requestId);
    const occurred = usagePeriod(timestamp);
    return this.#transaction(() => {
      const admission = this.#database
        .prepare(
          `SELECT player_uuid, state FROM usage_request_admissions
           WHERE server_id = ? AND request_id = ?`,
        )
        .get(this.#serverId, requestId);
      if (admission === undefined || rowString(admission, "state") !== "ACTIVE") {
        return false;
      }
      const playerUuid = rowString(admission, "player_uuid");
      const started = this.#database
        .prepare(
          `SELECT provider_round, reserved_micro_usd, reserved_at, usage_month
           FROM usage_round_reservations
           WHERE server_id = ? AND request_id = ? AND state = 'ACTIVE'
             AND started_at IS NOT NULL`,
        )
        .all(this.#serverId, requestId);
      for (const reservation of started) {
        const providerRound = rowInteger(reservation, "provider_round");
        const costMicroUsd = rowInteger(reservation, "reserved_micro_usd");
        const reservedPeriod = usagePeriod(Date.parse(rowString(reservation, "reserved_at")));
        if (reservedPeriod.month !== rowString(reservation, "usage_month")) {
          throw new Error("Provider reservation period is inconsistent.");
        }
        this.#database
          .prepare(
            `INSERT INTO provider_usage_events
               (server_id, request_id, provider_round, player_uuid, provider, model, usage_kind,
                input_tokens, output_tokens, cost_micro_usd, occurred_at, usage_day, usage_month)
             VALUES (?, ?, ?, ?, ?, ?, 'ESTIMATED', 0, 0, ?, ?, ?, ?)`,
          )
          .run(
            this.#serverId,
            requestId,
            providerRound,
            playerUuid,
            this.#provider,
            this.#model,
            costMicroUsd,
            occurred.iso,
            reservedPeriod.day,
            reservedPeriod.month,
          );
        this.#incrementUsageAggregate("usage_daily", "usage_day", reservedPeriod.day, {
          usageKind: "ESTIMATED",
          inputTokens: 0,
          outputTokens: 0,
          costMicroUsd,
        });
        this.#incrementUsageAggregate("usage_monthly", "usage_month", reservedPeriod.month, {
          usageKind: "ESTIMATED",
          inputTokens: 0,
          outputTokens: 0,
          costMicroUsd,
        });
      }
      this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'SETTLED', settled_at = ?
           WHERE server_id = ? AND request_id = ? AND state = 'ACTIVE'
             AND started_at IS NOT NULL`,
        )
        .run(occurred.iso, this.#serverId, requestId);
      const updated = this.#database
        .prepare(
          `UPDATE usage_request_admissions
           SET state = 'CLOSED', closed_at = ?
           WHERE server_id = ? AND request_id = ? AND state = 'ACTIVE'`,
        )
        .run(occurred.iso, this.#serverId, requestId);
      if (updated.changes === 0) {
        return false;
      }
      this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'RELEASED', settled_at = ?
           WHERE server_id = ? AND request_id = ? AND state = 'ACTIVE'
             AND started_at IS NULL`,
        )
        .run(occurred.iso, this.#serverId, requestId);
      return true;
    });
  }

  public recoverAbandonedRequests(timestamp: number): number {
    const occurred = usagePeriod(timestamp);
    return this.#transaction(() => {
      const started = this.#database
        .prepare(
          `SELECT r.request_id, r.provider_round, r.reserved_micro_usd, r.reserved_at,
                  r.usage_month, a.player_uuid
           FROM usage_round_reservations r
           JOIN usage_request_admissions a
             ON a.server_id = r.server_id AND a.request_id = r.request_id
           WHERE r.server_id = ? AND r.state = 'ACTIVE' AND r.started_at IS NOT NULL
           ORDER BY r.request_id, r.provider_round`,
        )
        .all(this.#serverId);
      for (const reservation of started) {
        const requestId = rowString(reservation, "request_id");
        const providerRound = rowInteger(reservation, "provider_round");
        const playerUuid = rowString(reservation, "player_uuid");
        const costMicroUsd = rowInteger(reservation, "reserved_micro_usd");
        const reservedPeriod = usagePeriod(Date.parse(rowString(reservation, "reserved_at")));
        if (reservedPeriod.month !== rowString(reservation, "usage_month")) {
          throw new Error("Provider reservation period is inconsistent.");
        }
        this.#database
          .prepare(
            `INSERT INTO provider_usage_events
               (server_id, request_id, provider_round, player_uuid, provider, model, usage_kind,
                input_tokens, output_tokens, cost_micro_usd, occurred_at, usage_day, usage_month)
             VALUES (?, ?, ?, ?, ?, ?, 'ESTIMATED', 0, 0, ?, ?, ?, ?)`,
          )
          .run(
            this.#serverId,
            requestId,
            providerRound,
            playerUuid,
            this.#provider,
            this.#model,
            costMicroUsd,
            occurred.iso,
            reservedPeriod.day,
            reservedPeriod.month,
          );
        this.#incrementUsageAggregate("usage_daily", "usage_day", reservedPeriod.day, {
          usageKind: "ESTIMATED",
          inputTokens: 0,
          outputTokens: 0,
          costMicroUsd,
        });
        this.#incrementUsageAggregate("usage_monthly", "usage_month", reservedPeriod.month, {
          usageKind: "ESTIMATED",
          inputTokens: 0,
          outputTokens: 0,
          costMicroUsd,
        });
      }
      this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'SETTLED', settled_at = ?
           WHERE server_id = ? AND state = 'ACTIVE' AND started_at IS NOT NULL`,
        )
        .run(occurred.iso, this.#serverId);
      this.#database
        .prepare(
          `UPDATE usage_round_reservations
           SET state = 'RELEASED', settled_at = ?
           WHERE server_id = ? AND state = 'ACTIVE' AND started_at IS NULL`,
        )
        .run(occurred.iso, this.#serverId);
      const updated = this.#database
        .prepare(
          `UPDATE usage_request_admissions
           SET state = 'CLOSED', closed_at = ?
           WHERE server_id = ? AND state = 'ACTIVE'`,
        )
        .run(occurred.iso, this.#serverId);
      return Number(updated.changes);
    });
  }

  public pruneHistoricalDetails(timestamp: number): void {
    const cutoff = usagePeriod(timestamp - DETAIL_RETENTION_DAYS * 86_400_000).day;
    this.#transaction(() => {
      this.#database
        .prepare(
          `DELETE FROM provider_usage_events
           WHERE server_id = ? AND request_id IN (
             SELECT request_id FROM usage_request_admissions
             WHERE server_id = ? AND state = 'CLOSED' AND usage_day < ?
           )`,
        )
        .run(this.#serverId, this.#serverId, cutoff);
      this.#database
        .prepare(
          `DELETE FROM usage_round_reservations
           WHERE server_id = ? AND request_id IN (
             SELECT request_id FROM usage_request_admissions
             WHERE server_id = ? AND state = 'CLOSED' AND usage_day < ?
           )`,
        )
        .run(this.#serverId, this.#serverId, cutoff);
      this.#database
        .prepare(
          `DELETE FROM usage_request_admissions
           WHERE server_id = ? AND state = 'CLOSED' AND usage_day < ?`,
        )
        .run(this.#serverId, cutoff);
      this.#database
        .prepare("DELETE FROM usage_player_daily WHERE server_id = ? AND usage_day < ?")
        .run(this.#serverId, cutoff);
      this.#database
        .prepare("DELETE FROM usage_daily WHERE server_id = ? AND usage_day < ?")
        .run(this.#serverId, cutoff);
    });
  }

  public snapshot(timestamp: number): ManagementCostSnapshot {
    const period = usagePeriod(timestamp);
    const dailyRow = this.#database
      .prepare("SELECT * FROM usage_daily WHERE server_id = ? AND usage_day = ?")
      .get(this.#serverId, period.day);
    const monthlyRow = this.#database
      .prepare("SELECT * FROM usage_monthly WHERE server_id = ? AND usage_month = ?")
      .get(this.#serverId, period.month);
    const reservationRow = this.#database
      .prepare(
        `SELECT COALESCE(SUM(reserved_micro_usd), 0) AS reserved_micro_usd
         FROM usage_round_reservations
         WHERE server_id = ? AND usage_month = ? AND state = 'ACTIVE'`,
      )
      .get(this.#serverId, period.month);
    const recentRows = this.#database
      .prepare(
        `SELECT * FROM usage_daily
         WHERE server_id = ? AND usage_day <= ?
         ORDER BY usage_day DESC LIMIT ?`,
      )
      .all(this.#serverId, period.day, MAXIMUM_RECENT_DAYS);
    const currentMonth = aggregate(monthlyRow, period.month);
    const activeReservationsMicroUsd = optionalRowInteger(reservationRow, "reserved_micro_usd");
    const exposure = currentMonth.costMicroUsd + activeReservationsMicroUsd;
    const remainingMicroUsd = Math.max(0, this.#limits.monthlyBudgetMicroUsd - exposure);

    return {
      generatedAt: period.iso,
      currency: "USD",
      accountingUnit: "micro_usd",
      serverId: this.#serverId,
      provider: this.#provider,
      model: this.#model,
      pricing: { ...this.#pricing },
      budget: {
        month: period.month,
        limitMicroUsd: this.#limits.monthlyBudgetMicroUsd,
        settledMicroUsd: currentMonth.costMicroUsd,
        activeReservationsMicroUsd,
        remainingMicroUsd,
        exhausted: remainingMicroUsd === 0,
      },
      currentDay: aggregate(dailyRow, period.day),
      currentMonth,
      recentDays: recentRows.map((row) => aggregate(row, rowString(row, "usage_day"))),
    };
  }

  #assertIdentity(input: UsageRequestIdentity): void {
    assertRequestId(input.requestId);
    assertPlayerUuid(input.playerUuid);
    usagePeriod(input.timestamp);
  }

  #transaction<Result>(operation: () => Result): Result {
    this.#database.exec("BEGIN IMMEDIATE");
    try {
      const result = operation();
      this.#database.exec("COMMIT");
      return result;
    } catch (error) {
      if (this.#database.isTransaction) {
        this.#database.exec("ROLLBACK");
      }
      throw error;
    }
  }

  #hasBudget(month: string, requestedMicroUsd: number): boolean {
    const monthly = this.#database
      .prepare("SELECT cost_micro_usd FROM usage_monthly WHERE server_id = ? AND usage_month = ?")
      .get(this.#serverId, month);
    const reservations = this.#database
      .prepare(
        `SELECT COALESCE(SUM(reserved_micro_usd), 0) AS reserved_micro_usd
         FROM usage_round_reservations
         WHERE server_id = ? AND usage_month = ? AND state = 'ACTIVE'`,
      )
      .get(this.#serverId, month);
    const settled = optionalRowInteger(monthly, "cost_micro_usd");
    const active = optionalRowInteger(reservations, "reserved_micro_usd");
    const exposure = settled + active;
    return (
      exposure < this.#limits.monthlyBudgetMicroUsd &&
      exposure + requestedMicroUsd <= this.#limits.monthlyBudgetMicroUsd
    );
  }

  #insertRoundReservation(requestId: string, providerRound: number, period: UsagePeriod): void {
    this.#database
      .prepare(
        `INSERT INTO usage_round_reservations
           (server_id, request_id, provider_round, usage_month, reserved_micro_usd, state,
            reserved_at, settled_at)
         VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)`,
      )
      .run(
        this.#serverId,
        requestId,
        providerRound,
        period.month,
        this.#limits.providerRoundReservationMicroUsd,
        period.iso,
      );
  }

  #incrementAdmissionAggregate(
    table: "usage_daily" | "usage_monthly",
    periodColumn: string,
    period: string,
  ): void {
    const existing = this.#database
      .prepare(`SELECT admitted_requests FROM ${table} WHERE server_id = ? AND ${periodColumn} = ?`)
      .get(this.#serverId, period);
    safeIntegerSum(
      "Usage admission aggregate",
      optionalRowInteger(existing, "admitted_requests"),
      1,
    );
    this.#database
      .prepare(
        `INSERT INTO ${table}
           (server_id, ${periodColumn}, admitted_requests, provider_calls,
            reported_provider_calls, estimated_provider_calls, input_tokens, output_tokens,
            cost_micro_usd)
         VALUES (?, ?, 1, 0, 0, 0, 0, 0, 0)
         ON CONFLICT(server_id, ${periodColumn}) DO UPDATE SET
           admitted_requests = admitted_requests + 1`,
      )
      .run(this.#serverId, period);
  }

  #decrementAdmissionAggregate(
    table: "usage_player_daily" | "usage_daily" | "usage_monthly",
    periodColumn: string,
    period: string,
    playerUuid?: string,
  ): void {
    const playerClause = playerUuid === undefined ? "" : " AND player_uuid = ?";
    const values =
      playerUuid === undefined ? [this.#serverId, period] : [this.#serverId, period, playerUuid];
    const updated = this.#database
      .prepare(
        `UPDATE ${table} SET admitted_requests = admitted_requests - 1
         WHERE server_id = ? AND ${periodColumn} = ?${playerClause} AND admitted_requests > 0`,
      )
      .run(...values);
    if (updated.changes !== 1) {
      throw new Error("Usage admission aggregate could not be rolled back.");
    }
  }

  #incrementUsageAggregate(
    table: "usage_daily" | "usage_monthly",
    periodColumn: string,
    period: string,
    usage: {
      readonly usageKind: "REPORTED" | "ESTIMATED";
      readonly inputTokens: number;
      readonly outputTokens: number;
      readonly costMicroUsd: number;
    },
  ): void {
    const reported = usage.usageKind === "REPORTED" ? 1 : 0;
    const estimated = usage.usageKind === "ESTIMATED" ? 1 : 0;
    const existing = this.#database
      .prepare(
        `SELECT provider_calls, reported_provider_calls, estimated_provider_calls,
                input_tokens, output_tokens, cost_micro_usd
         FROM ${table} WHERE server_id = ? AND ${periodColumn} = ?`,
      )
      .get(this.#serverId, period);
    safeIntegerSum("Provider call aggregate", optionalRowInteger(existing, "provider_calls"), 1);
    safeIntegerSum(
      "Reported provider call aggregate",
      optionalRowInteger(existing, "reported_provider_calls"),
      reported,
    );
    safeIntegerSum(
      "Estimated provider call aggregate",
      optionalRowInteger(existing, "estimated_provider_calls"),
      estimated,
    );
    safeIntegerSum(
      "Input token aggregate",
      optionalRowInteger(existing, "input_tokens"),
      usage.inputTokens,
    );
    safeIntegerSum(
      "Output token aggregate",
      optionalRowInteger(existing, "output_tokens"),
      usage.outputTokens,
    );
    safeIntegerSum(
      "Usage cost aggregate",
      optionalRowInteger(existing, "cost_micro_usd"),
      usage.costMicroUsd,
    );
    this.#database
      .prepare(
        `INSERT INTO ${table}
           (server_id, ${periodColumn}, admitted_requests, provider_calls,
            reported_provider_calls, estimated_provider_calls, input_tokens, output_tokens,
            cost_micro_usd)
         VALUES (?, ?, 0, 1, ?, ?, ?, ?, ?)
         ON CONFLICT(server_id, ${periodColumn}) DO UPDATE SET
           provider_calls = provider_calls + 1,
           reported_provider_calls = reported_provider_calls + excluded.reported_provider_calls,
           estimated_provider_calls = estimated_provider_calls + excluded.estimated_provider_calls,
           input_tokens = input_tokens + excluded.input_tokens,
           output_tokens = output_tokens + excluded.output_tokens,
           cost_micro_usd = cost_micro_usd + excluded.cost_micro_usd`,
      )
      .run(
        this.#serverId,
        period,
        reported,
        estimated,
        usage.inputTokens,
        usage.outputTokens,
        usage.costMicroUsd,
      );
  }

  #replaceEstimatedUsageAggregate(
    table: "usage_daily" | "usage_monthly",
    periodColumn: string,
    period: string,
    usage: {
      readonly inputTokens: number;
      readonly outputTokens: number;
      readonly previousCostMicroUsd: number;
      readonly costMicroUsd: number;
    },
  ): void {
    const row = this.#database
      .prepare(
        `SELECT provider_calls, reported_provider_calls, estimated_provider_calls,
                input_tokens, output_tokens, cost_micro_usd
         FROM ${table} WHERE server_id = ? AND ${periodColumn} = ?`,
      )
      .get(this.#serverId, period);
    if (row === undefined || rowInteger(row, "estimated_provider_calls") < 1) {
      throw new Error("Estimated usage aggregate is missing.");
    }
    const reportedProviderCalls = safeIntegerSum(
      "Reported provider call aggregate",
      rowInteger(row, "reported_provider_calls"),
      1,
    );
    const estimatedProviderCalls = rowInteger(row, "estimated_provider_calls") - 1;
    const inputTokens = safeIntegerSum(
      "Input token aggregate",
      rowInteger(row, "input_tokens"),
      usage.inputTokens,
    );
    const outputTokens = safeIntegerSum(
      "Output token aggregate",
      rowInteger(row, "output_tokens"),
      usage.outputTokens,
    );
    const correctedCost =
      BigInt(rowInteger(row, "cost_micro_usd")) -
      BigInt(usage.previousCostMicroUsd) +
      BigInt(usage.costMicroUsd);
    if (correctedCost < 0n || correctedCost > MAXIMUM_SAFE_INTEGER) {
      throw new Error("Usage cost aggregate exceeds the supported accounting range.");
    }
    const updated = this.#database
      .prepare(
        `UPDATE ${table}
         SET reported_provider_calls = ?, estimated_provider_calls = ?, input_tokens = ?,
             output_tokens = ?, cost_micro_usd = ?
         WHERE server_id = ? AND ${periodColumn} = ?`,
      )
      .run(
        reportedProviderCalls,
        estimatedProviderCalls,
        inputTokens,
        outputTokens,
        Number(correctedCost),
        this.#serverId,
        period,
      );
    if (updated.changes !== 1) {
      throw new Error("Estimated usage aggregate could not be corrected.");
    }
  }
}
