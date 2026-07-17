package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientStateCoordinatorTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void projectsExactRequestCapabilitiesAndClearsTransfersWithState() {
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);

    state.join(PLAYER);
    var vanilla = state.capabilitySnapshot(PLAYER);
    assertFalse(vanilla.connected());
    assertTrue(vanilla.toAgentRequestJson().get("clientProtocolVersion").isJsonNull());

    var negotiated = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    var snapshot = state.capabilitySnapshot(PLAYER);
    assertTrue(snapshot.connected());
    assertEquals(negotiated.generation(), snapshot.generation());
    assertEquals(
        1, snapshot.toAgentRequestJson().getAsJsonObject("features").get("recipeView").getAsInt());

    transfers.prepare(
        PLAYER,
        negotiated.generation(),
        ClientViewSelectorTest.view(ClientViewType.TEXT),
        Instant.EPOCH);
    state.clearTransientState();
    assertTrue(connections.lookup(PLAYER).isEmpty());
    assertEquals(0, transfers.pendingCount(PLAYER));
  }

  @Test
  void rejectionMovesToAFreshVanillaGenerationAndDropsPendingTransfers() {
    var connections = new ClientConnectionRegistry();
    var transfers = ClientTransferManager.withProductionLimits();
    var state = new ClientStateCoordinator(connections, transfers);
    state.join(PLAYER);
    var negotiated = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    transfers.prepare(
        PLAYER,
        negotiated.generation(),
        ClientViewSelectorTest.view(ClientViewType.TEXT),
        Instant.EPOCH);

    var rejected = state.reject(PLAYER).orElseThrow();

    assertTrue(rejected.generation() > negotiated.generation());
    assertFalse(rejected.negotiated());
    assertFalse(state.capabilitySnapshot(PLAYER).connected());
    assertEquals(0, transfers.pendingCount(PLAYER));
  }

  @Test
  void aggregatesOnlyCurrentGenerationsAcrossDisconnectAndReconnect() {
    var connections = new ClientConnectionRegistry();
    var state =
        new ClientStateCoordinator(connections, ClientTransferManager.withProductionLimits());
    state.join(PLAYER);
    var ready = state.negotiate(PLAYER, diagnosticOnlyReadyHandshake());

    var sentControls = new AtomicInteger();
    var controls =
        new ClientUiCommandGateway(
            connections, (ignoredPlayer, ignored) -> sentControls.incrementAndGet());
    assertEquals(
        ClientUiCommandGateway.Result.CLIENT_UNAVAILABLE,
        controls.invoke(
            PLAYER,
            ClientUiCommandGateway.Action.LITEMATICA_PREVIEW_LOAD,
            UUID.fromString("22222222-2222-4222-8222-222222222222")));
    assertEquals(0, sentControls.get());

    var first = state.diagnosticsSnapshot();
    assertEquals(1, first.onlineConnections());
    assertEquals(1, first.negotiatedClients());
    assertEquals(1, first.protocolVersions().get("1.1"));
    assertEquals(1, first.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.READY));
    assertEquals(0, first.capabilityVersions().get(ClientFeature.LITEMATICA_PREVIEW).get(1));

    var replaced = state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 0));
    assertTrue(replaced.generation() > ready.generation());
    var second = state.diagnosticsSnapshot();
    assertEquals(
        0, second.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.READY));
    assertEquals(
        1, second.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.NOT_INSTALLED));

    state.quit(PLAYER);
    assertEquals(0, state.diagnosticsSnapshot().onlineConnections());
    var rejoined = state.join(PLAYER);
    assertTrue(rejoined.generation() > replaced.generation());
    assertEquals(1, state.diagnosticsSnapshot().unnegotiatedConnections());
    state.negotiate(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 1));
    var reconnected = state.diagnosticsSnapshot();
    assertEquals(0, reconnected.unnegotiatedConnections());
    assertEquals(
        1, reconnected.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.READY));
    assertEquals(1, reconnected.litematicaCompatibilityGroups().size());
  }

  @Test
  void diagnosticSnapshotTypesExposeNoIdentityGenerationAuthorityOrPath() {
    var componentNames =
        java.util.stream.Stream.of(
                ClientDiagnosticsSnapshot.class,
                ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup.class,
                ClientLitematicaDiagnostic.class)
            .flatMap(type -> java.util.Arrays.stream(type.getRecordComponents()))
            .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
            .toList();

    assertTrue(
        componentNames.stream()
            .noneMatch(
                name ->
                    name.contains("player")
                        || name.contains("uuid")
                        || name.contains("generation")
                        || name.contains("path")
                        || name.contains("permission")
                        || name.contains("authoriz")));
  }

  @Test
  void capabilityAndCompatibilityDistributionsUseNegotiatedClientsAsTheirDenominator() {
    var second = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var third = UUID.fromString("33333333-3333-4333-8333-333333333333");
    var vanilla = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var connections = new ClientConnectionRegistry();
    var state =
        new ClientStateCoordinator(connections, ClientTransferManager.withProductionLimits());
    state.join(PLAYER);
    state.join(second);
    state.join(third);
    state.join(vanilla);
    state.negotiate(PLAYER, diagnosticOnlyReadyHandshake());
    state.negotiate(second, ClientConnectionRegistryTest.handshake(1, 2, 0));
    state.negotiate(third, ClientConnectionRegistryTest.handshake(1, 2, 0));

    var snapshot = state.diagnosticsSnapshot();

    assertEquals(4, snapshot.onlineConnections());
    assertEquals(3, snapshot.negotiatedClients());
    assertEquals(1, snapshot.unnegotiatedConnections());
    assertEquals(3, snapshot.protocolVersions().get("1.1"));
    assertEquals(1, snapshot.capabilityVersions().get(ClientFeature.OVERLAY).get(0));
    assertEquals(2, snapshot.capabilityVersions().get(ClientFeature.OVERLAY).get(1));
    assertEquals(1, snapshot.capabilityVersions().get(ClientFeature.RECIPE_VIEW).get(0));
    assertEquals(0, snapshot.capabilityVersions().get(ClientFeature.RECIPE_VIEW).get(1));
    assertEquals(2, snapshot.capabilityVersions().get(ClientFeature.RECIPE_VIEW).get(2));
    assertEquals(
        1, snapshot.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.READY));
    assertEquals(
        2,
        snapshot.litematicaAdapterStatuses().get(ClientLitematicaDiagnostic.Status.NOT_INSTALLED));
    assertEquals(2, snapshot.litematicaCompatibilityGroups().size());
    assertEquals(
        2,
        snapshot.litematicaCompatibilityGroups().stream()
            .filter(
                group ->
                    group.diagnostic().status() == ClientLitematicaDiagnostic.Status.NOT_INSTALLED)
            .findFirst()
            .orElseThrow()
            .clientCount());
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.protocolVersions().put("2.0", 1));
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.capabilityVersions().get(ClientFeature.OVERLAY).put(1, 99));
  }

  private static ClientHandshake diagnosticOnlyReadyHandshake() {
    var versions = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      versions.put(feature, 0);
    }
    var dependencies = new LinkedHashMap<String, String>();
    dependencies.put("litematica", "0.26.12");
    dependencies.put("malilib", "0.27.16");
    return new ClientHandshake(
        "1.1",
        "1.2.3",
        new ClientCapabilities(versions),
        dependencies,
        new ClientLitematicaDiagnostic(
            ClientLitematicaDiagnostic.Status.READY,
            "1.21.11",
            "0.19.3",
            Optional.of("0.26.12"),
            Optional.of("0.27.16"),
            Optional.of("litematica-reflection-1")));
  }
}
