package dev.minecraftagent.paper.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic proposal storage boundary. Persistent implementations must preserve compare-and-set. */
public interface ProposalRepository {
  enum State {
    PENDING,
    CLAIMED,
    EXECUTING,
    EXECUTED,
    REJECTED,
    FAILED,
    INVALIDATED
  }

  record Snapshot(StoredProposal proposal, State state) {
    public Snapshot {
      Objects.requireNonNull(proposal);
      Objects.requireNonNull(state);
    }
  }

  boolean insert(StoredProposal proposal);

  Optional<Snapshot> find(UUID proposalId);

  boolean transition(UUID proposalId, State expected, State updated);

  /**
   * Atomically invalidates all pending or claimed entries and returns the changed proposals.
   * EXECUTING means final admission already passed and must reach its audited terminal state.
   */
  List<StoredProposal> invalidateAll();

  /** Atomically invalidates pending or claimed entries owned by one disconnected player. */
  List<StoredProposal> invalidatePlayer(UUID playerUuid);

  /** Removes expired and terminal entries before admitting more untrusted work. */
  void prune(Instant now);
}
