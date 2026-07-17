package dev.minecraftagent.paper.capability.model;

import java.util.Objects;

/** Exact owner approval key. Approval never carries across a version or content change. */
public record CapabilityApproval(String id, int version, String contentSha256) {
  public CapabilityApproval {
    Objects.requireNonNull(id);
    Objects.requireNonNull(contentSha256);
    if (!id.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+") || id.length() > 128 || version < 1) {
      throw new IllegalArgumentException("Invalid capability approval");
    }
    if (!contentSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid capability approval hash");
    }
  }

  public static CapabilityApproval from(CapabilityIdentity identity) {
    Objects.requireNonNull(identity);
    return new CapabilityApproval(identity.id(), identity.version(), identity.contentSha256());
  }
}
