package dev.minecraftagent.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Frame grammar coverage, driven by the shared client-payload fixtures plus the failure
 * vocabularies both ends publish today.
 */
final class ClientPayloadFramesTest {
  /** The codes the Paper plugin reports for the shared grammar. */
  private static final ClientPayloadFrames.Codes PAPER_CODES =
      new ClientPayloadFrames.Codes(
          "CLIENT_MESSAGE_TOO_LARGE",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_ENVELOPE_INVALID",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_MESSAGE_INVALID",
          "CLIENT_PROTOCOL_INCOMPATIBLE",
          "CLIENT_OUTBOUND_FRAME_TOO_LARGE");

  /** The codes the Fabric client mod reports for the shared grammar. */
  private static final ClientPayloadFrames.Codes CLIENT_CODES =
      new ClientPayloadFrames.Codes(
          "CLIENT_FRAME_TOO_LARGE",
          "CLIENT_FRAME_UTF8_INVALID",
          "CLIENT_FRAME_JSON_INVALID",
          "CLIENT_FRAME_JSON_LIMIT",
          "CLIENT_FRAME_DUPLICATE_FIELD",
          "CLIENT_FRAME_FIELDS_INVALID",
          "CLIENT_FRAME_FIELD_INVALID",
          "CLIENT_FRAME_UUID_INVALID",
          "CLIENT_PROTOCOL_INCOMPATIBLE",
          "CLIENT_OUTBOUND_FRAME_TOO_LARGE");

  private static final String MESSAGE_ID = "d0000000-0000-4000-8000-000000000001";
  private static final int CLIENT_TO_SERVER = ClientPayloadLimits.MAX_CLIENT_TO_SERVER_FRAME_BYTES;
  private static final int SERVER_TO_CLIENT = ClientPayloadLimits.MAX_SERVER_TO_CLIENT_FRAME_BYTES;

  private final ClientPayloadFrames paper = new ClientPayloadFrames(PAPER_CODES);
  private final ClientPayloadFrames client = new ClientPayloadFrames(CLIENT_CODES);
  private final Path fixtures =
      Path.of(System.getProperty("minecraftAgent.protocolDir"))
          .toAbsolutePath()
          .normalize()
          .resolve("fixtures");

  @Test
  void decodesEveryFixtureMessageAndRoundTripsIt() throws IOException {
    var fixture = readFixture("valid/client-payload-messages.json");
    for (var entry : fixture.fields().entrySet()) {
      var envelope = (WireJson.ObjectNode) entry.getValue();
      var expectedId = ((WireJson.TextNode) envelope.get("messageId")).value();
      var expectedType = ((WireJson.TextNode) envelope.get("type")).value();
      var bytes = envelope.toJsonText().getBytes(StandardCharsets.UTF_8);

      var decoded = decodeOnBothEnds(bytes);
      assertEquals(UUID.fromString(expectedId), decoded.messageId());
      assertEquals(expectedType, decoded.type());
      assertEquals(envelope.get("payload"), decoded.payload());

      var encoded = encodeOnBothEnds(decoded.messageId(), decoded.type(), decoded.payload());
      assertEquals(decoded, decodeOnBothEnds(encoded));
    }
  }

