package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExternalRuntimeSupervisorTest {
  @Test
  void isImmediatelyReadyAndAllLifecycleOperationsAreNoOps() {
    var supervisor = new ExternalRuntimeSupervisor();

    var attempt = supervisor.prepare(RuntimeSupervisorTestFixture.connectionSettings());

    assertTrue(attempt.ready().toCompletableFuture().isDone());
    assertDoesNotThrow(attempt::cancel);
    assertDoesNotThrow(supervisor::stop);
    assertDoesNotThrow(supervisor::close);
  }
}
