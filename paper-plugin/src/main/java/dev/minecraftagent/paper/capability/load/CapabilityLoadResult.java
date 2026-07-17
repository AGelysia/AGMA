package dev.minecraftagent.paper.capability.load;

import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import dev.minecraftagent.paper.capability.model.CapabilityDraft;
import dev.minecraftagent.paper.capability.model.EffectiveCapability;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete proposed pack state, or a non-publishable result when discovery was incomplete. */
public final class CapabilityLoadResult {
  private final boolean complete;
  private final Map<String, EffectiveCapability> effectiveCapabilities;
  private final List<CapabilityDraft> drafts;
  private final List<CapabilityDiagnostic> globalDiagnostics;

  CapabilityLoadResult(
      boolean complete,
      Map<String, EffectiveCapability> effectiveCapabilities,
      List<CapabilityDraft> drafts,
      List<CapabilityDiagnostic> globalDiagnostics) {
    this.complete = complete;
    this.effectiveCapabilities =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(effectiveCapabilities)));
    this.drafts = List.copyOf(drafts);
    this.globalDiagnostics = List.copyOf(globalDiagnostics);
    if (!complete && !this.effectiveCapabilities.isEmpty()) {
      throw new IllegalArgumentException(
          "Incomplete capability load cannot expose effective entries");
    }
    for (var entry : this.effectiveCapabilities.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().identity().id())) {
        throw new IllegalArgumentException("Capability map key does not match identity");
      }
    }
  }

  public boolean complete() {
    return complete;
  }

  public Map<String, EffectiveCapability> effectiveCapabilities() {
    return effectiveCapabilities;
  }

  public List<CapabilityDraft> drafts() {
    return drafts;
  }

  public List<CapabilityDiagnostic> globalDiagnostics() {
    return globalDiagnostics;
  }
}
