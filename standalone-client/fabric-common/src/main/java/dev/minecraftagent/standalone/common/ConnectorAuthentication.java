package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Exact `agma-connector-handshake-v1` transcript and HMAC implementation. */
public final class ConnectorAuthentication {
  public static final String DOMAIN = "agma-connector-handshake-v1";
  public static final String KEY_ID = "session-key";
  public static final List<ConnectorHello.Capability> C1_CAPABILITIES =
      List.of(
          new ConnectorHello.Capability("client.cancel", 1),
          new ConnectorHello.Capability("client.status", 1),
          new ConnectorHello.Capability("client.text", 1));

  private ConnectorAuthentication() {}

  public static String transcript(ConnectorHello hello) {
    return String.join(
        "\n",
        DOMAIN,
        hello.schemaVersion(),
        hello.connectorKind(),
        hello.type(),
        hello.messageId().toString(),
        hello.requestId() == null ? "-" : hello.requestId().toString(),
        hello.timestamp(),
        hello.nonce(),
        hello.component(),
        hello.componentVersion(),
        hello.scopeId().toString(),
        String.join(",", hello.supportedProtocolVersions()),
        hello.selectedProtocolVersion() == null ? "-" : hello.selectedProtocolVersion(),
        hello.capabilities().stream()
            .map(capability -> capability.id() + "=" + capability.version())
            .reduce((left, right) -> left + "," + right)
            .orElse(""),
        hello.authentication().scheme(),
        hello.authentication().keyId(),
        hello.authentication().challenge());
  }

  static ConnectorHello createRequest(
      UUID scopeId,
      String componentVersion,
      Clock clock,
      UUID messageId,
      String nonce,
      String challenge,
      byte[] token) {
    return createRequest(
        scopeId, componentVersion, clock, messageId, nonce, challenge, C1_CAPABILITIES, token);
  }

  static ConnectorHello createRequest(
      UUID scopeId,
      String componentVersion,
      Clock clock,
      UUID messageId,
      String nonce,
      String challenge,
      List<ConnectorHello.Capability> capabilities,
      byte[] token) {
    var unsigned =
        new ConnectorHello(
            "1.0",
            ConnectorHello.CONNECTOR_KIND,
            ConnectorHello.TYPE,
            messageId,
            null,
            clock.instant().toString(),
            nonce,
            "standalone_client",
            componentVersion,
            scopeId,
            List.of(ConnectorHello.PROTOCOL_VERSION),
            null,
            capabilities,
            new ConnectorHello.Authentication(
                ConnectorHello.Authentication.SCHEME, KEY_ID, challenge, "A".repeat(43)));
    return withProof(unsigned, token);
  }

  static ConnectorHello withProof(ConnectorHello unsigned, byte[] token) {
    return new ConnectorHello(
        unsigned.schemaVersion(),
        unsigned.connectorKind(),
        unsigned.type(),
        unsigned.messageId(),
        unsigned.requestId(),
        unsigned.timestamp(),
        unsigned.nonce(),
        unsigned.component(),
        unsigned.componentVersion(),
        unsigned.scopeId(),
        unsigned.supportedProtocolVersions(),
        unsigned.selectedProtocolVersion(),
        unsigned.capabilities(),
        new ConnectorHello.Authentication(
            unsigned.authentication().scheme(),
            unsigned.authentication().keyId(),
            unsigned.authentication().challenge(),
            proof(unsigned, token)));
  }

  static boolean verify(ConnectorHello hello, byte[] token) {
    final byte[] supplied;
    try {
      supplied = Base64.getUrlDecoder().decode(hello.authentication().proof());
    } catch (IllegalArgumentException exception) {
      return false;
    }
    var expected = hmac(hello, token);
    try {
      return supplied.length == 32 && MessageDigest.isEqual(expected, supplied);
    } finally {
      Arrays.fill(expected, (byte) 0);
      Arrays.fill(supplied, (byte) 0);
    }
  }

  static String proof(ConnectorHello hello, byte[] token) {
    var digest = hmac(hello, token);
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } finally {
      Arrays.fill(digest, (byte) 0);
    }
  }

  static String encodeNonce(byte[] bytes) {
    if (bytes == null || bytes.length < 16 || bytes.length > 64) {
      throw new IllegalArgumentException("Connector nonce entropy is invalid");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static boolean canonicalBase64Url(String value, int minimum, int maximum) {
    if (value == null || !value.matches("[A-Za-z0-9_-]+") || value.length() % 4 == 1) {
      return false;
    }
    try {
      var decoded = Base64.getUrlDecoder().decode(value);
      return decoded.length >= minimum
          && decoded.length <= maximum
          && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static byte[] hmac(ConnectorHello hello, byte[] token) {
    if (token == null || token.length < 1 || token.length > 8192) {
      throw new IllegalArgumentException("Connector token is invalid");
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token, "HmacSHA256"));
      return mac.doFinal(transcript(hello).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    }
  }
}
