package dev.minecraftagent.paper.management;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.load.CapabilityPackLoader;
import dev.minecraftagent.paper.capability.registry.CapabilityRegistry;
import dev.minecraftagent.paper.client.ClientDiagnosticsSnapshot;
import dev.minecraftagent.paper.client.ClientFeature;
import dev.minecraftagent.paper.client.ClientLitematicaDiagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementSnapshotFactoryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void aggregatesImmutableSubsystemGenerationsWithoutCapabilitySourcesOrPlayerIdentity()
      throws Exception {
    var root = createPack(temporaryDirectory, "management-pack");
    write(root, "enabled.json", manifest("worldedit.selection"));
    write(
        root,
        "draft.json",
        manifest(
            "worldedit.draft",
            "Draft capability.",
            "player",
            "draft",
            "frozen_selection",
            "none",
            ">=7.3 <8"));
    var registry = new CapabilityRegistry();
    var preview =
        registry.preview(
            new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root));
    assertEquals(CapabilityRegistry.PublishStatus.PUBLISHED, registry.publish(preview).status());

    var ready =
        new ClientLitematicaDiagnostic(
            ClientLitematicaDiagnostic.Status.READY,
            "1.21.11",
            "0.18.4",
            Optional.of("0.26.12"),
            Optional.of("0.27.16"),
            Optional.of("litematica-0.26"));
    var unsupported =
        new ClientLitematicaDiagnostic(
            ClientLitematicaDiagnostic.Status.UNSUPPORTED_VERSION,
            "1.21.11",
            "0.18.4",
            Optional.of("0.25.0"),
            Optional.of("0.26.0"),
            Optional.empty());
    var clients =
        diagnostics(
            3,
            2,
            List.of(
                new ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup(ready, 1),
                new ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup(unsupported, 1)));

    var snapshot =
        ManagementSnapshotFactory.create("0.1.0", "1.0", true, 2, registry.snapshot(), clients);

    assertEquals(1, snapshot.capabilities().generation());
    assertEquals(
        List.of("worldedit.selection"),
        snapshot.capabilities().effective().stream()
            .map(ManagementSnapshot.CapabilityEntry::id)
            .toList());
    assertEquals(1, snapshot.capabilities().disabledCount());
    assertEquals(1, snapshot.capabilities().diagnosticCounts().get("DRAFT_ONLY"));
    assertEquals(2, snapshot.clients().featureCounts().get("overlay@0"));
    assertEquals(
        Map.of("READY", 1, "UNSUPPORTED_VERSION", 1), snapshot.clients().litematicaAdapterCounts());
    assertEquals(2, snapshot.clients().litematicaCompatibility().size());
    var rendered = snapshot.toString();
    assertFalse(rendered.contains(root.toString()));
    assertFalse(rendered.toLowerCase(java.util.Locale.ROOT).contains("uuid"));
  }

  @Test
  void boundsUntrustedCompatibilityCardinalityAndReportsOmittedGroups() {
    var groups = new ArrayList<ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup>();
    for (int index = 0; index < 33; index++) {
      groups.add(
          new ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup(
              new ClientLitematicaDiagnostic(
                  ClientLitematicaDiagnostic.Status.NOT_INSTALLED,
                  "1.21." + index,
                  "0.18.4",
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()),
              1));
    }

    var snapshot =
        ManagementSnapshotFactory.create(
            "0.1.0",
            "1.0",
            false,
            0,
            new CapabilityRegistry().snapshot(),
            diagnostics(33, 33, groups));

    assertEquals(32, snapshot.clients().litematicaCompatibility().size());
    assertEquals(1, snapshot.clients().omittedCompatibilityGroups());
    assertTrue(
        snapshot.clients().litematicaCompatibility().stream()
            .allMatch(group -> group.status().equals("NOT_INSTALLED")));
  }

  @Test
  void rendersLegacyUnreportedClientsAsABoundedDiagnosticGroup() {
    var legacy = ClientLitematicaDiagnostic.legacy(Optional.of("0.26.12"), Optional.of("0.27.16"));
    var clients =
        diagnostics(
            1, 1, List.of(new ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup(legacy, 1)));

    var snapshot =
        ManagementSnapshotFactory.create(
            "0.1.0", "1.0", true, 0, new CapabilityRegistry().snapshot(), clients);

    assertEquals(Map.of("LEGACY_UNREPORTED", 1), snapshot.clients().litematicaAdapterCounts());
    var compatibility = snapshot.clients().litematicaCompatibility().getFirst();
    assertEquals("LEGACY_UNREPORTED", compatibility.status());
    assertEquals("unknown", compatibility.minecraftVersion());
    assertEquals("none", compatibility.adapterId());
  }

  @Test
  void rejectsPathShapedCompatibilityValuesAtTheManagementBoundary() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ManagementSnapshot.LitematicaCompatibility(
                "READY",
                "/home/player/.minecraft",
                "0.18.4",
                "0.26.12",
                "0.27.16",
                "litematica-reflection-1",
                1));
  }

  private static ClientDiagnosticsSnapshot diagnostics(
      int online,
      int negotiated,
      List<ClientDiagnosticsSnapshot.LitematicaCompatibilityGroup> groups) {
    var capabilities = new EnumMap<ClientFeature, Map<Integer, Integer>>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      var versions = new LinkedHashMap<Integer, Integer>();
      for (int version = 0; version <= feature.maximumVersion(); version++) {
        versions.put(version, version == 0 ? negotiated : 0);
      }
      capabilities.put(feature, versions);
    }
    var statuses =
        new EnumMap<ClientLitematicaDiagnostic.Status, Integer>(
            ClientLitematicaDiagnostic.Status.class);
    for (var status : ClientLitematicaDiagnostic.Status.values()) {
      statuses.put(status, 0);
    }
    for (var group : groups) {
      statuses.merge(group.diagnostic().status(), group.clientCount(), Integer::sum);
    }
    return new ClientDiagnosticsSnapshot(
        online, negotiated, Map.of("1.0", negotiated), capabilities, statuses, groups);
  }
}
