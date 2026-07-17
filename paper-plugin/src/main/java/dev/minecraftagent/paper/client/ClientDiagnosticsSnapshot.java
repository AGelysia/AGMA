package dev.minecraftagent.paper.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Anonymous aggregate of current client generations. It deliberately exposes no player identity,
 * connection generation, or client-local path.
 */
public record ClientDiagnosticsSnapshot(
    int onlineConnections,
    int negotiatedClients,
    Map<String, Integer> protocolVersions,
    Map<ClientFeature, Map<Integer, Integer>> capabilityVersions,
    Map<ClientLitematicaDiagnostic.Status, Integer> litematicaAdapterStatuses,
    List<LitematicaCompatibilityGroup> litematicaCompatibilityGroups) {
  public ClientDiagnosticsSnapshot {
    if (onlineConnections < 0 || negotiatedClients < 0 || negotiatedClients > onlineConnections) {
      throw new IllegalArgumentException("Invalid client diagnostic counts");
    }
    protocolVersions = immutableProtocols(protocolVersions);
    capabilityVersions = immutableCapabilities(capabilityVersions);
    litematicaAdapterStatuses = immutableStatuses(litematicaAdapterStatuses);
    litematicaCompatibilityGroups = List.copyOf(litematicaCompatibilityGroups);
    if (protocolVersions.values().stream().mapToInt(Integer::intValue).sum() != negotiatedClients
        || capabilityVersions.values().stream()
            .anyMatch(
                versions ->
                    versions.values().stream().mapToInt(Integer::intValue).sum()
                        != negotiatedClients)
        || litematicaAdapterStatuses.values().stream().mapToInt(Integer::intValue).sum()
            != negotiatedClients
        || litematicaCompatibilityGroups.stream()
                .mapToInt(LitematicaCompatibilityGroup::clientCount)
                .sum()
            != negotiatedClients) {
      throw new IllegalArgumentException("Client diagnostic distributions do not balance");
    }
  }

  public int unnegotiatedConnections() {
    return onlineConnections - negotiatedClients;
  }

  static ClientDiagnosticsSnapshot from(
      Collection<ClientConnectionRegistry.ClientConnection> connections) {
    Objects.requireNonNull(connections);
    var protocols = new TreeMap<String, Integer>();
    var capabilities = new EnumMap<ClientFeature, Map<Integer, Integer>>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      var versions = new LinkedHashMap<Integer, Integer>();
      for (int version = 0; version <= feature.maximumVersion(); version++) {
        versions.put(version, 0);
      }
      capabilities.put(feature, versions);
    }
    var statuses =
        new EnumMap<ClientLitematicaDiagnostic.Status, Integer>(
            ClientLitematicaDiagnostic.Status.class);
    for (var status : ClientLitematicaDiagnostic.Status.values()) {
      statuses.put(status, 0);
    }
    var compatibilityCounts = new LinkedHashMap<ClientLitematicaDiagnostic, Integer>();
    int negotiated = 0;
    for (var connection : connections) {
      Objects.requireNonNull(connection);
      if (!connection.negotiated()) {
        continue;
      }
      negotiated++;
      var handshake = connection.handshake();
      protocols.merge(handshake.clientProtocolVersion(), 1, Integer::sum);
      for (var feature : ClientFeature.values()) {
        capabilities.get(feature).merge(handshake.capabilities().version(feature), 1, Integer::sum);
      }
      var diagnostic = handshake.litematicaAdapterDiagnostic();
      statuses.merge(diagnostic.status(), 1, Integer::sum);
      compatibilityCounts.merge(diagnostic, 1, Integer::sum);
    }
    var groups = new ArrayList<LitematicaCompatibilityGroup>();
    compatibilityCounts.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(DIAGNOSTIC_ORDER))
        .forEach(
            entry ->
                groups.add(new LitematicaCompatibilityGroup(entry.getKey(), entry.getValue())));
    return new ClientDiagnosticsSnapshot(
        connections.size(), negotiated, protocols, capabilities, statuses, groups);
  }

  private static Map<String, Integer> immutableProtocols(Map<String, Integer> source) {
    Objects.requireNonNull(source);
    var copy = new TreeMap<String, Integer>();
    source.forEach(
        (version, count) -> {
          if (version == null || version.isEmpty() || count == null || count < 1) {
            throw new IllegalArgumentException("Invalid protocol distribution");
          }
          copy.put(version, count);
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Map<ClientFeature, Map<Integer, Integer>> immutableCapabilities(
      Map<ClientFeature, Map<Integer, Integer>> source) {
    Objects.requireNonNull(source);
    var copy = new EnumMap<ClientFeature, Map<Integer, Integer>>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      var versions = Objects.requireNonNull(source.get(feature));
      var versionCopy = new LinkedHashMap<Integer, Integer>();
      for (int version = 0; version <= feature.maximumVersion(); version++) {
        Integer count = versions.get(version);
        if (count == null || count < 0) {
          throw new IllegalArgumentException("Invalid capability distribution");
        }
        versionCopy.put(version, count);
      }
      if (versions.size() != versionCopy.size()) {
        throw new IllegalArgumentException("Invalid capability distribution");
      }
      copy.put(feature, Collections.unmodifiableMap(versionCopy));
    }
    if (source.size() != copy.size()) {
      throw new IllegalArgumentException("Invalid capability distribution");
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<ClientLitematicaDiagnostic.Status, Integer> immutableStatuses(
      Map<ClientLitematicaDiagnostic.Status, Integer> source) {
    Objects.requireNonNull(source);
    var copy =
        new EnumMap<ClientLitematicaDiagnostic.Status, Integer>(
            ClientLitematicaDiagnostic.Status.class);
    for (var status : ClientLitematicaDiagnostic.Status.values()) {
      Integer count = source.get(status);
      if (count == null || count < 0) {
        throw new IllegalArgumentException("Invalid adapter status distribution");
      }
      copy.put(status, count);
    }
    if (source.size() != copy.size()) {
      throw new IllegalArgumentException("Invalid adapter status distribution");
    }
    return Collections.unmodifiableMap(copy);
  }

  public record LitematicaCompatibilityGroup(
      ClientLitematicaDiagnostic diagnostic, int clientCount) {
    public LitematicaCompatibilityGroup {
      Objects.requireNonNull(diagnostic);
      if (clientCount < 1) {
        throw new IllegalArgumentException("clientCount must be positive");
      }
    }
  }

  private static final Comparator<ClientLitematicaDiagnostic> DIAGNOSTIC_ORDER =
      Comparator.comparing((ClientLitematicaDiagnostic value) -> value.status().name())
          .thenComparing(ClientLitematicaDiagnostic::minecraftVersion)
          .thenComparing(ClientLitematicaDiagnostic::fabricLoaderVersion)
          .thenComparing(value -> value.litematicaVersion().orElse(""))
          .thenComparing(value -> value.malilibVersion().orElse(""))
          .thenComparing(value -> value.adapterId().orElse(""));
}
