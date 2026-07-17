package dev.minecraftagent.paper.capability.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One discovered manifest and its stable, non-executable load disposition. */
public record CapabilityDraft(
    String source,
    Optional<CapabilityManifest> manifest,
    Optional<CapabilityIdentity> identity,
    List<CapabilityDiagnostic> diagnostics) {
  public CapabilityDraft {
    Objects.requireNonNull(source);
    if (source.isBlank() || source.startsWith("/") || source.contains("\\")) {
      throw new IllegalArgumentException("Invalid capability source name");
    }
    manifest = Objects.requireNonNull(manifest);
    identity = Objects.requireNonNull(identity);
    diagnostics =
        diagnostics.stream()
            .sorted(
                Comparator.comparing((CapabilityDiagnostic value) -> value.code().ordinal())
                    .thenComparing(CapabilityDiagnostic::field))
            .distinct()
            .toList();
  }

  public boolean enabled() {
    return manifest.isPresent() && identity.isPresent() && diagnostics.isEmpty();
  }
}
