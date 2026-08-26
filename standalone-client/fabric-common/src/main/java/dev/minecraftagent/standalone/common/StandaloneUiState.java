package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.util.List;
import java.util.UUID;

/** Client-thread state preserved while moving between the three standalone screens. */
public final class StandaloneUiState {
  public UUID sessionId;
  public String question = "";
  public String answer = "";
  public String status = "";
  public long lastCostMicroUsd;
  public TextCompletion.CostKind lastCostKind;
  public List<TextCompletion.Source> sources = List.of();
  public LocalPlanView localPlan;
  public int localPlanAmount = 1;
  public int selectedRoute;
  public int localPlanScroll;
  public ResourceRef selected;
  public UUID activeRequestId;
  public boolean webOnce;
  public boolean inventoryOnce;
  public int answerScroll;
}
