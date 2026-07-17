package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeploymentRuntimeSupervisorTest {
  @Test
  void selectsOnlyTheConfiguredDeploymentMode() {
    var external = new RecordingSupervisor();
    var managed = new RecordingSupervisor();
    var supervisor = new DeploymentRuntimeSupervisor(external, managed);

    supervisor.prepare(settings(RuntimeDeploymentMode.EXTERNAL));
    supervisor.prepare(settings(RuntimeDeploymentMode.MANAGED));
    supervisor.stop();
    supervisor.close();

    assertEquals(1, external.prepares.get());
    assertEquals(1, managed.prepares.get());
    assertEquals(0, external.stops.get());
    assertEquals(1, managed.stops.get());
    assertEquals(1, external.closes.get());
    assertEquals(1, managed.closes.get());
  }

  private static RuntimeConnectionSettings settings(RuntimeDeploymentMode mode) {
    return new RuntimeConnectionSettings(
        mode,
        URI.create("ws://127.0.0.1:38127/agent"),
        "server",
        "server-token-that-is-long-enough-0123456789",
        "test",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private static final class RecordingSupervisor implements RuntimeSupervisor {
    private final AtomicInteger prepares = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public RuntimeStartAttempt prepare(RuntimeConnectionSettings settings) {
      prepares.incrementAndGet();
      return new RuntimeStartAttempt() {
        @Override
        public CompletableFuture<Void> ready() {
          return CompletableFuture.completedFuture(null);
        }

        @Override
        public void cancel() {}
      };
    }

    @Override
    public void stop() {
      stops.incrementAndGet();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
