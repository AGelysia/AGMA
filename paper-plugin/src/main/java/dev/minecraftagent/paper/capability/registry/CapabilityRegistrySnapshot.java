package dev.minecraftagent.paper.capability.registry;

import dev.minecraftagent.paper.capability.model.CapabilityDraft;
import dev.minecraftagent.paper.capability.model.EffectiveCapability;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One immutable, generation-bound capability catalog. */
public final class CapabilityRegistrySnapshot {
  private final long generation;
  private final Map<String, EffectiveCapability> effectiveCapabilities;
  private final List<CapabilityDraft> drafts;

  CapabilityRegistrySnapshot(
      long generation,
      Map<String, EffectiveCapability> effectiveCapabilities,
      List<CapabilityDraft> drafts) {
    if (generation < 0) {
      throw new IllegalArgumentException("Invalid capability generation");
    }
    this.generation = generation;
    this.effectiveCapabilities =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(effectiveCapabilities)));
    this.drafts = List.copyOf(drafts);
  }

  public long generation() {
    return generation;
  }

  public Map<String, EffectiveCapability> effectiveCapabilities() {
    return effectiveCapabilities;
  }

  public List<CapabilityDraft> drafts() {
    return drafts;
  }
}
