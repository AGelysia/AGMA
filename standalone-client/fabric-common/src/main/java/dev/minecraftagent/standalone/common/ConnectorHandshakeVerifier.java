package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure validation rules for the Runtime's handshake response, plus the clock-skew policy shared by
 * handshake and envelope freshness checks.
 *
 * <p>The verifier reads only its arguments and throws {@link ConnectorException} with the protocol
 * error code for the first violated rule; session state, replay claims, and the authentication
 * token lifecycle stay with {@link ConnectorConnection}.
 */
final class ConnectorHandshakeVerifier {
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

  private ConnectorHandshakeVerifier() {}

  /**
   * Verifies a Runtime handshake response against the request hello that solicited it.
   *
   * @throws ConnectorException {@code HANDSHAKE_INVALID} when the response does not answer the
   *     request or negotiates capabilities the client never offered, and {@code
   *     AUTHENTICATION_FAILED} when the scope or proof does not match
   */
  static void verify(
      ConnectorHello request,
      ConnectorHello response,
      UUID scopeId,
      List<ConnectorHello.Capability> requestedCapabilities,
      byte[] token) {
    if (!"runtime".equals(response.component())
        || !request.messageId().equals(response.requestId())
        || request.messageId().equals(response.messageId())
        || request.nonce().equals(response.nonce())
        || !request.authentication().challenge().equals(response.authentication().challenge())
        || !ConnectorAuthentication.KEY_ID.equals(response.authentication().keyId())
        || !negotiationValid(response.capabilities(), requestedCapabilities)) {
      throw new ConnectorException("HANDSHAKE_INVALID", "Runtime handshake is invalid");
    }
    if (!scopeId.equals(response.scopeId()) || !ConnectorAuthentication.verify(response, token)) {
      throw new ConnectorException(
          "AUTHENTICATION_FAILED", "Runtime connector authentication failed");
    }
  }

  /** The client-relevant tools among the negotiated capabilities. */
  static Set<String> negotiatedTools(List<ConnectorHello.Capability> capabilities) {
    return capabilities.stream()
        .map(ConnectorHello.Capability::id)
        .filter(ClientToolPayloads.TOOLS::contains)
        .collect(Collectors.toUnmodifiableSet());
  }

  static boolean withinClockSkew(String timestamp, long nowMillis) {
    try {
      return withinClockSkew(Instant.parse(timestamp), nowMillis);
    } catch (RuntimeException exception) {
      return false;
    }
  }

  static boolean withinClockSkew(Instant timestamp, long nowMillis) {
    try {
      var now = Instant.ofEpochMilli(nowMillis);
      return !timestamp.isBefore(now.minus(CLOCK_SKEW)) && !timestamp.isAfter(now.plus(CLOCK_SKEW));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static boolean negotiationValid(
      List<ConnectorHello.Capability> capabilities,
      List<ConnectorHello.Capability> requestedCapabilities) {
    if (!capabilities.containsAll(ConnectorAuthentication.C1_CAPABILITIES)) {
      return false;
    }
    for (var capability : capabilities) {
      if (!requestedCapabilities.contains(capability)) {
        return false;
      }
    }
    return true;
  }
}
