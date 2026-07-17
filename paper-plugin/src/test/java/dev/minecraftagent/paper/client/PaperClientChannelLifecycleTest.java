package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.minecraftagent.paper.client.ClientInboundMessage.Hello;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperClientChannelLifecycleTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String FALLBACK = "Private text remains available.";

  @Test
  void protocolRejectionFallsBackBeforeReplacingTheNegotiatedGeneration() {
    var fixture = fixture();

    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
      bukkit.when(() -> Bukkit.getPlayer(PLAYER)).thenReturn(fixture.player);
      fixture.channel.rejectConnection(fixture.player, "CLIENT_MESSAGE_INVALID");
    }

    verify(fixture.player).sendMessage(Component.text(FALLBACK));
    assertFalse(fixture.state.connection(PLAYER).orElseThrow().negotiated());
    assertFalse(fixture.transfers.isPending(PLAYER, fixture.generation, fixture.transferId));
  }

  @Test
  void validRehelloFallsBackBeforeOpeningTheNewNegotiatedGeneration() {
    var fixture = fixture();

    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
      bukkit.when(() -> Bukkit.getPlayer(PLAYER)).thenReturn(fixture.player);
      fixture.channel.acceptHello(
          fixture.player,
          new Hello(UUID.randomUUID(), ClientConnectionRegistryTest.handshake(1, 0, 0)));
    }

    verify(fixture.player).sendMessage(Component.text(FALLBACK));
    var replacement = fixture.state.connection(PLAYER).orElseThrow();
    assertTrue(replacement.negotiated());
    assertTrue(replacement.generation() > fixture.generation);
    assertFalse(fixture.transfers.isPending(PLAYER, fixture.generation, fixture.transferId));
  }

  @Test
  void transferlessClientErrorLeavesCorrelatedDeliveryForScopedResolutionOrExpiry() {
    var fixture = fixture();

    fixture.channel.handleError(
        PLAYER,
        new ClientInboundMessage.Error(
            UUID.randomUUID(), null, fixture.generation, "CLIENT_PRESENTATION_FAILED"));

    assertTrue(fixture.transfers.isPending(PLAYER, fixture.generation, fixture.transferId));
  }

  @Test
  void fallbackRegistrationFailureStillCancelsTheNewPublication() {
    var fixture = fixture();
    var duplicate =
        fixture.publisher.prepare(
            PLAYER,
            FALLBACK,
            List.of(ClientViewSelectorTest.view(ClientViewType.TEXT)),
            Instant.now());

    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
      assertThrows(
          ClientProtocolException.class,
          () -> fixture.channel.sendPublication(fixture.player, duplicate));
    }

    assertFalse(
        fixture.transfers.isPending(
            PLAYER, fixture.generation, duplicate.transfers().getFirst().transferId()));
    assertTrue(fixture.transfers.isPending(PLAYER, fixture.generation, fixture.transferId));
  }

  private static Fixture fixture() {
    var plugin = mock(Plugin.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("PaperClientChannelLifecycleTest"));
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(PLAYER);
    when(player.isOnline()).thenReturn(true);
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);
    state.join(PLAYER);
    var negotiated = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 0));
    var channel = new PaperClientChannel(plugin, new ClientPayloadCodec(), state, transfers);
    var publisher =
        new ClientViewPublisher(
            new ClientViewSelector(connections, ClientViewSchemaRegistry.versionOne()), transfers);
    var publication =
        publisher.prepare(
            PLAYER,
            FALLBACK,
            List.of(ClientViewSelectorTest.view(ClientViewType.TEXT)),
            Instant.now());
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
      assertTrue(channel.sendPublication(player, publication));
    }
    return new Fixture(
        player,
        transfers,
        state,
        channel,
        publisher,
        negotiated.generation(),
        publication.transfers().getFirst().transferId());
  }

  private record Fixture(
      Player player,
      ClientTransferManager transfers,
      ClientStateCoordinator state,
      PaperClientChannel channel,
      ClientViewPublisher publisher,
      long generation,
      UUID transferId) {}
}
