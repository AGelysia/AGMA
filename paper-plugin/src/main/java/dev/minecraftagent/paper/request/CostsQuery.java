package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.lifecycle.OperationalGate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** A single in-flight management costs query bound to one Runtime connection. */
final class CostsQuery {
  private final UUID requestId;
  private final OperationalGate.Permit permit;
  private final ConnectionBinding binding;
  private final CompletableFuture<AgentRequestService.CostsQueryResult> completion =
      new CompletableFuture<>();
  private final AtomicReference<TimeoutScheduler.Cancellable> timeout = new AtomicReference<>();

  CostsQuery(UUID requestId, OperationalGate.Permit permit, ConnectionBinding binding) {
    this.requestId = Objects.requireNonNull(requestId);
    this.permit = Objects.requireNonNull(permit);
    this.binding = Objects.requireNonNull(binding);
  }

  UUID requestId() {
    return requestId;
  }

  OperationalGate.Permit permit() {
    return permit;
  }

  ConnectionBinding binding() {
    return binding;
  }

  CompletableFuture<AgentRequestService.CostsQueryResult> completion() {
    return completion;
  }

  void timeout(TimeoutScheduler.Cancellable scheduled) {
    if (!timeout.compareAndSet(null, scheduled) || completion.isDone()) {
      var current = timeout.getAndSet(null);
      if (current != null) {
        current.cancel();
      } else {
        scheduled.cancel();
      }
    }
  }

  void cancelTimeout() {
    var scheduled = timeout.getAndSet(null);
    if (scheduled != null) {
      scheduled.cancel();
    }
  }
}
