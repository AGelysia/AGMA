package dev.minecraftagent.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RecipePresentationStateTest {
  private static final UUID VIEW_ONE = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID VIEW_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");

  @Test
  void pagesVariantsWithWrappingAndResetsOnlyForANewViewRevision() {
    RecipePresentationState state = new RecipePresentationState();
    state.synchronize(VIEW_ONE, 1, 1, 3, 1_000);

    assertEquals(1, state.selectedRecipe());
    assertTrue(state.next(3));
    assertEquals(2, state.selectedRecipe());
    assertTrue(state.next(3));
    assertEquals(0, state.selectedRecipe());
    assertTrue(state.previous(3));
    assertEquals(2, state.selectedRecipe());

    state.synchronize(VIEW_ONE, 1, 0, 3, 5_000);
    assertEquals(2, state.selectedRecipe());
    state.synchronize(VIEW_ONE, 2, 0, 3, 5_000);
    assertEquals(0, state.selectedRecipe());
    state.synchronize(VIEW_TWO, 1, 1, 3, 6_000);
    assertEquals(1, state.selectedRecipe());
    assertFalse(state.next(1));
  }

  @Test
  void cyclesAlternativesAtAStableBoundedInterval() {
    RecipePresentationState state = new RecipePresentationState();
    state.synchronize(VIEW_ONE, 1, 0, 1, 10_000);

    assertEquals(-1, state.alternativeIndex(0, 10_000));
    assertEquals(0, state.alternativeIndex(3, 10_000));
    assertEquals(0, state.alternativeIndex(3, 11_499));
    assertEquals(1, state.alternativeIndex(3, 11_500));
    assertEquals(2, state.alternativeIndex(3, 13_000));
    assertEquals(0, state.alternativeIndex(3, 14_500));
    assertEquals(0, state.alternativeIndex(3, 9_000));
  }
}
