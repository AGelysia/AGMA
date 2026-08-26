package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One admitted player request: identity, admission permit, expected session, and the read-tool
 * round state that binds intermediate tool calls to exactly this request.
 *
 * <p>The timeout handle is thread-safe on its own; the tool-round fields are guarded by the service
 * lock.
 */
final class LiveRequest {
  enum Operation {
    QUERY,
    RESUME
  }

  private final UUID requestId;
  private final UUID playerId;
  private final OperationalGate.Permit permit;
  private final ConnectionBinding binding;
  private final Operation operation;
  private final UUID expectedSessionId;
  private final AgentModule module;
  private final AtomicReference<TimeoutScheduler.Cancellable> timeout = new AtomicReference<>();
  private final HashSet<UUID> toolCallIds = new HashSet<>();
  private ReadToolCall activeToolCall;
  private UUID toolSessionId;
  private int nextToolSequence;
  private CompletableFuture<ReadToolResult> toolExecution;

  LiveRequest(
      UUID requestId,
      UUID playerId,
      OperationalGate.Permit permit,
      ConnectionBinding binding,
      Operation operation,
      UUID expectedSessionId,
      AgentModule module) {
    this.requestId = requestId;
    this.playerId = playerId;
    this.permit = permit;
    this.binding = binding;
    this.operation = operation;
    this.expectedSessionId = expectedSessionId;
    this.module = module;
  }

  UUID requestId() {
    return requestId;
  }

  UUID playerId() {
    return playerId;
  }

  OperationalGate.Permit permit() {
    return permit;
  }

  ConnectionBinding binding() {
    return binding;
  }

  Operation operation() {
    return operation;
  }

  UUID expectedSessionId() {
    return expectedSessionId;
  }

  AgentModule module() {
    return module;
  }

  ReadToolCall activeToolCall() {
    return activeToolCall;
  }

  int nextToolSequence() {
    return nextToolSequence;
  }

  boolean hasToolCallId(UUID toolCallId) {
    return toolCallIds.contains(toolCallId);
  }

  boolean matchesToolSession(UUID sessionId) {
    if (expectedSessionId != null) {
      return expectedSessionId.equals(sessionId);
    }
    return toolSessionId == null || toolSessionId.equals(sessionId);
  }

  boolean matchesCompletionSession(UUID sessionId) {
    return sessionId == null || toolSessionId == null || toolSessionId.equals(sessionId);
  }

  void acceptToolCall(ReadToolCall call) {
    activeToolCall = call;
    toolSessionId = call.sessionId();
    toolCallIds.add(call.toolCallId());
  }

  void completeToolCall(ReadToolCall call) {
    if (activeToolCall == call) {
      activeToolCall = null;
      toolExecution = null;
      nextToolSequence++;
    }
  }

  void toolExecution(ReadToolCall call, CompletableFuture<ReadToolResult> execution) {
    if (activeToolCall != call || toolExecution != null) {
      execution.cancel(false);
      throw new IllegalStateException("Tool execution is no longer current");
    }
    toolExecution = execution;
  }

  void cancelToolExecution() {
    var execution = toolExecution;
    toolExecution = null;
    if (execution != null) {
      execution.cancel(false);
    }
  }

  void timeout(TimeoutScheduler.Cancellable scheduled) {
    if (!timeout.compareAndSet(null, scheduled)) {
      scheduled.cancel();
    }
  }

  void cancelTimeout() {
    var scheduled = timeout.getAndSet(null);
    if (scheduled != null) {
      scheduled.cancel();
    }
  }
}
