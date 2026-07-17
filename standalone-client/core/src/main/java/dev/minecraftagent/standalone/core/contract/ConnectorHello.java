package dev.minecraftagent.standalone.core.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ConnectorHello(
    String schemaVersion,
    String connectorKind,
    String type,
    UUID messageId,
    UUID requestId,
    String timestamp,
    String nonce,
    String component,
    String componentVersion,
    UUID scopeId,
    List<String> supportedProtocolVersions,
    String selectedProtocolVersion,
    List<Capability> capabilities,
    Authentication authentication) {
  public static final String CONNECTOR_KIND = "standalone_client";
  public static final String TYPE = "connector.hello";
  public static final String PROTOCOL_VERSION = "client-1.0";

  public ConnectorHello {
    if (!"1.0".equals(schemaVersion)
        || !CONNECTOR_KIND.equals(connectorKind)
        || !TYPE.equals(type)) {
      throw new IllegalArgumentException("unsupported connector hello identity");
    }
    Objects.requireNonNull(messageId, "messageId");
    ContractChecks.text(timestamp, "timestamp", 64);
    try {
      java.time.Instant.parse(timestamp);
    } catch (java.time.format.DateTimeParseException error) {
      throw new IllegalArgumentException("timestamp must be an RFC 3339 instant", error);
    }
    nonce = ContractChecks.canonicalBase64Url(nonce, "nonce", 16, 64);
    if (!SetHolder.COMPONENTS.contains(component)) {
      throw new IllegalArgumentException("unsupported connector component");
    }
    componentVersion = ContractChecks.semanticVersion(componentVersion, "componentVersion");
    Objects.requireNonNull(scopeId, "scopeId");
    supportedProtocolVersions =
        ContractChecks.nonEmptyList(supportedProtocolVersions, "supportedProtocolVersions", 8)
            .stream()
            .map(value -> ContractChecks.text(value, "protocolVersion", 32))
            .toList();
    selectedProtocolVersion =
        ContractChecks.optionalText(selectedProtocolVersion, "selectedProtocolVersion", 32);
    if (!supportedProtocolVersions.equals(List.of(PROTOCOL_VERSION))) {
      throw new IllegalArgumentException("only the reviewed client protocol version is supported");
    }
    if (("standalone_client".equals(component)
            && (requestId != null || selectedProtocolVersion != null))
        || ("runtime".equals(component)
            && (requestId == null || !PROTOCOL_VERSION.equals(selectedProtocolVersion)))) {
      throw new IllegalArgumentException("protocol selection does not match the hello component");
    }
    capabilities = ContractChecks.list(capabilities, "capabilities", 64);
    if (capabilities.stream().map(Capability::id).distinct().count() != capabilities.size()) {
      throw new IllegalArgumentException("capability ids must be unique");
    }
    var sortedCapabilities =
        capabilities.stream().sorted(java.util.Comparator.comparing(Capability::id)).toList();
    if (!capabilities.equals(sortedCapabilities)) {
      throw new IllegalArgumentException("capabilities must be sorted by id");
    }
    Objects.requireNonNull(authentication, "authentication");
  }

  private static final class SetHolder {
    private static final java.util.Set<String> COMPONENTS =
        java.util.Set.of("standalone_client", "runtime");

    private SetHolder() {}
  }

  public record Capability(String id, int version) {
    public Capability {
      id = ContractChecks.symbolicId(id, "capability.id");
      if (version < 1 || version > 1_000_000) {
        throw new IllegalArgumentException("capability.version is out of range");
      }
    }
  }

  public record Authentication(String scheme, String keyId, String challenge, String proof) {
    public static final String SCHEME = "hmac-sha256";

    public Authentication {
      if (!SCHEME.equals(scheme)) {
        throw new IllegalArgumentException("unsupported connector authentication scheme");
      }
      keyId = ContractChecks.symbolicId(keyId, "authentication.keyId");
      challenge = ContractChecks.canonicalBase64Url(challenge, "authentication.challenge", 16, 96);
      proof = ContractChecks.canonicalBase64Url(proof, "authentication.proof", 32, 32);
    }
  }
}
