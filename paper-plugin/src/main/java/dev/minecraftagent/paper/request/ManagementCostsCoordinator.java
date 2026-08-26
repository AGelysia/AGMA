package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.request.AgentRequestService.CostsQueryResult;
import dev.minecraftagent.paper.request.AgentRequestService.EventSink;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the single-flight management costs query against the attached Runtime connection.
 *
 * <p>The {@code costsQuery} field is guarded by the shared service lock. The monitor is owned by
 * {@link AgentRequestService} so offline transitions can clear requests and costs queries as one
 * atomic step.
 */
final class ManagementCostsCoordinator {
  private static final Duration MANAGEMENT_QUERY_TIMEOUT = Duration.ofSeconds(5);

  private final Object lock;
  private final OperationalGate operationalGate;
  private final AtomicReference<ConnectionBinding> connection;
  private final AtomicBoolean closed;
  private final TimeoutScheduler timeoutScheduler;
  private final EventSink events;
  private CostsQuery costsQuery;

  ManagementCostsCoordinator(
      Object lock,
      OperationalGate operationalGate,
      AtomicReference<ConnectionBinding> connection,
      AtomicBoolean closed,
      TimeoutScheduler timeoutScheduler,
      EventSink events) {
    this.lock = Objects.requireNonNull(lock);
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.connection = Objects.requireNonNull(connection);
    this.closed = Objects.requireNonNull(closed);
    this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
    this.events = Objects.requireNonNull(events);
  }

  CompletionStage<CostsQueryResult> query() {
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
    }
    var binding = connection.get();
    if (closed.get() || binding == null || !binding.connection().isOpen()) {
      return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
    }

    CostsQuery query;
    synchronized (lock) {
      if (closed.get()
          || connection.get() != binding
          || !binding.connection().isOpen()
          || !operationalGate.revalidate(permit.orElseThrow())) {
        return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
      }
      if (costsQuery != null) {
        return costsQuery.completion().copy();
      }
      query = new CostsQuery(UUID.randomUUID(), permit.orElseThrow(), binding);
      costsQuery = query;
      try {
        query.timeout(
            timeoutScheduler.schedule(MANAGEMENT_QUERY_TIMEOUT, () -> timeoutCosts(query)));
      } catch (RuntimeException error) {
        costsQuery = null;
        query.completion().complete(CostsQueryResult.failed());
        return query.completion().copy();
      }
    }

    try {
      var encoded = binding.codec().encodeCostsRequest(query.requestId());
      if (!costsCurrent(query)) {
        finishCosts(query, CostsQueryResult.unavailable());
        return query.completion().copy();
      }
      var sent = Objects.requireNonNull(binding.connection().sendApplication(encoded));
      sent.whenComplete(
          (ignored, error) -> {
            if (error != null) {
              costsSendFailed(query);
            }
          });
    } catch (RuntimeException error) {
      costsSendFailed(query);
    }
    return query.completion().copy();
  }

  void receive(ConnectionBinding source, AgentProtocolCodec.ManagementCosts response) {
    CostsQuery query;
    CostsQueryResult result;
    synchronized (lock) {
      query = costsQuery;
      if (query == null || !query.requestId().equals(response.requestId())) {
        return;
      }
      if (query.binding() != source) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      costsQuery = null;
      result =
          operationalGate.revalidate(query.permit())
              ? CostsQueryResult.available(response)
              : CostsQueryResult.unavailable();
    }
    completeCosts(query, result);
  }

  private void timeoutCosts(CostsQuery query) {
    finishCosts(query, CostsQueryResult.timedOut());
  }

  private void costsSendFailed(CostsQuery query) {
    if (!finishCosts(query, CostsQueryResult.failed())) {
      return;
    }
    events.event("RUNTIME_MANAGEMENT_COSTS_SEND_FAILED");
    query.binding().connection().close();
  }

  private boolean costsCurrent(CostsQuery query) {
    synchronized (lock) {
      return costsQuery == query
          && !closed.get()
          && connection.get() == query.binding()
          && query.binding().connection().isOpen()
          && operationalGate.revalidate(query.permit());
    }
  }

  private boolean finishCosts(CostsQuery query, CostsQueryResult result) {
    synchronized (lock) {
      if (costsQuery != query) {
        return false;
      }
      costsQuery = null;
    }
    completeCosts(query, result);
    return true;
  }

  void disconnect(ConnectionBinding binding) {
    disconnect(binding, CostsQueryResult.unavailable());
  }

  void disconnect(ConnectionBinding binding, CostsQueryResult result) {
    CostsQuery query;
    synchronized (lock) {
      query = take(binding);
    }
    completeCosts(query, result);
  }

  /** Removes the current query if it matches {@code binding}; the caller must hold the lock. */
  CostsQuery take(ConnectionBinding binding) {
    if (costsQuery == null || binding != null && costsQuery.binding() != binding) {
      return null;
    }
    var query = costsQuery;
    costsQuery = null;
    return query;
  }

  void completeCosts(CostsQuery query, CostsQueryResult result) {
    if (query == null) {
      return;
    }
    query.cancelTimeout();
    query.completion().complete(result);
  }
}
