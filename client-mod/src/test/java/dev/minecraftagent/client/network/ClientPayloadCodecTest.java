package dev.minecraftagent.client.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.minecraftagent.client.litematica.LitematicaAdapterDiagnostic;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPayloadCodecTest {
  private static final UUID MESSAGE_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
  private static final UUID TRANSFER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000010");
  private static final String ABCD_HASH =
      "88d4266fd4e6338d13b845fcf289579d209c897823b9217da3e161936f031589";

  private final ClientPayloadCodec codec = new ClientPayloadCodec();

  @Test
  void decodesEveryServerMessageWithClosedTypedFields() {
    var hello =
        assertInstanceOf(
            ClientServerMessage.ServerHello.class,
            decode(
                "server.hello",
                "{\"generation\":4,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    assertEquals(4, hello.generation());
    assertEquals("1.0", hello.viewSchemaVersion());

    var begin =
        assertInstanceOf(
            ClientServerMessage.ViewBegin.class,
            decode(
                "view.begin",
                "{\"generation\":4,\"transferId\":\"d0000000-0000-4000-8000-000000000010\","
                    + "\"viewId\":\"d0000000-0000-4000-8000-000000000011\","
                    + "\"requestId\":\"d0000000-0000-4000-8000-000000000012\","
                    + "\"revision\":1,\"mode\":\"show\",\"encoding\":\"identity\","
                    + "\"compressedBytes\":4,\"uncompressedBytes\":4,\"chunkCount\":1,"
                    + "\"contentSha256\":\""
                    + ABCD_HASH
                    + "\"}"));
    assertEquals(ClientServerMessage.Mode.SHOW, begin.mode());
    assertEquals(ClientServerMessage.Encoding.IDENTITY, begin.encoding());

    var chunk =
        assertInstanceOf(
            ClientServerMessage.ViewChunk.class,
            decode(
                "view.chunk",
                "{\"generation\":4,\"transferId\":\"d0000000-0000-4000-8000-000000000010\","
                    + "\"index\":0,\"byteLength\":4,\"sha256\":\""
                    + ABCD_HASH
                    + "\",\"data\":\"YWJjZA==\"}"));
    assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), chunk.data());

    var clear =
        assertInstanceOf(
            ClientServerMessage.ViewClear.class,
            decode("view.clear", "{\"generation\":4,\"viewId\":null}"));
    assertNull(clear.viewId());

    var control =
        assertInstanceOf(
            ClientServerMessage.UiControl.class,
            decode("ui.control", "{\"generation\":4,\"action\":\"pin\",\"viewId\":null}"));
    assertEquals(ClientServerMessage.Action.PIN, control.action());
  }

  @Test
  void rejectsWrongDirectionDuplicateExtraAndMalformedFrames() {
    assertCode("CLIENT_MESSAGE_DIRECTION_INVALID", envelope("client.ack", "{\"generation\":4}"));
    assertCode(
        "CLIENT_FRAME_DUPLICATE_FIELD",
        "{\"clientPayloadVersion\":\"1.0\",\"clientPayloadVersion\":\"1.0\","
            + "\"messageId\":\""
            + MESSAGE_ID
            + "\",\"type\":\"server.hello\",\"payload\":{}}");
    assertCode(
        "CLIENT_FRAME_FIELDS_INVALID",
        "{\"clientPayloadVersion\":\"1.0\",\"messageId\":\""
            + MESSAGE_ID
            + "\",\"type\":\"server.hello\",\"payload\":{},\"permission\":\"op\"}");
    assertCode("CLIENT_FRAME_UTF8_INVALID", new byte[] {(byte) 0xc3, 0x28});
    assertCode("CLIENT_FRAME_TOO_LARGE", new byte[ClientPayloadCodec.MAX_INBOUND_BYTES + 1]);
  }

  @Test
  void rejectsNonCanonicalBase64AndChunkHashMismatch() {
    assertCode(
        "CLIENT_CHUNK_BASE64_INVALID",
        envelope(
            "view.chunk",
            "{\"generation\":4,\"transferId\":\""
                + TRANSFER_ID
                + "\",\"index\":0,\"byteLength\":4,\"sha256\":\""
                + ABCD_HASH
                + "\",\"data\":\"YWJjZA\"}"));
    assertCode(
        "CLIENT_CHUNK_HASH_MISMATCH",
        envelope(
            "view.chunk",
            "{\"generation\":4,\"transferId\":\""
                + TRANSFER_ID
                + "\",\"index\":0,\"byteLength\":4,\"sha256\":\""
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + "\",\"data\":\"YWJjZA==\"}"));
  }

  @Test
  void emitsOnlyClosedPresentationFactsFromTheClient() {
    var advertisement =
        new ClientHandshakeAdvertisement(
            "0.1.0", 1, 1, 2, 0, 0, Optional.empty(), Optional.empty(), notInstalled());
    var hello =
        JsonParser.parseString(text(codec.encodeHello(MESSAGE_ID, advertisement)))
            .getAsJsonObject();
    assertEquals("client.hello", hello.get("type").getAsString());
    assertEquals("1.0", hello.get("clientPayloadVersion").getAsString());
    assertEquals(
        "1.1", hello.getAsJsonObject("payload").get("clientProtocolVersion").getAsString());
    assertEquals(
        2,
        hello
            .getAsJsonObject("payload")
            .getAsJsonObject("capabilities")
            .get("recipeView")
            .getAsInt());
    assertTrue(
        hello
            .getAsJsonObject("payload")
            .getAsJsonObject("dependencies")
            .get("litematica")
            .isJsonNull());
    assertEquals(
        "NOT_INSTALLED",
        hello
            .getAsJsonObject("payload")
            .getAsJsonObject("diagnostics")
            .getAsJsonObject("litematicaAdapter")
            .get("status")
            .getAsString());
    assertEquals(
        6,
        hello
            .getAsJsonObject("payload")
            .getAsJsonObject("diagnostics")
            .getAsJsonObject("litematicaAdapter")
            .size());
    assertEquals(4, hello.size());

    var ack =
        JsonParser.parseString(
                text(codec.encodeAck(MESSAGE_ID, 4, TRANSFER_ID, true, "VIEW_DISPLAYED")))
            .getAsJsonObject();
    assertEquals("client.ack", ack.get("type").getAsString());
    assertEquals("DISPLAYED", ack.getAsJsonObject("payload").get("status").getAsString());
    assertEquals(4, ack.getAsJsonObject("payload").size());

    var error =
        JsonParser.parseString(text(codec.encodeError(MESSAGE_ID, 4, null, "TRANSFER_TIMED_OUT")))
            .getAsJsonObject();
    assertTrue(error.getAsJsonObject("payload").get("transferId").isJsonNull());
    assertEquals(3, error.getAsJsonObject("payload").size());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientHandshakeAdvertisement(
                "0.1.0", 1, 1, 3, 0, 0, Optional.empty(), Optional.empty(), notInstalled()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LitematicaAdapterDiagnostic(
                LitematicaAdapterDiagnostic.Status.NOT_INSTALLED,
                "/home/player/.minecraft",
                "0.19.3",
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void doesNotAdvertiseLitematicaWithoutAnExactDependencyPair() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientHandshakeAdvertisement(
                "0.1.0",
                1,
                1,
                1,
                1,
                1,
                Optional.of("0.26.12"),
                Optional.empty(),
                new LitematicaAdapterDiagnostic(
                    LitematicaAdapterDiagnostic.Status.MISSING_DEPENDENCY,
                    "1.21.11",
                    "0.19.3",
                    Optional.of("0.26.12"),
                    Optional.empty(),
                    Optional.empty())));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientHandshakeAdvertisement(
                "0.1.0",
                1,
                1,
                1,
                1,
                1,
                Optional.of("0.26.11"),
                Optional.of("0.27.16"),
                new LitematicaAdapterDiagnostic(
                    LitematicaAdapterDiagnostic.Status.UNSUPPORTED_VERSION,
                    "1.21.11",
                    "0.19.3",
                    Optional.of("0.26.11"),
                    Optional.of("0.27.16"),
                    Optional.empty())));
  }

  private ClientServerMessage decode(String type, String payload) {
    return codec.decodeServer(envelope(type, payload).getBytes(StandardCharsets.UTF_8));
  }

  private static String envelope(String type, String payload) {
    return "{\"clientPayloadVersion\":\"1.0\",\"messageId\":\""
        + MESSAGE_ID
        + "\",\"type\":\""
        + type
        + "\",\"payload\":"
        + payload
        + "}";
  }

  private void assertCode(String code, String source) {
    assertCode(code, source.getBytes(StandardCharsets.UTF_8));
  }

  private void assertCode(String code, byte[] source) {
    assertEquals(
        code, assertThrows(ClientPayloadException.class, () -> codec.decodeServer(source)).code());
  }

  private static String text(byte[] bytes) {
    assertTrue(bytes.length > 0 && bytes[0] == '{');
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static LitematicaAdapterDiagnostic notInstalled() {
    return new LitematicaAdapterDiagnostic(
        LitematicaAdapterDiagnostic.Status.NOT_INSTALLED,
        "1.21.11",
        "0.19.3",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
