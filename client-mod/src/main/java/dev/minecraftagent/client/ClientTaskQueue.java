package dev.minecraftagent.client;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded queue for client protocol work outside the render thread. */
final class ClientTaskQueue {
  static final int MAX_PENDING_TASKS = 256;

  private final ThreadPoolExecutor worker;
  private final ScheduledExecutorService scheduler;
  private boolean stopped;

  ClientTaskQueue(ThreadPoolExecutor worker, ScheduledExecutorService scheduler) {
    this.worker = Objects.requireNonNull(worker);
    this.scheduler = Objects.requireNonNull(scheduler);
  }

  static ClientTaskQueue create() {
    var worker =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_TASKS),
            task -> {
              var thread = new Thread(task, "agma-client-network");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    var scheduler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "agma-client-expiry");
              thread.setDaemon(true);
              return thread;
            });
    return new ClientTaskQueue(worker, scheduler);
  }

  synchronized boolean enqueue(Runnable task) {
    Objects.requireNonNull(task);
    if (stopped) {
      return false;
    }
    try {
      worker.execute(task);
      return true;
    } catch (RejectedExecutionException failure) {
      return false;
    }
  }

  /** Drops stale queued frames and places the newest connection transition next. */
  synchronized boolean replacePending(Runnable task) {
    Objects.requireNonNull(task);
    if (stopped) {
      return false;
    }
    worker.getQueue().clear();
    try {
      worker.execute(task);
      return true;
    } catch (RejectedExecutionException failure) {
      return false;
    }
  }

  synchronized boolean scheduleEverySecond(Runnable task) {
    Objects.requireNonNull(task);
    if (stopped) {
      return false;
    }
    try {
      scheduler.scheduleAtFixedRate(() -> enqueue(task), 1, 1, TimeUnit.SECONDS);
      return true;
    } catch (RejectedExecutionException failure) {
      return false;
    }
  }

  void stop(Runnable cleanup) {
    Objects.requireNonNull(cleanup);
    synchronized (this) {
      if (stopped) {
        return;
      }
      stopped = true;
    }
    try {
      cleanup.run();
    } finally {
      scheduler.shutdownNow();
      worker.shutdownNow();
    }
  }
}
