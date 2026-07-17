package dev.minecraftagent.paper.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class OperationalGate {
  private final Object transitionLock = new Object();
  private final AtomicReference<Snapshot> snapshot;

  public OperationalGate() {
    this(AgentState.UNREGISTERED);
  }

  public OperationalGate(AgentState initialState) {
    snapshot = new AtomicReference<>(new Snapshot(Objects.requireNonNull(initialState), 0));
  }

  public long transitionTo(AgentState nextState) {
    Objects.requireNonNull(nextState);
    synchronized (transitionLock) {
      return snapshot
          .updateAndGet(current -> new Snapshot(nextState, incrementEpoch(current.epoch())))
          .epoch();
    }
  }

  public AgentState state() {
    return snapshot.get().state();
  }

  public long epoch() {
    return snapshot.get().epoch();
  }

  public Optional<Permit> tryAcquire() {
    synchronized (transitionLock) {
      var current = snapshot.get();
      if (current.state() != AgentState.ONLINE) {
        return Optional.empty();
      }
      return Optional.of(new Permit(this, current.epoch()));
    }
  }

  public boolean revalidate(Permit permit) {
    Objects.requireNonNull(permit);
    synchronized (transitionLock) {
      return valid(permit, snapshot.get());
    }
  }

  public boolean executeIfValid(Permit permit, Runnable operation) {
    Objects.requireNonNull(permit);
    Objects.requireNonNull(operation);
    synchronized (transitionLock) {
      if (!valid(permit, snapshot.get())) {
        return false;
      }
      operation.run();
      return true;
    }
  }

  private boolean valid(Permit permit, Snapshot current) {
    return permit.owner == this
        && current.state() == AgentState.ONLINE
        && current.epoch() == permit.epoch;
  }

  private static long incrementEpoch(long epoch) {
    try {
      return Math.incrementExact(epoch);
    } catch (ArithmeticException error) {
      throw new IllegalStateException("Operational gate epoch exhausted", error);
    }
  }

  private record Snapshot(AgentState state, long epoch) {}

  public static final class Permit {
    private final OperationalGate owner;
    private final long epoch;

    private Permit(OperationalGate owner, long epoch) {
      this.owner = owner;
      this.epoch = epoch;
    }

    public long epoch() {
      return epoch;
    }
  }
}
