package dev.minecraftagent.standalone.common;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;

/**
 * Correlation state for one connector session: pending requests, pending tool calls, capacity
 * counters, and the settled-request windows that make late Runtime terminals idempotent.
 *
 * <p>Instances hold no lock of their own. Every method must be invoked while the owning {@link
 * ConnectorConnection} monitor is held, so admission, correlation, and settlement stay atomic with
 * the session state machine. Methods that complete futures ({@link #complete} and {@link
 * #failPending}) may be called after the monitor is released.
 */
final class ConnectorPendingTable {
  private static final int STATUS_CAPACITY = 8;

  private final Map<UUID, Pending<?>> pending = new LinkedHashMap<>();
  private final Map<UUID, PendingTool> pendingTools = new LinkedHashMap<>();
  private final SettledRequestWindow settledRequests;
  private final SettledRequestWindow settledToolCalls;
  private int pendingTextRequests;
  private int pendingStatusRequests;

  ConnectorPendingTable(Duration ttl, int maximumEntries) {
    settledRequests = new SettledRequestWindow(ttl, maximumEntries);
    settledToolCalls = new SettledRequestWindow(ttl, maximumEntries);
  }

  boolean textCapacityExceeded(int maximumTextRequests) {
    return pendingTextRequests >= maximumTextRequests;
  }

  boolean statusCapacityExceeded() {
    return pendingStatusRequests >= STATUS_CAPACITY;
  }

  /** Whether a request identifier is in flight or was settled recently. */
  boolean knownRequest(UUID requestId, long nowMillis) {
    return pending.containsKey(requestId) || settledRequests.contains(requestId, nowMillis);
  }

  Pending<?> find(UUID requestId) {
    return pending.get(requestId);
  }

  void addText(PendingText entry) {
    pending.put(entry.requestId, entry);
    pendingTextRequests++;
  }

  void addStatus(PendingStatus entry) {
    pending.put(entry.requestId, entry);
    pendingStatusRequests++;
  }

  /** Retires a text entry that never reached the wire, remembering it as settled. */
  void forgetText(UUID requestId, long nowMillis) {
    pending.remove(requestId);
    pendingTextRequests--;
    settledRequests.remember(requestId, nowMillis);
  }

  /** Retires a status entry that never reached the wire, remembering it as settled. */
  void forgetStatus(UUID requestId, long nowMillis) {
    pending.remove(requestId);
    pendingStatusRequests--;
    settledRequests.remember(requestId, nowMillis);
  }

  /** Cancels a locally cancelled text entry: removes it and stops its timeout. */
  void cancelText(PendingText entry, long nowMillis) {
    pending.remove(entry.requestId);
    pendingTextRequests--;
    cancelTimeout(entry);
    settledRequests.remember(entry.requestId, nowMillis);
  }

  /** Removes the entry only if it is still the one registered under its identifier. */
  boolean takeIfCurrent(Pending<?> entry) {
    if (pending.get(entry.requestId) != entry) {
      return false;
    }
    pending.remove(entry.requestId);
    if (entry instanceof PendingText) {
      pendingTextRequests--;
    } else {
      pendingStatusRequests--;
    }
    return true;
  }

  void rememberRequest(UUID requestId, long nowMillis) {
    settledRequests.remember(requestId, nowMillis);
  }

  /**
   * Locates the pending entry a Runtime response must settle.
   *
   * @return the entry, or {@code null} when the request was already settled (late terminal)
   * @throws ConnectorException {@code APPLICATION_MESSAGE_INVALID} when nothing known matches the
   *     response or the request still has an active tool call
   */
  Pending<?> settle(UUID requestId, long nowMillis) {
    var entry = pending.get(requestId);
    if (entry == null) {
      if (settledRequests.contains(requestId, nowMillis)) {
        return null;
      }
      throw new ConnectorException(
          "APPLICATION_MESSAGE_INVALID", "Runtime response correlation is invalid");
    }
    if (pendingTools.values().stream()
        .anyMatch(tool -> tool.call.requestId().equals(entry.requestId))) {
      throw new ConnectorException(
          "APPLICATION_MESSAGE_INVALID", "Runtime completed a request with an active tool");
    }
    return entry;
  }

  /** Retires an entry whose response passed correlation validation. */
  void remove(Pending<?> entry, long nowMillis) {
    pending.remove(entry.requestId);
    if (entry instanceof PendingText) {
      pendingTextRequests--;
    } else {
      pendingStatusRequests--;
    }
    cancelTimeout(entry);
    settledRequests.remember(entry.requestId, nowMillis);
  }

  /** Checks that a response kind can answer the pending entry kind. */
  void validateCorrelation(Pending<?> entry, ConnectorResponseParser.Response response) {
    if (entry instanceof PendingText text) {
      if (response instanceof ConnectorResponseParser.Status) {
        throw JsonFields.invalid("/type");
      }
      if (response instanceof ConnectorResponseParser.Complete complete
          && text.request.sessionId() != null
          && !text.request.sessionId().equals(complete.sessionId())) {
        throw JsonFields.invalid("/payload/sessionId");
      }
    } else if (!(response instanceof ConnectorResponseParser.Status)) {
      throw JsonFields.invalid("/type");
    }
  }

