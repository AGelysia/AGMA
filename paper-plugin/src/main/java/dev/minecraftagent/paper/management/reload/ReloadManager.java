package dev.minecraftagent.paper.management.reload;

import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.startup.PaperStartupConfig;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** Loads complete candidates on a worker and atomically publishes local Paper policy only. */
public final class ReloadManager implements AutoCloseable {
  @FunctionalInterface
  public interface CandidateSource {
    ReloadCandidate load() throws StartupFailure;
  }

  public enum Disposition {
    STARTED,
    IN_PROGRESS,
    MANAGER_CLOSED,
    NOT_ONLINE,
    WORKER_REJECTED
  }

  public record Request(Disposition disposition, CompletionStage<ReloadResult> completion) {
    public Request {
      Objects.requireNonNull(disposition);
      Objects.requireNonNull(completion);
    }
  }

  private final Executor worker;
  private final CandidateSource candidates;
  private final ReloadCandidate trustedStartup;
  private final OperationalGate operationalGate;
  private final AtomicReference<State> state;

  public ReloadManager(
      Executor worker, CandidateSource candidates, PaperStartupConfig trustedStartup) {
    this(worker, candidates, ReloadCandidate.from(trustedStartup), null);
  }

  public ReloadManager(
      Executor worker,
      CandidateSource candidates,
      PaperStartupConfig trustedStartup,
      OperationalGate operationalGate) {
    this(worker, candidates, ReloadCandidate.from(trustedStartup), operationalGate);
  }

  public ReloadManager(
      Executor worker, CandidateSource candidates, ReloadCandidate trustedStartup) {
    this(worker, candidates, trustedStartup, null);
  }

  private ReloadManager(
      Executor worker,
      CandidateSource candidates,
      ReloadCandidate trustedStartup,
      OperationalGate operationalGate) {
    this.worker = Objects.requireNonNull(worker);
    this.candidates = Objects.requireNonNull(candidates);
    this.trustedStartup = Objects.requireNonNull(trustedStartup);
    this.operationalGate = operationalGate;
    validateTrustedStartup(trustedStartup);
    state =
        new AtomicReference<>(new State(true, trustedStartup.policySnapshot(0), Optional.empty()));
  }

  public ReloadPolicySnapshot snapshot() {
    return state.get().snapshot();
  }

  public Optional<ReloadResult.Code> restartRequired(PaperStartupConfig candidate) {
    return restartRequired(ReloadCandidate.from(Objects.requireNonNull(candidate)));
  }

  public Request reload() {
    while (true) {
      var current = state.get();
      if (!current.accepting()) {
        return completedRequest(
            Disposition.MANAGER_CLOSED,
            rejected(ReloadResult.Code.RELOAD_MANAGER_CLOSED, current.snapshot()));
      }
      if (current.attempt().isPresent()) {
        return completedRequest(
            Disposition.IN_PROGRESS,
            rejected(ReloadResult.Code.RELOAD_IN_PROGRESS, current.snapshot()));
      }

      OperationalGate.Permit permit = null;
      if (operationalGate != null) {
        permit = operationalGate.tryAcquire().orElse(null);
        if (permit == null) {
          return completedRequest(
              Disposition.NOT_ONLINE,
              rejected(ReloadResult.Code.RELOAD_OPERATION_NOT_ONLINE, current.snapshot()));
        }
      }

      var attempt = new Attempt(current.snapshot(), permit);
      var claimed = new State(true, current.snapshot(), Optional.of(attempt));
      if (!state.compareAndSet(current, claimed)) {
        continue;
      }

      try {
        worker.execute(() -> loadAndPublish(attempt));
        return new Request(Disposition.STARTED, attempt.completion().copy());
      } catch (RuntimeException error) {
        var result =
            finishWithoutPublication(
                attempt, rejected(ReloadResult.Code.RELOAD_WORKER_REJECTED, attempt.base()));
        attempt.completion().complete(result);
        var disposition =
            result.code() == ReloadResult.Code.RELOAD_WORKER_REJECTED
                ? Disposition.WORKER_REJECTED
                : Disposition.MANAGER_CLOSED;
        return completedRequest(disposition, result);
      }
    }
  }

