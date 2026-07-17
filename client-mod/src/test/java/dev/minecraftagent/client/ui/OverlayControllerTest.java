package dev.minecraftagent.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.client.ui.OverlayController.ViewUpdateResult;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.TextView;
import dev.minecraftagent.client.view.ViewType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class OverlayControllerTest {
  @Test
  void ignoresStaleRevisionAndPreservesPinAcrossUpdates() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    UUID viewId = UUID.randomUUID();
    assertEquals(ViewUpdateResult.ADDED, controller.show(view(viewId, 1, true)));
    assertTrue(controller.pin());
    assertEquals(ViewUpdateResult.IGNORED_STALE, controller.update(view(viewId, 1, true)));
    assertEquals(ViewUpdateResult.UPDATED, controller.update(view(viewId, 2, false)));
    assertTrue(controller.snapshot().orElseThrow().pinned());
    assertEquals(2, controller.snapshot().orElseThrow().view().revision());
    assertTrue(controller.unpin());
  }

  @Test
  void aNewUnpinnedViewReplacesOnlyUnpinnedState() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    controller.show(view(first, 1, true));
    assertEquals(ViewUpdateResult.ADDED, controller.show(view(second, 1, true)));
    assertEquals(second, controller.snapshot().orElseThrow().view().viewId());
    assertTrue(controller.close());
    assertTrue(controller.snapshot().isEmpty());
  }

  @Test
  void updateRequiresAnExistingViewAndDoesNotStealFocus() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    UUID pinned = UUID.randomUUID();
    UUID active = UUID.randomUUID();
    assertEquals(ViewUpdateResult.UNKNOWN_VIEW, controller.update(view(pinned, 1, true)));
    controller.show(view(pinned, 1, true));
    controller.pin();
    controller.show(view(active, 1, false));
    assertEquals(ViewUpdateResult.UPDATED, controller.update(view(pinned, 2, true)));
    assertEquals(active, controller.snapshot().orElseThrow().view().viewId());
  }

  @Test
  void pinnedViewsAreNeverEvictedByServerUpdates() {
    OverlayController controller =
        new OverlayController(new OverlayPreferences(12, 12, 320, 200, true));
    for (int index = 0; index < OverlayController.MAX_OPEN_VIEWS; index++) {
      assertEquals(ViewUpdateResult.ADDED, controller.show(view(UUID.randomUUID(), 1, true)));
    }
    assertEquals(
        ViewUpdateResult.CAPACITY_REJECTED, controller.show(view(UUID.randomUUID(), 1, true)));
  }

  @Test
  void clampsScrollingMovementAndStableDimensions() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    controller.show(view(UUID.randomUUID(), 1, true));
    controller.setViewportMetrics(500, 100);
    controller.scrollBy(1000);
    assertEquals(400, controller.snapshot().orElseThrow().scroll());
    controller.scrollBy(-1000);
    assertEquals(0, controller.snapshot().orElseThrow().scroll());

    controller.moveTo(10000, 10000, 800, 600);
    OverlayBounds bounds = controller.bounds(800, 600);
    assertEquals(OverlayPreferences.MAX_WIDTH >= bounds.width(), true);
    assertTrue(bounds.right() <= 800);
    assertTrue(bounds.bottom() <= 600);

    controller.moveTo(4, 4, 800, 600);
    controller.resizeTo(10000, 10000, 800, 600);
    assertEquals(OverlayPreferences.MAX_WIDTH, controller.preferences().width());
    assertEquals(OverlayPreferences.MAX_HEIGHT, controller.preferences().height());
  }

  @Test
  void clearAndNonPinnableViewsRemainClientControlled() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    controller.show(view(UUID.randomUUID(), 1, false));
    assertFalse(controller.pin());
    assertTrue(controller.clear());
    assertFalse(controller.clear());
  }

  @Test
  void serverLifecycleClearCannotRemovePinnedViews() {
    OverlayController controller = new OverlayController(OverlayPreferences.defaults());
    UUID pinned = UUID.randomUUID();
    UUID transientView = UUID.randomUUID();
    controller.show(view(pinned, 1, true));
    controller.pin();
    controller.show(view(transientView, 1, true));
    controller.unpin();
    assertTrue(controller.dismiss(transientView));
    assertFalse(controller.dismiss(pinned));
    assertEquals(pinned, controller.snapshot().orElseThrow().view().viewId());
  }

  private static StructuredView view(UUID viewId, int revision, boolean pinnable) {
    return new StructuredView(
        "1.0",
        viewId,
        UUID.randomUUID(),
        ViewType.TEXT,
        revision,
        "Title",
        "fallback",
        pinnable,
        new TextView("content"));
  }
}
