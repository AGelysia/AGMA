package dev.minecraftagent.paper.client;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.client.ClientTransferManager.TransferChunk;
import dev.minecraftagent.paper.client.ClientTransferManager.TransferPlan;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.StrictJson;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Strict raw-UTF-8 JSON codec for the single Bukkit/Fabric custom payload channel. */
public final class ClientPayloadCodec {
  public static final String CHANNEL = "minecraftagent:client";
  public static final String PAYLOAD_VERSION = "1.0";
  public static final int MAX_INBOUND_BYTES = 16 * 1024;
  public static final int MAX_OUTBOUND_FRAME_BYTES = 40 * 1024;

  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("clientPayloadVersion", "messageId", "type", "payload");
  private static final Set<String> LEGACY_HELLO_FIELDS =
      Set.of("clientProtocolVersion", "modVersion", "capabilities", "dependencies");
  private static final Set<String> CURRENT_HELLO_FIELDS =
      Set.of("clientProtocolVersion", "modVersion", "capabilities", "dependencies", "diagnostics");
  private static final Set<String> CAPABILITY_FIELDS =
      Set.of("overlay", "itemIcons", "recipeView", "litematicaPreview", "litematicaMaterialList");
  private static final Set<String> DEPENDENCY_FIELDS = Set.of("litematica", "malilib");
  private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("litematicaAdapter");
  private static final Set<String> LITEMATICA_ADAPTER_FIELDS =
      Set.of(
          "status",
          "minecraftVersion",
          "fabricLoaderVersion",
          "litematicaVersion",
          "malilibVersion",
          "adapterId");
  private static final Set<String> ACK_FIELDS =
      Set.of("transferId", "generation", "status", "code");
  private static final Set<String> ERROR_FIELDS = Set.of("transferId", "generation", "code");

