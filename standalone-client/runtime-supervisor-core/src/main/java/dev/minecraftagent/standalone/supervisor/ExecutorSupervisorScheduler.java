package dev.minecraftagent.standalone.supervisor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single daemon-thread scheduler used by the production Fabric shell. */
public final class ExecutorSupervisorScheduler implements SupervisorScheduler {
  private final ScheduledExecutorService executor;

  public ExecutorSupervisorScheduler(String threadName) {
    Objects.requireNonNull(threadName, "threadName");
    if (threadName.isBlank()) {
      throw new IllegalArgumentException("Scheduler thread name cannot be blank");
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            action -> {
              var thread = new Thread(action, threadName);
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public Cancellable schedule(Runnable action, Duration delay) {
    Objects.requireNonNull(action, "action");
    requireDelay(delay);
    var future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private static void requireDelay(Duration value) {
    Objects.requireNonNull(value, "delay");
    if (value.isNegative()) {
      throw new IllegalArgumentException("Scheduler delay cannot be negative");
    }
  }
}
