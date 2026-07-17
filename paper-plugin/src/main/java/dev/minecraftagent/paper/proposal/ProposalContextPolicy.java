package dev.minecraftagent.paper.proposal;

import java.util.Objects;
import java.util.UUID;

/** Fail-closed binding to the currently active request and ephemeral session context. */
@FunctionalInterface
public interface ProposalContextPolicy {
  boolean isActive(Context context);

  record Context(
      UUID requestId, UUID sessionId, UUID playerUuid, String tool, long catalogGeneration) {
    public Context {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(sessionId);
      Objects.requireNonNull(playerUuid);
      Objects.requireNonNull(tool);
    }
  }
}
