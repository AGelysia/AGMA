package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientFallbackTrackerTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void displayedTransfersCompleteSilentlyWhileRejectionFallsBackOnce() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(PLAYER, 3);
    var first =
        manager.prepare(PLAYER, 3, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);
    var second =
        manager.prepare(PLAYER, 3, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);
    var publication =
        new ClientViewPublisher.Publication("Private fallback", List.of(first, second), null);
    var tracker = new ClientFallbackTracker();
    tracker.register(publication);

    tracker.displayed(PLAYER, 3, first.transferId());
    assertEquals(1, tracker.pendingDeliveryCount());
    var fallback = tracker.rejectTransfer(PLAYER, 3, second.transferId());

    assertEquals(1, fallback.size());
    assertEquals("Private fallback", fallback.getFirst().fallbackText());
    assertEquals(java.util.Set.of(second.transferId()), fallback.getFirst().transferIds());
    assertTrue(tracker.rejectTransfer(PLAYER, 3, second.transferId()).isEmpty());
    assertEquals(0, tracker.pendingDeliveryCount());
  }

  @Test
  void timeoutAndGenerationReplacementEachResolveEveryDeliveryAtMostOnce() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(PLAYER, 7);
    var first =
        manager.prepare(PLAYER, 7, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);
    var tracker = new ClientFallbackTracker();
    tracker.register(new ClientViewPublisher.Publication("Timeout fallback", List.of(first), null));

    var expired =
        new ClientTransferManager.ExpiredTransfer(PLAYER, 7, first.transferId(), first.viewId());
    assertEquals(1, tracker.expired(expired).size());
    assertTrue(tracker.expired(expired).isEmpty());

    var second =
        manager.prepare(PLAYER, 7, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);
    tracker.register(new ClientViewPublisher.Publication("Error fallback", List.of(second), null));
    assertEquals(1, tracker.resolveGeneration(PLAYER, 7).size());
    assertTrue(tracker.resolveGeneration(PLAYER, 7).isEmpty());
  }
}
