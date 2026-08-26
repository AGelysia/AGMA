package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class LiveRequestTest {
  private static final String SERVER_ID = "survival-main";

  @Test
  void toolSessionsLatchToTheFirstRoundAndStayBoundAfterwards() {
    var request = request(null);
    var firstSession = UUID.randomUUID();
    var secondSession = UUID.randomUUID();

    assertTrue(request.matchesToolSession(firstSession));
    var first = call(request, firstSession, 0);
    request.acceptToolCall(first);
    assertTrue(request.matchesToolSession(firstSession));
    assertFalse(request.matchesToolSession(secondSession));

    request.completeToolCall(first);

    assertTrue(request.matchesToolSession(firstSession));
    assertFalse(request.matchesToolSession(secondSession));
  }

  @Test
  void anExpectedSessionPinsToolMatchingBeforeAnyRound() {
    var expected = UUID.randomUUID();
    var request = request(expected);

    assertTrue(request.matchesToolSession(expected));
    assertFalse(request.matchesToolSession(UUID.randomUUID()));
  }

  @Test
  void completionsAcceptNullSessionsButNoOtherLatchedSession() {
    var request = request(null);
    var session = UUID.randomUUID();

    assertTrue(request.matchesCompletionSession(UUID.randomUUID()));
    request.acceptToolCall(call(request, session, 0));

    assertTrue(request.matchesCompletionSession(null));
    assertTrue(request.matchesCompletionSession(session));
    assertFalse(request.matchesCompletionSession(UUID.randomUUID()));
  }

  @Test
  void completedRoundsAdvanceSequencingAndRememberReplayedToolCallIds() {
    var request = request(null);
    var round = call(request, UUID.randomUUID(), 0);

    request.acceptToolCall(round);
    request.completeToolCall(round);

    assertNull(request.activeToolCall());
    assertEquals(1, request.nextToolSequence());
    assertTrue(request.hasToolCallId(round.toolCallId()));
  }

  @Test
  void onlyTheActiveToolCallOwnsTheExecutionSlot() {
    var request = request(null);
    var active = call(request, UUID.randomUUID(), 0);
    request.acceptToolCall(active);

    var pending = new CompletableFuture<ReadToolResult>();
    request.toolExecution(active, pending);

    var stale = new CompletableFuture<ReadToolResult>();
    assertThrows(IllegalStateException.class, () -> request.toolExecution(active, stale));
    assertTrue(stale.isCancelled());

    request.cancelToolExecution();

    assertTrue(pending.isCancelled());
    assertEquals(active, request.activeToolCall());
  }

  private static LiveRequest request(UUID expectedSessionId) {
    var gate = new OperationalGate(AgentState.ONLINE);
    return new LiveRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        gate.tryAcquire().orElseThrow(),
        new ConnectionBinding(new UnusedConnection(), new AgentProtocolCodec(SERVER_ID)),
        LiveRequest.Operation.QUERY,
        expectedSessionId,
        AgentModule.GENERAL);
  }

  private static ReadToolCall call(LiveRequest request, UUID sessionId, int sequence) {
    return new ReadToolCall(
        UUID.randomUUID(),
        request.requestId(),
        sessionId,
        request.playerId(),
        request.module(),
        "player.context.read",
        new JsonObject(),
        sequence);
  }

  private static final class UnusedConnection implements AuthenticatedRuntimeConnection {
    @Override
    public boolean isOpen() {
      return false;
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return new CompletableFuture<>();
    }

    @Override
    public void close() {}
  }
}