  @Test
  void decodesTheSameFrameUnderBothFailureVocabularies() {
    var frame = frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", "\"client.ack\"", "{\"generation\":1}");
    var bytes = frame.getBytes(StandardCharsets.UTF_8);
    assertEquals(paper.decode(bytes, CLIENT_TO_SERVER), client.decode(bytes, SERVER_TO_CLIENT));
  }

  @Test
  void rejectsNonCanonicalUuidsFromTheSharedFixture() throws IOException {
    var fixture = readFixture("invalid/client-payload-uuid-noncanonical.json");
    for (var entry : fixture.fields().entrySet()) {
      var envelope = (WireJson.ObjectNode) entry.getValue();
      var bytes = envelope.toJsonText().getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.uuidInvalid(), paper, bytes);
      assertCode(CLIENT_CODES.uuidInvalid(), client, bytes);
    }
  }

  @Test
  void rejectsFramesOutsideTheByteBudget() {
    assertEquals(
        PAPER_CODES.tooLarge(),
        assertThrows(
                ProtocolViolationException.class,
                () -> paper.decode(new byte[CLIENT_TO_SERVER + 1], CLIENT_TO_SERVER))
            .code());
    assertEquals(
        CLIENT_CODES.tooLarge(),
        assertThrows(
                ProtocolViolationException.class,
                () -> client.decode(new byte[0], SERVER_TO_CLIENT))
            .code());
    var bytes = validFrame().getBytes(StandardCharsets.UTF_8);
    paper.decode(bytes, CLIENT_TO_SERVER);
    client.decode(bytes, SERVER_TO_CLIENT);
  }

  @Test
  void rejectsFramesThatAreNotRawUtf8() {
    assertCode(PAPER_CODES.utf8Invalid(), paper, new byte[] {(byte) 0xc3, 0x28, '}'});
    assertCode(CLIENT_CODES.utf8Invalid(), client, new byte[] {(byte) 0xff});
  }

  @Test
  void rejectsFramesThatAreNotStrictJson() {
    for (var source :
        List.of(
            "{oops",
            "{\"a\":1,}",
            frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", "\"client.ack\"", "{}") + " true",
            "{\"clientPayloadVersion\":\"1.0\",\"messageId\":"
                + "\"d0000000-0000-4000-8000-000000000001\",\"type\":\"client.ack\","
                + "\"payload\":{}} []")) {
      var bytes = source.getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.jsonInvalid(), paper, bytes);
      assertCode(CLIENT_CODES.jsonInvalid(), client, bytes);
    }
  }

  @Test
  void rejectsFramesThatBreachAParseBudget() {
    var payload =
        "{\"a\":".repeat(ClientPayloadFrames.BUDGET.maxDepth())
            + "1"
            + "}".repeat(ClientPayloadFrames.BUDGET.maxDepth());
    var bytes =
        frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", "\"client.ack\"", payload)
            .getBytes(StandardCharsets.UTF_8);
    assertCode(PAPER_CODES.jsonLimit(), paper, bytes);
    assertCode(CLIENT_CODES.jsonLimit(), client, bytes);
  }

  @Test
  void rejectsRepeatedEnvelopeFieldNames() {
    var duplicate =
        "{\"clientPayloadVersion\":\"1.0\",\"clientPayloadVersion\":\"1.0\",\"messageId\":"
            + "\""
            + MESSAGE_ID
            + "\",\"type\":\"client.ack\",\"payload\":{}}";
    var bytes = duplicate.getBytes(StandardCharsets.UTF_8);
    assertCode(PAPER_CODES.duplicateField(), paper, bytes);
    assertCode(CLIENT_CODES.duplicateField(), client, bytes);
  }

  @Test
  void rejectsEnvelopesWithoutTheClosedKeySet() {
    var extraField =
        "{\"clientPayloadVersion\":\"1.0\",\"messageId\":"
            + "\""
            + MESSAGE_ID
            + "\",\"type\":\"client.ack\",\"payload\":{},\"extra\":1}";
    var missingPayload =
        "{\"clientPayloadVersion\":\"1.0\",\"messageId\":"
            + "\""
            + MESSAGE_ID
            + "\",\"type\":\"client.ack\"}";
    for (var source : List.of(extraField, missingPayload)) {
      var bytes = source.getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.envelopeFieldsInvalid(), paper, bytes);
      assertCode(CLIENT_CODES.envelopeFieldsInvalid(), client, bytes);
    }
  }

  @Test
  void rejectsMessageIdsThatAreNotCanonicalLowercaseUuids() {
    for (var messageId :
        List.of(
            "\"not-a-uuid\"",
            "\"1-1-1-1-1\"",
            "\"" + MESSAGE_ID.toUpperCase(java.util.Locale.ROOT) + "\"")) {
      var bytes =
          frame("\"1.0\"", messageId, "\"client.ack\"", "{}").getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.uuidInvalid(), paper, bytes);
      assertCode(CLIENT_CODES.uuidInvalid(), client, bytes);
    }
    // Non-string spellings are shape failures, which each end reports with its field code.
    for (var messageId : List.of("7", "null")) {
      var bytes =
          frame("\"1.0\"", messageId, "\"client.ack\"", "{}").getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.fieldInvalid(), paper, bytes);
      assertCode(CLIENT_CODES.fieldInvalid(), client, bytes);
    }
  }

  @Test
  void rejectsEnvelopeFieldsOfTheWrongShape() {
    for (var type :
        List.of("\"\"", "\"" + "x".repeat(ClientPayloadLimits.MAX_TYPE_CHARS + 1) + "\"", "7")) {
      assertFieldInvalid(frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", type, "{}"));
    }
    for (var payload : List.of("[]", "\"text\"", "7")) {
      assertFieldInvalid(frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", "\"client.ack\"", payload));
    }
  }

  @Test
  void rejectsAnyOtherPayloadVersionAsProtocolIncompatible() {
    for (var version : List.of("\"9.99\"", "\"1\"", "\"2.0\"")) {
      var bytes =
          frame(version, "\"" + MESSAGE_ID + "\"", "\"client.ack\"", "{}")
              .getBytes(StandardCharsets.UTF_8);
      assertCode(PAPER_CODES.protocolIncompatible(), paper, bytes);
      assertCode(CLIENT_CODES.protocolIncompatible(), client, bytes);
    }
    for (var version : List.of("1", "null")) {
      assertFieldInvalid(frame(version, "\"" + MESSAGE_ID + "\"", "\"client.ack\"", "{}"));
    }
  }

  @Test
  void encodesWithinTheOutboundBudgetOnly() {
    var messageId = UUID.fromString(MESSAGE_ID);
    var payload = new WireJson.ObjectNode().putLong("generation", 1);
    var encoded = paper.encode(messageId, "client.ack", payload, 128);
    assertEquals("client.ack", paper.decode(encoded, 128).type());
    assertArrayEquals(encoded, client.encode(messageId, "client.ack", payload, 128));
    assertEquals(
        PAPER_CODES.outboundTooLarge(),
        assertThrows(
                ProtocolViolationException.class,
                () -> paper.encode(messageId, "client.ack", payload, 32))
            .code());
    assertEquals(
        CLIENT_CODES.outboundTooLarge(),
        assertThrows(
                ProtocolViolationException.class,
                () -> client.encode(messageId, "client.ack", payload, 8))
            .code());
  }

  @Test
  void reportsChunkCountMathSharedByBothEnds() {
    assertEquals(1, ClientPayloadLimits.expectedChunkCount(1));
    assertEquals(1, ClientPayloadLimits.expectedChunkCount(ClientPayloadLimits.MAX_CHUNK_BYTES));
    assertEquals(
        2, ClientPayloadLimits.expectedChunkCount(ClientPayloadLimits.MAX_CHUNK_BYTES + 1));
    assertEquals(
        ClientPayloadLimits.CHUNK_COUNT_MAX,
        ClientPayloadLimits.expectedChunkCount(
            ClientPayloadLimits.CHUNK_COUNT_MAX * ClientPayloadLimits.MAX_CHUNK_BYTES));
  }

  private ClientPayloadFrames.Frame decodeOnBothEnds(byte[] bytes) {
    var fromPaper = paper.decode(bytes, CLIENT_TO_SERVER);
    var fromClient = client.decode(bytes, SERVER_TO_CLIENT);
    assertEquals(fromPaper, fromClient);
    return fromPaper;
  }

  private byte[] encodeOnBothEnds(UUID messageId, String type, WireJson.ObjectNode payload) {
    var paperBytes = paper.encode(messageId, type, payload, SERVER_TO_CLIENT);
    var clientBytes = client.encode(messageId, type, payload, CLIENT_TO_SERVER);
    assertArrayEquals(paperBytes, clientBytes);
    return paperBytes;
  }

  private void assertFieldInvalid(String source) {
    var bytes = source.getBytes(StandardCharsets.UTF_8);
    assertCode(PAPER_CODES.fieldInvalid(), paper, bytes);
    assertCode(CLIENT_CODES.fieldInvalid(), client, bytes);
  }

  private void assertCode(String code, ClientPayloadFrames codec, byte[] bytes) {
    assertEquals(
        code,
        assertThrows(ProtocolViolationException.class, () -> codec.decode(bytes, 64 * 1024))
            .code());
  }

  private static String validFrame() {
    return frame("\"1.0\"", "\"" + MESSAGE_ID + "\"", "\"client.ack\"", "{}");
  }

  private static String frame(String version, String messageId, String type, String payload) {
    return "{\"clientPayloadVersion\":"
        + version
        + ",\"messageId\":"
        + messageId
        + ",\"type\":"
        + type
        + ",\"payload\":"
        + payload
        + "}";
  }

  private WireJson.ObjectNode readFixture(String relativePath) throws IOException {
    var path = fixtures.resolve(relativePath).normalize();
    assertEquals(true, path.startsWith(fixtures));
    return (WireJson.ObjectNode)
        WireJsonReader.parse(
            Files.readString(path, StandardCharsets.UTF_8), ClientPayloadFrames.BUDGET);
  }
}
