package dev.minecraftagent.paper.proposal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Paper-owned proposal state suitable for a future persistent repository. */
public record StoredProposal(
    UUID proposalId,
    String serverId,
    UUID requestId,
    UUID sessionId,
    UUID playerUuid,
    String tool,
    long catalogGeneration,
    RiskLevel risk,
    String displayName,
    CanonicalArguments.Frozen arguments,
    Instant createdAt,
    Instant expiresAt) {
  public StoredProposal {
    Objects.requireNonNull(proposalId);
    Objects.requireNonNull(serverId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(risk);
    Objects.requireNonNull(displayName);
    Objects.requireNonNull(arguments);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("Proposal expiry must follow creation");
    }
  }
}
