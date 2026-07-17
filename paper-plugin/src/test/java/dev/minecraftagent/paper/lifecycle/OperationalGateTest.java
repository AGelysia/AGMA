package dev.minecraftagent.paper.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OperationalGateTest {
  @Test
  void issuesPermitsOnlyWhileOnlineAndInvalidatesThemAcrossEveryTransition() {
    var gate = new OperationalGate();
    assertEquals(AgentState.UNREGISTERED, gate.state());
    assertEquals(0, gate.epoch());
    assertTrue(gate.tryAcquire().isEmpty());

    assertEquals(1, gate.transitionTo(AgentState.ONLINE));
    var first = gate.tryAcquire().orElseThrow();
    assertEquals(1, first.epoch());
    assertTrue(gate.revalidate(first));

    assertEquals(2, gate.transitionTo(AgentState.STOPPING));
    assertFalse(gate.revalidate(first));
    assertTrue(gate.tryAcquire().isEmpty());

    assertEquals(3, gate.transitionTo(AgentState.OFFLINE));
    assertEquals(4, gate.transitionTo(AgentState.STARTING));
    assertEquals(5, gate.transitionTo(AgentState.ONLINE));
    var second = gate.tryAcquire().orElseThrow();

    assertFalse(gate.revalidate(first));
    assertTrue(gate.revalidate(second));
  }

  @Test
  void rejectsAPermitIssuedByAnotherGateEvenWhenEpochsMatch() {
    var firstGate = new OperationalGate(AgentState.ONLINE);
    var secondGate = new OperationalGate(AgentState.ONLINE);
    var permit = firstGate.tryAcquire().orElseThrow();

    assertFalse(secondGate.revalidate(permit));
  }

  @Test
  void evenAnOnlineToOnlineTransitionRotatesTheEpoch() {
    var gate = new OperationalGate(AgentState.ONLINE);
    var permit = gate.tryAcquire().orElseThrow();

    assertEquals(1, gate.transitionTo(AgentState.ONLINE));

    assertFalse(gate.revalidate(permit));
    assertTrue(gate.tryAcquire().isPresent());
  }

  @Test
  void validOperationAndOfflineTransitionHaveOneOrderedCommitBoundary() throws Exception {
    var gate = new OperationalGate(AgentState.ONLINE);
    var permit = gate.tryAcquire().orElseThrow();
    var operationStarted = new CountDownLatch(1);
    var finishOperation = new CountDownLatch(1);
    var published = new AtomicBoolean();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var publication =
          executor.submit(
              () ->
                  gate.executeIfValid(
                      permit,
                      () -> {
                        operationStarted.countDown();
                        try {
                          if (!finishOperation.await(3, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test operation timed out");
                          }
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        published.set(true);
                      }));
      assertTrue(operationStarted.await(3, TimeUnit.SECONDS));
      var transition = executor.submit(() -> gate.transitionTo(AgentState.OFFLINE));
      org.junit.jupiter.api.Assertions.assertThrows(
          TimeoutException.class, () -> transition.get(100, TimeUnit.MILLISECONDS));

      finishOperation.countDown();
      assertTrue(publication.get(3, TimeUnit.SECONDS));
      assertEquals(1, transition.get(3, TimeUnit.SECONDS));
      assertTrue(published.get());
      assertFalse(gate.revalidate(permit));
    }
  }
}
