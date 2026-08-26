package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ConnectorHandshakeVerifierTest {
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String TOKEN =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID SCOPE_ID = TestProfiles.INSTALLATION_ID;

  @Test
  void acceptsARuntimeResponseThatAnswersTheRequest() {
    var request = request(ConnectorAuthentication.C1_CAPABILITIES);

    assertDoesNotThrow(
        () ->
            ConnectorHandshakeVerifier.verify(
                request,
                runtimeHello(request, request.capabilities()),
                SCOPE_ID,
                request.capabilities(),
                token()));
  }

  @Test
  void rejectsResponsesThatDoNotAnswerTheRequest() {
    var request = request(ConnectorAuthentication.C1_CAPABILITIES);
    var requested = request.capabilities();

    assertInvalid(
        request,
        withResponse(request, response -> amend(response, null, uuid(999), null, null, null)),
        requested);
    assertInvalid(
        request,
        withResponse(
            request, response -> amend(response, request.messageId(), null, null, null, null)),
        requested);
    assertInvalid(
        request,
        withResponse(request, response -> amend(response, null, null, null, request.nonce(), null)),
        requested);
  }

  @Test
  void rejectsResponsesThatEchoTheWrongChallengeOrKeyId() {
    var request = request(ConnectorAuthentication.C1_CAPABILITIES);
    var requested = request.capabilities();

    var wrongChallenge =
        new ConnectorHello.Authentication(
            ConnectorHello.Authentication.SCHEME,
            ConnectorAuthentication.KEY_ID,
            challenge((byte) 6),
            "A".repeat(43));
    assertInvalid(
        request,
        withResponse(request, response -> amend(response, null, null, null, null, wrongChallenge)),
        requested);

    var wrongKeyId =
        new ConnectorHello.Authentication(
            ConnectorHello.Authentication.SCHEME,
            "other-key",
            request.authentication().challenge(),
            "A".repeat(43));
    assertInvalid(
        request,
        withResponse(request, response -> amend(response, null, null, null, null, wrongKeyId)),
        requested);
  }

  @Test
  void rejectsCapabilityNegotiationOutsideTheRequest() {
    var cancel = new ConnectorHello.Capability("client.cancel", 1);
    var status = new ConnectorHello.Capability("client.status", 1);
    var text = new ConnectorHello.Capability("client.text", 1);
    var search = new ConnectorHello.Capability("game.resource.search", 1);
    var request = request(List.of(cancel, status, text, search));

    var missingC1Capability = List.of(cancel, status, search);
    assertInvalid(request, runtimeHello(request, missingC1Capability), request.capabilities());

    var unsolicited =
        List.of(
            cancel,
            status,
            text,
            new ConnectorHello.Capability("game.inventory.snapshot", 1),
            search);
    assertInvalid(request, runtimeHello(request, unsolicited), request.capabilities());
  }

  @Test
  void rejectsUnscopedOrUnprovenResponsesWithAuthenticationFailed() {
    var request = request(ConnectorAuthentication.C1_CAPABILITIES);
    var requested = request.capabilities();

    assertAuthenticationFailed(
        request,
        withResponse(request, response -> amend(response, null, null, uuid(500), null, null)),
        requested);
    assertAuthenticationFailed(
        request,
        withResponse(
            request,
            response ->
                amend(
                    response,
                    null,
                    null,
                    null,
                    null,
                    new ConnectorHello.Authentication(
                        response.authentication().scheme(),
                        response.authentication().keyId(),
                        response.authentication().challenge(),
                        "A".repeat(43)))),
        requested);
  }

  @Test
  void negotiatedToolsKeepsOnlyKnownClientTools() {
    var negotiated =
        ConnectorHandshakeVerifier.negotiatedTools(
            List.of(
                new ConnectorHello.Capability("client.cancel", 1),
                new ConnectorHello.Capability("game.resource.search", 1),
                new ConnectorHello.Capability("runtime.private", 1)));

    assertEquals(Set.of("game.resource.search"), negotiated);
  }

  @Test
  void clockSkewAcceptsExactlyThirtySecondsInEitherDirection() {
    var now = NOW.toEpochMilli();

    assertTrue(ConnectorHandshakeVerifier.withinClockSkew(NOW.toString(), now));
    assertTrue(ConnectorHandshakeVerifier.withinClockSkew(NOW.minusSeconds(30).toString(), now));
    assertTrue(ConnectorHandshakeVerifier.withinClockSkew(NOW.plusSeconds(30).toString(), now));
    assertFalse(ConnectorHandshakeVerifier.withinClockSkew(NOW.minusSeconds(31).toString(), now));
    assertFalse(ConnectorHandshakeVerifier.withinClockSkew(NOW.plusSeconds(31).toString(), now));
    assertFalse(ConnectorHandshakeVerifier.withinClockSkew("not-an-instant", now));
  }

  private static void assertInvalid(
      ConnectorHello request, ConnectorHello response, List<ConnectorHello.Capability> requested) {
    var failure =
        assertThrows(
            ConnectorException.class,
            () ->
                ConnectorHandshakeVerifier.verify(request, response, SCOPE_ID, requested, token()));
    assertEquals("HANDSHAKE_INVALID", failure.code());
  }

  private static void assertAuthenticationFailed(
      ConnectorHello request, ConnectorHello response, List<ConnectorHello.Capability> requested) {
    var failure =
        assertThrows(
            ConnectorException.class,
            () ->
                ConnectorHandshakeVerifier.verify(request, response, SCOPE_ID, requested, token()));
    assertEquals("AUTHENTICATION_FAILED", failure.code());
  }

  private static ConnectorHello request(List<ConnectorHello.Capability> capabilities) {
    return ConnectorAuthentication.createRequest(
        SCOPE_ID, "0.2.0", CLOCK, uuid(1), nonce(7), challenge((byte) 5), capabilities, token());
  }

  private static String challenge(byte value) {
    var bytes = new byte[32];
    Arrays.fill(bytes, value);
    return ConnectorAuthentication.encodeNonce(bytes);
  }

  private static ConnectorHello runtimeHello(
      ConnectorHello request, List<ConnectorHello.Capability> capabilities) {
    var unsigned =
        new ConnectorHello(
            "1.0",
            ConnectorHello.CONNECTOR_KIND,
            ConnectorHello.TYPE,
            uuid(100),
            request.messageId(),
            NOW.toString(),
            nonce(100),
            "runtime",
            "0.2.0",
            request.scopeId(),
            List.of(ConnectorHello.PROTOCOL_VERSION),
            ConnectorHello.PROTOCOL_VERSION,
            capabilities,
            new ConnectorHello.Authentication(
                ConnectorHello.Authentication.SCHEME,
                ConnectorAuthentication.KEY_ID,
                request.authentication().challenge(),
                "A".repeat(43)));
    return ConnectorAuthentication.withProof(unsigned, token());
  }

  /**
   * Mutates an already signed response without re-signing it, so the mutation is exactly the kind
   * of hostile answer the verifier must reject (a re-signed mutation would present a valid proof
   * for the hostile content).
   */
  private static ConnectorHello withResponse(
      ConnectorHello request, UnaryOperator<ConnectorHello> adjust) {
    return adjust.apply(runtimeHello(request, request.capabilities()));
  }

  /** Copies a signed response, replacing the fields the caller mutated ({@code null} keeps it). */
  private static ConnectorHello amend(
      ConnectorHello response,
      UUID messageId,
      UUID requestId,
      UUID scopeId,
      String nonce,
      ConnectorHello.Authentication authentication) {
    return new ConnectorHello(
        response.schemaVersion(),
        response.connectorKind(),
        response.type(),
        messageId == null ? response.messageId() : messageId,
        requestId == null ? response.requestId() : requestId,
        response.timestamp(),
        nonce == null ? response.nonce() : nonce,
        response.component(),
        response.componentVersion(),
        scopeId == null ? response.scopeId() : scopeId,
        response.supportedProtocolVersions(),
        response.selectedProtocolVersion(),
        response.capabilities(),
        authentication == null ? response.authentication() : authentication);
  }

  private static byte[] token() {
    return TOKEN.getBytes(StandardCharsets.UTF_8);
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }

  private static String nonce(int value) {
    var bytes = new byte[16];
    Arrays.fill(bytes, (byte) value);
    return ConnectorAuthentication.encodeNonce(bytes);
  }
}
