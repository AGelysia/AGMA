package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.supervisor.SupervisorPolicy;
import dev.minecraftagent.standalone.supervisor.SupervisorScheduler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRuntimeSupervisorTest {
  @TempDir Path temporaryDirectory;

  private ManualScheduler scheduler;
  private ManagedRuntimeSupervisor supervisor;

  @AfterEach
  void closeResources() {
    if (supervisor != null) {
      supervisor.close();
    }
    if (scheduler != null) {
      scheduler.close();
    }
  }

  @Test
  void startsOneFixedCommandAndCompletesAfterTheHealthProbeSucceeds() throws Exception {
    var process = new FakeProcess(1234, true);
    var builder = new AtomicReference<ProcessBuilder>();
    var starts = new AtomicInteger();
    var probes = new AtomicInteger();
    supervisor =
        supervisor(
            candidate -> {
              starts.incrementAndGet();
              builder.set(candidate);
              return process;
            },
            ignored -> probes.incrementAndGet() >= 2,
            policy(1));

    var first = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    var second = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());

    assertSame(first, second);
    scheduler.runAll();
    first.ready().toCompletableFuture().get(2, TimeUnit.SECONDS);
    scheduler.runAll();

    assertEquals(1, starts.get());
    assertTrue(probes.get() >= 2);
    assertEquals(
        List.of(
            temporaryDirectory.resolve("runtime/bin/node").toAbsolutePath().normalize().toString(),
            temporaryDirectory
                .resolve("runtime/app/main.js")
                .toAbsolutePath()
                .normalize()
                .toString(),
            "--config",
            temporaryDirectory.resolve("state/runtime.yml").toAbsolutePath().normalize().toString(),
            "--managed"),
        builder.get().command());
    assertEquals(
        temporaryDirectory.resolve("runtime").toAbsolutePath().normalize().toFile(),
        builder.get().directory());
    assertEquals(
        Map.of(
            "LANG",
            "C.UTF-8",
            "NODE_ENV",
            "production",
            "AGMA_MANAGED_SERVER_ID",
            "test-server",
            "AGMA_MANAGED_RUNTIME_PORT",
            "8765"),
        builder.get().environment());
    assertEquals(
        temporaryDirectory.resolve("logs/runtime.out.log").toAbsolutePath().normalize().toFile(),
        builder.get().redirectOutput().file());
    assertEquals(
        temporaryDirectory.resolve("logs/runtime.err.log").toAbsolutePath().normalize().toFile(),
        builder.get().redirectError().file());
    assertEquals(ProcessBuilder.Redirect.Type.APPEND, builder.get().redirectOutput().type());
    assertEquals(ProcessBuilder.Redirect.Type.APPEND, builder.get().redirectError().type());
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(
            temporaryDirectory.resolve("logs/runtime.out.log").toAbsolutePath().normalize()));
  }

  @Test
  void failsWhenTheChildExitsBeforeReadiness() {
    var process = new FakeProcess(1235, true);
    var exits = new ArrayList<String>();
    supervisor = supervisor(ignored -> process, ignored -> false, policy(0), exits);
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runDue();

    process.exit();
    scheduler.runAll();

    assertEquals("MANAGED_RUNTIME_EXITED", awaitFailure(attempt).code());
    assertTrue(exits.isEmpty());
  }

  @Test
  void timesOutAndTerminatesAChildThatNeverBecomesHealthy() {
    var process = new FakeProcess(1236, true);
    supervisor = supervisor(ignored -> process, ignored -> false, policy(0));
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();

    scheduler.advance(Duration.ofSeconds(15));

    assertEquals("MANAGED_RUNTIME_START_TIMEOUT", awaitFailure(attempt).code());
    assertEquals(List.of("destroy"), process.terminationEvents());
    assertFalse(process.isAlive());
  }

  @Test
  void forciblyTerminatesAChildThatIgnoresTheGracefulStop() {
    var process = new FakeProcess(1237, false);
    supervisor = supervisor(ignored -> process, ignored -> false, policy(0));
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();

    supervisor.stop();
    scheduler.advance(Duration.ofSeconds(15));
    scheduler.runAll();

    assertEquals(List.of("destroy", "destroyForcibly"), process.terminationEvents());
    assertFalse(process.isAlive());
  }

  @Test
  void cancellationTerminatesTheCurrentChild() {
    var process = new FakeProcess(1238, true);
    supervisor = supervisor(ignored -> process, ignored -> false, policy(0));
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runDue();

    attempt.cancel();
    scheduler.runAll();

    assertEquals("MANAGED_RUNTIME_CANCELLED", awaitFailure(attempt).code());
    assertEquals(List.of("destroy"), process.terminationEvents());
  }

  @Test
  void closeStopsTheChildAndPreventsAnotherStart() {
    var process = new FakeProcess(1239, true);
    supervisor = supervisor(ignored -> process, ignored -> false, policy(1));
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();

    supervisor.close();
    scheduler.runAll();

    assertFalse(process.isAlive());
    assertThrows(
        IllegalStateException.class,
        () -> supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()));
  }

  @Test
  void startFailureDoesNotExposeTheCommandEnvironmentOrUnderlyingError() {
    supervisor =
        supervisor(
            ignored -> {
              throw new IOException("secret-from-operating-system");
            },
            ignored -> false,
            policy(0));
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();

    var failure = awaitFailure(attempt);
    assertEquals("MANAGED_RUNTIME_START_FAILED", failure.code());
    assertFalse(failure.toString().contains("secret-from-operating-system"));
    assertFalse(failure.toString().contains("NODE_ENV"));
    assertFalse(failure.toString().contains("main.js"));
  }

  @Test
  void restartsTheRuntimeAndNotifiesOperatorsWhenItDiesAfterReadiness() {
    var first = new FakeProcess(1240, true);
    var second = new FakeProcess(1241, true);
    var third = new FakeProcess(1242, true);
    var fourth = new FakeProcess(1243, true);
    var launches = new AtomicInteger();
    var exits = new ArrayList<String>();
    supervisor =
        supervisor(
            ignored -> {
              var process = List.of(first, second, third, fourth).get(launches.getAndIncrement());
              return process;
            },
            ignored -> true,
            policy(3),
            exits);
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();
    attempt.ready().toCompletableFuture().join();
    assertEquals(1, launches.get());
    assertTrue(exits.isEmpty());

    first.exit();
    scheduler.runDue();

    assertEquals(List.of("MANAGED_RUNTIME_EXITED"), exits);
    scheduler.advance(Duration.ofSeconds(5));
    scheduler.runAll();
    assertEquals(2, launches.get());

    second.exit();
    third.exit();
    fourth.exit();
    scheduler.runAll();

    assertEquals(4, launches.get());
    assertEquals("MANAGED_RUNTIME_EXITED", exits.getLast());
    scheduler.advance(Duration.ofMinutes(1));
    scheduler.runAll();
    assertEquals(4, launches.get());
  }

  @Test
  void deliberateStopsDoNotNotifyOperatorsOfRuntimeExits() {
    var process = new FakeProcess(1244, true);
    var exits = new ArrayList<String>();
    supervisor = supervisor(ignored -> process, ignored -> true, policy(1), exits);
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();
    attempt.ready().toCompletableFuture().join();

    supervisor.stop();
    scheduler.runAll();

    assertFalse(process.isAlive());
    assertTrue(exits.isEmpty());
  }

  @Test
  void prepareAfterAStopStartsAFreshRuntime() {
    var first = new FakeProcess(1245, true);
    var second = new FakeProcess(1246, true);
    var processes = new ArrayDeque<>(List.of(first, second));
    var launches = new AtomicInteger();
    supervisor =
        supervisor(
            ignored -> {
              launches.incrementAndGet();
              return processes.pollFirst();
            },
            ignored -> true,
            policy(1));
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()).ready();
    scheduler.runAll();

    supervisor.stop();
    scheduler.runAll();
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    scheduler.runAll();

    assertEquals(2, launches.get());
    assertFalse(first.isAlive());
    assertTrue(second.isAlive());
  }

  private ManagedRuntimeSupervisor supervisor(
      ProcessFactory processFactory, HealthProbe healthProbe, SupervisorPolicy policy) {
    return supervisor(processFactory, healthProbe, policy, new ArrayList<String>());
  }

  private ManagedRuntimeSupervisor supervisor(
      ProcessFactory processFactory,
      HealthProbe healthProbe,
      SupervisorPolicy policy,
      List<String> exits) {
    scheduler = new ManualScheduler();
    supervisor =
        new ManagedRuntimeSupervisor(
            () -> {},
            processFactory,
            healthProbe,
            scheduler,
            new ManagedRuntimeSupervisor.Settings(
                temporaryDirectory.resolve("runtime/bin/node"),
                temporaryDirectory.resolve("runtime/app/main.js"),
                temporaryDirectory.resolve("state/runtime.yml"),
                temporaryDirectory.resolve("runtime"),
                temporaryDirectory.resolve("logs/runtime.out.log"),
                temporaryDirectory.resolve("logs/runtime.err.log"),
                Map.of("NODE_ENV", "production", "LANG", "C.UTF-8")),
            policy,
            exits::add);
    return supervisor;
  }

  private static SupervisorPolicy policy(int maximumRestarts) {
    return new SupervisorPolicy(
        Duration.ofSeconds(10),
        Duration.ofMillis(100),
        Duration.ofMillis(100),
        Duration.ofSeconds(1),
        Duration.ofSeconds(4),
        maximumRestarts);
  }

  private static RuntimeSupervisorException awaitFailure(RuntimeStartAttempt attempt) {
    var error =
        assertThrows(
            ExecutionException.class,
            () -> attempt.ready().toCompletableFuture().get(2, TimeUnit.SECONDS));
    return (RuntimeSupervisorException) error.getCause();
  }

  private static final class FakeProcess extends Process {
    private final long pid;
    private final boolean gracefulStop;
    private final List<String> terminationEvents = new ArrayList<>();
    private final CompletableFuture<Process> exit = new CompletableFuture<>();
    private volatile boolean alive = true;

    private FakeProcess(long pid, boolean gracefulStop) {
      this.pid = pid;
      this.gracefulStop = gracefulStop;
    }

    @Override
    public long pid() {
      return pid;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      throw new UnsupportedOperationException("unused by the supervisor");
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException();
      }
      return 0;
    }

    @Override
    public void destroy() {
      terminationEvents.add("destroy");
      if (gracefulStop) {
        exit();
      }
    }

    @Override
    public Process destroyForcibly() {
      terminationEvents.add("destroyForcibly");
      exit();
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public CompletableFuture<Process> onExit() {
      return exit;
    }

    private void exit() {
      alive = false;
      exit.complete(this);
    }

    private List<String> terminationEvents() {
      return List.copyOf(terminationEvents);
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
