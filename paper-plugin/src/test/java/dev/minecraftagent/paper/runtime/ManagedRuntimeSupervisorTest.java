package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRuntimeSupervisorTest {
  @TempDir Path temporaryDirectory;

  private ScheduledExecutorService scheduler;
  private ManagedRuntimeSupervisor supervisor;

  @AfterEach
  void closeResources() throws InterruptedException {
    if (supervisor != null) {
      supervisor.close();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
      assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void startsOneFixedCommandAndCompletesAfterTheHealthProbeSucceeds() throws Exception {
    var events = java.util.Collections.synchronizedList(new ArrayList<String>());
    var process = new FakeProcess(true, true, events);
    var builder = new AtomicReference<ProcessBuilder>();
    var starts = new AtomicInteger();
    var probes = new AtomicInteger();
    supervisor =
        supervisor(
            candidate -> {
              events.add("start");
              starts.incrementAndGet();
              builder.set(candidate);
              return process;
            },
            ignored -> {
              events.add("probe");
              return probes.incrementAndGet() >= 2;
            },
            Duration.ofSeconds(1),
            Duration.ofMillis(10));

    var first = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    var second = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    first.ready().toCompletableFuture().get(2, TimeUnit.SECONDS);

    assertSame(first, second);
    assertEquals(1, starts.get());
    assertTrue(probes.get() >= 2);
    assertEquals("start", events.getFirst());
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
  }

  @Test
  void failsWhenTheChildExitsBeforeReadiness() throws Exception {
    var process = new FakeProcess(false, true, new ArrayList<>());
    supervisor =
        supervisor(
            ignored -> process,
            ignored -> {
              throw new AssertionError("health must not be probed after exit");
            },
            Duration.ofSeconds(1),
            Duration.ofMillis(5));

    var failure =
        awaitFailure(supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()));

    assertEquals("MANAGED_RUNTIME_EXITED", failure.code());
  }

  @Test
  void timesOutAndTerminatesAChildThatNeverBecomesHealthy() throws Exception {
    var process = new FakeProcess(true, true, new ArrayList<>());
    supervisor =
        supervisor(
            ignored -> process, ignored -> false, Duration.ofMillis(35), Duration.ofMillis(5));

    var failure =
        awaitFailure(supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()));

    assertEquals("MANAGED_RUNTIME_START_TIMEOUT", failure.code());
    assertEquals(List.of("destroy", "waitFor"), process.terminationEvents());
    assertFalse(process.isAlive());
  }

  @Test
  void cancellationTerminatesTheCurrentChild() throws Exception {
    var process = new FakeProcess(true, true, new ArrayList<>());
    var started = new CountDownLatch(1);
    supervisor =
        supervisor(
            ignored -> process,
            ignored -> {
              started.countDown();
              return false;
            },
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    assertTrue(started.await(1, TimeUnit.SECONDS));

    attempt.cancel();

    assertEquals("MANAGED_RUNTIME_CANCELLED", awaitFailure(attempt).code());
    assertEquals(List.of("destroy", "waitFor"), process.terminationEvents());
  }

  @Test
  void forciblyTerminatesAChildThatIgnoresTheGracefulStop() throws Exception {
    var process = new FakeProcess(true, false, new ArrayList<>());
    var started = new CountDownLatch(1);
    supervisor =
        supervisor(
            ignored -> process,
            ignored -> {
              started.countDown();
              return false;
            },
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    assertTrue(started.await(1, TimeUnit.SECONDS));

    supervisor.stop();

    assertEquals(
        List.of("destroy", "waitFor", "destroyForcibly", "waitFor"), process.terminationEvents());
    assertFalse(process.isAlive());
  }

  @Test
  void closeStopsTheChildAndPreventsAnotherStart() throws Exception {
    var process = new FakeProcess(true, true, new ArrayList<>());
    var started = new CountDownLatch(1);
    supervisor =
        supervisor(
            ignored -> process,
            ignored -> {
              started.countDown();
              return false;
            },
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    assertTrue(started.await(1, TimeUnit.SECONDS));

    supervisor.close();

    assertFalse(process.isAlive());
    assertThrows(
        IllegalStateException.class,
        () -> supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()));
  }

  @Test
  void closeReturnsWhileProvisioningIsStillInProgress() throws Exception {
    var provisioning = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    supervisor =
        supervisor(
            ignored -> {
              provisioning.countDown();
              try {
                release.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled");
              }
              return new FakeProcess(true, true, new ArrayList<>());
            },
            ignored -> false,
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());
    assertTrue(provisioning.await(1, TimeUnit.SECONDS));

    try {
      org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofMillis(250), supervisor::close);
      assertEquals("MANAGED_RUNTIME_CANCELLED", awaitFailure(attempt).code());
    } finally {
      release.countDown();
    }
  }

  @Test
  void startFailureDoesNotExposeTheCommandEnvironmentOrUnderlyingError() throws Exception {
    supervisor =
        supervisor(
            ignored -> {
              throw new IOException("secret-from-operating-system");
            },
            ignored -> false,
            Duration.ofSeconds(1),
            Duration.ofMillis(10));

    var failure =
        awaitFailure(supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings()));

    assertEquals("MANAGED_RUNTIME_START_FAILED", failure.code());
    assertFalse(failure.toString().contains("secret-from-operating-system"));
    assertFalse(failure.toString().contains("NODE_ENV"));
    assertFalse(failure.toString().contains("main.js"));
  }

  private ManagedRuntimeSupervisor supervisor(
      ProcessFactory processFactory,
      HealthProbe healthProbe,
      Duration startupTimeout,
      Duration pollInterval) {
    scheduler = Executors.newSingleThreadScheduledExecutor();
    return new ManagedRuntimeSupervisor(
        scheduler,
        processFactory,
        healthProbe,
        new ManagedRuntimeSupervisor.Settings(
            temporaryDirectory.resolve("runtime/bin/node"),
            temporaryDirectory.resolve("runtime/app/main.js"),
            temporaryDirectory.resolve("state/runtime.yml"),
            temporaryDirectory.resolve("runtime"),
            temporaryDirectory.resolve("logs/runtime.out.log"),
            temporaryDirectory.resolve("logs/runtime.err.log"),
            pollInterval,
            startupTimeout,
            Duration.ofMillis(5),
            Map.of("NODE_ENV", "production", "LANG", "C.UTF-8")));
  }

  private static RuntimeSupervisorException awaitFailure(RuntimeStartAttempt attempt)
      throws Exception {
    var error =
        assertThrows(
            ExecutionException.class,
            () -> attempt.ready().toCompletableFuture().get(2, TimeUnit.SECONDS));
    return (RuntimeSupervisorException) error.getCause();
  }

  private static final class FakeProcess extends Process {
    private final boolean gracefulStop;
    private final List<String> terminationEvents = new ArrayList<>();
    private final List<String> sharedEvents;
    private volatile boolean alive;

    private FakeProcess(boolean alive, boolean gracefulStop, List<String> sharedEvents) {
      this.alive = alive;
      this.gracefulStop = gracefulStop;
      this.sharedEvents = sharedEvents;
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
    public int waitFor() throws InterruptedException {
      while (alive) {
        Thread.sleep(1);
      }
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      terminationEvents.add("waitFor");
      return !alive;
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
      sharedEvents.add("destroy");
      if (gracefulStop) {
        alive = false;
      }
    }

    @Override
    public Process destroyForcibly() {
      terminationEvents.add("destroyForcibly");
      sharedEvents.add("destroyForcibly");
      alive = false;
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    private List<String> terminationEvents() {
      return List.copyOf(terminationEvents);
    }
  }
}
