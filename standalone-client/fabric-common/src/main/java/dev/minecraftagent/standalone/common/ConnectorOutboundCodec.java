package dev.minecraftagent.standalone.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes client-to-Runtime envelopes (requests, cancellations, status probes, tool outcomes) and
 * enforces the outbound protocol size limit.
 *
 * <p>Encoding is pure given an already allocated {@link ConnectorMessageIdentity}; it never touches
 * session state. Oversized messages raise {@link ConnectorException} with {@code
 * APPLICATION_MESSAGE_INVALID}, which callers translate into a protocol failure.
 */
final class ConnectorOutboundCodec {
  private ConnectorOutboundCodec() {}

  static String envelope(
      ConnectorMessageIdentity identity,
      UUID scopeId,
      String type,
      Instant timestamp,
      Map<String, Object> payload) {
    var result =
        ConnectorEnvelopeCodec.outbound(
            identity.messageId(), scopeId, type, timestamp, identity.nonce(), payload);
    if (result.getBytes(StandardCharsets.UTF_8).length > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
      throw new ConnectorException(
          "APPLICATION_MESSAGE_INVALID", "Connector request exceeds the protocol limit");
    }
    return result;
  }

  static String cancellation(
      ConnectorMessageIdentity identity,
      UUID scopeId,
      Instant timestamp,
      UUID targetRequestId,
      CancelReason reason) {
    return envelope(
        identity,
        scopeId,
        "client.cancel",
        timestamp,
        ConnectorEnvelopeCodec.map(
            "targetRequestId", targetRequestId.toString(), "reason", reason.name()));
  }

  static String toolOutcome(
      ConnectorMessageIdentity identity,
      ClientToolCall call,
      UUID scopeId,
      Instant timestamp,
      ClientToolOutcome outcome) {
    final String type;
    final Map<String, Object> payload;
    if (outcome instanceof ClientToolResult result) {
      type = "client.tool.result";
      payload =
          ConnectorEnvelopeCodec.map(
              "requestId",
              call.requestId().toString(),
              "toolCallId",
              call.toolCallId().toString(),
              "subjectId",
              call.subjectId().toString(),
              "tool",
              call.tool(),
              "sequence",
              call.sequence(),
              "result",
              result.result());
    } else if (outcome instanceof ClientToolError error) {
      type = "client.tool.error";
      payload =
          ConnectorEnvelopeCodec.map(
              "requestId",
              call.requestId().toString(),
              "toolCallId",
              call.toolCallId().toString(),
              "subjectId",
              call.subjectId().toString(),
              "tool",
              call.tool(),
              "sequence",
              call.sequence(),
              "status",
              error.status().wireName(),
              "code",
              error.code(),
              "message",
              error.message(),
              "retryable",
              error.retryable());
    } else {
      throw new IllegalArgumentException("Client tool outcome is invalid");
    }
    var encoded =
        ConnectorEnvelopeCodec.outboundTool(
            identity.messageId(),
            call.requestId(),
            scopeId,
            type,
            timestamp,
            identity.nonce(),
            payload);
    if (encoded.getBytes(StandardCharsets.UTF_8).length > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
      throw new ConnectorException(
          "APPLICATION_MESSAGE_INVALID", "Client tool response exceeds the protocol limit");
    }
    return encoded;
  }
}
