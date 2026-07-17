package dev.minecraftagent.paper.management;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, redacted state that is safe to render on the Paper command thread. */
public record ManagementSnapshot(
    String componentVersion,
    String protocolVersion,
    boolean runtimeConnected,
    int activeRequests,
    CapabilitySummary capabilities,
    ClientSummary clients) {
  private static final java.util.Set<String> LITEMATICA_STATUSES =
      java.util.Set.of(
          "READY",
          "NOT_INSTALLED",
          "MISSING_DEPENDENCY",
          "UNSUPPORTED_VERSION",
          "ADAPTER_LINKAGE_FAILED",
          "PREVIEW_STORAGE_UNAVAILABLE",
          "LEGACY_UNREPORTED");

  public ManagementSnapshot {
    componentVersion = bounded(componentVersion, 64);
    protocolVersion = bounded(protocolVersion, 16);
    if (activeRequests < 0 || activeRequests > 64) {
      throw new IllegalArgumentException("Invalid active request count");
    }
    Objects.requireNonNull(capabilities);
    Objects.requireNonNull(clients);
  }

  public static ManagementSnapshot unavailable() {
    return new ManagementSnapshot(
        "unknown",
        "1.0",
        false,
        0,
        new CapabilitySummary(0, List.of(), 0, Map.of()),
        new ClientSummary(0, 0, Map.of(), Map.of(), Map.of(), List.of(), 0));
  }

  public record CapabilityEntry(String id, int version, String sha256) {
    public CapabilityEntry {
      id = bounded(id, 128);
      if (!id.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+") || version < 1) {
        throw new IllegalArgumentException("Invalid capability entry");
      }
      Objects.requireNonNull(sha256);
      if (!sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid capability hash");
      }
    }
  }

  public record CapabilitySummary(
      long generation,
      List<CapabilityEntry> effective,
      int disabledCount,
      Map<String, Integer> diagnosticCounts) {
    public CapabilitySummary {
      if (generation < 0 || disabledCount < 0 || disabledCount > 128) {
        throw new IllegalArgumentException("Invalid capability summary");
      }
      effective =
          List.copyOf(Objects.requireNonNull(effective)).stream()
              .sorted(Comparator.comparing(CapabilityEntry::id))
              .toList();
      if (effective.size() > 128) {
        throw new IllegalArgumentException("Capability summary is too large");
      }
      diagnosticCounts = counts(diagnosticCounts);
    }
  }

  public record ClientSummary(
      int onlinePlayers,
      int negotiatedClients,
      Map<String, Integer> protocolCounts,
      Map<String, Integer> featureCounts,
      Map<String, Integer> litematicaAdapterCounts,
      List<LitematicaCompatibility> litematicaCompatibility,
      int omittedCompatibilityGroups) {
    public ClientSummary {
      if (onlinePlayers < 0
          || negotiatedClients < 0
          || negotiatedClients > onlinePlayers
          || omittedCompatibilityGroups < 0) {
        throw new IllegalArgumentException("Invalid client summary");
      }
      protocolCounts = counts(protocolCounts);
      featureCounts = counts(featureCounts);
      litematicaAdapterCounts = counts(litematicaAdapterCounts);
      litematicaCompatibility = List.copyOf(Objects.requireNonNull(litematicaCompatibility));
      if (litematicaCompatibility.size() > 32) {
        throw new IllegalArgumentException("Client compatibility summary is too large");
      }
    }
  }

  public record LitematicaCompatibility(
      String status,
      String minecraftVersion,
      String fabricLoaderVersion,
      String litematicaVersion,
      String malilibVersion,
      String adapterId,
      int clients) {
    public LitematicaCompatibility {
      status = bounded(status, 64);
      if (!LITEMATICA_STATUSES.contains(status)) {
        throw new IllegalArgumentException("Invalid compatibility status");
      }
      minecraftVersion = diagnosticVersion(minecraftVersion);
      fabricLoaderVersion = diagnosticVersion(fabricLoaderVersion);
      litematicaVersion = diagnosticVersion(litematicaVersion);
      malilibVersion = diagnosticVersion(malilibVersion);
      adapterId = bounded(adapterId, 64);
      if (!adapterId.equals("none") && !adapterId.matches("[A-Za-z0-9._-]{1,64}")) {
        throw new IllegalArgumentException("Invalid compatibility adapter");
      }
      if (clients < 1) {
        throw new IllegalArgumentException("Invalid compatibility client count");
      }
    }
  }

  private static Map<String, Integer> counts(Map<String, Integer> source) {
    Objects.requireNonNull(source);
    if (source.size() > 64) {
      throw new IllegalArgumentException("Diagnostic summary is too large");
    }
    var result = new TreeMap<String, Integer>();
    for (var entry : source.entrySet()) {
      var key = bounded(entry.getKey(), 128);
      if (!key.matches("[A-Za-z0-9_.@:-]+") || entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("Invalid diagnostic count");
      }
      result.put(key, entry.getValue());
    }
    return java.util.Collections.unmodifiableMap(result);
  }

  private static String bounded(String value, int maximum) {
    Objects.requireNonNull(value);
    if (value.isBlank()
        || value.length() > maximum
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw new IllegalArgumentException("Invalid management text");
    }
    return value;
  }

  private static String diagnosticVersion(String value) {
    value = bounded(value, 64);
    if (!value.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")) {
      throw new IllegalArgumentException("Invalid compatibility version");
    }
    return value;
  }
}
