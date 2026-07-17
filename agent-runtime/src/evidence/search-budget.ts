import type { DatabaseSync, StatementSync } from "node:sqlite";

export interface WebSearchBudget {
  charge(): boolean;
}

export interface SqliteWebSearchBudgetOptions {
  readonly scopeId: string;
  readonly requestCostMicroUsd: number;
  readonly monthlyBudgetMicroUsd: number;
  readonly now?: () => Date;
}

export class SqliteWebSearchBudget implements WebSearchBudget {
  readonly #database: DatabaseSync;
  readonly #scopeId: string;
  readonly #requestCostMicroUsd: number;
  readonly #monthlyBudgetMicroUsd: number;
  readonly #now: () => Date;
  readonly #insertMonth: StatementSync;
  readonly #chargeMonth: StatementSync;

  public constructor(database: DatabaseSync, options: SqliteWebSearchBudgetOptions) {
    if (
      options.scopeId.length < 1 ||
      options.scopeId.length > 64 ||
      !Number.isSafeInteger(options.requestCostMicroUsd) ||
      options.requestCostMicroUsd < 1 ||
      !Number.isSafeInteger(options.monthlyBudgetMicroUsd) ||
      options.monthlyBudgetMicroUsd < options.requestCostMicroUsd
    ) {
      throw new TypeError("The persistent web Search budget is invalid.");
    }
    this.#database = database;
    this.#scopeId = options.scopeId;
    this.#requestCostMicroUsd = options.requestCostMicroUsd;
    this.#monthlyBudgetMicroUsd = options.monthlyBudgetMicroUsd;
    this.#now = options.now ?? (() => new Date());
    this.#insertMonth = database.prepare(`
      INSERT INTO web_search_usage_monthly (
        scope_id, usage_month, charged_requests, spent_micro_usd, updated_at
      ) VALUES (?, ?, 0, 0, ?)
      ON CONFLICT(scope_id, usage_month) DO NOTHING
    `);
    this.#chargeMonth = database.prepare(`
      UPDATE web_search_usage_monthly
      SET charged_requests = charged_requests + 1,
          spent_micro_usd = spent_micro_usd + ?,
          updated_at = ?
      WHERE scope_id = ?
        AND usage_month = ?
        AND spent_micro_usd <= ?
    `);
  }

  public charge(): boolean {
    const now = this.#now();
    const timestamp = now.toISOString();
    const month = timestamp.slice(0, 7);
    const maximumPreviousSpend = this.#monthlyBudgetMicroUsd - this.#requestCostMicroUsd;
    this.#database.exec("BEGIN IMMEDIATE");
    try {
      this.#insertMonth.run(this.#scopeId, month, timestamp);
      const charged = this.#chargeMonth.run(
        this.#requestCostMicroUsd,
        timestamp,
        this.#scopeId,
        month,
        maximumPreviousSpend,
      );
      this.#database.exec("COMMIT");
      return charged.changes === 1;
    } catch (error) {
      if (this.#database.isTransaction) this.#database.exec("ROLLBACK");
      throw error;
    }
  }
}
