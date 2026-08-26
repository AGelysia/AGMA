package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ConnectorPendingTableTest {
  private static final long NOW = Instant.parse("2026-07-17T00:00:00Z").toEpochMilli();

  private final ConnectorPendingTable table =
      new ConnectorPendingTable(Duration.ofSeconds(60), 4096);

  @Test
  void tracksTextCapacityAndRequestIdentifierReuse() {
    var entry = textEntry(uuid(1), null);

    assertFalse(table.textCapacityExceeded(1));
    table.addText(entry);
    assertTrue(table.textCapacityExceeded(1));
    assertFalse(table.textCapacityExceeded(2));

    assertTrue(table.knownRequest(entry.requestId, NOW));
    assertFalse(table.knownRequest(uuid(2), NOW));
  }

  @Test
  void forgetTextReleasesCapacityAndRemembersTheIdentifier() {
    var entry = textEntry(uuid(1), null);
    table.addText(entry);

    table.forgetText(entry.requestId, NOW);

    assertFalse(table.textCapacityExceeded(1));
    assertFalse(table.find(entry.requestId) == entry);
    assertTrue(table.knownRequest(entry.requestId, NOW));
  }

  @Test
  void settleRejectsUnknownIdentifiersButIgnoresLateTerminals() {
    var failure = assertThrows(ConnectorException.class, () -> table.settle(uuid(404), NOW));
    assertEquals("APPLICATION_MESSAGE_INVALID", failure.code());
    assertEquals("Runtime response correlation is invalid", failure.getMessage());

    var entry = textEntry(uuid(1), null);
    table.addText(entry);
    table.remove(entry, NOW);

    assertNull(table.settle(entry.requestId, NOW));
  }

  @Test
  void settleRejectsRequestsThatStillHaveAnActiveTool() {
    var requestId = uuid(1);
    var entry = textEntry(requestId, null);
    table.addText(entry);
    table.addTool(toolEntry(requestId, uuid(10)));

    var failure = assertThrows(ConnectorException.class, () -> table.settle(requestId, NOW));
    assertEquals("APPLICATION_MESSAGE_INVALID", failure.code());
    assertEquals("Runtime completed a request with an active tool", failure.getMessage());
  }

  @Test
  void validatesResponseKindAgainstEntryKind() {
    var textWithoutSession = textEntry(uuid(1), null);
    var textWithSession = textEntry(uuid(2), uuid(200));
    var status = statusEntry(uuid(3));
    var complete = complete(null);
    var statusResponse = new ConnectorResponseParser.Status(RuntimeStatus.State.READY, 0, 0);

    assertInvalidField(
        () -> table.validateCorrelation(textWithoutSession, statusResponse), "/type");
    assertInvalidField(
        () -> table.validateCorrelation(textWithSession, complete(uuid(999))),
        "/payload/sessionId");
    assertInvalidField(() -> table.validateCorrelation(status, complete(null)), "/type");
    table.validateCorrelation(textWithoutSession, complete(null));
    table.validateCorrelation(textWithSession, complete(uuid(200)));
    table.validateCorrelation(
        status, new ConnectorResponseParser.Status(RuntimeStatus.State.STOPPING, 8, 128));
  }

  @Test
  void completesTextEntriesFromParsedResponses() {
    var text = textEntry(uuid(1), null);
    table.complete(text, complete(uuid(100)));
    var completion = text.future.join();
    assertEquals(TextCompletion.Status.COMPLETED, completion.status());
    assertEquals(uuid(100), completion.sessionId());
    assertEquals("done", completion.text());
    assertEquals(19, completion.costMicroUsd());
    assertEquals(TextCompletion.CostKind.REPORTED, completion.costKind());

    var cancelled = textEntry(uuid(2), null);
    table.complete(
        cancelled, new ConnectorResponseParser.Error("REQUEST_CANCELLED", "cancelled", false));
    assertEquals(TextCompletion.Status.CANCELLED, cancelled.future.join().status());

    var timedOut = textEntry(uuid(3), null);
    table.complete(timedOut, new ConnectorResponseParser.Error("MODEL_TIMEOUT", "timed out", true));
    var timeout = timedOut.future.join();
    assertEquals(TextCompletion.Status.TIMED_OUT, timeout.status());
    assertEquals("MODEL_TIMEOUT", timeout.errorCode());
    assertTrue(timeout.retryable());

    var failed = textEntry(uuid(4), null);
    table.complete(failed, new ConnectorResponseParser.Error("BUDGET_EXCEEDED", "spent", false));
    assertEquals(TextCompletion.Status.FAILED, failed.future.join().status());
  }

  @Test
  void completesStatusEntriesFromParsedStatuses() {
    var status = statusEntry(uuid(1));

    table.complete(status, new ConnectorResponseParser.Status(RuntimeStatus.State.STOPPING, 3, 12));

    var result = status.future.join();
    assertEquals(uuid(1), result.requestId());
    assertEquals(RuntimeStatus.State.STOPPING, result.state());
    assertEquals(3, result.activeRequests());
    assertEquals(12, result.queuedRequests());
  }

  @Test
  void takeIfCurrentOnlyRemovesTheRegisteredEntry() {
    var requestId = uuid(1);
    var registered = textEntry(requestId, null);
    var stale = textEntry(requestId, null);
    table.addText(registered);
    assertTrue(table.textCapacityExceeded(1));

    assertFalse(table.takeIfCurrent(stale));
    assertSame(registered, table.find(requestId));
    assertTrue(table.takeIfCurrent(registered));
    assertFalse(table.takeIfCurrent(registered));
    assertFalse(table.textCapacityExceeded(1));
    assertFalse(stale.future.isDone());
  }

  @Test
  void enforcesToolRegistrationRules() {
    var requestId = uuid(1);
    var toolCallId = uuid(10);
    var otherToolCallId = uuid(11);
    var call = toolCall(requestId, toolCallId);

    assertFalse(table.canRegisterTool(call, NOW));

    table.addText(textEntry(requestId, null));
    assertTrue(table.canRegisterTool(call, NOW));

    var tool = new ConnectorPendingTable.PendingTool(call, handler());
    table.addTool(tool);
    assertFalse(table.canRegisterTool(call, NOW));
    assertFalse(
        table.canRegisterTool(toolCall(requestId, otherToolCallId), NOW),
        "only one tool may run per request");

    table.removeTool(tool, NOW);
    assertTrue(table.toolSettled(toolCallId, NOW));
    assertFalse(table.canRegisterTool(call, NOW), "a settled tool call cannot be reopened");
    assertFalse(
        table.canRegisterTool(toolCall(uuid(2), otherToolCallId), NOW),
        "a request without a pending text entry cannot open tools");
  }

  @Test
  void drainsEverythingForShutdownAndFailsPendingEntries() {
    var requestId = uuid(1);
    var text = textEntry(requestId, null);
    var status = statusEntry(uuid(2));
    var tool = new ConnectorPendingTable.PendingTool(toolCall(requestId, uuid(10)), handler());
    table.addText(text);
    table.addStatus(status);
    table.addTool(tool);

    var abandoned = table.drainAll(NOW);
    var abandonedTools = table.drainTools(null, NOW);

    assertEquals(List.of(text, status), abandoned);
    assertEquals(List.of(tool), abandonedTools);
    assertFalse(table.textCapacityExceeded(1));
    assertFalse(table.statusCapacityExceeded());
    assertNull(table.settle(requestId, NOW));
    assertTrue(table.toolSettled(uuid(10), NOW));

    var error = new ConnectorException("CONNECTOR_CLOSED", "Connector session is closed");
    ConnectorPendingTable.failPending(abandoned, error);
    assertTrue(text.future.isCompletedExceptionally());
    assertTrue(status.future.isCompletedExceptionally());
  }

  @Test
  void drainsOnlyTheToolsOfOneRequest() {
    var requestId = uuid(1);
    var kept = new ConnectorPendingTable.PendingTool(toolCall(uuid(2), uuid(20)), handler());
    var drained = new ConnectorPendingTable.PendingTool(toolCall(requestId, uuid(21)), handler());
    table.addTool(kept);
    table.addTool(drained);

    assertEquals(List.of(drained), table.drainTools(requestId, NOW));

    assertSame(kept, table.findTool(uuid(20)));
    assertFalse(table.toolSettled(uuid(20), NOW));
    assertTrue(table.toolSettled(uuid(21), NOW));
  }

  @Test
  void limitsStatusProbesToEight() {
    for (var index = 0; index < 8; index++) {
      assertFalse(table.statusCapacityExceeded());
      table.addStatus(statusEntry(uuid(index)));
    }
    assertTrue(table.statusCapacityExceeded());
  }

  private static void assertInvalidField(Executable action, String field) {
    var failure = assertThrows(IllegalArgumentException.class, action);
    assertEquals("Invalid JSON field: " + field, failure.getMessage());
  }

  private static ConnectorPendingTable.PendingText textEntry(UUID requestId, UUID sessionId) {
    return new ConnectorPendingTable.PendingText(
        new TextRequest(requestId, sessionId, "Search iron", Duration.ofSeconds(5)),
        new CancellableFuture<>());
  }

  private static ConnectorPendingTable.PendingStatus statusEntry(UUID requestId) {
    return new ConnectorPendingTable.PendingStatus(requestId, new CancellableFuture<>());
  }

  private static ClientToolCall toolCall(UUID requestId, UUID toolCallId) {
    return new ClientToolCall(
        requestId,
        toolCallId,
        TestProfiles.INSTALLATION_ID,
        "game.resource.search",
        0,
        ConnectorEnvelopeCodec.map("query", "iron", "limit", 5));
  }

  private static ConnectorPendingTable.PendingTool toolEntry(UUID requestId, UUID toolCallId) {
    return new ConnectorPendingTable.PendingTool(toolCall(requestId, toolCallId), handler());
  }

  private static ClientToolHandler handler() {
    return call -> new CompletableFuture<ClientToolOutcome>();
  }

  private static ConnectorResponseParser.Complete complete(UUID sessionId) {
    return new ConnectorResponseParser.Complete(
        sessionId, "done", 19, TextCompletion.CostKind.REPORTED, List.of());
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }
}