  public ClientInboundMessage decodeInbound(byte[] bytes) {
    var envelope = parse(bytes);
    requireFields(envelope, ENVELOPE_FIELDS, "CLIENT_ENVELOPE_INVALID");
    if (!PAYLOAD_VERSION.equals(string(envelope, "clientPayloadVersion"))) {
      throw new ClientProtocolException("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    var messageId = uuid(envelope, "messageId", false);
    var type = string(envelope, "type");
    var payload = object(envelope, "payload");
    return switch (type) {
      case "client.hello" -> decodeHello(messageId, payload);
      case "client.ack" -> decodeAck(messageId, payload);
      case "client.error" -> decodeError(messageId, payload);
      default -> throw new ClientProtocolException("CLIENT_MESSAGE_DIRECTION_INVALID");
    };
  }

  public byte[] encodeServerHello(UUID messageId, long generation) {
    return encodeServerHello(messageId, generation, true);
  }

  public byte[] encodeServerHello(UUID messageId, long generation, boolean accepted) {
    if (generation < 1 || generation > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("generation must be positive");
    }
    var payload = new JsonObject();
    payload.addProperty("generation", generation);
    payload.addProperty("accepted", accepted);
    if (accepted) {
      payload.addProperty("viewSchemaVersion", ClientViewSchemaRegistry.VIEW_SCHEMA_V1);
    } else {
      payload.add("viewSchemaVersion", com.google.gson.JsonNull.INSTANCE);
    }
    return encode(messageId, "server.hello", payload);
  }

  public byte[] encodeViewBegin(UUID messageId, TransferPlan plan) {
    Objects.requireNonNull(plan);
    var payload = new JsonObject();
    payload.addProperty("transferId", plan.transferId().toString());
    payload.addProperty("generation", plan.generation());
    payload.addProperty("viewId", plan.viewId().toString());
    payload.addProperty("requestId", plan.requestId().toString());
    payload.addProperty("revision", plan.revision());
    payload.addProperty("mode", plan.mode().wireName());
    payload.addProperty("encoding", plan.encoding().wireName());
    payload.addProperty("compressedBytes", plan.compressedBytes());
    payload.addProperty("uncompressedBytes", plan.uncompressedBytes());
    payload.addProperty("chunkCount", plan.chunkCount());
    payload.addProperty("contentSha256", plan.contentSha256());
    return encode(messageId, "view.begin", payload);
  }

  public byte[] encodeViewChunk(UUID messageId, long generation, TransferChunk chunk) {
    Objects.requireNonNull(chunk);
    if (generation < 1
        || generation > Integer.MAX_VALUE
        || chunk.bytes().length > 24 * 1024
        || chunk.bytes().length == 0) {
      throw new ClientProtocolException("CLIENT_TRANSFER_CHUNK_INVALID");
    }
    var payload = new JsonObject();
    payload.addProperty("transferId", chunk.transferId().toString());
    payload.addProperty("generation", generation);
    payload.addProperty("index", chunk.index());
    payload.addProperty("byteLength", chunk.bytes().length);
    payload.addProperty("sha256", chunk.sha256());
    payload.addProperty("data", Base64.getEncoder().encodeToString(chunk.bytes()));
    return encode(messageId, "view.chunk", payload);
  }

  public byte[] encodeViewClear(UUID messageId, long generation, UUID viewId) {
    if (generation < 1 || generation > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("generation must be positive");
    }
    var payload = new JsonObject();
    payload.addProperty("generation", generation);
    if (viewId == null) {
      payload.add("viewId", com.google.gson.JsonNull.INSTANCE);
    } else {
      payload.addProperty("viewId", viewId.toString());
    }
    return encode(messageId, "view.clear", payload);
  }

  public byte[] encodeUiControl(UUID messageId, ClientUiCommandGateway.Control control) {
    Objects.requireNonNull(control);
    var payload = new JsonObject();
    payload.addProperty("generation", control.generation());
    payload.addProperty("action", control.action().wireName());
    if (control.viewId() == null) {
      payload.add("viewId", com.google.gson.JsonNull.INSTANCE);
    } else {
      payload.addProperty("viewId", control.viewId().toString());
    }
    return encode(messageId, "ui.control", payload);
  }

  private static ClientInboundMessage decodeHello(UUID messageId, JsonObject payload) {
    String protocolVersion = string(payload, "clientProtocolVersion");
    boolean legacy = ClientHandshake.LEGACY_PROTOCOL_VERSION.equals(protocolVersion);
    if (legacy) {
      requireFields(payload, LEGACY_HELLO_FIELDS, "CLIENT_HELLO_INVALID");
    } else if (ClientHandshake.CURRENT_PROTOCOL_VERSION.equals(protocolVersion)) {
      requireFields(payload, CURRENT_HELLO_FIELDS, "CLIENT_HELLO_INVALID");
    } else {
      throw new ClientProtocolException("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    var capabilitiesObject = object(payload, "capabilities");
    requireFields(capabilitiesObject, CAPABILITY_FIELDS, "CLIENT_HELLO_INVALID");
    var versions = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      versions.put(
          feature, integer(capabilitiesObject, feature.wireName(), 0, feature.maximumVersion()));
    }

    var dependencyObject = object(payload, "dependencies");
    requireFields(dependencyObject, DEPENDENCY_FIELDS, "CLIENT_HELLO_INVALID");
    var dependencies = new LinkedHashMap<String, String>();
    for (var name : java.util.List.of("litematica", "malilib")) {
      dependencies.put(name, nullableString(dependencyObject, name));
    }
    ClientLitematicaDiagnostic adapterDiagnostic;
    if (legacy) {
      adapterDiagnostic =
          ClientLitematicaDiagnostic.legacy(
              Optional.ofNullable(dependencies.get("litematica")),
              Optional.ofNullable(dependencies.get("malilib")));
    } else {
      var diagnosticsObject = object(payload, "diagnostics");
      requireFields(diagnosticsObject, DIAGNOSTIC_FIELDS, "CLIENT_HELLO_INVALID");
      var adapterObject = object(diagnosticsObject, "litematicaAdapter");
      requireFields(adapterObject, LITEMATICA_ADAPTER_FIELDS, "CLIENT_HELLO_INVALID");
      adapterDiagnostic =
          new ClientLitematicaDiagnostic(
              ClientLitematicaDiagnostic.Status.fromWireName(string(adapterObject, "status")),
              string(adapterObject, "minecraftVersion"),
              string(adapterObject, "fabricLoaderVersion"),
              Optional.ofNullable(nullableString(adapterObject, "litematicaVersion")),
              Optional.ofNullable(nullableString(adapterObject, "malilibVersion")),
              Optional.ofNullable(nullableString(adapterObject, "adapterId")));
    }
    return new ClientInboundMessage.Hello(
        messageId,
        new ClientHandshake(
            protocolVersion,
            string(payload, "modVersion"),
            new ClientCapabilities(versions),
            dependencies,
            adapterDiagnostic));
  }

  private static ClientInboundMessage decodeAck(UUID messageId, JsonObject payload) {
    requireFields(payload, ACK_FIELDS, "CLIENT_ACK_INVALID");
    return new ClientInboundMessage.Ack(
        messageId,
        uuid(payload, "transferId", false),
        longValue(payload, "generation", 1, Integer.MAX_VALUE),
        ClientInboundMessage.Ack.Status.fromWireName(string(payload, "status")),
        nullableString(payload, "code"));
  }

  private static ClientInboundMessage decodeError(UUID messageId, JsonObject payload) {
    requireFields(payload, ERROR_FIELDS, "CLIENT_ERROR_INVALID");
    return new ClientInboundMessage.Error(
        messageId,
        uuid(payload, "transferId", true),
        longValue(payload, "generation", 1, Integer.MAX_VALUE),
        string(payload, "code"));
  }

  private static JsonObject parse(byte[] bytes) {
    Objects.requireNonNull(bytes);
    if (bytes.length == 0 || bytes.length > MAX_INBOUND_BYTES) {
      throw new ClientProtocolException("CLIENT_MESSAGE_TOO_LARGE");
    }
    try {
      var decoder = StandardCharsets.UTF_8.newDecoder();
      decoder.onMalformedInput(CodingErrorAction.REPORT);
      decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
      var text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
      return StrictJson.parseObject(text, "CLIENT_MESSAGE_INVALID", "client-channel");
    } catch (CharacterCodingException | RuntimeConnectionFailure error) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
  }

  private static byte[] encode(UUID messageId, String type, JsonObject payload) {
    var envelope = new JsonObject();
    envelope.addProperty("clientPayloadVersion", PAYLOAD_VERSION);
    envelope.addProperty("messageId", Objects.requireNonNull(messageId).toString());
    envelope.addProperty("type", type);
    envelope.add("payload", Objects.requireNonNull(payload));
    var bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_OUTBOUND_FRAME_BYTES) {
      throw new ClientProtocolException("CLIENT_OUTBOUND_FRAME_TOO_LARGE");
    }
    return bytes;
  }

  private static JsonObject object(JsonObject parent, String name) {
    var value = parent.get(name);
    if (value == null || !value.isJsonObject()) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
    return value.getAsJsonObject();
  }

  private static String string(JsonObject parent, String name) {
    var value = parent.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
    var result = value.getAsString();
    if (result.chars().anyMatch(character -> character == 0)) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
    return result;
  }

  private static String nullableString(JsonObject parent, String name) {
    var value = parent.get(name);
    if (value == null) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
    if (value.isJsonNull()) {
      return null;
    }
    return string(parent, name);
  }

  private static UUID uuid(JsonObject parent, String name, boolean nullable) {
    var value = parent.get(name);
    if (nullable && value != null && value.isJsonNull()) {
      return null;
    }
    try {
      var source = string(parent, name);
      var parsed = UUID.fromString(source);
      if (!parsed.toString().equals(source)) {
        throw new IllegalArgumentException("UUID is not canonical lowercase");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
  }

  private static int integer(JsonObject parent, String name, int minimum, int maximum) {
    return Math.toIntExact(longValue(parent, name, minimum, maximum));
  }

  private static long longValue(JsonObject parent, String name, long minimum, long maximum) {
    var value = parent.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
    try {
      var number = new BigDecimal(value.getAsString()).longValueExact();
      if (number < minimum || number > maximum) {
        throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
      }
      return number;
    } catch (NumberFormatException | ArithmeticException error) {
      throw new ClientProtocolException("CLIENT_MESSAGE_INVALID");
    }
  }

  private static void requireFields(JsonObject object, Set<String> fields, String code) {
    if (!object.keySet().equals(fields)) {
      throw new ClientProtocolException(code);
    }
  }
}
