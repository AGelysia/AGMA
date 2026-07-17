package dev.minecraftagent.paper.command;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Command-facing boundary for responding to a Paper-owned proposal. */
public interface ProposalResponseGateway {
  enum Result {
    CONFIRMED,
    REJECTED,
    UNAVAILABLE,
    FAILED
  }

  default CompletionStage<Result> confirm(UUID playerId, UUID proposalId) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(proposalId);
    return CompletableFuture.completedFuture(Result.UNAVAILABLE);
  }

  default CompletionStage<Result> reject(UUID playerId, UUID proposalId) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(proposalId);
    return CompletableFuture.completedFuture(Result.UNAVAILABLE);
  }

  static ProposalResponseGateway unavailable() {
    return new ProposalResponseGateway() {};
  }
}