  /** Completes the entry's future from its parsed response. */
  void complete(Pending<?> entry, ConnectorResponseParser.Response response) {
    if (entry instanceof PendingText text) {
      if (response instanceof ConnectorResponseParser.Complete complete) {
        text.future.complete(
            TextCompletion.completed(
                text.requestId,
                complete.sessionId(),
                complete.text(),
                complete.costMicroUsd(),
                complete.costKind(),
                complete.sources()));
      } else if (response instanceof ConnectorResponseParser.Error error) {
        var status =
            switch (error.code()) {
              case "MODEL_TIMEOUT" -> TextCompletion.Status.TIMED_OUT;
              case "REQUEST_CANCELLED" -> TextCompletion.Status.CANCELLED;
              default -> TextCompletion.Status.FAILED;
            };
        text.future.complete(
            TextCompletion.failed(
                text.requestId, status, error.code(), error.message(), error.retryable()));
      }
    } else if (entry instanceof PendingStatus status
        && response instanceof ConnectorResponseParser.Status value) {
      status.future.complete(
          new RuntimeStatus(
              status.requestId, value.state(), value.activeRequests(), value.queuedRequests()));
    }
  }

  /** Whether a Runtime tool call may open a new pending tool entry. */
  boolean canRegisterTool(ClientToolCall call, long nowMillis) {
    return pending.get(call.requestId()) instanceof PendingText
        && !pendingTools.containsKey(call.toolCallId())
        && !settledToolCalls.contains(call.toolCallId(), nowMillis)
        && pendingTools.values().stream()
            .noneMatch(tool -> tool.call.requestId().equals(call.requestId()));
  }

  void addTool(PendingTool entry) {
    pendingTools.put(entry.call.toolCallId(), entry);
  }

  PendingTool findTool(UUID toolCallId) {
    return pendingTools.get(toolCallId);
  }

  boolean toolSettled(UUID toolCallId, long nowMillis) {
    return settledToolCalls.contains(toolCallId, nowMillis);
  }

  /** Retires a tool entry that reached a terminal state. */
  void removeTool(PendingTool entry, long nowMillis) {
    pendingTools.remove(entry.call.toolCallId());
    settledToolCalls.remember(entry.call.toolCallId(), nowMillis);
  }

  /** Drains every pending request, remembering each as settled. */
  List<Pending<?>> drainAll(long nowMillis) {
    var result = new ArrayList<>(pending.values());
    for (var entry : result) {
      cancelTimeout(entry);
      settledRequests.remember(entry.requestId, nowMillis);
    }
    pending.clear();
    pendingTextRequests = 0;
    pendingStatusRequests = 0;
    return result;
  }

  /**
   * Drains pending tools, either all of them ({@code requestId == null}) or only those belonging to
   * one request.
   */
  List<PendingTool> drainTools(UUID requestId, long nowMillis) {
    var result = new ArrayList<PendingTool>();
    for (var iterator = pendingTools.entrySet().iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      if (requestId == null || entry.getValue().call.requestId().equals(requestId)) {
        result.add(entry.getValue());
        settledToolCalls.remember(entry.getKey(), nowMillis);
        iterator.remove();
      }
    }
    return result;
  }

  /** Fails every drained entry with the session error that abandoned it. */
  static void failPending(List<Pending<?>> entries, ConnectorException error) {
    for (var entry : entries) {
      entry.future.completeExceptionally(error);
    }
  }

  /** Stops an entry's timeout without retiring it, for entries abandoned by their own future. */
  static void cancelTimeout(Pending<?> entry) {
    if (entry.timeout != null) {
      entry.timeout.cancel(false);
    }
  }

  /** An in-flight request awaiting one Runtime response. */
  abstract static class Pending<T> {
    final UUID requestId;
    final CancellableFuture<T> future;
    ScheduledFuture<?> timeout;

    Pending(UUID requestId, CancellableFuture<T> future) {
      this.requestId = requestId;
      this.future = future;
    }
  }

  /** An in-flight text request. */
  static final class PendingText extends Pending<TextCompletion> {
    final TextRequest request;

    PendingText(TextRequest request, CancellableFuture<TextCompletion> future) {
      super(request.requestId(), future);
      this.request = request;
    }
  }

  /** An in-flight status probe. */
  static final class PendingStatus extends Pending<RuntimeStatus> {
    PendingStatus(UUID requestId, CancellableFuture<RuntimeStatus> future) {
      super(requestId, future);
    }
  }

  /** A tool call handed to the client tool handler. */
  static final class PendingTool {
    final ClientToolCall call;
    final ClientToolHandler handler;
    CompletionStage<? extends ClientToolOutcome> execution;

    PendingTool(ClientToolCall call, ClientToolHandler handler) {
      this.call = call;
      this.handler = handler;
    }
  }
}
