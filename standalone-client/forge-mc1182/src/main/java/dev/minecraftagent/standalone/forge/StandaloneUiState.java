package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.common.LocalPlanView;
import dev.minecraftagent.standalone.common.TextCompletion;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.util.List;
import java.util.UUID;

/** Client-thread state preserved while moving between the three standalone screens. */
final class StandaloneUiState {
  UUID sessionId;
  String question = "";
  String answer = "";
  String status = "";
  long lastCostMicroUsd;
  TextCompletion.CostKind lastCostKind;
  List<TextCompletion.Source> sources = List.of();
  LocalPlanView localPlan;
  int localPlanAmount = 1;
  int selectedRoute;
  int localPlanScroll;
  ResourceRef selected;
  UUID activeRequestId;
  boolean webOnce;
  boolean inventoryOnce;
  int answerScroll;
}
