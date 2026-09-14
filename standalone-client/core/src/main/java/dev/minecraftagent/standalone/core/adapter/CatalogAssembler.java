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
    return assemble(
        generationId,
        packFingerprint,
        createdAt,
        registry,
        selectedProcesses,
        new CatalogAdapter.Contribution(
            "mod_archive", generationId, List.of(), List.of(), List.of()));
  }

  /**
   * Assembles the registry, the selected process source, and an optional gap-fill contribution.
   * Gap-fill processes are added only when their process id is absent (live data always wins), and
   * gap-fill resources are added only when their {@link ResourceKey} is absent; skipped gap
   * resources never trigger the metadata agreement check because the base metadata wins by
   * definition.
   */
  public CatalogSnapshot assemble(
      String generationId,
      String packFingerprint,
      Instant createdAt,
      CatalogAdapter.Contribution registry,
      CatalogAdapter.Contribution selectedProcesses,
      CatalogAdapter.Contribution gapFillProcesses) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(selectedProcesses, "selectedProcesses");
    Objects.requireNonNull(gapFillProcesses, "gapFillProcesses");
    if (!generationId.equals(registry.generationId())
        || !generationId.equals(selectedProcesses.generationId())
        || !generationId.equals(gapFillProcesses.generationId())) {
      throw new IllegalArgumentException("catalog contributions use different generations");
    }

    var resources = new TreeMap<ResourceKey, ResourceRef>();
    addResources(resources, registry.resources());
    addResources(resources, selectedProcesses.resources());
    addGapResources(resources, gapFillProcesses.resources());
    var processes = new TreeMap<String, ProcessRecord>();
    for (var process : selectedProcesses.processes()) {
      if (processes.putIfAbsent(process.processId(), process) != null) {
        throw new IllegalArgumentException("selected process source returned duplicate ids");
      }
    }
    for (var process : gapFillProcesses.processes()) {
      processes.putIfAbsent(process.processId(), process);
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

  private static void addGapResources(
      TreeMap<ResourceKey, ResourceRef> destination, List<ResourceRef> source) {
    for (var resource : source) {
      destination.putIfAbsent(ResourceKey.from(resource), resource);
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
