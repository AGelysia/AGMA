package dev.minecraftagent.client.litematica;

import java.util.Objects;
import java.util.Optional;

public record LitematicaCompatibility(
    Status status,
    DetectedVersions detectedVersions,
    Optional<LitematicaSupportMatrix.Entry> supportedCombination,
    Optional<LitematicaAdapter> adapter) {
  public enum Status {
    READY,
    NOT_INSTALLED,
    MISSING_DEPENDENCY,
    UNSUPPORTED_VERSION,
    ADAPTER_LINKAGE_FAILED
  }

  public LitematicaCompatibility {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(detectedVersions, "detectedVersions");
    Objects.requireNonNull(supportedCombination, "supportedCombination");
    Objects.requireNonNull(adapter, "adapter");
    boolean linkedCombination = status == Status.READY || status == Status.ADAPTER_LINKAGE_FAILED;
    if ((status == Status.READY) != adapter.isPresent()
        || linkedCombination != supportedCombination.isPresent()) {
      throw new IllegalArgumentException("compatibility status and adapter do not agree");
    }
  }

  public record DetectedVersions(
      String minecraftVersion,
      String fabricLoaderVersion,
      Optional<String> litematicaVersion,
      Optional<String> malilibVersion) {
    public DetectedVersions {
      Objects.requireNonNull(minecraftVersion, "minecraftVersion");
      Objects.requireNonNull(fabricLoaderVersion, "fabricLoaderVersion");
      Objects.requireNonNull(litematicaVersion, "litematicaVersion");
      Objects.requireNonNull(malilibVersion, "malilibVersion");
    }
  }
}
