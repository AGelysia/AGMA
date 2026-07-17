package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ConnectorEnvelopeCodec {
  static final int MAXIMUM_BYTES = 64 * 1024;
  static final Set<String> RESPONSE_TYPES =
      Set.of(
          "client.complete",
          "client.error",
          "client.status",
          "client.tool.call",
          "client.tool.cancel");

  private ConnectorEnvelopeCodec() {}

  static String outbound(
      UUID messageId,
      UUID scopeId,
      String type,
      Instant timestamp,
      String nonce,
      Map<String, Object> payload) {
    if (!Set.of("client.request", "client.cancel", "client.status.request").contains(type)) {
      throw new IllegalArgumentException("Outbound connector type is invalid");
    }
    return StrictJson.write(
        map(
            "schemaVersion",
            "1.0",
            "connectorKind",
            ConnectorHello.CONNECTOR_KIND,
            "protocolVersion",
            ConnectorHello.PROTOCOL_VERSION,
            "messageId",
            messageId.toString(),
            "requestId",
            null,
            "scopeId",
            scopeId.toString(),
            "type",
            type,
            "timestamp",
            timestamp.toString(),
            "nonce",
            nonce,
            "payload",
            payload));
  }

  static String outboundTool(
      UUID messageId,
      UUID requestId,
      UUID scopeId,
      String type,
      Instant timestamp,
      String nonce,
      Map<String, Object> payload) {
    if (!Set.of("client.tool.result", "client.tool.error").contains(type)
        || requestId == null
        || messageId.equals(requestId)) {
      throw new IllegalArgumentException("Outbound client tool type is invalid");
    }
    return StrictJson.write(
        map(
            "schemaVersion",
            "1.0",
            "connectorKind",
            ConnectorHello.CONNECTOR_KIND,
            "protocolVersion",
            ConnectorHello.PROTOCOL_VERSION,
            "messageId",
            messageId.toString(),
            "requestId",
            requestId.toString(),
            "scopeId",
            scopeId.toString(),
            "type",
            type,
            "timestamp",
            timestamp.toString(),
            "nonce",
            nonce,
            "payload",
            payload));
  }

  static ConnectorEnvelope inbound(String source) {
    try {
      var root =
          JsonFields.exactObject(
              StrictJson.parse(source),
              "/",
              "schemaVersion",
              "connectorKind",
              "protocolVersion",
              "messageId",
              "requestId",
              "scopeId",
              "type",
              "timestamp",
              "nonce",
              "payload");
      if (!"1.0".equals(JsonFields.string(root.get("schemaVersion"), "/schemaVersion", 16))
          || !ConnectorHello.CONNECTOR_KIND.equals(
              JsonFields.string(root.get("connectorKind"), "/connectorKind", 64))
          || !ConnectorHello.PROTOCOL_VERSION.equals(
              JsonFields.string(root.get("protocolVersion"), "/protocolVersion", 32))) {
        throw JsonFields.invalid("/");
      }
      var type = JsonFields.string(root.get("type"), "/type", 64);
      if (!RESPONSE_TYPES.contains(type)) {
        throw JsonFields.invalid("/type");
      }
      var requestId = JsonFields.nullableUuid(root.get("requestId"), "/requestId");
      if (requestId == null) {
        throw JsonFields.invalid("/requestId");
      }
      var nonce = JsonFields.string(root.get("nonce"), "/nonce", 86);
      if (!ConnectorAuthentication.canonicalBase64Url(nonce, 16, 64)) {
        throw JsonFields.invalid("/nonce");
      }
      var payload = JsonFields.object(root.get("payload"), "/payload");
      if (payload.size() > 16) {
        throw JsonFields.invalid("/payload");
      }
      return new ConnectorEnvelope(
          JsonFields.uuid(root.get("messageId"), "/messageId"),
          requestId,
          JsonFields.uuid(root.get("scopeId"), "/scopeId"),
          type,
          JsonFields.instant(root.get("timestamp"), "/timestamp"),
          nonce,
          Collections.unmodifiableMap(new LinkedHashMap<>(payload)));
    } catch (RuntimeException exception) {
      throw new ConnectorException(
          "APPLICATION_MESSAGE_INVALID", "Runtime response is invalid", exception);
    }
  }

  static Map<String, Object> map(Object... entries) {
    var result = new LinkedHashMap<String, Object>();
    for (var index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }
}
