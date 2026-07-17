package dev.minecraftagent.standalone.supervisor;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Platform-neutral owner of one Runtime child. All launch, probe, retry, and stop work is scheduled
 * away from the caller; only the exact process returned by {@link RuntimeProcessLauncher} is ever
 * terminated.
 */
public final class RuntimeSupervisor implements AutoCloseable {
  private static final SupervisorScheduler.Cancellable NONE = () -> {};

  private final RuntimePathPolicy pathPolicy;
  private final RuntimeProcessLauncher launcher;
  private final RuntimeHealthProbe healthProbe;
  private final SupervisorScheduler scheduler;
  private final SupervisorPolicy policy;

  private RuntimeLaunchSpec configured;
  private SupervisorState state = SupervisorState.UNCONFIGURED;
  private Attempt current;
  private String failureCode;
  private long generation;
  private boolean closed;

  public RuntimeSupervisor(
      RuntimePathPolicy pathPolicy,
      RuntimeProcessLauncher launcher,
      RuntimeHealthProbe healthProbe,
      SupervisorScheduler scheduler,
      SupervisorPolicy policy) {
    this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public synchronized SupervisorSnapshot configure(RuntimeLaunchSpec requested) {
    requireOpen();
    Objects.requireNonNull(requested, "requested");
    if (state == SupervisorState.STARTING
        || state == SupervisorState.READY
        || state == SupervisorState.STOPPING
        || hasLiveProcess()) {
      throw failure("CONFIGURATION_BUSY", "Runtime configuration cannot change while active");
    }
    RuntimeLaunchSpec prepared;
    try {
      prepared = Objects.requireNonNull(pathPolicy.prepare(requested), "prepared launch spec");
    } catch (SupervisorException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw failure("PATH_POLICY_FAILED", "Runtime paths could not be prepared", exception);
    }
    if (!prepared.instanceId().equals(requested.instanceId())) {
      throw failure("PATH_POLICY_FAILED", "Runtime path preparation changed the instance identity");
    }
    configured = prepared;
    current = null;
    failureCode = null;
    state = SupervisorState.STOPPED;
    return snapshotLocked();
  }

  public CompletionStage<SupervisorSnapshot> start() {
    synchronized (this) {
      requireOpen();
      if (configured == null) {
        throw failure("NOT_CONFIGURED", "Runtime is not configured");
      }
      if (state == SupervisorState.READY) {
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      if ((state == SupervisorState.STARTING || state == SupervisorState.ERROR)
          && current != null
          && !current.stopRequested
          && !current.readiness.isDone()) {
        return current.readiness;
      }
      if (state == SupervisorState.STOPPING || hasLiveProcess()) {
        throw failure("START_BUSY", "Runtime is still stopping");
      }
      var attempt = new Attempt(++generation, configured);
      current = attempt;
      state = SupervisorState.STARTING;
      failureCode = null;
      scheduleLaunchLocked(attempt, Duration.ZERO);
      return attempt.readiness;
    }
  }

  public CompletionStage<SupervisorSnapshot> cancelStart() {
    OwnedRuntimeProcess process;
    CompletionStage<SupervisorSnapshot> stopped;
    synchronized (this) {
      requireOpen();
      if (state != SupervisorState.STARTING && state != SupervisorState.ERROR) {
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      var attempt = current;
      if (attempt == null) {
        state = SupervisorState.STOPPED;
        failureCode = null;
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      if (!attempt.readiness.isDone()) {
        attempt.readiness.completeExceptionally(
            new CancellationException("Runtime start cancelled"));
      }
      process = beginStopLocked(attempt, null);
      stopped = attempt.stopped;
    }
    requestStop(process);
    return stopped;
  }

  public CompletionStage<SupervisorSnapshot> stop() {
    OwnedRuntimeProcess process;
    CompletionStage<SupervisorSnapshot> stopped;
    synchronized (this) {
      if (state == SupervisorState.UNCONFIGURED || state == SupervisorState.STOPPED) {
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      var attempt = current;
      if (attempt == null) {
        state = configured == null ? SupervisorState.UNCONFIGURED : SupervisorState.STOPPED;
        failureCode = null;
        return CompletableFuture.completedFuture(snapshotLocked());
      }
      process = beginStopLocked(attempt, null);
      stopped = attempt.stopped;
    }
    requestStop(process);
    return stopped;
  }

  public synchronized SupervisorSnapshot snapshot() {
    return snapshotLocked();
  }

  @Override
  public void close() {
    CompletionStage<SupervisorSnapshot> stopped;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      stopped = stopForCloseLocked();
    }
    stopped.whenComplete((ignored, failure) -> scheduler.close());
  }

  private CompletionStage<SupervisorSnapshot> stopForCloseLocked() {
    if (state == SupervisorState.UNCONFIGURED || state == SupervisorState.STOPPED) {
      return CompletableFuture.completedFuture(snapshotLocked());
    }
    var attempt = current;
    if (attempt == null) {
      state = configured == null ? SupervisorState.UNCONFIGURED : SupervisorState.STOPPED;
      failureCode = null;
      return CompletableFuture.completedFuture(snapshotLocked());
    }
    var process = beginStopLocked(attempt, null);
    scheduler.schedule(() -> requestStop(process), Duration.ZERO);
    return attempt.stopped;
  }

  private void launch(Attempt attempt) {
    final OwnedRuntimeProcess process;
    try {
      process = Objects.requireNonNull(launcher.start(attempt.spec), "launched process");
      if (process.pid() < 1) {
        throw new IllegalStateException("Runtime process has an invalid pid");
      }
    } catch (IOException | RuntimeException exception) {
      handleLaunchFailure(attempt, exception);
      return;
    }

    var stale = false;
    synchronized (this) {
      if (!active(attempt) || attempt.stopRequested) {
        stale = true;
      } else {
        attempt.process = process;
      }
    }
    if (stale) {
      requestStop(process);
      return;
    }

    try {
      process.onExit().whenComplete((exitCode, exception) -> processExited(attempt, process));
    } catch (RuntimeException exception) {
      var shouldStop = false;
      synchronized (this) {
        if (active(attempt) && attempt.process == process) {
          attempt.readiness.completeExceptionally(
              failure("PROCESS_MONITOR_FAILED", "Runtime process could not be monitored"));
          beginStopLocked(attempt, "PROCESS_MONITOR_FAILED");
          shouldStop = true;
        }
      }
      if (shouldStop) {
        requestStop(process);
      }
      return;
    }

    synchronized (this) {
      if (!active(attempt)
          || attempt.stopRequested
          || attempt.process != process
          || state != SupervisorState.STARTING) {
        return;
      }
      attempt.startTimeout =
          scheduler.schedule(() -> startTimedOut(attempt, process), policy.startTimeout());
      scheduleHealthPollLocked(attempt, process, Duration.ZERO);
    }
  }

  private void handleLaunchFailure(Attempt attempt, Exception exception) {
    synchronized (this) {
      if (active(attempt) && !attempt.stopRequested) {
        retryOrFailLocked(attempt, "START_FAILED");
      }
    }
  }

  private void scheduleHealthPollLocked(
      Attempt attempt, OwnedRuntimeProcess process, Duration delay) {
    attempt.healthPoll = scheduler.schedule(() -> pollHealth(attempt, process), delay);
  }

  private void pollHealth(Attempt attempt, OwnedRuntimeProcess process) {
    final CompletionStage<Boolean> result;
    synchronized (this) {
      if (!activeStartingProcess(attempt, process)) {
        return;
      }
      attempt.healthPoll = NONE;
    }
    try {
      result = Objects.requireNonNull(healthProbe.isReady(process, attempt.spec), "health result");
    } catch (RuntimeException exception) {
      healthCompleted(attempt, process, false);
      return;
    }
    result.whenComplete(
        (ready, exception) ->
            healthCompleted(attempt, process, exception == null && Boolean.TRUE.equals(ready)));
  }

  private void healthCompleted(Attempt attempt, OwnedRuntimeProcess process, boolean ready) {
    synchronized (this) {
      if (!activeStartingProcess(attempt, process)) {
        return;
      }
      if (!ready) {
        scheduleHealthPollLocked(attempt, process, policy.healthPollInterval());
        return;
      }
      attempt.startTimeout.cancel();
      attempt.startTimeout = NONE;
      state = SupervisorState.READY;
      failureCode = null;
      attempt.readiness.complete(snapshotLocked());
    }
  }

  private void startTimedOut(Attempt attempt, OwnedRuntimeProcess process) {
    CompletionStage<SupervisorSnapshot> ignored;
    synchronized (this) {
      if (!activeStartingProcess(attempt, process)) {
        return;
      }
      attempt.readiness.completeExceptionally(
          failure("START_TIMEOUT", "Runtime did not become ready before its deadline"));
      beginStopLocked(attempt, "START_TIMEOUT");
      ignored = attempt.stopped;
    }
    requestStop(process);
    ignored.exceptionally(failure -> null);
  }

  private void processExited(Attempt attempt, OwnedRuntimeProcess process) {
    synchronized (this) {
      if (!active(attempt) || attempt.process != process) {
        return;
      }
      attempt.process = null;
      if (attempt.stopRequested) {
        finishStopLocked(attempt);
      } else {
        retryOrFailLocked(attempt, "RUNTIME_EXITED");
      }
    }
  }

  private OwnedRuntimeProcess beginStopLocked(Attempt attempt, String terminalFailure) {
    if (attempt.stopRequested) {
      return attempt.process;
    }
    attempt.stopRequested = true;
    attempt.terminalFailure = terminalFailure;
    cancelLifecycleTasks(attempt);
    state = SupervisorState.STOPPING;
    failureCode = null;
    if (!attempt.readiness.isDone()) {
      attempt.readiness.completeExceptionally(
          terminalFailure == null
              ? new CancellationException("Runtime stopped before it became ready")
              : failure(terminalFailure, "Runtime startup failed"));
    }
    var process = attempt.process;
    if (process == null || !process.isAlive()) {
      finishStopLocked(attempt);
      return null;
    }
    attempt.forceStop = scheduler.schedule(() -> forceStop(attempt, process), policy.stopTimeout());
    return process;
  }

  private void forceStop(Attempt attempt, OwnedRuntimeProcess process) {
    synchronized (this) {
      if (!active(attempt) || !attempt.stopRequested || attempt.process != process) {
        return;
      }
      attempt.forceStop = NONE;
      if (!process.isAlive()) {
        attempt.process = null;
        finishStopLocked(attempt);
        return;
      }
    }
    try {
      process.forceStop();
    } catch (RuntimeException ignored) {
      // The bounded verification below decides whether the exact child stopped.
    }
    synchronized (this) {
      if (active(attempt) && attempt.stopRequested && attempt.process == process) {
        if (!process.isAlive()) {
          attempt.process = null;
          finishStopLocked(attempt);
        } else {
          attempt.verifyStopped =
              scheduler.schedule(() -> verifyForcedStop(attempt, process), policy.stopTimeout());
        }
      }
    }
  }

  private void verifyForcedStop(Attempt attempt, OwnedRuntimeProcess process) {
    synchronized (this) {
      if (!active(attempt) || attempt.process != process) {
        return;
      }
      attempt.verifyStopped = NONE;
      if (!process.isAlive()) {
        attempt.process = null;
        finishStopLocked(attempt);
        return;
      }
      state = SupervisorState.ERROR;
      failureCode = "PROCESS_STOP_TIMEOUT";
      attempt.stopped.completeExceptionally(
          failure("PROCESS_STOP_TIMEOUT", "Owned Runtime process did not stop"));
    }
  }

  private void retryOrFailLocked(Attempt attempt, String code) {
    cancelLifecycleTasks(attempt);
    attempt.process = null;
    if (attempt.restartAttempts >= policy.maximumRestartAttempts()) {
      state = SupervisorState.ERROR;
      failureCode = code;
      if (!attempt.readiness.isDone()) {
        attempt.readiness.completeExceptionally(failure(code, "Runtime restart limit reached"));
      }
      return;
    }
    attempt.restartAttempts++;
    if (attempt.readiness.isDone()) {
      attempt.readiness = new CompletableFuture<>();
    }
    state = SupervisorState.ERROR;
    failureCode = code;
    attempt.retry =
        scheduler.schedule(() -> beginRetry(attempt), policy.restartDelay(attempt.restartAttempts));
  }

  private void beginRetry(Attempt attempt) {
    synchronized (this) {
      if (!active(attempt) || attempt.stopRequested) {
        return;
      }
      attempt.retry = NONE;
      state = SupervisorState.STARTING;
      failureCode = null;
      scheduleLaunchLocked(attempt, Duration.ZERO);
    }
  }

  private void scheduleLaunchLocked(Attempt attempt, Duration delay) {
    try {
      attempt.launch = scheduler.schedule(() -> launch(attempt), delay);
    } catch (RuntimeException exception) {
      state = SupervisorState.ERROR;
      failureCode = "SCHEDULER_REJECTED";
      attempt.readiness.completeExceptionally(
          failure("SCHEDULER_REJECTED", "Runtime start could not be scheduled", exception));
    }
  }

  private void finishStopLocked(Attempt attempt) {
    cancelLifecycleTasks(attempt);
    attempt.process = null;
    if (attempt.terminalFailure == null) {
      state = configured == null ? SupervisorState.UNCONFIGURED : SupervisorState.STOPPED;
      failureCode = null;
      attempt.stopped.complete(snapshotLocked());
    } else {
      state = SupervisorState.ERROR;
      failureCode = attempt.terminalFailure;
      attempt.stopped.completeExceptionally(
          failure(attempt.terminalFailure, "Runtime stopped after a startup failure"));
    }
  }

  private void cancelLifecycleTasks(Attempt attempt) {
    attempt.launch.cancel();
    attempt.launch = NONE;
    attempt.retry.cancel();
    attempt.retry = NONE;
    attempt.healthPoll.cancel();
    attempt.healthPoll = NONE;
    attempt.startTimeout.cancel();
    attempt.startTimeout = NONE;
    attempt.forceStop.cancel();
    attempt.forceStop = NONE;
    attempt.verifyStopped.cancel();
    attempt.verifyStopped = NONE;
  }

  private static void requestStop(OwnedRuntimeProcess process) {
    if (process == null) {
      return;
    }
    try {
      process.requestStop();
    } catch (RuntimeException ignored) {
      // The force-stop deadline remains authoritative.
    }
  }

  private boolean active(Attempt attempt) {
    return current == attempt && attempt.generation == generation;
  }

  private boolean activeStartingProcess(Attempt attempt, OwnedRuntimeProcess process) {
    return active(attempt)
        && !attempt.stopRequested
        && attempt.process == process
        && state == SupervisorState.STARTING;
  }

  private boolean hasLiveProcess() {
    return current != null && current.process != null && current.process.isAlive();
  }

  private SupervisorSnapshot snapshotLocked() {
    Long pid = hasLiveProcess() ? current.process.pid() : null;
    int restarts = current == null ? 0 : current.restartAttempts;
    return new SupervisorSnapshot(state, restarts, pid, failureCode);
  }

  private void requireOpen() {
    if (closed) {
      throw failure("SUPERVISOR_CLOSED", "Runtime supervisor is closed");
    }
  }

  private static SupervisorException failure(String code, String message) {
    return new SupervisorException(code, message);
  }

  private static SupervisorException failure(String code, String message, Throwable cause) {
    return new SupervisorException(code, message, cause);
  }

  private static final class Attempt {
    private final long generation;
    private final RuntimeLaunchSpec spec;
    private CompletableFuture<SupervisorSnapshot> readiness = new CompletableFuture<>();
    private final CompletableFuture<SupervisorSnapshot> stopped = new CompletableFuture<>();
    private int restartAttempts;
    private OwnedRuntimeProcess process;
    private boolean stopRequested;
    private String terminalFailure;
    private SupervisorScheduler.Cancellable launch = NONE;
    private SupervisorScheduler.Cancellable retry = NONE;
    private SupervisorScheduler.Cancellable healthPoll = NONE;
    private SupervisorScheduler.Cancellable startTimeout = NONE;
    private SupervisorScheduler.Cancellable forceStop = NONE;
    private SupervisorScheduler.Cancellable verifyStopped = NONE;

    private Attempt(long generation, RuntimeLaunchSpec spec) {
      this.generation = generation;
      this.spec = spec;
    }
  }
}
