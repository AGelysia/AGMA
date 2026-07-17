package dev.minecraftagent.client;

import java.util.Objects;
import java.util.function.Consumer;

/** Bounds this mod's pending work on the Minecraft client thread. */
final class ClientMainThreadQueue {
  static final int MAX_PENDING_TASKS = 128;

  private final Consumer<Runnable> dispatcher;
  private final int capacity;
  private int pending;
  private boolean stopped;

  ClientMainThreadQueue(Consumer<Runnable> dispatcher, int capacity) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
    if (capacity < 1) {
      throw new IllegalArgumentException("Client task capacity must be positive");
    }
    this.capacity = capacity;
  }

  static ClientMainThreadQueue create(Consumer<Runnable> dispatcher) {
    return new ClientMainThreadQueue(dispatcher, MAX_PENDING_TASKS);
  }

  synchronized boolean enqueue(Runnable task) {
    Objects.requireNonNull(task);
    if (stopped || pending >= capacity) {
      return false;
    }
    var reservation = new Reservation();
    pending++;
    try {
      dispatcher.accept(
          () -> {
            try {
              task.run();
            } finally {
              release(reservation);
            }
          });
      return true;
    } catch (RuntimeException failure) {
      release(reservation);
      return false;
    }
  }

  synchronized void stop() {
    stopped = true;
  }

  synchronized int pendingTasks() {
    return pending;
  }

  private synchronized void release(Reservation reservation) {
    if (!reservation.released) {
      reservation.released = true;
      pending--;
    }
  }

  private static final class Reservation {
    private boolean released;
  }
}
