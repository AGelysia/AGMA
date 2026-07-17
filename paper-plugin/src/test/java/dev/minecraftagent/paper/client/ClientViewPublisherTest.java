package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientViewPublisherTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void selectsFallbackForVanillaAndBoundedPlansForNegotiatedClient() {
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);
    var publisher =
        new ClientViewPublisher(
            new ClientViewSelector(connections, ClientViewSchemaRegistry.versionOne()), transfers);
    var view = ClientViewSelectorTest.view(ClientViewType.TEXT);

    var vanilla = publisher.prepare(PLAYER, view.fallbackText(), List.of(view), Instant.EPOCH);
    assertTrue(vanilla.useFallback());
    assertEquals("CLIENT_VIEW_UNAVAILABLE", vanilla.fallbackReason());

    state.join(PLAYER);
    state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 0));
    var rich = publisher.prepare(PLAYER, view.fallbackText(), List.of(view), Instant.EPOCH);
    assertFalse(rich.useFallback());
    assertEquals(1, rich.transfers().size());
    assertEquals(ClientTransferManager.Mode.SHOW, rich.transfers().getFirst().mode());

    var revised =
        new ClientStructuredView(
            view.viewSchemaVersion(),
            view.viewId(),
            view.requestId(),
            view.viewType(),
            2,
            view.title(),
            view.fallbackText(),
            view.pinnable(),
            view.content());
    var freshClientPlan =
        publisher.prepare(PLAYER, revised.fallbackText(), List.of(revised), Instant.EPOCH);
    assertEquals(ClientTransferManager.Mode.SHOW, freshClientPlan.transfers().getFirst().mode());

    assertEquals(2, transfers.pendingCount(PLAYER));
    publisher.discard(freshClientPlan);
    publisher.discard(freshClientPlan);
    assertEquals(1, transfers.pendingCount(PLAYER));
    publisher.discard(rich);
    assertEquals(0, transfers.pendingCount(PLAYER));
  }
}
