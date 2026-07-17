package dev.minecraftagent.paper.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.command.AgentControl.RecoveryDisposition;
import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import dev.minecraftagent.paper.request.RuntimeApplicationLifecycle;
import dev.minecraftagent.paper.runtime.RuntimeStartAttempt;
import dev.minecraftagent.paper.runtime.RuntimeSupervisor;
import dev.minecraftagent.paper.runtime.RuntimeSupervisorException;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.state.DesiredModeStore;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import dev.minecraftagent.paper.transport.RuntimeConnectAttempt;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import dev.minecraftagent.paper.transport.RuntimeConnector;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PaperStartupCoordinatorTest {
  @Test
  void waitsForRuntimePreparationBeforeConnectingAndCancelsItOnClose() throws Exception {
    var supervisor = new FakeRuntimeSupervisor();
    var fixture = fixture(DesiredMode.ENABLED, List.of(), OfflineCleanup.empty(), supervisor);

    fixture.coordinator.start();
    await(() -> supervisor.prepareCalls.get() == 1);
    assertEquals(0, fixture.connector.connectCalls);

    supervisor.ready.complete(null);
    fixture.connector.completeNext();
    fixture.finishMainTransition();

    assertEquals(AgentState.ONLINE, fixture.coordinator.diagnostics().state());
    fixture.close();
    assertEquals(1, supervisor.closeCalls.get());
  }

  @Test
  void runtimePreparationFailureIsRedactedAndNeverConnectsOrRegisters() throws Exception {
    var supervisor = new FakeRuntimeSupervisor();
    var fixture = fixture(DesiredMode.ENABLED, List.of(), OfflineCleanup.empty(), supervisor);

    fixture.coordinator.start();
    await(() -> supervisor.prepareCalls.get() == 1);
    supervisor.ready.completeExceptionally(
        new RuntimeSupervisorException(
            "MANAGED_RUNTIME_START_FAILED", "fixed message without process details"));
    fixture.finishMainTransition();

    assertEquals(0, fixture.connector.connectCalls);
    assertEquals(0, fixture.commands.registerCalls);
    assertEquals("MANAGED_RUNTIME_START_FAILED", fixture.coordinator.diagnostics().failureCode());
    assertEquals(
        List.of("runtime-supervisor:MANAGED_RUNTIME_START_FAILED"), fixture.events.failures);
    fixture.close();
  }

  @Test
  void setupRetryCanRecoverAnUnregisteredInitialFailure() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of());
    fixture.checkFailure =
        new StartupFailure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    fixture.coordinator.start();
    fixture.finishMainTransition();
    fixture.checkFailure = null;

    var retry = fixture.coordinator.retryInitial();
    fixture.connector.completeNext();
    fixture.finishUntil(() -> retry.toCompletableFuture().isDone());

    assertTrue(retry.toCompletableFuture().join());
    assertEquals(AgentState.ONLINE, fixture.coordinator.diagnostics().state());
    assertEquals(1, fixture.commands.registerCalls);
    fixture.close();
  }

  @Test
  void initialSuccessRegistersExactlyOnceAndPublishesSeparatedHealth() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));

    fixture.coordinator.start();
    assertEquals(AgentState.STARTING, fixture.coordinator.diagnostics().state());
    var connection = fixture.connector.completeNext();
    fixture.finishMainTransition();

    assertEquals(1, fixture.commands.registerCalls);
    assertEquals(AgentState.ONLINE, fixture.coordinator.diagnostics().state());
    assertEquals(AgentHealth.DEGRADED, fixture.coordinator.diagnostics().health());
    assertEquals(List.of(AgentHealth.DEGRADED), fixture.events.availableHealth);
    assertTrue(fixture.coordinator.operationalGate().tryAcquire().isPresent());
    assertEquals(1, fixture.publicationCalls.get());
    assertEquals(0, fixture.discardCalls.get());
    fixture.close();
  }

  @Test
  void initialCoreOrRuntimeFailureNeverRegistersTheCommand() throws Exception {
    var coreFailure = fixture(DesiredMode.ENABLED, List.of());
    coreFailure.checkFailure =
        new StartupFailure(StartupFailure.Code.STATE_DIRECTORY_UNSAFE, StartupFailure.Stage.STATE);
    coreFailure.coordinator.start();
    coreFailure.finishMainTransition();

    assertEquals(0, coreFailure.connector.connectCalls);
    assertEquals(0, coreFailure.commands.registerCalls);
    assertEquals(AgentState.UNREGISTERED, coreFailure.coordinator.diagnostics().state());
    assertEquals("STATE_DIRECTORY_UNSAFE", coreFailure.coordinator.diagnostics().failureCode());
    coreFailure.close();

    var runtimeFailure = fixture(DesiredMode.ENABLED, List.of());
    runtimeFailure.coordinator.start();
    runtimeFailure.connector.failNext(
        new RuntimeConnectionFailure("TOKEN_AUTH_FAILED", "authentication"));
    runtimeFailure.finishMainTransition();

    assertEquals(0, runtimeFailure.commands.registerCalls);
    assertEquals(AgentState.UNREGISTERED, runtimeFailure.coordinator.diagnostics().state());
    assertEquals("TOKEN_AUTH_FAILED", runtimeFailure.coordinator.diagnostics().failureCode());
    assertEquals(0, runtimeFailure.publicationCalls.get());
    assertEquals(1, runtimeFailure.discardCalls.get());
    runtimeFailure.close();
  }

  @Test
  void persistedDisabledModeStillAuthenticatesThenRegistersOffline() throws Exception {
    var fixture = fixture(DesiredMode.DISABLED, List.of());

    fixture.coordinator.start();
    var connection = fixture.connector.completeNext();
    fixture.finishMainTransition();

    assertEquals(1, fixture.connector.connectCalls);
    assertEquals(1, fixture.commands.registerCalls);
    assertTrue(fixture.commands.registered);
    assertEquals(AgentState.OFFLINE, fixture.coordinator.diagnostics().state());
    assertEquals(DesiredMode.DISABLED, fixture.coordinator.diagnostics().desiredMode());
    assertEquals(OfflineReason.MANUAL, fixture.coordinator.diagnostics().offlineReason());
    assertFalse(connection.isOpen());
    fixture.close();
  }

  @Test
  void runtimeLossClosesAdmissionButKeepsCommandAndDesiredMode() throws Exception {
    var cleanupCalls = new ArrayList<String>();
    var fixture = fixture(DesiredMode.ENABLED, List.of(), cleanup(cleanupCalls, false));
    fixture.coordinator.start();
    var connection = fixture.connector.completeNext();
    fixture.finishMainTransition();
    var permit = fixture.coordinator.operationalGate().tryAcquire().orElseThrow();

    connection.remoteClose();
    fixture.finishMainTransition();

    assertTrue(fixture.commands.registered);
    assertEquals(0, fixture.commands.unregisterCalls);
    assertEquals(AgentState.OFFLINE, fixture.coordinator.diagnostics().state());
    assertEquals(DesiredMode.ENABLED, fixture.coordinator.diagnostics().desiredMode());
    assertEquals(
        OfflineReason.RUNTIME_UNAVAILABLE, fixture.coordinator.diagnostics().offlineReason());
    assertFalse(fixture.coordinator.operationalGate().revalidate(permit));
    assertEquals(List.of("requests", "proposals", "operations", "client"), cleanupCalls);
    assertTrue(fixture.store.saves.isEmpty());
    fixture.close();
  }

  @Test
  void manualOffInvalidatesOldEpochCleansEverythingAndPersistsDisabled() throws Exception {
    var cleanupCalls = new ArrayList<String>();
    var fixture = fixture(DesiredMode.ENABLED, List.of(), cleanup(cleanupCalls, true));
    fixture.coordinator.start();
    var connection = fixture.connector.completeNext();
    fixture.finishMainTransition();
    var permit = fixture.coordinator.operationalGate().tryAcquire().orElseThrow();

    fixture.coordinator.turnOff();

    assertEquals(AgentState.STOPPING, fixture.coordinator.diagnostics().state());
    assertFalse(fixture.coordinator.operationalGate().revalidate(permit));
    assertFalse(connection.isOpen());
    fixture.finishUntil(() -> fixture.coordinator.diagnostics().state() == AgentState.OFFLINE);

    assertEquals(DesiredMode.DISABLED, fixture.store.mode);
    assertEquals(List.of(DesiredMode.DISABLED), fixture.store.saves);
    assertEquals(List.of("requests", "proposals", "operations", "client"), cleanupCalls);
    assertEquals(List.of("offline-cleanup:PROPOSAL_INVALIDATION_FAILED"), fixture.events.failures);
    assertTrue(fixture.commands.registered);
    fixture.close();
  }

  @Test
  void recoveryRechecksReconnectsAndPersistsBeforePublishingOnline() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();
    fixture.activeConnection().remoteClose();
    fixture.finishMainTransition();

    var recovery = fixture.coordinator.turnOn();
    assertEquals(RecoveryDisposition.STARTED, recovery.disposition());
    var duplicate = fixture.coordinator.turnOn();
    assertEquals(RecoveryDisposition.ALREADY_STARTING, duplicate.disposition());
    assertEquals(AgentState.STARTING, fixture.coordinator.diagnostics().state());
    var replacement = fixture.connector.completeNext();
    fixture.finishUntil(() -> recovery.completion().toCompletableFuture().isDone());

    assertTrue(recovery.completion().toCompletableFuture().join());
    assertTrue(replacement.isOpen());
    assertEquals(2, fixture.checkCalls.get());
    assertEquals(2, fixture.connector.connectCalls);
    assertEquals(1, fixture.commands.registerCalls);
    assertEquals(List.of(DesiredMode.ENABLED), fixture.store.saves);
    assertEquals(AgentState.ONLINE, fixture.coordinator.diagnostics().state());
    fixture.close();
  }

  @Test
  void failedRecoveryStaysOfflineAndClosesCandidate() throws Exception {
    var fixture = fixture(DesiredMode.DISABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();
    fixture.store.saveFailure =
        new StartupFailure(
            StartupFailure.Code.STATE_PERSISTENCE_FAILED, StartupFailure.Stage.STATE);

    var recovery = fixture.coordinator.turnOn();
    var candidate = fixture.connector.completeNext();
    fixture.finishUntil(() -> recovery.completion().toCompletableFuture().isDone());

    assertFalse(recovery.completion().toCompletableFuture().join());
    assertFalse(candidate.isOpen());
    assertEquals(AgentState.OFFLINE, fixture.coordinator.diagnostics().state());
    assertEquals(DesiredMode.DISABLED, fixture.coordinator.diagnostics().desiredMode());
    assertEquals("STATE_PERSISTENCE_FAILED", fixture.coordinator.diagnostics().failureCode());
    assertTrue(fixture.commands.registered);
    assertEquals(1, fixture.publicationCalls.get());
    assertEquals(1, fixture.discardCalls.get());
    fixture.close();
  }

  @Test
  void connectionLossAfterEnabledCommitKeepsDiskAndMemoryDesiredModeConsistent() throws Exception {
    var fixture = fixture(DesiredMode.DISABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();

    var recovery = fixture.coordinator.turnOn();
    var candidate = fixture.connector.completeNext();
    await(() -> fixture.store.mode == DesiredMode.ENABLED && fixture.main.hasTasks());
    candidate.remoteClose();
    fixture.main.drain();

    assertFalse(recovery.completion().toCompletableFuture().join());
    assertEquals(DesiredMode.ENABLED, fixture.store.mode);
    assertEquals(DesiredMode.ENABLED, fixture.coordinator.diagnostics().desiredMode());
    assertEquals(AgentState.OFFLINE, fixture.coordinator.diagnostics().state());
    fixture.close();
  }

  @Test
  void failedOffPersistenceCanBeRetriedWithoutReenablingAdmission() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();
    fixture.store.saveFailure =
        new StartupFailure(
            StartupFailure.Code.STATE_PERSISTENCE_FAILED, StartupFailure.Stage.STATE);

    fixture.coordinator.turnOff();
    fixture.finishUntil(
        () -> "STATE_PERSISTENCE_FAILED".equals(fixture.coordinator.diagnostics().failureCode()));
    fixture.store.saveFailure = null;
    fixture.coordinator.turnOff();
    fixture.finishUntil(
        () ->
            fixture.coordinator.diagnostics().state() == AgentState.OFFLINE
                && fixture.coordinator.diagnostics().offlineReason() == OfflineReason.MANUAL);

    assertEquals(DesiredMode.DISABLED, fixture.store.mode);
    assertEquals(List.of(DesiredMode.DISABLED), fixture.store.saves);
    assertTrue(fixture.commands.registered);
    fixture.close();
  }

  @Test
  void immediateDisableLetsAnAlreadyQueuedOffWriteFinishDurably() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();
    fixture.store.saveBlock = new CompletableFuture<>();

    fixture.coordinator.turnOff();
    await(fixture.store.saveStarted::isDone);
    fixture.coordinator.close();
    fixture.store.saveBlock.complete(null);
    await(() -> fixture.store.mode == DesiredMode.DISABLED);

    assertEquals(List.of(DesiredMode.DISABLED), fixture.store.saves);
    assertEquals(1, fixture.commands.unregisterCalls);
  }

  @Test
  void offPreemptsRecoveryAndLateConnectionCannotTakeOwnership() throws Exception {
    var fixture = fixture(DesiredMode.DISABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();

    var recovery = fixture.coordinator.turnOn();
    await(() -> fixture.connector.connectCalls == 2);
    fixture.coordinator.turnOff();
    var stale = fixture.connector.completePending();
    fixture.finishUntil(() -> fixture.coordinator.diagnostics().state() == AgentState.OFFLINE);
    await(() -> !stale.isOpen());

    assertFalse(recovery.completion().toCompletableFuture().join());
    assertFalse(stale.isOpen());
    assertEquals(DesiredMode.DISABLED, fixture.store.mode);
    assertEquals(AgentState.OFFLINE, fixture.coordinator.diagnostics().state());
    assertEquals(1, fixture.commands.registerCalls);
    assertTrue(fixture.connector.cancelCalls > 0);
    fixture.close();
  }

  @Test
  void pluginDisableIsTheOnlyPostRegistrationUnregisterPath() throws Exception {
    var fixture = fixture(DesiredMode.ENABLED, List.of());
    fixture.coordinator.start();
    fixture.connector.completeNext();
    fixture.finishMainTransition();

    fixture.coordinator.close();
    fixture.coordinator.close();

    assertEquals(1, fixture.commands.unregisterCalls);
    assertFalse(fixture.commands.registered);
    assertEquals(AgentState.UNREGISTERED, fixture.coordinator.diagnostics().state());
  }

  @Test
  void rejectedMainThreadSchedulingFailsClosedAndClosesTheCandidate() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var connector = new FakeConnector();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var store = new FakeStore(DesiredMode.ENABLED);
    var coordinator =
        new PaperStartupCoordinator(
            worker,
            () ->
                new CoreReadiness(
                    settings(), List.of(), store.load(), store, new AdminPolicy(Set.of(), false)),
            connector,
            task -> {
              throw new IllegalStateException("scheduler stopped");
            },
            commands,
            () -> true,
            events);

    coordinator.start();
    var candidate = connector.completeNext();
    await(coordinator::startupComplete);

    assertFalse(candidate.isOpen());
    assertEquals(AgentState.UNREGISTERED, coordinator.diagnostics().state());
    assertEquals("MAIN_THREAD_SCHEDULE_FAILED", coordinator.diagnostics().failureCode());
    assertEquals(0, commands.registerCalls);
    coordinator.close();
  }

  @Test
  void connectionClosedDuringApplicationAttachCannotPublishOnline() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var connector = new FakeConnector();
    var commands = new FakeCommandGate();
    var events = new RecordingEvents();
    var store = new FakeStore(DesiredMode.ENABLED);
    var main = new QueuedMainThread();
    var gate = new OperationalGate();
    var detachCalls = new AtomicInteger();
    var applications =
        new RuntimeApplicationLifecycle() {
          @Override
          public void attach(AuthenticatedRuntimeConnection connection, String serverId) {
            connection.close();
          }

          @Override
          public void detach(AuthenticatedRuntimeConnection connection) {
            detachCalls.incrementAndGet();
          }
        };
    var coordinator =
        new PaperStartupCoordinator(
            worker,
            () ->
                new CoreReadiness(
                    settings(), List.of(), store.load(), store, new AdminPolicy(Set.of(), false)),
            connector,
            main,
            commands,
            () -> true,
            events,
            OfflineCleanup.empty(),
            gate,
            applications);

    coordinator.start();
    connector.completeNext();
    await(main::hasTasks);
    main.drain();

    assertEquals(AgentState.UNREGISTERED, coordinator.diagnostics().state());
    assertEquals("RUNTIME_CONNECTION_LOST", coordinator.diagnostics().failureCode());
    assertEquals(1, detachCalls.get());
    assertFalse(commands.registered);
    coordinator.close();
  }

  private static Fixture fixture(DesiredMode mode, List<String> warnings) {
    return fixture(mode, warnings, OfflineCleanup.empty());
  }

  private static Fixture fixture(DesiredMode mode, List<String> warnings, OfflineCleanup cleanup) {
    return fixture(
        mode, warnings, cleanup, new dev.minecraftagent.paper.runtime.ExternalRuntimeSupervisor());
  }

  private static Fixture fixture(
      DesiredMode mode,
      List<String> warnings,
      OfflineCleanup cleanup,
      RuntimeSupervisor runtimeSupervisor) {
    var fixture = new Fixture(mode, warnings);
    fixture.coordinator =
        new PaperStartupCoordinator(
            fixture.worker,
            fixture::check,
            fixture.connector,
            fixture.main,
            fixture.commands,
            () -> true,
            fixture.events,
            cleanup,
            new OperationalGate(),
            RuntimeApplicationLifecycle.empty(),
            runtimeSupervisor);
    return fixture;
  }

  private static OfflineCleanup cleanup(List<String> calls, boolean failProposal) {
    return new OfflineCleanup(
        (epoch, reason) -> calls.add("requests"),
        (epoch, reason) -> {
          calls.add("proposals");
          if (failProposal) {
            throw new IllegalStateException("expected test failure");
          }
        },
        (epoch, reason) -> calls.add("operations"),
        (epoch, reason) -> calls.add("client"));
  }

  private static RuntimeConnectionSettings settings() {
    return new RuntimeConnectionSettings(
        URI.create("ws://127.0.0.1:38127/agent"),
        "survival-main",
        "phase-4-public-test-token-32-characters",
        "0.1.0",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  private static final class Fixture {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FakeConnector connector = new FakeConnector();
    private final QueuedMainThread main = new QueuedMainThread();
    private final FakeCommandGate commands = new FakeCommandGate();
    private final RecordingEvents events = new RecordingEvents();
    private final FakeStore store;
    private final List<String> warnings;
    private final AtomicInteger checkCalls = new AtomicInteger();
    private final AtomicInteger publicationCalls = new AtomicInteger();
    private final AtomicInteger discardCalls = new AtomicInteger();
    private volatile StartupFailure checkFailure;
    private PaperStartupCoordinator coordinator;

    private Fixture(DesiredMode mode, List<String> warnings) {
      store = new FakeStore(mode);
      this.warnings = warnings;
    }

    private CoreReadiness check() throws StartupFailure {
      checkCalls.incrementAndGet();
      if (checkFailure != null) {
        throw checkFailure;
      }
      return new CoreReadiness(
          settings(),
          warnings,
          store.load(),
          store,
          new AdminPolicy(Set.of(), false),
          new CoreReadiness.Publication() {
            @Override
            public void publish() {
              publicationCalls.incrementAndGet();
            }

            @Override
            public void discard() {
              discardCalls.incrementAndGet();
            }
          });
    }

    private void finishMainTransition() throws InterruptedException {
      await(main::hasTasks);
      main.drain();
    }

    private void finishUntil(BooleanSupplier condition) throws InterruptedException {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
        if (main.hasTasks()) {
          main.drain();
        } else {
          Thread.sleep(5);
        }
      }
      if (main.hasTasks()) {
        main.drain();
      }
      assertTrue(condition.getAsBoolean());
    }

    private FakeConnection activeConnection() {
      return connector.connections.get(0);
    }

    private void close() {
      coordinator.close();
    }
  }

  private static final class FakeStore implements DesiredModeStore {
    private final List<DesiredMode> saves = new ArrayList<>();
    private volatile DesiredMode mode;
    private volatile StartupFailure saveFailure;
    private volatile CompletableFuture<Void> saveBlock;
    private final CompletableFuture<Void> saveStarted = new CompletableFuture<>();

    private FakeStore(DesiredMode mode) {
      this.mode = mode;
    }

    @Override
    public DesiredMode load() {
      return mode;
    }

    @Override
    public synchronized void save(DesiredMode mode) throws StartupFailure {
      saveStarted.complete(null);
      var block = saveBlock;
      if (block != null) {
        block.join();
      }
      if (saveFailure != null) {
        throw saveFailure;
      }
      saves.add(mode);
      this.mode = mode;
    }
  }

  private static final class FakeConnector implements RuntimeConnector {
    private final Queue<CompletableFuture<AuthenticatedRuntimeConnection>> pending =
        new ArrayDeque<>();
    private final List<FakeConnection> connections = new ArrayList<>();
    private volatile int connectCalls;
    private volatile int cancelCalls;

    @Override
    public synchronized CompletionStage<AuthenticatedRuntimeConnection> connect(
        RuntimeConnectionSettings settings) {
      connectCalls++;
      var result = new CompletableFuture<AuthenticatedRuntimeConnection>();
      pending.add(result);
      return result;
    }

    @Override
    public RuntimeConnectAttempt begin(RuntimeConnectionSettings settings) {
      var result = connect(settings);
      return new RuntimeConnectAttempt() {
        @Override
        public CompletionStage<AuthenticatedRuntimeConnection> result() {
          return result;
        }

        @Override
        public void cancel() {
          cancelCalls++;
        }
      };
    }

    private FakeConnection completeNext() throws InterruptedException {
      await(() -> connectCalls > connections.size());
      return completePending();
    }

    private synchronized FakeConnection completePending() {
      var connection = new FakeConnection();
      connections.add(connection);
      pending.remove().complete(connection);
      return connection;
    }

    private void failNext(RuntimeException failure) throws InterruptedException {
      await(
          () -> {
            synchronized (this) {
              return !pending.isEmpty();
            }
          });
      synchronized (this) {
        pending.remove().completeExceptionally(failure);
      }
    }
  }

  private static final class FakeConnection implements AuthenticatedRuntimeConnection {
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    @Override
    public boolean isOpen() {
      return open.get();
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return closed;
    }

    @Override
    public void close() {
      remoteClose();
    }

    private void remoteClose() {
      open.set(false);
      closed.complete(null);
    }
  }

  private static final class QueuedMainThread
      implements PaperStartupCoordinator.MainThreadExecutor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
      tasks.add(task);
    }

    private synchronized boolean hasTasks() {
      return !tasks.isEmpty();
    }

    private void drain() {
      while (true) {
        Runnable task;
        synchronized (this) {
          task = tasks.poll();
        }
        if (task == null) {
          return;
        }
        task.run();
      }
    }
  }

  private static final class FakeCommandGate implements PaperStartupCoordinator.CommandGate {
    private int registerCalls;
    private int unregisterCalls;
    private boolean registered;
    private CommandRegistrationFailure registrationFailure;

    @Override
    public void register() {
      registerCalls++;
      if (registrationFailure != null) {
        throw registrationFailure;
      }
      registered = true;
    }

    @Override
    public void unregister() {
      unregisterCalls++;
      registered = false;
    }
  }

  private static final class RecordingEvents implements PaperStartupCoordinator.EventSink {
    private final List<String> failures = new ArrayList<>();
    private final List<AgentHealth> availableHealth = new ArrayList<>();

    @Override
    public void failure(String stage, String code) {
      failures.add(stage + ":" + code);
    }

    @Override
    public void warning(String stage, String code) {}

    @Override
    public void available(AgentHealth health) {
      availableHealth.add(health);
    }
  }

  private static final class FakeRuntimeSupervisor implements RuntimeSupervisor {
    private final AtomicInteger prepareCalls = new AtomicInteger();
    private final AtomicInteger stopCalls = new AtomicInteger();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    @Override
    public RuntimeStartAttempt prepare(RuntimeConnectionSettings settings) {
      prepareCalls.incrementAndGet();
      return new RuntimeStartAttempt() {
        @Override
        public CompletionStage<Void> ready() {
          return ready;
        }

        @Override
        public void cancel() {
          stopCalls.incrementAndGet();
        }
      };
    }

    @Override
    public void stop() {
      stopCalls.incrementAndGet();
    }

    @Override
    public void close() {
      closeCalls.incrementAndGet();
    }
  }
}
