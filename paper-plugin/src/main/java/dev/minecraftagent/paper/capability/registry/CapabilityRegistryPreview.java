package dev.minecraftagent.paper.capability.registry;

import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import java.util.List;
import java.util.Objects;

/** Side-effect-free preview that can only be published by its originating registry. */
public final class CapabilityRegistryPreview {
  private final Object registryToken;
  private final CapabilityRegistrySnapshot base;
  private final CapabilityRegistrySnapshot proposed;
  private final CapabilityRegistryDiff diff;
  private final boolean publishable;
  private final List<CapabilityDiagnostic> diagnostics;

  CapabilityRegistryPreview(
      Object registryToken,
      CapabilityRegistrySnapshot base,
      CapabilityRegistrySnapshot proposed,
      CapabilityRegistryDiff diff,
      boolean publishable,
      List<CapabilityDiagnostic> diagnostics) {
    this.registryToken = Objects.requireNonNull(registryToken);
    this.base = Objects.requireNonNull(base);
    this.proposed = Objects.requireNonNull(proposed);
    this.diff = Objects.requireNonNull(diff);
    this.publishable = publishable;
    this.diagnostics = List.copyOf(diagnostics);
  }

  public long baseGeneration() {
    return base.generation();
  }

  public CapabilityRegistrySnapshot proposed() {
    return proposed;
  }

  public CapabilityRegistryDiff diff() {
    return diff;
  }

  public boolean publishable() {
    return publishable;
  }

  public List<CapabilityDiagnostic> diagnostics() {
    return diagnostics;
  }

  Object registryToken() {
    return registryToken;
  }

  CapabilityRegistrySnapshot base() {
    return base;
  }
}
