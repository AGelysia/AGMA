package dev.minecraftagent.standalone.core.adapter;

import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.util.List;
import java.util.Optional;

public interface CatalogAdapter {
  Descriptor descriptor();

  Probe probe();

  Contribution capture(
      String generationId,
      CatalogPublisher.Cancellation cancellation,
      CatalogPublisher.ProgressListener progress)
      throws Exception;

  /** Returns the hovered item id when a reviewed public viewer API exposes one. */
  default Optional<String> hoveredItemId() {
    return Optional.empty();
  }

  record Descriptor(String id, Kind kind, int priority) {
    public Descriptor {
      if (id == null || !id.matches("^[a-z][a-z0-9_.-]{0,63}$")) {
        throw new IllegalArgumentException("adapter id is invalid");
      }
      if (kind == null) {
        throw new NullPointerException("kind");
      }
      if (priority < 0 || priority > 1_000) {
        throw new IllegalArgumentException("adapter priority is out of range");
      }
    }
  }

  record Probe(Status status, String version, boolean reviewed, String diagnosticCode) {
    public Probe {
      if (status == null) {
        throw new NullPointerException("status");
      }
      if (version != null && !version.matches("^[0-9A-Za-z][0-9A-Za-z._+-]{0,127}$")) {
        throw new IllegalArgumentException("adapter version is invalid");
      }
      if (diagnosticCode == null || !diagnosticCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
        throw new IllegalArgumentException("adapter diagnostic code is invalid");
      }
      if (status == Status.READY && (!reviewed || version == null)) {
        throw new IllegalArgumentException("ready adapter must have a reviewed exact version");
      }
    }
  }

  record Contribution(
      String adapterId,
      String generationId,
      List<ResourceRef> resources,
      List<ProcessRecord> processes,
      List<String> warnings) {
    public Contribution {
      if (adapterId == null || !adapterId.matches("^[a-z][a-z0-9_.-]{0,63}$")) {
        throw new IllegalArgumentException("adapterId is invalid");
      }
      if (generationId == null || !generationId.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
        throw new IllegalArgumentException("generationId is invalid");
      }
      resources = List.copyOf(resources);
      processes = List.copyOf(processes);
      warnings = List.copyOf(warnings);
      if (warnings.size() > 64
          || warnings.stream()
              .anyMatch(value -> value == null || value.isBlank() || value.length() > 512)) {
        throw new IllegalArgumentException("adapter warnings are invalid");
      }
    }
  }

  enum Kind {
    VANILLA,
    JEI,
    EMI
  }

  enum Status {
    READY,
    NOT_INSTALLED,
    VERSION_UNAVAILABLE,
    INCOMPATIBLE,
    RELOADING,
    FAILED
  }
}
