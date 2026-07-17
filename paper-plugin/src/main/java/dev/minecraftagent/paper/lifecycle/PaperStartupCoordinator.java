package dev.minecraftagent.paper.lifecycle;

import dev.minecraftagent.paper.command.AgentControl;
import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import dev.minecraftagent.paper.request.RuntimeApplicationLifecycle;
import dev.minecraftagent.paper.runtime.ExternalRuntimeSupervisor;
import dev.minecraftagent.paper.runtime.RuntimeStartAttempt;
import dev.minecraftagent.paper.runtime.RuntimeSupervisor;
import dev.minecraftagent.paper.runtime.RuntimeSupervisorException;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.state.DesiredModeStore;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import dev.minecraftagent.paper.transport.RuntimeConnectAttempt;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.RuntimeConnector;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class PaperStartupCoordinator implements AgentControl, AutoCloseable {
  @FunctionalInterface
  public interface CoreSelfCheck {
    CoreReadiness run() throws StartupFailure;
  }

  @FunctionalInterface
  public interface MainThreadExecutor {
    void execute(Runnable task);
  }

  public interface CommandGate {
    void register() throws CommandRegistrationFailure;

    void unregister();
  }

  public interface EventSink {
    void failure(String stage, String code);

    void warning(String stage, String code);

    void available(AgentHealth health);

    default void offline(OfflineReason reason) {}
  }

  private enum AttemptKind {
    INITIAL,
    RECOVERY
  }

  private final ExecutorService worker;
  private final CoreSelfCheck coreSelfCheck;
  private final RuntimeConnector runtimeConnector;
  private final RuntimeSupervisor runtimeSupervisor;
  private final MainThreadExecutor mainThread;
  private final CommandGate commandGate;
  private final BooleanSupplier pluginEnabled;
  private final EventSink events;
  private final OfflineCleanup offlineCleanup;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicReference<Attempt> currentAttempt = new AtomicReference<>();
  private final AtomicReference<AgentStatus> status =
      new AtomicReference<>(AgentStatus.unregistered(null));
  private final AtomicReference<AdminPolicy> adminPolicy =
      new AtomicReference<>(AdminPolicy.locked());
  private final OperationalGate operationalGate;
  private final RuntimeApplicationLifecycle runtimeApplications;
  private final CompletableFuture<Void> initialCompletion = new CompletableFuture<>();

  private AuthenticatedRuntimeConnection activeConnection;
  private DesiredModeStore desiredModeStore;
  private boolean commandRegistered;
  private volatile CompletableFuture<Void> pendingOffPersistence;

  public PaperStartupCoordinator(
      ExecutorService worker,
      CoreSelfCheck coreSelfCheck,
      RuntimeConnector runtimeConnector,
      MainThreadExecutor mainThread,
      CommandGate commandGate,
      BooleanSupplier pluginEnabled,
      EventSink events) {
    this(
        worker,
        coreSelfCheck,
        runtimeConnector,
        mainThread,
        commandGate,
        pluginEnabled,
        events,
        OfflineCleanup.empty(),
        new OperationalGate(),
        RuntimeApplicationLifecycle.empty(),
        new ExternalRuntimeSupervisor());
  }

  public PaperStartupCoordinator(
      ExecutorService worker,
      CoreSelfCheck coreSelfCheck,
      RuntimeConnector runtimeConnector,
      MainThreadExecutor mainThread,
      CommandGate commandGate,
      BooleanSupplier pluginEnabled,
      EventSink events,
      OfflineCleanup offlineCleanup) {
    this(
        worker,
        coreSelfCheck,
        runtimeConnector,
        mainThread,
        commandGate,
        pluginEnabled,
        events,
        offlineCleanup,
        new OperationalGate(),
        RuntimeApplicationLifecycle.empty(),
        new ExternalRuntimeSupervisor());
  }

  public PaperStartupCoordinator(
      ExecutorService worker,
      CoreSelfCheck coreSelfCheck,
      RuntimeConnector runtimeConnector,
      MainThreadExecutor mainThread,
      CommandGate commandGate,
      BooleanSupplier pluginEnabled,
      EventSink events,
      OfflineCleanup offlineCleanup,
      OperationalGate operationalGate,
      RuntimeApplicationLifecycle runtimeApplications) {
    this(
        worker,
        coreSelfCheck,
        runtimeConnector,
        mainThread,
        commandGate,
        pluginEnabled,
        events,
        offlineCleanup,
        operationalGate,
        runtimeApplications,
        new ExternalRuntimeSupervisor());
  }

  public PaperStartupCoordinator(
      ExecutorService worker,
      CoreSelfCheck coreSelfCheck,
      RuntimeConnector runtimeConnector,
      MainThreadExecutor mainThread,
      CommandGate commandGate,
      BooleanSupplier pluginEnabled,
      EventSink events,
      OfflineCleanup offlineCleanup,
      OperationalGate operationalGate,
      RuntimeApplicationLifecycle runtimeApplications,
      RuntimeSupervisor runtimeSupervisor) {
    this.worker = Objects.requireNonNull(worker);
    this.coreSelfCheck = Objects.requireNonNull(coreSelfCheck);
    this.runtimeConnector = Objects.requireNonNull(runtimeConnector);
    this.mainThread = Objects.requireNonNull(mainThread);
    this.commandGate = Objects.requireNonNull(commandGate);
    this.pluginEnabled = Objects.requireNonNull(pluginEnabled);
    this.events = Objects.requireNonNull(events);
    this.offlineCleanup = Objects.requireNonNull(offlineCleanup);
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.runtimeApplications = Objects.requireNonNull(runtimeApplications);
    this.runtimeSupervisor = Objects.requireNonNull(runtimeSupervisor);
  }

  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Paper startup has already begun");
    }
    publish(AgentStatus.starting(DesiredMode.ENABLED));
    beginAttempt(AttemptKind.INITIAL, DesiredMode.ENABLED);
  }

  public AgentStatus diagnostics() {
    return status.get();
  }

  public AdminPolicy adminPolicy() {
    return adminPolicy.get();
  }

  public OperationalGate operationalGate() {
    return operationalGate;
  }

  public boolean startupComplete() {
    return initialCompletion.isDone();
  }

  /** Retries a failed initial startup without exposing the readiness-gated /agent command. */
  public synchronized CompletionStage<Boolean> retryInitial() {
    if (!running.get()
        || currentAttempt.get() != null
        || status.get().state() != AgentState.UNREGISTERED) {
      return CompletableFuture.completedFuture(false);
    }
    publish(AgentStatus.starting(DesiredMode.ENABLED));
    return beginAttempt(AttemptKind.INITIAL, DesiredMode.ENABLED).completion;
  }

  @Override
  public synchronized void turnOff() {
    if (!running.get() || !commandRegistered) {
      return;
    }
    var snapshot = status.get();
    if (snapshot.state() == AgentState.STOPPING
        || (snapshot.state() == AgentState.OFFLINE
            && snapshot.desiredMode() == DesiredMode.DISABLED
            && snapshot.offlineReason() == OfflineReason.MANUAL
            && snapshot.failureCode() == null)) {
      return;
    }

    var currentGeneration = generation.incrementAndGet();
    publish(AgentStatus.stopping());
    cancelCurrentAttempt();
    var epoch = operationalGate.epoch();
    reportCleanupFailures(offlineCleanup.quiesce(epoch, OfflineReason.MANUAL));
    detachAndCloseActiveConnection();
    runtimeSupervisor.stop();

    var store = desiredModeStore;
    var persistence =
        CompletableFuture.runAsync(() -> saveDesiredMode(store, DesiredMode.DISABLED), worker);
    pendingOffPersistence = persistence;
    persistence.whenComplete(
        (ignored, error) -> {
          var completion = (Runnable) () -> finishOff(currentGeneration, error);
          if (!schedule(completion) && running.get()) {
            completion.run();
          }
        });
  }

  @Override
  public synchronized RecoveryRequest turnOn() {
    if (!running.get() || !commandRegistered) {
      return completedRecovery(RecoveryDisposition.UNAVAILABLE, false);
    }
    var snapshot = status.get();
    if (snapshot.state() == AgentState.ONLINE) {
      return completedRecovery(RecoveryDisposition.ALREADY_ONLINE, true);
    }
    var attempt = currentAttempt.get();
    if (snapshot.state() == AgentState.STARTING
        && attempt != null
        && attempt.kind == AttemptKind.RECOVERY) {
      return new RecoveryRequest(RecoveryDisposition.ALREADY_STARTING, attempt.completion);
    }
    if (snapshot.state() != AgentState.OFFLINE) {
      return completedRecovery(RecoveryDisposition.UNAVAILABLE, false);
    }

    publish(AgentStatus.starting(snapshot.desiredMode()));
    var recovery = beginAttempt(AttemptKind.RECOVERY, snapshot.desiredMode());
    return new RecoveryRequest(RecoveryDisposition.STARTED, recovery.completion);
  }

  @Override
  public synchronized void close() {
    if (!running.getAndSet(false)) {
      return;
    }
    generation.incrementAndGet();
    cancelCurrentAttempt();
    operationalGate.transitionTo(AgentState.UNREGISTERED);
    reportCleanupFailures(offlineCleanup.quiesce(operationalGate.epoch(), OfflineReason.MANUAL));
    detachAndCloseActiveConnection();
    runtimeSupervisor.close();
    if (commandRegistered) {
      commandGate.unregister();
      commandRegistered = false;
    }
    status.set(AgentStatus.unregistered("PLUGIN_DISABLED"));
    initialCompletion.complete(null);
    var persistence = pendingOffPersistence;
    if (persistence != null && !persistence.isDone()) {
      worker.shutdown();
    } else {
      worker.shutdownNow();
    }
  }

  private Attempt beginAttempt(AttemptKind kind, DesiredMode fallbackDesiredMode) {
    var attempt = new Attempt(generation.incrementAndGet(), kind, fallbackDesiredMode);
    if (!currentAttempt.compareAndSet(null, attempt)) {
      throw new IllegalStateException("An Agent self-check is already active");
    }

    CompletableFuture<Candidate> pipeline =
        CompletableFuture.supplyAsync(() -> runCoreCheck(attempt), worker)
            .thenCompose(
                readiness -> {
                  requireCurrent(attempt);
                  var startAttempt = runtimeSupervisor.prepare(readiness.runtimeSettings());
                  attempt.runtimeStartAttempt.set(startAttempt);
                  if (!isCurrent(attempt)) {
                    startAttempt.cancel();
                    throw cancelled();
                  }
                  return startAttempt
                      .ready()
                      .thenApply(
                          ignored -> {
                            requireCurrent(attempt);
                            return readiness;
                          });
                })
            .thenCompose(
                readiness -> {
                  requireCurrent(attempt);
                  var connectionAttempt = runtimeConnector.begin(readiness.runtimeSettings());
                  attempt.connectionAttempt.set(connectionAttempt);
                  if (!isCurrent(attempt)) {
                    connectionAttempt.cancel();
                    throw cancelled();
                  }
                  return connectionAttempt
                      .result()
                      .thenApply(connection -> prepareCandidate(attempt, readiness, connection));
                });
    if (kind == AttemptKind.RECOVERY) {
      pipeline =
          pipeline.thenApplyAsync(
              candidate -> {
                requireCurrent(attempt);
                saveDesiredMode(candidate.readiness().desiredModeStore(), DesiredMode.ENABLED);
                attempt.persistedDesiredMode = DesiredMode.ENABLED;
                requireCurrent(attempt);
                return candidate;
              },
              worker);
    }
    pipeline.whenComplete(
        (candidate, error) -> {
          if (!isCurrent(attempt)) {
            closeCandidate(candidate);
            discardPublication(attempt);
            attempt.completion.complete(false);
            return;
          }
          var completion =
              (Runnable)
                  () -> {
                    synchronized (PaperStartupCoordinator.this) {
                      if (!isCurrent(attempt)) {
                        closeCandidate(candidate);
                        discardPublication(attempt);
                        attempt.completion.complete(false);
                      } else if (error == null) {
                        finishSuccess(attempt, candidate);
                      } else {
                        finishFailure(attempt, error);
                      }
                    }
                  };
          if (!schedule(completion) && running.get()) {
            synchronized (PaperStartupCoordinator.this) {
              if (isCurrent(attempt)) {
                finishFailure(
                    attempt,
                    new RuntimeConnectionFailure("MAIN_THREAD_SCHEDULE_FAILED", "lifecycle"));
              }
            }
          }
        });
    return attempt;
  }

  private CoreReadiness runCoreCheck(Attempt attempt) {
    requireCurrent(attempt);
    try {
      var readiness = coreSelfCheck.run();
      attempt.readiness.set(readiness);
      requireCurrent(attempt);
      return readiness;
    } catch (StartupFailure failure) {
      throw new CompletionException(failure);
    } catch (CompletionException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new CompletionException(
          new RuntimeConnectionFailure("SELF_CHECK_INTERNAL_ERROR", "lifecycle"));
    }
  }

  private Candidate prepareCandidate(
      Attempt attempt, CoreReadiness readiness, AuthenticatedRuntimeConnection connection) {
    Objects.requireNonNull(connection);
    attempt.candidate.set(connection);
    connection.whenClosed().whenComplete((ignored, error) -> scheduleConnectionLost(connection));
    if (!isCurrent(attempt)) {
      attempt.candidate.compareAndSet(connection, null);
      connection.close();
      throw cancelled();
    }
    return new Candidate(readiness, connection);
  }

  private void finishSuccess(Attempt attempt, Candidate candidate) {
    var connection = candidate.connection();
    if (!pluginEnabled.getAsBoolean() || !connection.isOpen()) {
      finishFailure(
          attempt, new RuntimeConnectionFailure("RUNTIME_CONNECTION_LOST", "runtime-connect"));
      return;
    }

    if (attempt.kind == AttemptKind.INITIAL) {
      try {
        commandGate.register();
        commandRegistered = true;
      } catch (CommandRegistrationFailure failure) {
        finishFailure(attempt, failure);
        return;
      } catch (RuntimeException error) {
        finishFailure(attempt, new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED"));
        return;
      }
      if (!connection.isOpen()) {
        commandGate.unregister();
        commandRegistered = false;
        finishFailure(
            attempt, new RuntimeConnectionFailure("RUNTIME_CONNECTION_LOST", "runtime-connect"));
        return;
      }
    }

    var shouldBecomeOnline =
        attempt.kind != AttemptKind.INITIAL
            || candidate.readiness().desiredMode() != DesiredMode.DISABLED;
    if (shouldBecomeOnline) {
      try {
        runtimeApplications.attach(connection, candidate.readiness().runtimeSettings().serverId());
      } catch (RuntimeException error) {
        finishFailure(
            attempt, new RuntimeConnectionFailure("APPLICATION_CHANNEL_UNAVAILABLE", "protocol"));
        return;
      }
      if (!connection.isOpen()) {
        runtimeApplications.detach(connection);
        finishFailure(
            attempt, new RuntimeConnectionFailure("RUNTIME_CONNECTION_LOST", "runtime-connect"));
        return;
      }
    }

    try {
      publishPublication(attempt, candidate.readiness());
    } catch (RuntimeException error) {
      if (shouldBecomeOnline) {
        runtimeApplications.detach(connection);
      }
      finishFailure(
          attempt, new RuntimeConnectionFailure("CANDIDATE_PUBLICATION_FAILED", "lifecycle"));
      return;
    }

    desiredModeStore = candidate.readiness().desiredModeStore();
    adminPolicy.set(candidate.readiness().adminPolicy());
    attempt.connectionAttempt.set(null);
    attempt.runtimeStartAttempt.set(null);
    currentAttempt.compareAndSet(attempt, null);
    attempt.candidate.compareAndSet(connection, null);
    reportWarnings(candidate.readiness().warningCodes());

    if (attempt.kind == AttemptKind.INITIAL
        && candidate.readiness().desiredMode() == DesiredMode.DISABLED) {
      connection.close();
      runtimeSupervisor.stop();
      publish(
          AgentStatus.offline(
              DesiredMode.DISABLED,
              OfflineReason.MANUAL,
              null,
              candidate.readiness().warningCodes()));
      events.offline(OfflineReason.MANUAL);
    } else {
      activeConnection = connection;
      publish(AgentStatus.online(candidate.readiness().warningCodes()));
      events.available(status.get().health());
    }
    attempt.completion.complete(true);
    if (attempt.kind == AttemptKind.INITIAL) {
      initialCompletion.complete(null);
    }
  }

  private void finishFailure(Attempt attempt, Throwable error) {
    currentAttempt.compareAndSet(attempt, null);
    var connectionAttempt = attempt.connectionAttempt.getAndSet(null);
    if (connectionAttempt != null) {
      connectionAttempt.cancel();
    }
    var runtimeStartAttempt = attempt.runtimeStartAttempt.getAndSet(null);
    if (runtimeStartAttempt != null) {
      runtimeStartAttempt.cancel();
    }
    var connection = attempt.candidate.getAndSet(null);
    if (connection != null) {
      connection.close();
    }
    discardPublication(attempt);
    var failure = failureDetails(error);
    if (attempt.kind == AttemptKind.INITIAL) {
      if (commandRegistered) {
        commandGate.unregister();
        commandRegistered = false;
      }
      publish(AgentStatus.unregistered(failure.code()));
      initialCompletion.complete(null);
    } else {
      publish(
          AgentStatus.offline(
              attempt.persistedDesiredMode, failure.reason(), failure.code(), List.of()));
      events.offline(failure.reason());
    }
    events.failure(failure.stage(), failure.code());
    attempt.completion.complete(false);
  }

  private void scheduleConnectionLost(AuthenticatedRuntimeConnection connection) {
    var transition = (Runnable) () -> finishConnectionLost(connection);
    if (!schedule(transition) && running.get()) {
      transition.run();
    }
  }

  private synchronized void finishOff(long expectedGeneration, Throwable error) {
    if (!isGenerationCurrent(expectedGeneration)) {
      return;
    }
    if (error == null) {
      publish(AgentStatus.offline(DesiredMode.DISABLED, OfflineReason.MANUAL, null, List.of()));
      events.offline(OfflineReason.MANUAL);
    } else {
      publish(
          AgentStatus.offline(
              DesiredMode.DISABLED,
              OfflineReason.SECURITY_FAILURE,
              "STATE_PERSISTENCE_FAILED",
              List.of()));
      events.failure("STATE", "STATE_PERSISTENCE_FAILED");
    }
  }

  private synchronized void finishConnectionLost(AuthenticatedRuntimeConnection connection) {
    if (!running.get() || activeConnection != connection) {
      return;
    }
    activeConnection = null;
    generation.incrementAndGet();
    var desired = status.get().desiredMode();
    var warnings = status.get().warningCodes();
    publish(
        AgentStatus.offline(
            desired, OfflineReason.RUNTIME_UNAVAILABLE, "RUNTIME_CONNECTION_LOST", warnings));
    reportCleanupFailures(
        offlineCleanup.quiesce(operationalGate.epoch(), OfflineReason.RUNTIME_UNAVAILABLE));
    runtimeApplications.detach(connection);
    runtimeSupervisor.stop();
    events.failure("runtime-connect", "RUNTIME_CONNECTION_LOST");
    events.offline(OfflineReason.RUNTIME_UNAVAILABLE);
  }

  private void cancelCurrentAttempt() {
    var attempt = currentAttempt.getAndSet(null);
    if (attempt == null) {
      return;
    }
    var connectionAttempt = attempt.connectionAttempt.getAndSet(null);
    if (connectionAttempt != null) {
      connectionAttempt.cancel();
    }
    var runtimeStartAttempt = attempt.runtimeStartAttempt.getAndSet(null);
    if (runtimeStartAttempt != null) {
      runtimeStartAttempt.cancel();
    }
    var candidate = attempt.candidate.getAndSet(null);
    if (candidate != null) {
      candidate.close();
    }
    discardPublication(attempt);
    attempt.completion.complete(false);
  }

  private void detachAndCloseActiveConnection() {
    var connection = activeConnection;
    activeConnection = null;
    if (connection != null) {
      runtimeApplications.detach(connection);
      connection.close();
    }
  }

  private void publish(AgentStatus next) {
    operationalGate.transitionTo(next.state());
    status.set(next);
  }

  private void reportWarnings(List<String> warningCodes) {
    for (var warning : warningCodes) {
      events.warning("optional-capabilities", warning);
    }
  }

  private void reportCleanupFailures(List<String> failureCodes) {
    for (var failure : failureCodes) {
      events.failure("offline-cleanup", failure);
    }
  }

  private boolean schedule(Runnable task) {
    if (!running.get()) {
      return false;
    }
    try {
      mainThread.execute(
          () -> {
            if (running.get()) {
              task.run();
            }
          });
      return true;
    } catch (RuntimeException error) {
      events.failure("lifecycle", "MAIN_THREAD_SCHEDULE_FAILED");
      return false;
    }
  }

  private boolean isCurrent(Attempt attempt) {
    return running.get()
        && generation.get() == attempt.generation
        && currentAttempt.get() == attempt;
  }

  private boolean isGenerationCurrent(long expectedGeneration) {
    return running.get() && generation.get() == expectedGeneration;
  }

  private void requireCurrent(Attempt attempt) {
    if (!isCurrent(attempt)) {
      throw cancelled();
    }
  }

  private static CompletionException cancelled() {
    return new CompletionException(
        new RuntimeConnectionFailure("SELF_CHECK_CANCELLED", "lifecycle"));
  }

  private static RecoveryRequest completedRecovery(
      RecoveryDisposition disposition, boolean result) {
    return new RecoveryRequest(disposition, CompletableFuture.completedFuture(result));
  }

  private static void saveDesiredMode(DesiredModeStore store, DesiredMode mode) {
    if (store == null) {
      throw new CompletionException(
          new StartupFailure(
              StartupFailure.Code.STATE_PERSISTENCE_FAILED, StartupFailure.Stage.STATE));
    }
    try {
      store.save(mode);
    } catch (StartupFailure failure) {
      throw new CompletionException(failure);
    }
  }

  private static void closeCandidate(Candidate candidate) {
    if (candidate != null) {
      candidate.connection().close();
    }
  }

  private static void publishPublication(Attempt attempt, CoreReadiness readiness) {
    if (!attempt.publicationFinalized.compareAndSet(false, true)) {
      throw new IllegalStateException("Core readiness publication was already finalized");
    }
    try {
      readiness.publication().publish();
    } catch (RuntimeException error) {
      readiness.publication().discard();
      throw error;
    }
  }

  private static void discardPublication(Attempt attempt) {
    var readiness = attempt.readiness.get();
    if (readiness != null && attempt.publicationFinalized.compareAndSet(false, true)) {
      readiness.publication().discard();
    }
  }

  private static FailureDetails failureDetails(Throwable error) {
    var failure = unwrap(error);
    if (failure instanceof StartupFailure startupFailure) {
      var reason =
          switch (startupFailure.stage()) {
            case SECURITY_POLICY, STATE -> OfflineReason.SECURITY_FAILURE;
            case CONFIG, ENVIRONMENT, CORE_TOOLS -> OfflineReason.CONFIG_INVALID;
          };
      return new FailureDetails(
          startupFailure.stage().name(), startupFailure.code().name(), reason);
    }
    if (failure instanceof RuntimeConnectionFailure connectionFailure) {
      var reason =
          connectionFailure.code().startsWith("MODEL_")
              ? OfflineReason.MODEL_UNAVAILABLE
              : OfflineReason.RUNTIME_UNAVAILABLE;
      return new FailureDetails(connectionFailure.stage(), connectionFailure.code(), reason);
    }
    if (failure instanceof RuntimeSupervisorException supervisorFailure) {
      return new FailureDetails(
          "runtime-supervisor", supervisorFailure.code(), OfflineReason.RUNTIME_UNAVAILABLE);
    }
    if (failure instanceof CommandRegistrationFailure registrationFailure) {
      return new FailureDetails(
          "command-registration", registrationFailure.code(), OfflineReason.CONFIG_INVALID);
    }
    return new FailureDetails(
        "lifecycle", "SELF_CHECK_INTERNAL_ERROR", OfflineReason.RUNTIME_UNAVAILABLE);
  }

  private static Throwable unwrap(Throwable error) {
    var current = error;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class Attempt {
    private final long generation;
    private final AttemptKind kind;
    private final AtomicReference<RuntimeConnectAttempt> connectionAttempt =
        new AtomicReference<>();
    private final AtomicReference<RuntimeStartAttempt> runtimeStartAttempt =
        new AtomicReference<>();
    private final AtomicReference<AuthenticatedRuntimeConnection> candidate =
        new AtomicReference<>();
    private final AtomicReference<CoreReadiness> readiness = new AtomicReference<>();
    private final AtomicBoolean publicationFinalized = new AtomicBoolean();
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
    private volatile DesiredMode persistedDesiredMode;

    private Attempt(long generation, AttemptKind kind, DesiredMode fallbackDesiredMode) {
      this.generation = generation;
      this.kind = kind;
      this.persistedDesiredMode = fallbackDesiredMode;
    }
  }

  private record Candidate(CoreReadiness readiness, AuthenticatedRuntimeConnection connection) {}

  private record FailureDetails(String stage, String code, OfflineReason reason) {}
}