  @Override
  public void close() {
    while (true) {
      var current = state.get();
      if (!current.accepting()) {
        return;
      }
      var closed = new State(false, current.snapshot(), Optional.empty());
      if (!state.compareAndSet(current, closed)) {
        continue;
      }
      current
          .attempt()
          .ifPresent(
              attempt ->
                  attempt
                      .completion()
                      .complete(
                          stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, current.snapshot())));
      return;
    }
  }

  private void loadAndPublish(Attempt attempt) {
    if (!isCurrent(attempt)) {
      attempt.completion().complete(stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, snapshot()));
      return;
    }
    if (!publicationAllowed(attempt)) {
      finishAttempt(attempt, stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, attempt.base()));
      return;
    }

    ReloadCandidate candidate;
    try {
      candidate = Objects.requireNonNull(candidates.load());
      candidate.securityPolicy().validate();
    } catch (StartupFailure failure) {
      finishAttempt(
          attempt,
          new ReloadResult(
              ReloadResult.Status.REJECTED,
              ReloadResult.Code.RELOAD_CONFIG_REJECTED,
              attempt.base(),
              Optional.of(failure.code())));
      return;
    } catch (RuntimeException error) {
      finishAttempt(
          attempt, rejected(ReloadResult.Code.RELOAD_CANDIDATE_LOAD_FAILED, attempt.base()));
      return;
    }

    var restartRequired = restartRequired(candidate);
    if (restartRequired.isPresent()) {
      finishAttempt(attempt, rejected(restartRequired.orElseThrow(), attempt.base()));
      return;
    }
    if (samePolicy(attempt.base(), candidate)) {
      finishAttempt(
          attempt,
          new ReloadResult(
              ReloadResult.Status.UNCHANGED,
              ReloadResult.Code.RELOAD_UNCHANGED,
              attempt.base(),
              Optional.empty()));
      return;
    }
    if (attempt.base().generation() == Long.MAX_VALUE) {
      finishAttempt(
          attempt, rejected(ReloadResult.Code.RELOAD_GENERATION_EXHAUSTED, attempt.base()));
      return;
    }

    publish(attempt, candidate.policySnapshot(attempt.base().generation() + 1));
  }

  private void publish(Attempt attempt, ReloadPolicySnapshot proposed) {
    while (true) {
      var current = state.get();
      if (!isCurrent(current, attempt)) {
        attempt
            .completion()
            .complete(stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, current.snapshot()));
        return;
      }
      var published = new State(true, proposed, Optional.empty());
      boolean gateValid;
      boolean stateChanged;
      if (operationalGate == null) {
        gateValid = true;
        stateChanged = state.compareAndSet(current, published);
      } else {
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        gateValid =
            operationalGate.executeIfValid(
                Objects.requireNonNull(attempt.permit()),
                () -> changed.set(state.compareAndSet(current, published)));
        stateChanged = changed.get();
      }
      if (!gateValid) {
        attempt
            .completion()
            .complete(
                finishWithoutPublication(
                    attempt, stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, current.snapshot())));
        return;
      }
      if (!stateChanged) {
        continue;
      }
      attempt
          .completion()
          .complete(
              new ReloadResult(
                  ReloadResult.Status.APPLIED,
                  ReloadResult.Code.RELOAD_APPLIED,
                  proposed,
                  Optional.empty()));
      return;
    }
  }

  private void finishAttempt(Attempt attempt, ReloadResult intended) {
    attempt.completion().complete(finishWithoutPublication(attempt, intended));
  }

  private ReloadResult finishWithoutPublication(Attempt attempt, ReloadResult intended) {
    while (true) {
      var current = state.get();
      if (!isCurrent(current, attempt)) {
        return stale(ReloadResult.Code.RELOAD_STALE_COMPLETION, current.snapshot());
      }
      var released = new State(true, current.snapshot(), Optional.empty());
      if (state.compareAndSet(current, released)) {
        return intended;
      }
    }
  }

  private boolean isCurrent(Attempt attempt) {
    return isCurrent(state.get(), attempt);
  }

  private boolean publicationAllowed(Attempt attempt) {
    return operationalGate == null
        || (attempt.permit() != null && operationalGate.revalidate(attempt.permit()));
  }

  private static boolean isCurrent(State current, Attempt attempt) {
    return current.accepting()
        && current.snapshot() == attempt.base()
        && current.attempt().filter(active -> active == attempt).isPresent();
  }

  private Optional<ReloadResult.Code> restartRequired(ReloadCandidate candidate) {
    if (!trustedStartup.serverId().equals(candidate.serverId())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_SERVER_ID);
    }
    if (trustedStartup.runtimeMode() != candidate.runtimeMode()) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_MODE);
    }
    if (!trustedStartup.runtimeEndpoint().equals(candidate.runtimeEndpoint())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_ENDPOINT);
    }
    if (!trustedStartup.serverToken().equals(candidate.serverToken())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_TOKEN);
    }
    if (!trustedStartup.connectTimeout().equals(candidate.connectTimeout())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_CONNECT_TIMEOUT);
    }
    if (!trustedStartup.handshakeTimeout().equals(candidate.handshakeTimeout())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_HANDSHAKE_TIMEOUT);
    }
    if (!trustedStartup.stateDirectory().equals(candidate.stateDirectory())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_STATE_DIRECTORY);
    }
    if (!trustedStartup.capabilityDirectory().equals(candidate.capabilityDirectory())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_CAPABILITY_DIRECTORY);
    }
    if (!trustedStartup.capabilityApprovals().equals(candidate.capabilityApprovals())) {
      return Optional.of(ReloadResult.Code.RELOAD_RESTART_REQUIRED_CAPABILITY_APPROVALS);
    }
    return Optional.empty();
  }

  private static boolean samePolicy(ReloadPolicySnapshot current, ReloadCandidate candidate) {
    return current.owners().equals(candidate.owners())
        && current.securityPolicy().equals(candidate.securityPolicy());
  }

  private static void validateTrustedStartup(ReloadCandidate candidate) {
    try {
      candidate.securityPolicy().validate();
    } catch (StartupFailure failure) {
      throw new IllegalArgumentException("Trusted startup policy is invalid", failure);
    }
  }

  private static Request completedRequest(Disposition disposition, ReloadResult result) {
    return new Request(disposition, CompletableFuture.completedFuture(result));
  }

  private static ReloadResult rejected(ReloadResult.Code code, ReloadPolicySnapshot snapshot) {
    return new ReloadResult(ReloadResult.Status.REJECTED, code, snapshot, Optional.empty());
  }

  private static ReloadResult stale(ReloadResult.Code code, ReloadPolicySnapshot snapshot) {
    return new ReloadResult(ReloadResult.Status.STALE, code, snapshot, Optional.empty());
  }

  private record State(
      boolean accepting, ReloadPolicySnapshot snapshot, Optional<Attempt> attempt) {
    private State {
      Objects.requireNonNull(snapshot);
      attempt = Objects.requireNonNull(attempt);
    }
  }

  private record Attempt(
      ReloadPolicySnapshot base,
      OperationalGate.Permit permit,
      CompletableFuture<ReloadResult> completion) {
    private Attempt(ReloadPolicySnapshot base, OperationalGate.Permit permit) {
      this(Objects.requireNonNull(base), permit, new CompletableFuture<>());
    }
  }
}
