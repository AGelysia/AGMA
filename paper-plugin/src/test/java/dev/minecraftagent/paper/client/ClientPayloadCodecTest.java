package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPayloadCodecTest {
  private static final UUID MESSAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void decodesClosedHelloWithoutPlayerIdentity() {
    var message =
        assertInstanceOf(ClientInboundMessage.Hello.class, codec().decodeInbound(hello()));

    assertEquals(MESSAGE, message.messageId());
    assertEquals("1.1", message.handshake().clientProtocolVersion());
    assertEquals("1.2.3", message.handshake().modVersion());
    assertEquals(1, message.handshake().capabilities().version(ClientFeature.OVERLAY));
    assertTrue(message.handshake().dependencyVersion("litematica").isEmpty());
    assertEquals(
        ClientLitematicaDiagnostic.Status.NOT_INSTALLED,
        message.handshake().litematicaAdapterDiagnostic().status());
  }

  @Test
  void decodesLegacyHelloWithoutDiagnosticsAndPreservesItsCapabilities() {
    var message =
        assertInstanceOf(
            ClientInboundMessage.Hello.class,
            codec()
                .decodeInbound(
                    json(
                        """
                        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{"clientProtocolVersion":"1.0","modVersion":"1.2.3","capabilities":{"overlay":1,"itemIcons":1,"recipeView":1,"litematicaPreview":1,"litematicaMaterialList":1},"dependencies":{"litematica":"0.26.12","malilib":"0.27.16"}}}
                        """)));

    assertEquals("1.0", message.handshake().clientProtocolVersion());
    assertEquals(1, message.handshake().capabilities().version(ClientFeature.LITEMATICA_PREVIEW));
    assertEquals(
        ClientLitematicaDiagnostic.Status.LEGACY_UNREPORTED,
        message.handshake().litematicaAdapterDiagnostic().status());
    assertEquals(
        Optional.of("0.26.12"),
        message.handshake().litematicaAdapterDiagnostic().litematicaVersion());
  }

  @Test
  void decodesAckAndRejectsOutboundTypesOrNonClosedJson() {
    var ack =
        assertInstanceOf(
            ClientInboundMessage.Ack.class,
            codec()
                .decodeInbound(
                    json(
                        """
                        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.ack","payload":{"transferId":"44444444-4444-4444-8444-444444444444","generation":7,"status":"DISPLAYED","code":"VIEW_DISPLAYED"}}
                        """)));
    assertEquals(7, ack.generation());
    assertEquals("VIEW_DISPLAYED", ack.code());

    assertCode(
        "CLIENT_MESSAGE_DIRECTION_INVALID",
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"view.clear","payload":{}}
        """);
    assertCode(
        "CLIENT_ENVELOPE_INVALID",
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","playerUuid":"11111111-1111-4111-8111-111111111111","payload":{}}
        """);
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        """
        {"clientPayloadVersion":"1.0","clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{}}
        """);
  }

  @Test
  void rejectsOversizeMalformedUtf8AndFalseDependencyClaims() {
    var oversized = new byte[ClientPayloadCodec.MAX_INBOUND_BYTES + 1];
    assertEquals(
        "CLIENT_MESSAGE_TOO_LARGE",
        assertThrows(ClientProtocolException.class, () -> codec().decodeInbound(oversized)).code());
    assertEquals(
        "CLIENT_MESSAGE_INVALID",
        assertThrows(
                ClientProtocolException.class,
                () -> codec().decodeInbound(new byte[] {(byte) 0xc3, (byte) 0x28}))
            .code());

    var falseClaim =
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"litematicaPreview\":0", "\"litematicaPreview\":1");
    assertCode("CLIENT_DEPENDENCY_CLAIM_INVALID", falseClaim);
  }

  @Test
  void rejectsVersionMixedUnknownIncoherentAndPathBearingAdapterDiagnostics() {
    assertCode(
        "CLIENT_HELLO_INVALID",
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{"clientProtocolVersion":"1.1","modVersion":"1.2.3","capabilities":{"overlay":1,"itemIcons":1,"recipeView":1,"litematicaPreview":0,"litematicaMaterialList":0},"dependencies":{"litematica":null,"malilib":null}}}
        """);
    assertCode(
        "CLIENT_HELLO_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"clientProtocolVersion\":\"1.1\"", "\"clientProtocolVersion\":\"1.0\""));
    assertCode(
        "CLIENT_ADAPTER_STATUS_INVALID",
        new String(hello(), StandardCharsets.UTF_8).replace("\"NOT_INSTALLED\"", "\"AVAILABLE\""));
    assertCode(
        "CLIENT_ADAPTER_DIAGNOSTIC_INVALID",
        new String(hello(), StandardCharsets.UTF_8).replace("\"NOT_INSTALLED\"", "\"READY\""));
    assertCode(
        "CLIENT_HELLO_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"adapterId\":null", "\"adapterId\":null,\"path\":\"/home/user\""));
    assertCode(
        "CLIENT_ADAPTER_DIAGNOSTIC_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"minecraftVersion\":\"1.21.11\"", "\"minecraftVersion\":\"/home/user\""));
    assertCode(
        "CLIENT_ADAPTER_DIAGNOSTIC_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"litematica\":null", "\"litematica\":\"0.26.12\""));
    assertCode(
        "CLIENT_ADAPTER_STATUS_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("\"NOT_INSTALLED\"", "\"LEGACY_UNREPORTED\""));

    var nonReadyCapability = object(hello("UNSUPPORTED_VERSION", "0.26.11", "0.27.16", null));
    var capabilities =
        nonReadyCapability.getAsJsonObject("payload").getAsJsonObject("capabilities");
    capabilities.addProperty("litematicaPreview", 1);
    capabilities.addProperty("litematicaMaterialList", 1);
    assertCode("CLIENT_ADAPTER_DIAGNOSTIC_INVALID", nonReadyCapability.toString());

    var versionMismatch = object(hello("READY", "0.26.12", "0.27.16", "adapter-1"));
    versionMismatch
        .getAsJsonObject("payload")
        .getAsJsonObject("dependencies")
        .addProperty("litematica", "0.26.13");
    assertCode("CLIENT_ADAPTER_DIAGNOSTIC_INVALID", versionMismatch.toString());
  }

  @Test
  void decodesEveryClosedAdapterStatusAndDependencyCombination() {
    assertAdapterStatus("READY", "0.26.12", "0.27.16", "Litematica.Adapter-1");
    assertAdapterStatus("NOT_INSTALLED", null, "0.27.16", null);
    assertAdapterStatus("MISSING_DEPENDENCY", "0.26.12", null, null);
    assertAdapterStatus("UNSUPPORTED_VERSION", "0.26.11", "0.27.16", null);
    assertAdapterStatus("ADAPTER_LINKAGE_FAILED", "0.26.12", "0.27.16", "litematica-reflection-1");
    assertAdapterStatus("PREVIEW_STORAGE_UNAVAILABLE", "0.26.12", "0.27.16", null);
  }

  @Test
  void acceptsRecipeCapabilityV2AndRejectsVersionsAboveItsClosedMaximum() {
    var versionTwo = object(hello());
    versionTwo
        .getAsJsonObject("payload")
        .getAsJsonObject("capabilities")
        .addProperty("recipeView", 2);
    var decoded =
        assertInstanceOf(
            ClientInboundMessage.Hello.class, codec().decodeInbound(json(versionTwo.toString())));
    assertEquals(2, decoded.handshake().capabilities().version(ClientFeature.RECIPE_VIEW));

    versionTwo
        .getAsJsonObject("payload")
        .getAsJsonObject("capabilities")
        .addProperty("recipeView", 3);
    assertEquals(
        "CLIENT_MESSAGE_INVALID",
        assertThrows(
                ClientProtocolException.class,
                () -> codec().decodeInbound(json(versionTwo.toString())))
            .code());
  }

  @Test
  void rejectsNonCanonicalUuidSpellingsAcceptedByJavaUuidParser() {
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace("33333333-3333-4333-8333-333333333333", "3-3-3-3-3"));
    assertCode(
        "CLIENT_MESSAGE_INVALID",
        new String(hello(), StandardCharsets.UTF_8)
            .replace(
                "33333333-3333-4333-8333-333333333333", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
  }

  @Test
  void encodesOnlyBoundedServerDirectionsAndTransferMetadata() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(UUID.randomUUID(), 9);
    var player = UUID.randomUUID();
    manager.open(player, 9);
    var plan =
        manager.prepare(player, 9, ClientViewSelectorTest.view(ClientViewType.TEXT), Instant.EPOCH);

    var hello = object(codec().encodeServerHello(MESSAGE, 9));
    assertEquals("server.hello", hello.get("type").getAsString());
    assertEquals(9, hello.getAsJsonObject("payload").get("generation").getAsLong());

    var begin = object(codec().encodeViewBegin(MESSAGE, plan));
    assertEquals("view.begin", begin.get("type").getAsString());
    assertEquals(
        plan.contentSha256(), begin.getAsJsonObject("payload").get("contentSha256").getAsString());

    var chunk = object(codec().encodeViewChunk(MESSAGE, 9, plan.chunks().getFirst()));
    assertEquals("view.chunk", chunk.get("type").getAsString());
    assertTrue(chunk.getAsJsonObject("payload").get("data").getAsString().length() > 0);
  }

  private static ClientPayloadCodec codec() {
    return new ClientPayloadCodec();
  }

  private static byte[] hello() {
    return json(
        """
        {"clientPayloadVersion":"1.0","messageId":"33333333-3333-4333-8333-333333333333","type":"client.hello","payload":{"clientProtocolVersion":"1.1","modVersion":"1.2.3","capabilities":{"overlay":1,"itemIcons":1,"recipeView":1,"litematicaPreview":0,"litematicaMaterialList":0},"dependencies":{"litematica":null,"malilib":null},"diagnostics":{"litematicaAdapter":{"status":"NOT_INSTALLED","minecraftVersion":"1.21.11","fabricLoaderVersion":"0.19.3","litematicaVersion":null,"malilibVersion":null,"adapterId":null}}}}
        """);
  }

  private void assertAdapterStatus(
      String status, String litematicaVersion, String malilibVersion, String adapterId) {
    var message =
        assertInstanceOf(
            ClientInboundMessage.Hello.class,
            codec().decodeInbound(hello(status, litematicaVersion, malilibVersion, adapterId)));
    assertEquals(
        ClientLitematicaDiagnostic.Status.valueOf(status),
        message.handshake().litematicaAdapterDiagnostic().status());
  }

  private static byte[] hello(
      String status, String litematicaVersion, String malilibVersion, String adapterId) {
    var envelope = object(hello());
    var payload = envelope.getAsJsonObject("payload");
    var dependencies = payload.getAsJsonObject("dependencies");
    putNullable(dependencies, "litematica", litematicaVersion);
    putNullable(dependencies, "malilib", malilibVersion);
    var adapter = payload.getAsJsonObject("diagnostics").getAsJsonObject("litematicaAdapter");
    adapter.addProperty("status", status);
    putNullable(adapter, "litematicaVersion", litematicaVersion);
    putNullable(adapter, "malilibVersion", malilibVersion);
    putNullable(adapter, "adapterId", adapterId);
    return json(envelope.toString());
  }

  private static void putNullable(com.google.gson.JsonObject object, String name, String value) {
    if (value == null) {
      object.add(name, com.google.gson.JsonNull.INSTANCE);
    } else {
      object.addProperty(name, value);
    }
  }

  private static byte[] json(String value) {
    return value.strip().getBytes(StandardCharsets.UTF_8);
  }

  private static com.google.gson.JsonObject object(byte[] bytes) {
    return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private static void assertCode(String code, String json) {
    assertEquals(
        code,
        assertThrows(ClientProtocolException.class, () -> codec().decodeInbound(json(json)))
            .code());
  }
}
