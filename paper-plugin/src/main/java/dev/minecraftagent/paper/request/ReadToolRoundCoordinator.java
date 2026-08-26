package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.request.AgentRequestService.EventSink;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.tool.ReadToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestrates the intermediate read-tool rounds of live requests: every bound {@code tool.call} is
 * validated against the request's round state, executed at most once, and reported back on the same
 * connection binding. Result delivery hops through the callbacks executor.
 *
 * <p>Round state lives in {@link LiveRequest} and the shared request registry; both are guarded by
 * the service lock. {@code sendFailed} and {@code rejectBinding} hand protocol-level failures back
 * to {@link AgentRequestService}.
 */
final class ReadToolRoundCoordinator {
  private final Object lock;
  private final Map<UUID, LiveRequest> requestsById;
  private final AtomicReference<ConnectionBinding> connection;
  private final OperationalGate operationalGate;
  private final ReadToolRegistry toolRegistry;
  private final ReadToolExecutor toolExecutor;
  private final Executor callbacks;
  private final EventSink events;
  private final Consumer<LiveRequest> sendFailed;
  private final Consumer<ConnectionBinding> rejectBinding;

  ReadToolRoundCoordinator(
      Object lock,
      Map<UUID, LiveRequest> requestsById,
      AtomicReference<ConnectionBinding> connection,
      OperationalGate operationalGate,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks,
      EventSink events,
      Consumer<LiveRequest> sendFailed,
      Consumer<ConnectionBinding> rejectBinding) {
    this.lock = Objects.requireNonNull(lock);
    this.requestsById = Objects.requireNonNull(requestsById);
    this.connection = Objects.requireNonNull(connection);
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.toolRegistry = Objects.requireNonNull(toolRegistry);
    this.toolExecutor = Objects.requireNonNull(toolExecutor);
    this.callbacks = Objects.requireNonNull(callbacks);
    this.events = Objects.requireNonNull(events);
    this.sendFailed = Objects.requireNonNull(sendFailed);
    this.rejectBinding = Objects.requireNonNull(rejectBinding);
  }

  void receive(ConnectionBinding source, AgentProtocolCodec.ToolCall message) {
    LiveRequest request;
    ReadToolCall call = message.call();
    ReadToolResult immediate = null;
    synchronized (lock) {
      request = requestsById.get(message.requestId());
      if (request == null) {
        return;
      }
      if (request.binding() != source || !request.playerId().equals(message.playerUuid())) {
        rejectBinding.accept(source);
        return;
      }
      if (!validToolBinding(request, call)) {
        rejectBinding.accept(source);
        return;
      }
      var validation = toolRegistry.validate(call.tool(), call.module(), call.arguments());
      request.acceptToolCall(call);
      if (!validation.accepted()) {
        immediate = validation.rejection();
      }
    }

    if (immediate != null) {
      finishTool(request, call, immediate, null);
      return;
    }
    try {
      var execution = toolExecutor.execute(call).toCompletableFuture();
      synchronized (lock) {
        if (!requestOwnsActiveTool(request, call)) {
          execution.cancel(false);
          return;
        }
        request.toolExecution(call, execution);
      }
      execution.whenCompleteAsync(
          (result, error) -> finishTool(request, call, result, error), callbacks);
    } catch (RuntimeException error) {
      finishTool(request, call, null, error);
    }
  }

  private boolean validToolBinding(LiveRequest request, ReadToolCall call) {
    return request.operation() == LiveRequest.Operation.QUERY
        && request.module() == call.module()
        && request.activeToolCall() == null
        && request.nextToolSequence() == call.sequence()
        && !request.hasToolCallId(call.toolCallId())
        && request.matchesToolSession(call.sessionId())
        && operationalGate.revalidate(request.permit());
  }

  private void finishTool(
      LiveRequest request, ReadToolCall call, ReadToolResult result, Throwable error) {
    var safeResult =
        error == null && result != null
            ? result
            : ReadToolResult.failed(
                toolRegistry
                    .find(call.tool())
                    .map(ReadToolRegistry.Descriptor::source)
                    .orElse(ReadToolResult.Source.PAPER_POLICY),
                "TOOL_EXECUTION_FAILED",
                "The server could not execute this read tool.",
                true);
    synchronized (lock) {
      if (!requestOwnsActiveTool(request, call)) {
        return;
      }
    }
    CompletionStage<Void> sent;
    try {
      var encoded =
          request.binding().codec().encodeToolResult(request.requestId(), call, safeResult);
      synchronized (lock) {
        if (!requestOwnsActiveTool(request, call)) {
          return;
        }
        sent = request.binding().connection().sendApplication(encoded);
        request.completeToolCall(call);
      }
      sent.whenCompleteAsync(
          (ignored, sendError) -> {
            if (sendError != null) {
              sendFailed.accept(request);
            }
          },
          callbacks);
    } catch (RuntimeException sendError) {
      sendFailed.accept(request);
    }
  }

  private boolean requestOwnsActiveTool(LiveRequest request, ReadToolCall call) {
    return requestsById.get(request.requestId()) == request
        && request.activeToolCall() == call
        && connection.get() == request.binding()
        && request.binding().connection().isOpen()
        && operationalGate.revalidate(request.permit());
  }
}
