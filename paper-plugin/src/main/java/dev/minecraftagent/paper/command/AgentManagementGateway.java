package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.management.ManagementSnapshot;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Command-facing asynchronous boundary for management operations. */
public interface AgentManagementGateway {
  long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  Pattern DAY_PERIOD = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  Pattern MONTH_PERIOD = Pattern.compile("^[0-9]{4}-[0-9]{2}$");

  default ManagementSnapshot snapshot() {
    return ManagementSnapshot.unavailable();
  }

  default CompletionStage<CostsResult> costs() {
    return CompletableFuture.completedFuture(CostsResult.unavailable());
  }

  default CompletionStage<ReloadResult> reload() {
    return CompletableFuture.completedFuture(new ReloadResult(ReloadStatus.UNAVAILABLE));
  }

  static AgentManagementGateway unavailable() {
    return new AgentManagementGateway() {};
  }

  record UsageWindow(
      String period,
      long requests,
      long providerCalls,
      long reportedProviderCalls,
      long estimatedProviderCalls,
      long inputTokens,
      long outputTokens,
      long costMicroUsd) {
    public UsageWindow {
      Objects.requireNonNull(period);
      if (requests < 0
          || providerCalls < 0
          || reportedProviderCalls < 0
          || estimatedProviderCalls < 0
          || inputTokens < 0
          || outputTokens < 0
          || costMicroUsd < 0
          || requests > MAX_SAFE_INTEGER
          || providerCalls > MAX_SAFE_INTEGER
          || reportedProviderCalls > MAX_SAFE_INTEGER
          || estimatedProviderCalls > MAX_SAFE_INTEGER
          || inputTokens > MAX_SAFE_INTEGER
          || outputTokens > MAX_SAFE_INTEGER
          || costMicroUsd > MAX_SAFE_INTEGER
          || providerCalls != reportedProviderCalls + estimatedProviderCalls) {
        throw new IllegalArgumentException("Invalid usage window");
      }
    }
  }

  record CostsSnapshot(
      UsageWindow today,
      UsageWindow month,
      String budgetMonth,
      long monthlyBudgetMicroUsd,
      long settledMonthlyCostMicroUsd,
      long activeReservationsMicroUsd,
      long remainingMonthlyBudgetMicroUsd,
      boolean budgetExhausted) {
    public CostsSnapshot {
      Objects.requireNonNull(today);
      Objects.requireNonNull(month);
      Objects.requireNonNull(budgetMonth);
      var exposure = settledMonthlyCostMicroUsd + activeReservationsMicroUsd;
      var expectedRemaining = Math.max(0, monthlyBudgetMicroUsd - exposure);
      if (monthlyBudgetMicroUsd < 0
          || settledMonthlyCostMicroUsd < 0
          || activeReservationsMicroUsd < 0
          || remainingMonthlyBudgetMicroUsd < 0
          || monthlyBudgetMicroUsd > MAX_SAFE_INTEGER
          || settledMonthlyCostMicroUsd > MAX_SAFE_INTEGER
          || activeReservationsMicroUsd > MAX_SAFE_INTEGER
          || remainingMonthlyBudgetMicroUsd > MAX_SAFE_INTEGER
          || remainingMonthlyBudgetMicroUsd > monthlyBudgetMicroUsd
          || budgetExhausted != (remainingMonthlyBudgetMicroUsd == 0)
          || !DAY_PERIOD.matcher(today.period()).matches()
          || !MONTH_PERIOD.matcher(month.period()).matches()
          || !today.period().startsWith(month.period() + "-")
          || !budgetMonth.equals(month.period())
          || settledMonthlyCostMicroUsd != month.costMicroUsd()
          || remainingMonthlyBudgetMicroUsd != expectedRemaining) {
        throw new IllegalArgumentException("Invalid cost snapshot");
      }
    }
  }

  enum CostsStatus {
    AVAILABLE,
    UNAVAILABLE,
    FAILED
  }

  record CostsResult(CostsStatus status, CostsSnapshot snapshot) {
    public CostsResult {
      Objects.requireNonNull(status);
      if ((status == CostsStatus.AVAILABLE) != (snapshot != null)) {
        throw new IllegalArgumentException("Invalid costs result");
      }
    }

    public static CostsResult available(CostsSnapshot snapshot) {
      return new CostsResult(CostsStatus.AVAILABLE, Objects.requireNonNull(snapshot));
    }

    public static CostsResult unavailable() {
      return new CostsResult(CostsStatus.UNAVAILABLE, null);
    }

    public static CostsResult failed() {
      return new CostsResult(CostsStatus.FAILED, null);
    }
  }

  enum ReloadStatus {
    RELOADED,
    UNCHANGED,
    RESTART_REQUIRED,
    INVALID_CONFIG,
    BUSY,
    UNAVAILABLE,
    FAILED
  }

  record ReloadResult(ReloadStatus status) {
    public ReloadResult {
      Objects.requireNonNull(status);
    }
  }
}
