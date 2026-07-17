package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.core.planning.ProcessPlanner;
import java.math.BigDecimal;
import java.util.Objects;

/** Immutable local-only process view used by the catalog UI without starting the Runtime. */
public record LocalPlanView(
    String generationId,
    ResourceRef target,
    BigDecimal requestedAmount,
    ProcessPlanner.PlanningResult planning) {
  public LocalPlanView {
    Objects.requireNonNull(generationId, "generationId");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAmount, "requestedAmount");
    Objects.requireNonNull(planning, "planning");
    if (!generationId.equals(target.source().generationId())
        || !generationId.equals(planning.generationId())
        || requestedAmount.signum() <= 0
        || requestedAmount.compareTo(planning.requestedQuantity()) != 0) {
      throw new IllegalArgumentException("local plan view is inconsistent");
    }
  }
}
