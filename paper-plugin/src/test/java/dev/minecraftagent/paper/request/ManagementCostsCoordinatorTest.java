package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.request.AgentRequestService.CostsQueryStatus;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagementCostsCoordinatorTest {
  private static final String SERVER_ID = "survival-main";

  @Test
  void queriesWithoutAttachedBindingAreUnavailable() {
    var fixture = new Fixture();

    var first = fixture.coordinator.query().toCompletableFuture().join();
    var second = fixture.coordinator.query().toCompletableFuture().join();

    assertEquals(CostsQueryStatus.UNAVAILABLE, first.status());
    assertEquals(CostsQueryStatus.UNAVAILABLE, second.status());
    assertTrue(fixture.events.isEmpty());
  }

  @Test
  void queriesAreSingleFlightAgainstOneWireRequest() {
    var fixture = new Fixture();
    fixture.connectionRef.set(fixture.binding);

    var first = fixture.coordinator.query().toCompletableFuture();
    var second = fixture.coordinator.query().toCompletableFuture();

    assertEquals(1, fixture.connection.sent.size());
    var envelope = JsonParser.parseString(fixture.connection.sent.getFirst()).getAsJsonObject();
    assertEquals("management.costs.request", envelope.get("type").getAsString());
    fixture.coordinator.receive(
        fixture.binding, costs(UUID.fromString(envelope.get("requestId").getAsString())));

    assertEquals(CostsQueryStatus.AVAILABLE, first.join().status());
    assertEquals(CostsQueryStatus.AVAILABLE, second.join().status());
    assertEquals(3, first.join().costs().currentDay().admittedRequests());
  }

  @Test
  void timeoutsFailTheQueryAndFreeTheSlotForAFreshOne() {
    var fixture = new Fixture();
    fixture.connectionRef.set(fixture.binding);

    var timedOut = fixture.coordinator.query().toCompletableFuture();
    var staleRequestId =
        JsonParser.parseString(fixture.connection.sent.getFirst())
            .getAsJsonObject()
            .get("requestId")
            .getAsString();
    fixture.timeouts.fire();

    assertEquals(CostsQueryStatus.TIMED_OUT, timedOut.join().status());
    fixture.coordinator.receive(fixture.binding, costs(UUID.fromString(staleRequestId)));

    var fresh = fixture.coordinator.query().toCompletableFuture();
    assertEquals(2, fixture.connection.sent.size());
    var freshRequestId =
        JsonParser.parseString(fixture.connection.sent.get(1))
            .getAsJsonObject()
            .get("requestId")
            .getAsString();
    fixture.coordinator.receive(fixture.binding, costs(UUID.fromString(freshRequestId)));

    assertEquals(CostsQueryStatus.AVAILABLE, fresh.join().status());
    assertTrue(fixture.events.isEmpty());
  }

  @Test
  void responsesFromAWrongBindingAreRejectedWithoutFailingTheQuery() {
    var fixture = new Fixture();
    fixture.connectionRef.set(fixture.binding);
    var intruderBinding =
        new ConnectionBinding(new FakeConnection(), new AgentProtocolCodec(SERVER_ID));

    var pending = fixture.coordinator.query().toCompletableFuture();
    var requestId =
        JsonParser.parseString(fixture.connection.sent.getFirst())
            .getAsJsonObject()
            .get("requestId")
            .getAsString();
    fixture.coordinator.receive(intruderBinding, costs(UUID.fromString(requestId)));

    assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
    assertFalse(intruderBinding.connection().isOpen());
    assertFalse(pending.isDone());

    fixture.coordinator.receive(fixture.binding, costs(UUID.fromString(requestId)));
    assertEquals(CostsQueryStatus.AVAILABLE, pending.join().status());
  }

  @Test
  void disconnectClearsThePendingQueryAsUnavailable() {
    var fixture = new Fixture();
    fixture.connectionRef.set(fixture.binding);
    var pending = fixture.coordinator.query().toCompletableFuture();

    fixture.coordinator.disconnect(fixture.binding);

    assertEquals(CostsQueryStatus.UNAVAILABLE, pending.join().status());
    assertTrue(fixture.events.isEmpty());
  }

  private static AgentProtocolCodec.ManagementCosts costs(UUID requestId) {
    return new AgentProtocolCodec.ManagementCosts(
        UUID.randomUUID(),
        requestId,
        new AgentProtocolCodec.UsageWindow("2026-07-14", 3, 5, 4, 1, 1200, 300, 1800),
        new AgentProtocolCodec.UsageWindow("2026-07", 30, 42, 40, 2, 12_000, 3000, 18_000),
        new AgentProtocolCodec.Budget("2026-07", 50_000_000, 18_000, 2000, 49_980_000, false));
  }

  private static final class Fixture {
    private final OperationalGate gate = new OperationalGate(AgentState.ONLINE);
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ManualTimeouts timeouts = new ManualTimeouts();
    private final List<String> events = new ArrayList<>();
    private final FakeConnection connection = new FakeConnection();
    private final ConnectionBinding binding =
        new ConnectionBinding(connection, new AgentProtocolCodec(SERVER_ID));
    private final AtomicReference<ConnectionBinding> connectionRef = new AtomicReference<>();
    private final ManagementCostsCoordinator coordinator;

    private Fixture() {
      coordinator =
          new ManagementCostsCoordinator(lock, gate, connectionRef, closed, timeouts, events::add);
    }
  }

  private static final class ManualTimeouts implements TimeoutScheduler {
    private final List<Scheduled> tasks = new ArrayList<>();

    @Override
    public TimeoutScheduler.Cancellable schedule(Duration delay, Runnable task) {
      var scheduled = new Scheduled(task);
      tasks.add(scheduled);
      return scheduled::cancel;
    }

    private void fire() {
      for (var task : List.copyOf(tasks)) {
        task.run();
      }
    }
  }

  private static final class Scheduled {
    private final Runnable task;
    private boolean cancelled;

    private Scheduled(Runnable task) {
      this.task = task;
    }

    private void cancel() {
      cancelled = true;
    }

    private void run() {
      if (!cancelled) {
        task.run();
      }
    }
  }

  private static final class FakeConnection implements AuthenticatedRuntimeConnection {
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private final List<String> sent = new ArrayList<>();
    private boolean open = true;

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return closedFuture;
    }

    @Override
    public CompletionStage<Void> sendApplication(String message) {
      sent.add(message);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      open = false;
      closedFuture.complete(null);
    }
  }
}
