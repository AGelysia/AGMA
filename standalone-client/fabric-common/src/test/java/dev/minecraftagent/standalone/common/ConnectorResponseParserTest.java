package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ConnectorResponseParserTest {
  private static final UUID MESSAGE_ID = uuid(120);
  private static final UUID REQUEST_ID = uuid(20);
  private static final UUID SCOPE_ID = TestProfiles.INSTALLATION_ID;

  @Test
  void parsesCompletionsWithSources() {
    var parsed = parse("client.complete", completionPayload(null, 19, List.of(source())));

    var complete = assertInstanceOf(ConnectorResponseParser.Complete.class, parsed);
    assertNull(complete.sessionId());
    assertEquals("Use planks and redstone.", complete.text());
    assertEquals(19, complete.costMicroUsd());
    assertEquals(TextCompletion.CostKind.REPORTED, complete.costKind());
    assertEquals(1, complete.sources().size());
    assertEquals("claim.0123456789abcdef01234567", complete.sources().get(0).claimId());
    assertEquals("https://example.com/guide", complete.sources().get(0).url().toString());
    assertEquals(TextCompletion.Match.MATCH, complete.sources().get(0).applicability().match());
  }

  @Test
  void rejectsCompletionsWithOutOfRangeCosts() {
    assertInvalidField(
        () -> parse("client.complete", completionPayload(null, -1, List.of())),
        "/payload/costMicroUsd");
    assertInvalidField(
        () ->
            parse(
                "client.complete",
                completionPayload(null, TextCompletion.MAXIMUM_COST_MICRO_USD + 1, List.of())),
        "/payload/costMicroUsd");
  }

  @Test
  void parsesErrorsWithKnownCodesOnly() {
    var parsed =
        parse(
            "client.error",
            ConnectorEnvelopeCodec.map(
                "code", "MODEL_TIMEOUT", "message", "Timed out", "retryable", true));

    var error = assertInstanceOf(ConnectorResponseParser.Error.class, parsed);
    assertEquals("MODEL_TIMEOUT", error.code());
    assertEquals("Timed out", error.message());
    assertTrue(error.retryable());

    assertInvalidField(
        () ->
            parse(
                "client.error",
                ConnectorEnvelopeCodec.map(
                    "code", "UNKNOWN_CODE", "message", "Nope", "retryable", false)),
        "/payload/code");
  }

  @Test
  void parsesStatusesWithinProtocolBounds() {
    var parsed =
        parse(
            "client.status",
            ConnectorEnvelopeCodec.map(
                "state", "READY", "activeRequests", number(0), "queuedRequests", number(0)));

    var status = assertInstanceOf(ConnectorResponseParser.Status.class, parsed);
    assertEquals(RuntimeStatus.State.READY, status.state());

    assertInvalidField(
        () ->
            parse(
                "client.status",
                ConnectorEnvelopeCodec.map(
                    "state", "READY", "activeRequests", number(9), "queuedRequests", number(0))),
        "/payload");
    assertInvalidField(
        () ->
            parse(
                "client.status",
                ConnectorEnvelopeCodec.map(
                    "state", "READY", "activeRequests", number(0), "queuedRequests", number(129))),
        "/payload");
  }

  @Test
  void parsesToolCallsBoundToTheEnvelopeRequest() {
    var parsed = parse("client.tool.call", toolCallPayload(REQUEST_ID.toString()));

    var toolCall = assertInstanceOf(ConnectorResponseParser.ToolCall.class, parsed);
    assertEquals(REQUEST_ID, toolCall.call().requestId());
    assertEquals(uuid(230), toolCall.call().toolCallId());
    assertEquals("game.resource.search", toolCall.call().tool());

    assertInvalidField(
        () -> parse("client.tool.call", toolCallPayload(uuid(999).toString())),
        "/payload/requestId");
  }

  @Test
  void parsesToolCancellations() {
    var parsed = parse("client.tool.cancel", toolCancelPayload(REQUEST_ID.toString()));

    var cancellation = assertInstanceOf(ConnectorResponseParser.ToolCancellation.class, parsed);
    assertEquals(REQUEST_ID, cancellation.cancellation().requestId());
    assertEquals(ClientToolCancellation.Reason.TOOL_TIMEOUT, cancellation.cancellation().reason());
  }

  @Test
  void rejectsUnknownResponseTypes() {
    assertInvalidField(() -> parse("client.unknown", Map.of()), "/type");
  }

  private static ConnectorResponseParser.Response parse(String type, Map<String, Object> payload) {
    return ConnectorResponseParser.parse(envelope(type, payload));
  }

  private static ConnectorEnvelope envelope(String type, Map<String, Object> payload) {
    return new ConnectorEnvelope(
        MESSAGE_ID,
        REQUEST_ID,
        SCOPE_ID,
        type,
        Instant.parse("2026-07-17T00:00:00Z"),
        "aaaaaaaaaaaaaaaaaaaaaa",
        payload);
  }

  private static Map<String, Object> completionPayload(
      UUID sessionId, long costMicroUsd, List<Map<String, Object>> sources) {
    return ConnectorEnvelopeCodec.map(
        "sessionId",
        sessionId == null ? null : sessionId.toString(),
        "text",
        "Use planks and redstone.",
        "costMicroUsd",
        number(costMicroUsd),
        "costKind",
        "reported",
        "sources",
        sources);
  }

  private static Map<String, Object> source() {
    return ConnectorEnvelopeCodec.map(
        "claimId",
        "claim.0123456789abcdef01234567",
        "title",
        "Example guide",
        "url",
        "https://example.com/guide",
        "publisher",
        "Example",
        "retrievedAt",
        "2026-07-17T00:00:00Z",
        "applicability",
        ConnectorEnvelopeCodec.map(
            "minecraftVersion",
            "1.21.11",
            "modVersions",
            Map.of("minecraft", "1.21.11"),
            "modpackVersion",
            null,
            "match",
            "match"),
        "warnings",
        List.of());
  }

  private static Map<String, Object> toolCallPayload(String requestId) {
    return ConnectorEnvelopeCodec.map(
        "requestId",
        requestId,
        "toolCallId",
        uuid(230).toString(),
        "subjectId",
        SCOPE_ID.toString(),
        "tool",
        "game.resource.search",
        "sequence",
        number(0),
        "arguments",
        ConnectorEnvelopeCodec.map("query", "iron", "limit", 5));
  }

  private static Map<String, Object> toolCancelPayload(String requestId) {
    return ConnectorEnvelopeCodec.map(
        "requestId",
        requestId,
        "toolCallId",
        uuid(230).toString(),
        "subjectId",
        SCOPE_ID.toString(),
        "tool",
        "game.resource.search",
        "sequence",
        number(0),
        "reason",
        "TOOL_TIMEOUT");
  }

  private static void assertInvalidField(Executable action, String field) {
    var failure = assertThrows(IllegalArgumentException.class, action);
    assertEquals("Invalid JSON field: " + field, failure.getMessage());
  }

  /** Numeric wire values are JSON numbers, which the strict parser surfaces as BigDecimal. */
  private static BigDecimal number(long value) {
    return BigDecimal.valueOf(value);
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }
}
