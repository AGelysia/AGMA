package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConnectorHelloCodec {
  private ConnectorHelloCodec() {}

  static String encode(ConnectorHello hello) {
    var capabilities = new ArrayList<Map<String, Object>>();
    for (var capability : hello.capabilities()) {
      capabilities.add(map("id", capability.id(), "version", capability.version()));
    }
    return StrictJson.write(
        map(
            "schemaVersion",
            hello.schemaVersion(),
            "connectorKind",
            hello.connectorKind(),
            "type",
            hello.type(),
            "messageId",
            hello.messageId().toString(),
            "requestId",
            hello.requestId() == null ? null : hello.requestId().toString(),
            "timestamp",
            hello.timestamp(),
            "nonce",
            hello.nonce(),
            "component",
            hello.component(),
            "componentVersion",
            hello.componentVersion(),
            "scopeId",
            hello.scopeId().toString(),
            "supportedProtocolVersions",
            hello.supportedProtocolVersions(),
            "selectedProtocolVersion",
            hello.selectedProtocolVersion(),
            "capabilities",
            capabilities,
            "authentication",
            map(
                "scheme",
                hello.authentication().scheme(),
                "keyId",
                hello.authentication().keyId(),
                "challenge",
                hello.authentication().challenge(),
                "proof",
                hello.authentication().proof())));
  }

  static ConnectorHello decode(String source) {
    try {
      var root =
          JsonFields.exactObject(
              StrictJson.parse(source),
              "/",
              "schemaVersion",
              "connectorKind",
              "type",
              "messageId",
              "requestId",
              "timestamp",
              "nonce",
              "component",
              "componentVersion",
              "scopeId",
              "supportedProtocolVersions",
              "selectedProtocolVersion",
              "capabilities",
              "authentication");
      var authentication =
          JsonFields.exactObject(
              root.get("authentication"),
              "/authentication",
              "scheme",
              "keyId",
              "challenge",
              "proof");
      var capabilities = new ArrayList<ConnectorHello.Capability>();
      for (var value : JsonFields.array(root.get("capabilities"), "/capabilities", 64)) {
        var capability = JsonFields.exactObject(value, "/capabilities", "id", "version");
        capabilities.add(
            new ConnectorHello.Capability(
                JsonFields.string(capability.get("id"), "/capabilities/id", 128),
                JsonFields.integer(capability.get("version"), "/capabilities/version")));
      }
      return new ConnectorHello(
          JsonFields.string(root.get("schemaVersion"), "/schemaVersion", 16),
          JsonFields.string(root.get("connectorKind"), "/connectorKind", 64),
          JsonFields.string(root.get("type"), "/type", 64),
          JsonFields.uuid(root.get("messageId"), "/messageId"),
          JsonFields.nullableUuid(root.get("requestId"), "/requestId"),
          JsonFields.string(root.get("timestamp"), "/timestamp", 64),
          JsonFields.string(root.get("nonce"), "/nonce", 86),
          JsonFields.string(root.get("component"), "/component", 64),
          JsonFields.string(root.get("componentVersion"), "/componentVersion", 64),
          JsonFields.uuid(root.get("scopeId"), "/scopeId"),
          JsonFields.stringArray(
              root.get("supportedProtocolVersions"), "/supportedProtocolVersions", 8),
          JsonFields.nullableString(
              root.get("selectedProtocolVersion"), "/selectedProtocolVersion", 32),
          capabilities,
          new ConnectorHello.Authentication(
              JsonFields.string(authentication.get("scheme"), "/authentication/scheme", 32),
              JsonFields.string(authentication.get("keyId"), "/authentication/keyId", 128),
              JsonFields.string(authentication.get("challenge"), "/authentication/challenge", 128),
              JsonFields.string(authentication.get("proof"), "/authentication/proof", 43)));
    } catch (RuntimeException exception) {
      throw new ConnectorException("HANDSHAKE_INVALID", "Runtime handshake is invalid");
    }
  }

  private static Map<String, Object> map(Object... entries) {
    var result = new LinkedHashMap<String, Object>();
    for (var index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }
}
