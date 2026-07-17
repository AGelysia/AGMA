package dev.minecraftagent.standalone.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class RuntimeSupervisorTest {
  @Test
  void becomesReadyOnlyAfterTheAsynchronousHealthProbe() {
    var scheduler = new ManualScheduler();
    var process = new FakeProcess(41);
    var probe = new CompletableFuture<Boolean>();
    var supervisor = supervisor(scheduler, launcher(process), (ignored, spec) -> probe, 2);

    assertEquals(SupervisorState.STOPPED, supervisor.configure(spec()).state());
    var readiness = supervisor.start().toCompletableFuture();
    assertEquals(SupervisorState.STARTING, supervisor.snapshot().state());

    scheduler.runDue();
    assertFalse(readiness.isDone());
    assertEquals(41L, supervisor.snapshot().ownedPid());

    probe.complete(true);
    assertEquals(SupervisorState.READY, readiness.join().state());
    assertEquals(SupervisorState.READY, supervisor.snapshot().state());
  }

  @Test
  void cancellationBeforeLaunchDoesNotCreateOrStopAProcess() {
    var scheduler = new ManualScheduler();
    var launches = new CountingLauncher();
    var supervisor = supervisor(scheduler, launches, readyProbe(), 1);
    supervisor.configure(spec());

    supervisor.start();
    assertEquals(
        SupervisorState.STOPPED, supervisor.cancelStart().toCompletableFuture().join().state());
    scheduler.runAll();

    assertEquals(0, launches.count);
    assertEquals(SupervisorState.STOPPED, supervisor.snapshot().state());
  }

  @Test
  void stopTargetsOnlyTheOwnedChildAndEscalatesAfterTheGracePeriod() {
    var scheduler = new ManualScheduler();
    var owned = new FakeProcess(51);
    owned.exitWhenForced = true;
    var unrelated = new FakeProcess(52);
    var neverReady = new CompletableFuture<Boolean>();
    var supervisor = supervisor(scheduler, launcher(owned), (ignored, spec) -> neverReady, 1);
    supervisor.configure(spec());
    supervisor.start();
    scheduler.runDue();

    var stopped = supervisor.cancelStart().toCompletableFuture();
    assertTrue(owned.stopRequested);
    assertFalse(owned.forceRequested);
    assertFalse(unrelated.stopRequested);

    scheduler.advance(Duration.ofSeconds(2));
    assertTrue(owned.forceRequested);
    assertEquals(SupervisorState.STOPPED, stopped.join().state());
    assertFalse(unrelated.stopRequested);
    assertFalse(unrelated.forceRequested);
  }

  @Test
  void startupTimeoutIsRecoverableButDoesNotStartASecondChild() {
    var scheduler = new ManualScheduler();
    var process = new FakeProcess(61);
    var neverReady = new CompletableFuture<Boolean>();
    var launches = launcher(process);
    var supervisor = supervisor(scheduler, launches, (ignored, spec) -> neverReady, 3);
    supervisor.configure(spec());
    var readiness = supervisor.start().toCompletableFuture();
    scheduler.runDue();

    scheduler.advance(Duration.ofSeconds(10));
    assertTrue(process.stopRequested);
    assertThrows(CompletionException.class, readiness::join);

    process.exit(1);
    assertEquals(SupervisorState.ERROR, supervisor.snapshot().state());
    assertEquals("START_TIMEOUT", supervisor.snapshot().failureCode());
    assertEquals(1, launches.count);
  }

  @Test
  void crashedChildrenRestartWithBoundedBackoff() {
    var scheduler = new ManualScheduler();
    var first = new FakeProcess(71);
    var second = new FakeProcess(72);
    var third = new FakeProcess(73);
    var launches = launcher(first, second, third);
    var supervisor = supervisor(scheduler, launches, readyProbe(), 1);
    supervisor.configure(spec());
    supervisor.start();
    scheduler.runDue();
    assertEquals(SupervisorState.READY, supervisor.snapshot().state());

    first.exit(7);
    assertEquals(SupervisorState.ERROR, supervisor.snapshot().state());
    assertEquals(1, supervisor.snapshot().restartAttempts());
    assertEquals(1, launches.count);

    scheduler.advance(Duration.ofMillis(999));
    assertEquals(1, launches.count);
    scheduler.advance(Duration.ofMillis(1));
    assertEquals(2, launches.count);
    assertEquals(SupervisorState.READY, supervisor.snapshot().state());
    assertEquals(72L, supervisor.snapshot().ownedPid());

    second.exit(8);
    assertEquals(SupervisorState.ERROR, supervisor.snapshot().state());
    assertEquals("RUNTIME_EXITED", supervisor.snapshot().failureCode());
    scheduler.runAll();
    assertEquals(2, launches.count);

    supervisor.start();
    scheduler.runDue();
    assertEquals(3, launches.count);
    assertEquals(73L, supervisor.snapshot().ownedPid());
    assertEquals(SupervisorState.READY, supervisor.snapshot().state());
  }

  @Test
  void monitorFailureStopsTheCurrentChildBeforeAllowingAnotherStart() {
    var scheduler = new ManualScheduler();
    var process = new FakeProcess(81);
    process.monitorFails = true;
    process.exitWhenForced = true;
    var launches = launcher(process);
    var supervisor = supervisor(scheduler, launches, readyProbe(), 3);
    supervisor.configure(spec());

    var readiness = supervisor.start().toCompletableFuture();
    scheduler.runDue();
    assertTrue(process.stopRequested);
    assertThrows(CompletionException.class, readiness::join);
    assertEquals(1, launches.count);

    scheduler.advance(Duration.ofSeconds(2));
    assertEquals(SupervisorState.ERROR, supervisor.snapshot().state());
    assertEquals("PROCESS_MONITOR_FAILED", supervisor.snapshot().failureCode());
    assertEquals(1, launches.count);
  }

  @Test
  void pathPolicyFailureLeavesTheSupervisorUnconfigured() {
    var scheduler = new ManualScheduler();
    var supervisor =
        new RuntimeSupervisor(
            ignored -> {
              throw new SupervisorException("PATH_UNSAFE", "Runtime path is unsafe");
            },
            new CountingLauncher(),
            readyProbe(),
            scheduler,
            policy(1));

    var failure = assertThrows(SupervisorException.class, () -> supervisor.configure(spec()));
    assertEquals("PATH_UNSAFE", failure.code());
    assertEquals(SupervisorState.UNCONFIGURED, supervisor.snapshot().state());
  }

  @Test
  void launchSpecDoesNotRenderEnvironmentSecrets() {
    var spec = spec();
    assertFalse(spec.toString().contains("connector-secret"));
    assertTrue(spec.toString().contains("AGMA_CLIENT_CONNECTOR_TOKEN"));
  }

  private static RuntimeSupervisor supervisor(
      ManualScheduler scheduler,
      RuntimeProcessLauncher launcher,
      RuntimeHealthProbe probe,
      int maximumRestarts) {
    return new RuntimeSupervisor(
        RuntimePathPolicy.externallyVerifiedDevelopmentPaths(),
        launcher,
        probe,
        scheduler,
        policy(maximumRestarts));
  }

  private static SupervisorPolicy policy(int maximumRestarts) {
    return new SupervisorPolicy(
        Duration.ofSeconds(10),
        Duration.ofMillis(100),
        Duration.ofSeconds(2),
        Duration.ofSeconds(1),
        Duration.ofSeconds(4),
        maximumRestarts);
  }

  private static RuntimeHealthProbe readyProbe() {
    return (process, spec) -> CompletableFuture.completedFuture(true);
  }

  private static CountingLauncher launcher(FakeProcess... processes) {
    return new CountingLauncher(processes);
  }

  private static RuntimeLaunchSpec spec() {
    return new RuntimeLaunchSpec(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        Path.of("/tmp/agma-test").normalize(),
        Path.of("/usr/bin/node").normalize(),
        List.of("dist/bootstrap/index.js", "--config", "/tmp/agma-test/config.yml"),
        Map.of("AGMA_CLIENT_CONNECTOR_TOKEN", "connector-secret"));
  }

  private static final class CountingLauncher implements RuntimeProcessLauncher {
    private final ArrayDeque<FakeProcess> processes = new ArrayDeque<>();
    private int count;

    private CountingLauncher(FakeProcess... processes) {
      this.processes.addAll(List.of(processes));
    }

    @Override
    public OwnedRuntimeProcess start(RuntimeLaunchSpec spec) throws IOException {
      count++;
      var process = processes.pollFirst();
      if (process == null) {
        throw new IOException("No fake process configured");
      }
      return process;
    }
  }

  private static final class FakeProcess implements OwnedRuntimeProcess {
    private final long pid;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private boolean alive = true;
    private boolean stopRequested;
    private boolean forceRequested;
    private boolean exitWhenForced;
    private boolean monitorFails;

    private FakeProcess(long pid) {
      this.pid = pid;
    }

    @Override
    public long pid() {
      return pid;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public CompletionStage<Integer> onExit() {
      if (monitorFails) {
        throw new IllegalStateException("fake process monitor failed");
      }
      return exit;
    }

    @Override
    public void requestStop() {
      stopRequested = true;
    }

    @Override
    public void forceStop() {
      forceRequested = true;
      if (exitWhenForced) {
        exit(137);
      }
    }

    private void exit(int code) {
      alive = false;
      exit.complete(code);
    }
  }

  private static final class ManualScheduler implements SupervisorScheduler {
    private final PriorityQueue<ScheduledAction> actions =
        new PriorityQueue<>(
            Comparator.comparingLong((ScheduledAction task) -> task.deadline)
                .thenComparingLong(task -> task.sequence));
    private long now;
    private long sequence;
    private boolean closed;

    @Override
    public Cancellable schedule(Runnable action, Duration delay) {
      if (closed) {
        throw new IllegalStateException("Scheduler is closed");
      }
      var scheduled = new ScheduledAction(now + delay.toNanos(), sequence++, action);
      actions.add(scheduled);
      return () -> scheduled.cancelled = true;
    }

    @Override
    public void close() {
      closed = true;
      actions.clear();
    }

    private void advance(Duration duration) {
      now += duration.toNanos();
      runDue();
    }

    private void runDue() {
      while (!actions.isEmpty() && actions.peek().deadline <= now) {
        var action = actions.remove();
        if (!action.cancelled) {
          action.action.run();
        }
      }
    }

    private void runAll() {
      var guard = 0;
      while (!actions.isEmpty()) {
        if (++guard > 1000) {
          throw new IllegalStateException("Manual scheduler did not quiesce");
        }
        now = Math.max(now, actions.peek().deadline);
        runDue();
      }
    }
  }

  private static final class ScheduledAction {
    private final long deadline;
    private final long sequence;
    private final Runnable action;
    private boolean cancelled;

    private ScheduledAction(long deadline, long sequence, Runnable action) {
      this.deadline = deadline;
      this.sequence = sequence;
      this.action = action;
    }
  }
}
