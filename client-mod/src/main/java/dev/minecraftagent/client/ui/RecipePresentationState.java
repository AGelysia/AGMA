package dev.minecraftagent.client.ui;

import java.util.Objects;
import java.util.UUID;

/** Pure view-local paging and ingredient-cycle state used by the recipe renderer. */
final class RecipePresentationState {
  static final long CHOICE_INTERVAL_MILLIS = 1_500;

  private UUID viewId;
  private int revision;
  private int selectedRecipe;
  private long choiceEpochMillis;

  void synchronize(
      UUID nextViewId, int nextRevision, int initialSelection, int recipeCount, long nowMillis) {
    Objects.requireNonNull(nextViewId, "nextViewId");
    if (recipeCount < 1
        || initialSelection < 0
        || initialSelection >= recipeCount
        || nextRevision < 1) {
      throw new IllegalArgumentException("Invalid recipe presentation state");
    }
    if (!nextViewId.equals(viewId) || nextRevision != revision) {
      viewId = nextViewId;
      revision = nextRevision;
      selectedRecipe = initialSelection;
      choiceEpochMillis = nowMillis;
    } else if (selectedRecipe >= recipeCount) {
      selectedRecipe = initialSelection;
    }
  }

  int selectedRecipe() {
    return selectedRecipe;
  }

  boolean previous(int recipeCount) {
    if (recipeCount <= 1) {
      return false;
    }
    selectedRecipe = Math.floorMod(selectedRecipe - 1, recipeCount);
    return true;
  }

  boolean next(int recipeCount) {
    if (recipeCount <= 1) {
      return false;
    }
    selectedRecipe = (selectedRecipe + 1) % recipeCount;
    return true;
  }

  int alternativeIndex(int alternativeCount, long nowMillis) {
    if (alternativeCount < 1) {
      return -1;
    }
    long elapsed = Math.max(0, nowMillis - choiceEpochMillis);
    return (int) ((elapsed / CHOICE_INTERVAL_MILLIS) % alternativeCount);
  }
}
