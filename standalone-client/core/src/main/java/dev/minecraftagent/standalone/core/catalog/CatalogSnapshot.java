package dev.minecraftagent.standalone.core.catalog;

import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class CatalogSnapshot {
  public static final int MAXIMUM_RESOURCES = 100_000;
  public static final int MAXIMUM_PROCESSES = 100_000;
  private static final Pattern GENERATION_ID =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

  private final String generationId;
  private final String packFingerprint;
  private final Instant createdAt;
  private final Map<ResourceKey, ResourceRef> resources;
  private final Map<String, ProcessRecord> processes;
  private final Map<ResourceKey, List<ProcessRecord>> processesByOutput;

  public CatalogSnapshot(
      String generationId,
      String packFingerprint,
      Instant createdAt,
      List<ResourceRef> resources,
      List<ProcessRecord> processes) {
    this.generationId = boundedGeneration(generationId);
    this.packFingerprint = sha256(packFingerprint);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(processes, "processes");
    if (resources.size() > MAXIMUM_RESOURCES || processes.size() > MAXIMUM_PROCESSES) {
      throw new IllegalArgumentException("catalog exceeds its bounded size");
    }

    var resourceMap = new TreeMap<ResourceKey, ResourceRef>();
    for (var resource : resources) {
      Objects.requireNonNull(resource, "resource");
      requireGeneration(resource.source().generationId());
      if (resourceMap.putIfAbsent(ResourceKey.from(resource), resource) != null) {
        throw new IllegalArgumentException("catalog resource identities must be unique");
      }
    }
    this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resourceMap));

    var processMap = new TreeMap<String, ProcessRecord>();
    var outputMap = new TreeMap<ResourceKey, List<ProcessRecord>>();
    for (var process : processes) {
      Objects.requireNonNull(process, "process");
      requireGeneration(process.source().generationId());
      if (processMap.putIfAbsent(process.processId(), process) != null) {
        throw new IllegalArgumentException("catalog process ids must be unique");
      }
      requireKnownResources(process);
      process.outputs().stream()
          .filter(ProcessRecord.Output::primary)
          .forEach(
              output ->
                  outputMap
                      .computeIfAbsent(
                          ResourceKey.from(output.resource()), ignored -> new ArrayList<>())
                      .add(process));
    }
    outputMap
        .values()
        .forEach(values -> values.sort(java.util.Comparator.comparing(ProcessRecord::processId)));
    var immutableOutputs = new LinkedHashMap<ResourceKey, List<ProcessRecord>>();
    outputMap.forEach((key, value) -> immutableOutputs.put(key, List.copyOf(value)));
    this.processes = Collections.unmodifiableMap(new LinkedHashMap<>(processMap));
    this.processesByOutput = Collections.unmodifiableMap(immutableOutputs);
  }

  public String generationId() {
    return generationId;
  }

  public String packFingerprint() {
    return packFingerprint;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public List<ResourceRef> resources() {
    return List.copyOf(resources.values());
  }

  public List<ProcessRecord> processes() {
    return List.copyOf(processes.values());
  }

  public Optional<ProcessRecord> process(String processId) {
    return Optional.ofNullable(processes.get(Objects.requireNonNull(processId, "processId")));
  }

  public Optional<ResourceRef> resource(ResourceKey key) {
    return Optional.ofNullable(resources.get(Objects.requireNonNull(key, "key")));
  }

  public List<ProcessRecord> processesProducing(ResourceKey key) {
    return processesByOutput.getOrDefault(Objects.requireNonNull(key, "key"), List.of());
  }

  public void requireCurrentGeneration(String expectedGenerationId) {
    if (!generationId.equals(expectedGenerationId)) {
      throw new IllegalArgumentException("catalog generation is stale");
    }
  }

  private void requireKnownResources(ProcessRecord process) {
    var referenced = new HashSet<ResourceKey>();
    process.workstations().forEach(resource -> referenced.add(ResourceKey.from(resource)));
    process
        .inputs()
        .forEach(
            group ->
                group
                    .alternatives()
                    .forEach(resource -> referenced.add(ResourceKey.from(resource))));
    process
        .catalysts()
        .forEach(
            catalyst -> {
              referenced.add(ResourceKey.from(catalyst.resource()));
              if (catalyst.returnedResource() != null) {
                referenced.add(ResourceKey.from(catalyst.returnedResource()));
              }
            });
    process.outputs().forEach(output -> referenced.add(ResourceKey.from(output.resource())));
    if (process.energy() != null) {
      referenced.add(ResourceKey.from(process.energy()));
    }
    if (!resources.keySet().containsAll(referenced)) {
      var missing = new HashSet<>(referenced);
      missing.removeAll(resources.keySet());
      throw new IllegalArgumentException(
          "process references resources outside the snapshot: " + missing);
    }
  }

  private void requireGeneration(String value) {
    if (!generationId.equals(value)) {
      throw new IllegalArgumentException("catalog entries must use one generation");
    }
  }

  private static String boundedGeneration(String value) {
    Objects.requireNonNull(value, "generationId");
    if (!GENERATION_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("generationId must be a bounded opaque id");
    }
    return value;
  }

  private static String sha256(String value) {
    Objects.requireNonNull(value, "packFingerprint");
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException("packFingerprint must be lowercase SHA-256");
    }
    return value;
  }
}
