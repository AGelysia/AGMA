package dev.minecraftagent.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientTaskQueueTest {
  @Test
  void rejectsFloodAndConnectionTransitionReplacesStaleQueuedFrames() throws Exception {
    var worker = worker(2);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var queue = new ClientTaskQueue(worker, scheduler);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var staleTasks = new AtomicInteger();
    var transition = new CountDownLatch(1);

    try {
      assertTrue(
          queue.enqueue(
              () -> {
                started.countDown();
                await(release);
              }));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertTrue(queue.enqueue(staleTasks::incrementAndGet));
      assertTrue(queue.enqueue(staleTasks::incrementAndGet));
      assertFalse(queue.enqueue(staleTasks::incrementAndGet));
      assertTrue(queue.replacePending(transition::countDown));

      release.countDown();
      assertTrue(transition.await(2, TimeUnit.SECONDS));
      assertEquals(0, staleTasks.get());
    } finally {
      release.countDown();
      queue.stop(() -> {});
    }
  }

  @Test
  void stopRunsCleanupOnceAndContainsLateCallbacks() {
    var worker = worker(2);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var queue = new ClientTaskQueue(worker, scheduler);
    var cleanups = new AtomicInteger();
    var lateTasks = new AtomicInteger();

    queue.stop(cleanups::incrementAndGet);
    queue.stop(cleanups::incrementAndGet);

    assertFalse(queue.enqueue(lateTasks::incrementAndGet));
    assertFalse(queue.replacePending(lateTasks::incrementAndGet));
    assertFalse(queue.scheduleEverySecond(lateTasks::incrementAndGet));
    assertEquals(1, cleanups.get());
    assertEquals(0, lateTasks.get());
    assertTrue(worker.isShutdown());
    assertTrue(scheduler.isShutdown());
  }

  private static ThreadPoolExecutor worker(int capacity) {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
    }
  }
}
