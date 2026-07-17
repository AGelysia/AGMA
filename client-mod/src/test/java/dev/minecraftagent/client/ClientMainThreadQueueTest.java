package dev.minecraftagent.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientMainThreadQueueTest {
  @Test
  void boundsPendingDispatchAndReleasesCapacityAfterExecution() {
    List<Runnable> dispatched = new ArrayList<>();
    var queue = new ClientMainThreadQueue(dispatched::add, 2);
    var executions = new AtomicInteger();

    assertTrue(queue.enqueue(executions::incrementAndGet));
    assertTrue(queue.enqueue(executions::incrementAndGet));
    assertFalse(queue.enqueue(executions::incrementAndGet));
    assertEquals(2, queue.pendingTasks());

    dispatched.removeFirst().run();
    assertEquals(1, queue.pendingTasks());
    assertTrue(queue.enqueue(executions::incrementAndGet));
    while (!dispatched.isEmpty()) {
      dispatched.removeFirst().run();
    }

    assertEquals(3, executions.get());
    assertEquals(0, queue.pendingTasks());
  }

  @Test
  void stopRejectsNewTasksWithoutDiscardingAcceptedCleanup() {
    List<Runnable> dispatched = new ArrayList<>();
    var queue = new ClientMainThreadQueue(dispatched::add, 1);
    var executions = new AtomicInteger();

    assertTrue(queue.enqueue(executions::incrementAndGet));
    queue.stop();
    assertFalse(queue.enqueue(executions::incrementAndGet));
    dispatched.getFirst().run();

    assertEquals(1, executions.get());
    assertEquals(0, queue.pendingTasks());
  }
}
