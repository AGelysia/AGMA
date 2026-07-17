package dev.minecraftagent.standalone.supervisor;

import java.time.Duration;

/** Small scheduling port so lifecycle races and retry timing can be tested without sleeping. */
public interface SupervisorScheduler extends AutoCloseable {
  Cancellable schedule(Runnable action, Duration delay);

  @Override
  void close();

  @FunctionalInterface
  interface Cancellable {
    void cancel();
  }
}
