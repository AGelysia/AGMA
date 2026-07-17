package dev.minecraftagent.paper.capability.model;

import java.util.Objects;

/** Approval and publication identity for canonical manifest content. */
public record CapabilityIdentity(String id, int version, String contentSha256) {
  public CapabilityIdentity {
    Objects.requireNonNull(id);
    Objects.requireNonNull(contentSha256);
    if (!id.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+") || id.length() > 128 || version < 1) {
      throw new IllegalArgumentException("Invalid capability identity");
    }
    if (!contentSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid capability content hash");
    }
  }
}
