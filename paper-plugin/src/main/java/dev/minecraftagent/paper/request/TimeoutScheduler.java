package dev.minecraftagent.paper.request;

import java.time.Duration;

/**
 * Schedules timeout callbacks for live requests and management queries. Tests substitute manual
 * control so timeouts stay deterministic.
 */
@FunctionalInterface
interface TimeoutScheduler {
  Cancellable schedule(Duration delay, Runnable task);

  @FunctionalInterface
  interface Cancellable {
    void cancel();
  }
}
