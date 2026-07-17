package dev.minecraftagent.paper.proposal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryProposalRepository implements ProposalRepository {
  private static final int DEFAULT_GLOBAL_ACTIVE_LIMIT = 256;
  private static final int DEFAULT_PLAYER_ACTIVE_LIMIT = 16;
  private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
  private final int globalActiveLimit;
  private final int playerActiveLimit;

  public InMemoryProposalRepository() {
    this(DEFAULT_GLOBAL_ACTIVE_LIMIT, DEFAULT_PLAYER_ACTIVE_LIMIT);
  }

  public InMemoryProposalRepository(int globalActiveLimit, int playerActiveLimit) {
    if (globalActiveLimit < 1 || playerActiveLimit < 1 || playerActiveLimit > globalActiveLimit) {
      throw new IllegalArgumentException("Invalid proposal repository limits");
    }
    this.globalActiveLimit = globalActiveLimit;
    this.playerActiveLimit = playerActiveLimit;
  }

  @Override
  public synchronized boolean insert(StoredProposal proposal) {
    long globalActive = entries.values().stream().filter(Entry::active).count();
    long playerActive =
        entries.values().stream()
            .filter(Entry::active)
            .filter(entry -> entry.proposal.playerUuid().equals(proposal.playerUuid()))
            .count();
    if (globalActive >= globalActiveLimit || playerActive >= playerActiveLimit) {
      return false;
    }
    return entries.putIfAbsent(proposal.proposalId(), new Entry(proposal)) == null;
  }

  @Override
  public Optional<Snapshot> find(UUID proposalId) {
    var entry = entries.get(proposalId);
    return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
  }

  @Override
  public synchronized boolean transition(UUID proposalId, State expected, State updated) {
    var entry = entries.get(proposalId);
    return entry != null && entry.state.compareAndSet(expected, updated);
  }

  @Override
  public synchronized List<StoredProposal> invalidateAll() {
    return invalidateMatching(ignored -> true);
  }

  @Override
  public synchronized List<StoredProposal> invalidatePlayer(UUID playerUuid) {
    return invalidateMatching(proposal -> proposal.playerUuid().equals(playerUuid));
  }

  private List<StoredProposal> invalidateMatching(
      java.util.function.Predicate<StoredProposal> predicate) {
    var invalidated = new ArrayList<StoredProposal>();
    entries.forEach(
        (ignored, entry) -> {
          if (predicate.test(entry.proposal)
              && (entry.state.compareAndSet(State.PENDING, State.INVALIDATED)
                  || entry.state.compareAndSet(State.CLAIMED, State.INVALIDATED))) {
            invalidated.add(entry.proposal);
          }
        });
    return List.copyOf(invalidated);
  }

  @Override
  public synchronized void prune(Instant now) {
    entries.entrySet().removeIf(entry -> entry.getValue().reclaimable(now));
  }

  private static final class Entry {
    private final StoredProposal proposal;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    private Entry(StoredProposal proposal) {
      this.proposal = proposal;
    }

    private Snapshot snapshot() {
      return new Snapshot(proposal, state.get());
    }

    private boolean active() {
      var current = state.get();
      return current == State.PENDING || current == State.CLAIMED || current == State.EXECUTING;
    }

    private boolean reclaimable(Instant now) {
      var current = state.get();
      return !active() || (current == State.PENDING && !now.isBefore(proposal.expiresAt()));
    }
  }
}
