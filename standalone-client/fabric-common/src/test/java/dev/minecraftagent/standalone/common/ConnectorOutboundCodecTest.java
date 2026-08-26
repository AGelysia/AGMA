package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorOutboundCodecTest {
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final UUID SCOPE_ID = TestProfiles.INSTALLATION_ID;

  @Test
  void encodesCancellationsWithTheAllocatedIdentity() {
    var identity = new ConnectorMessageIdentity(uuid(300), "bbbbbbbbbbbbbbbbbbbbbb");
    var targetRequestId = uuid(20);

    var encoded =
        ConnectorOutboundCodec.cancellation(
            identity, SCOPE_ID, NOW, targetRequestId, CancelReason.CONTEXT_CHANGED);

    var envelope = object(encoded);
    assertEquals("client.cancel", envelope.get("type"));
    assertEquals(uuid(300).toString(), envelope.get("messageId"));
    assertEquals("bbbbbbbbbbbbbbbbbbbbbb", envelope.get("nonce"));
    assertNull(envelope.get("requestId"));
    var payload = JsonFields.object(envelope.get("payload"), "/payload");
    assertEquals(targetRequestId.toString(), payload.get("targetRequestId"));
    assertEquals("CONTEXT_CHANGED", payload.get("reason"));
  }

  @Test
  void encodesToolResultsAndErrors() {
    var call = toolCall();
    var identity = new ConnectorMessageIdentity(uuid(301), "cccccccccccccccccccccc");

    var result =
        ConnectorOutboundCodec.toolOutcome(
            identity,
            call,
            SCOPE_ID,
            NOW,
            new ClientToolResult(
                ConnectorEnvelopeCodec.map(
                    "generationId",
                    "generation-1",
                    "visibility",
                    "no_world",
                    "completeness",
                    "complete",
                    "candidates",
                    List.of(),
                    "ambiguous",
                    false,
                    "truncated",
                    false,
                    "warnings",
                    List.of())));
    var resultEnvelope = object(result);
    assertEquals("client.tool.result", resultEnvelope.get("type"));
    assertEquals(call.requestId().toString(), resultEnvelope.get("requestId"));
    var resultPayload = JsonFields.object(resultEnvelope.get("payload"), "/payload");
    assertEquals(uuid(230).toString(), resultPayload.get("toolCallId"));
    assertEquals(
        "generation-1",
        JsonFields.object(resultPayload.get("result"), "/result").get("generationId"));

    var error =
        ConnectorOutboundCodec.toolOutcome(
            identity,
            call,
            SCOPE_ID,
            NOW,
            new ClientToolError(
                ClientToolError.Status.FAILED, "CLIENT_TOOL_EXECUTION_FAILED", "Boom", true));
    var errorEnvelope = object(error);
    assertEquals("client.tool.error", errorEnvelope.get("type"));
    var errorPayload = JsonFields.object(errorEnvelope.get("payload"), "/payload");
    assertEquals("failed", errorPayload.get("status"));
    assertEquals("CLIENT_TOOL_EXECUTION_FAILED", errorPayload.get("code"));
    assertEquals(true, errorPayload.get("retryable"));
  }

  @Test
  void rejectsOversizedMessagesWithTheProtocolLimit() {
    var oversized = "x".repeat(ConnectorEnvelopeCodec.MAXIMUM_BYTES);

    var failure =
        assertThrows(
            ConnectorException.class,
            () ->
                ConnectorOutboundCodec.envelope(
                    new ConnectorMessageIdentity(uuid(302), "dddddddddddddddddddddd"),
                    SCOPE_ID,
                    "client.request",
                    NOW,
                    ConnectorEnvelopeCodec.map("message", oversized)));
    assertEquals("APPLICATION_MESSAGE_INVALID", failure.code());
    assertEquals("Connector request exceeds the protocol limit", failure.getMessage());
  }

  private static java.util.Map<String, Object> object(String source) {
    return JsonFields.object(StrictJson.parse(source), "/");
  }

  private static ClientToolCall toolCall() {
    return new ClientToolCall(
        uuid(20),
        uuid(230),
        SCOPE_ID,
        "game.resource.search",
        0,
        ConnectorEnvelopeCodec.map("query", "iron", "limit", 5));
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }
}
