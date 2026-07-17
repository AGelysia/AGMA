package dev.minecraftagent.standalone.core.adapter;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Combines the vanilla registry with exactly one selected process source. */
public final class CatalogAssembler {
  public CatalogSnapshot assemble(
      String generationId,
      String packFingerprint,
      Instant createdAt,
      CatalogAdapter.Contribution registry,
      CatalogAdapter.Contribution selectedProcesses) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(selectedProcesses, "selectedProcesses");
    if (!generationId.equals(registry.generationId())
        || !generationId.equals(selectedProcesses.generationId())) {
      throw new IllegalArgumentException("catalog contributions use different generations");
    }

    var resources = new TreeMap<ResourceKey, ResourceRef>();
    addResources(resources, registry.resources());
    addResources(resources, selectedProcesses.resources());
    var processes = new TreeMap<String, ProcessRecord>();
    for (var process : selectedProcesses.processes()) {
      if (processes.putIfAbsent(process.processId(), process) != null) {
        throw new IllegalArgumentException("selected process source returned duplicate ids");
      }
    }
    return new CatalogSnapshot(
        generationId,
        packFingerprint,
        createdAt,
        new ArrayList<>(resources.values()),
        new ArrayList<>(processes.values()));
  }

  private static void addResources(
      TreeMap<ResourceKey, ResourceRef> destination, List<ResourceRef> source) {
    for (var resource : source) {
      var key = ResourceKey.from(resource);
      var existing = destination.putIfAbsent(key, resource);
      if (existing != null && !equivalentMetadata(existing, resource)) {
        throw new IllegalArgumentException("catalog sources disagree about resource metadata");
      }
    }
  }

  private static boolean equivalentMetadata(ResourceRef left, ResourceRef right) {
    return left.kind() == right.kind()
        && left.id().equals(right.id())
        && Objects.equals(left.componentsFingerprint(), right.componentsFingerprint())
        && left.modId().equals(right.modId())
        && left.modVersion().equals(right.modVersion())
        && left.unit().equals(right.unit())
        && left.source().generationId().equals(right.source().generationId());
  }
}
