package dev.minecraftagent.paper.management;

import dev.minecraftagent.paper.capability.registry.CapabilityRegistrySnapshot;
import dev.minecraftagent.paper.client.ClientDiagnosticsSnapshot;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds the bounded command snapshot from immutable subsystem generations. */
public final class ManagementSnapshotFactory {
  private static final int MAXIMUM_COMPATIBILITY_GROUPS = 32;

  private ManagementSnapshotFactory() {}

  public static ManagementSnapshot create(
      String componentVersion,
      String protocolVersion,
      boolean runtimeConnected,
      int activeRequests,
      CapabilityRegistrySnapshot capabilities,
      ClientDiagnosticsSnapshot clients) {
    Objects.requireNonNull(capabilities);
    Objects.requireNonNull(clients);
    return new ManagementSnapshot(
        componentVersion,
        protocolVersion,
        runtimeConnected,
        activeRequests,
        capabilitySummary(capabilities),
        clientSummary(clients));
  }

  private static ManagementSnapshot.CapabilitySummary capabilitySummary(
      CapabilityRegistrySnapshot snapshot) {
    var effective =
        snapshot.effectiveCapabilities().values().stream()
            .map(
                capability -> {
                  var identity = capability.identity();
                  return new ManagementSnapshot.CapabilityEntry(
                      identity.id(), identity.version(), identity.contentSha256());
                })
            .toList();
    var diagnostics = new TreeMap<String, Integer>();
    int disabled = 0;
    for (var draft : snapshot.drafts()) {
      if (draft.enabled()) {
        continue;
      }
      disabled++;
      for (var diagnostic : draft.diagnostics()) {
        diagnostics.merge(diagnostic.code().name(), 1, Integer::sum);
      }
    }
    return new ManagementSnapshot.CapabilitySummary(
        snapshot.generation(), effective, disabled, diagnostics);
  }

  private static ManagementSnapshot.ClientSummary clientSummary(
      ClientDiagnosticsSnapshot snapshot) {
    var featureCounts = new TreeMap<String, Integer>();
    snapshot
        .capabilityVersions()
        .forEach(
            (feature, versions) ->
                versions.forEach(
                    (version, count) -> {
                      if (count > 0) {
                        featureCounts.put(feature.wireName() + "@" + version, count);
                      }
                    }));
    var adapterCounts = new TreeMap<String, Integer>();
    snapshot
        .litematicaAdapterStatuses()
        .forEach(
            (status, count) -> {
              if (count > 0) {
                adapterCounts.put(status.name(), count);
              }
            });

    var compatibility = new ArrayList<ManagementSnapshot.LitematicaCompatibility>();
    var groups = snapshot.litematicaCompatibilityGroups();
    groups.stream()
        .limit(MAXIMUM_COMPATIBILITY_GROUPS)
        .forEach(
            group -> {
              var diagnostic = group.diagnostic();
              compatibility.add(
                  new ManagementSnapshot.LitematicaCompatibility(
                      diagnostic.status().name(),
                      diagnostic.minecraftVersion(),
                      diagnostic.fabricLoaderVersion(),
                      diagnostic.litematicaVersion().orElse("none"),
                      diagnostic.malilibVersion().orElse("none"),
                      diagnostic.adapterId().orElse("none"),
                      group.clientCount()));
            });
    return new ManagementSnapshot.ClientSummary(
        snapshot.onlineConnections(),
        snapshot.negotiatedClients(),
        Map.copyOf(snapshot.protocolVersions()),
        featureCounts,
        adapterCounts,
        compatibility,
        Math.max(0, groups.size() - MAXIMUM_COMPATIBILITY_GROUPS));
  }
}
