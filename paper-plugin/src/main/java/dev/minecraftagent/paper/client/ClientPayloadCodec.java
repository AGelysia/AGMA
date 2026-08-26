package dev.minecraftagent.paper.client;

import dev.minecraftagent.paper.client.ClientTransferManager.TransferChunk;
import dev.minecraftagent.paper.client.ClientTransferManager.TransferPlan;
import dev.minecraftagent.protocol.ClientChannelContract;
import dev.minecraftagent.protocol.ClientPayloadFields;
import dev.minecraftagent.protocol.ClientPayloadFrames;
import dev.minecraftagent.protocol.ClientPayloadLimits;
import dev.minecraftagent.protocol.ProtocolViolationException;
import dev.minecraftagent.protocol.WireJson;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Strict raw-UTF-8 JSON codec for the single Bukkit/Fabric custom payload channel.
 *
 * <p>The framing grammar, the strict parse budgets and the typed field access live in the shared
 * {@code protocol:jvm} module so the Paper plugin and the Fabric client mod cannot drift; this
 * class keeps only what is server specific, namely which messages the server accepts and how it
 * builds the ones it sends.
 */
public final class ClientPayloadCodec {
  public static final String CHANNEL = ClientChannelContract.CHANNEL;
  public static final String PAYLOAD_VERSION = ClientChannelContract.PAYLOAD_VERSION;
  public static final int MAX_INBOUND_BYTES = ClientPayloadLimits.MAX_CLIENT_TO_SERVER_FRAME_BYTES;
  public static final int MAX_OUTBOUND_FRAME_BYTES =
      ClientPayloadLimits.MAX_SERVER_TO_CLIENT_FRAME_BYTES;

  private static final String FIELD_INVALID = "CLIENT_MESSAGE_INVALID";

  /**
   * The stable codes the server already publishes for the shared grammar, mapped onto the shared
   * failure vocabulary so no wire-observable code is renamed.
   */
  private static final ClientPayloadFrames FRAMES =
      new ClientPayloadFrames(
          new ClientPayloadFrames.Codes(
              "CLIENT_MESSAGE_TOO_LARGE",
              FIELD_INVALID,
              FIELD_INVALID,
              FIELD_INVALID,
              FIELD_INVALID,
              "CLIENT_ENVELOPE_INVALID",
              FIELD_INVALID,
              FIELD_INVALID,
              "CLIENT_PROTOCOL_INCOMPATIBLE",
              "CLIENT_OUTBOUND_FRAME_TOO_LARGE"));

  public ClientInboundMessage decodeInbound(byte[] bytes) {
    try {
      return decodeFrame(bytes);
    } catch (ProtocolViolationException failure) {
      // The shared grammar carries this server's codes; re-surface them as the plugin's own
      // rejection type so channel listeners keep seeing ClientProtocolException.
      throw new ClientProtocolException(failure.code());
    }
  }

  private static ClientInboundMessage decodeFrame(byte[] bytes) {
    var frame = FRAMES.decode(bytes, MAX_INBOUND_BYTES);
    return switch (frame.type()) {
      case ClientChannelContract.MESSAGE_CLIENT_HELLO ->
          decodeHello(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_CLIENT_ACK ->
          decodeAck(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_CLIENT_ERROR ->
          decodeError(frame.messageId(), frame.payload());
      default -> throw new ClientProtocolException("CLIENT_MESSAGE_DIRECTION_INVALID");
    };
  }

  public byte[] encodeServerHello(UUID messageId, long generation) {
    return encodeServerHello(messageId, generation, true);
  }

  public byte[] encodeServerHello(UUID messageId, long generation, boolean accepted) {
    if (generation < ClientPayloadLimits.GENERATION_MIN
        || generation > ClientPayloadLimits.GENERATION_MAX) {
      throw new IllegalArgumentException("generation must be positive");
    }
    var payload = new WireJson.ObjectNode();
    payload.putLong("generation", generation);
    payload.putBoolean("accepted", accepted);
    if (accepted) {
      payload.putString("viewSchemaVersion", ClientViewSchemaRegistry.VIEW_SCHEMA_V1);
    } else {
      payload.putNull("viewSchemaVersion");
    }
    return FRAMES.encode(
        messageId, ClientChannelContract.MESSAGE_SERVER_HELLO, payload, MAX_OUTBOUND_FRAME_BYTES);
  }

  public byte[] encodeViewBegin(UUID messageId, TransferPlan plan) {
    Objects.requireNonNull(plan);
    var payload = new WireJson.ObjectNode();
    payload.putString("transferId", plan.transferId().toString());
    payload.putLong("generation", plan.generation());
    payload.putString("viewId", plan.viewId().toString());
    payload.putString("requestId", plan.requestId().toString());
    payload.putLong("revision", plan.revision());
    payload.putString("mode", plan.mode().wireName());
    payload.putString("encoding", plan.encoding().wireName());
    payload.putLong("compressedBytes", plan.compressedBytes());
    payload.putLong("uncompressedBytes", plan.uncompressedBytes());
    payload.putLong("chunkCount", plan.chunkCount());
    payload.putString("contentSha256", plan.contentSha256());
    return FRAMES.encode(
        messageId, ClientChannelContract.MESSAGE_VIEW_BEGIN, payload, MAX_OUTBOUND_FRAME_BYTES);
  }

  public byte[] encodeViewChunk(UUID messageId, long generation, TransferChunk chunk) {
    Objects.requireNonNull(chunk);
    if (generation < ClientPayloadLimits.GENERATION_MIN
        || generation > ClientPayloadLimits.GENERATION_MAX
        || chunk.bytes().length > ClientPayloadLimits.MAX_CHUNK_BYTES
        || chunk.bytes().length < ClientPayloadLimits.CHUNK_BYTES_MIN) {
      throw new ClientProtocolException("CLIENT_TRANSFER_CHUNK_INVALID");
    }
    var payload = new WireJson.ObjectNode();
    payload.putString("transferId", chunk.transferId().toString());
    payload.putLong("generation", generation);
    payload.putLong("index", chunk.index());
    payload.putLong("byteLength", chunk.bytes().length);
    payload.putString("sha256", chunk.sha256());
    payload.putString("data", Base64.getEncoder().encodeToString(chunk.bytes()));
    return FRAMES.encode(
        messageId, ClientChannelContract.MESSAGE_VIEW_CHUNK, payload, MAX_OUTBOUND_FRAME_BYTES);
  }

  public byte[] encodeViewClear(UUID messageId, long generation, UUID viewId) {
    if (generation < ClientPayloadLimits.GENERATION_MIN
        || generation > ClientPayloadLimits.GENERATION_MAX) {
      throw new IllegalArgumentException("generation must be positive");
    }
    var payload = new WireJson.ObjectNode();
    payload.putLong("generation", generation);
    if (viewId == null) {
      payload.putNull("viewId");
    } else {
      payload.putString("viewId", viewId.toString());
    }
    return FRAMES.encode(
        messageId, ClientChannelContract.MESSAGE_VIEW_CLEAR, payload, MAX_OUTBOUND_FRAME_BYTES);
  }

  public byte[] encodeUiControl(UUID messageId, ClientUiCommandGateway.Control control) {
    Objects.requireNonNull(control);
    var payload = new WireJson.ObjectNode();
    payload.putLong("generation", control.generation());
    payload.putString("action", control.action().wireName());
    if (control.viewId() == null) {
      payload.putNull("viewId");
    } else {
      payload.putString("viewId", control.viewId().toString());
    }
    return FRAMES.encode(
        messageId, ClientChannelContract.MESSAGE_UI_CONTROL, payload, MAX_OUTBOUND_FRAME_BYTES);
  }

  private static ClientInboundMessage decodeHello(UUID messageId, WireJson.ObjectNode payload) {
    var protocolVersion = string(payload, "clientProtocolVersion");
    boolean legacy = ClientHandshake.LEGACY_PROTOCOL_VERSION.equals(protocolVersion);
    if (legacy) {
      ClientPayloadFields.requireFields(
          payload, ClientChannelContract.LEGACY_HELLO_FIELDS, "CLIENT_HELLO_INVALID");
    } else if (ClientHandshake.CURRENT_PROTOCOL_VERSION.equals(protocolVersion)) {
      ClientPayloadFields.requireFields(
          payload, ClientChannelContract.CURRENT_HELLO_FIELDS, "CLIENT_HELLO_INVALID");
    } else {
      throw new ClientProtocolException("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    var capabilitiesObject = ClientPayloadFields.object(payload, "capabilities", FIELD_INVALID);
    ClientPayloadFields.requireFields(
        capabilitiesObject, ClientChannelContract.CAPABILITY_FIELDS, "CLIENT_HELLO_INVALID");
    var versions = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      versions.put(
          feature,
          ClientPayloadFields.integer(
              capabilitiesObject, feature.wireName(), 0, feature.maximumVersion(), FIELD_INVALID));
    }

    var dependencyObject = ClientPayloadFields.object(payload, "dependencies", FIELD_INVALID);
    ClientPayloadFields.requireFields(
        dependencyObject, ClientChannelContract.DEPENDENCY_FIELDS, "CLIENT_HELLO_INVALID");
    var dependencies = new LinkedHashMap<String, String>();
    for (var name :
        List.of(
            ClientChannelContract.DEPENDENCY_LITEMATICA,
            ClientChannelContract.DEPENDENCY_MALILIB)) {
      dependencies.put(
          name, ClientPayloadFields.nullableString(dependencyObject, name, 1, 64, FIELD_INVALID));
    }
    ClientLitematicaDiagnostic adapterDiagnostic;
    if (legacy) {
      adapterDiagnostic =
          ClientLitematicaDiagnostic.legacy(
              Optional.ofNullable(dependencies.get(ClientChannelContract.DEPENDENCY_LITEMATICA)),
              Optional.ofNullable(dependencies.get(ClientChannelContract.DEPENDENCY_MALILIB)));
    } else {
      var diagnosticsObject = ClientPayloadFields.object(payload, "diagnostics", FIELD_INVALID);
      ClientPayloadFields.requireFields(
          diagnosticsObject, ClientChannelContract.DIAGNOSTIC_FIELDS, "CLIENT_HELLO_INVALID");
      var adapterObject =
          ClientPayloadFields.object(diagnosticsObject, "litematicaAdapter", FIELD_INVALID);
      ClientPayloadFields.requireFields(
          adapterObject, ClientChannelContract.LITEMATICA_ADAPTER_FIELDS, "CLIENT_HELLO_INVALID");
      adapterDiagnostic =
          new ClientLitematicaDiagnostic(
              ClientLitematicaDiagnostic.Status.fromWireName(string(adapterObject, "status")),
              string(adapterObject, "minecraftVersion"),
              string(adapterObject, "fabricLoaderVersion"),
              Optional.ofNullable(
                  ClientPayloadFields.nullableString(
                      adapterObject, "litematicaVersion", 1, 64, FIELD_INVALID)),
              Optional.ofNullable(
                  ClientPayloadFields.nullableString(
                      adapterObject, "malilibVersion", 1, 64, FIELD_INVALID)),
              Optional.ofNullable(
                  ClientPayloadFields.nullableString(
                      adapterObject, "adapterId", 1, 64, FIELD_INVALID)));
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

  private static ClientInboundMessage decodeAck(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.CLIENT_ACK_FIELDS, "CLIENT_ACK_INVALID");
    return new ClientInboundMessage.Ack(
        messageId,
        ClientPayloadFields.uuid(payload, "transferId", false, FIELD_INVALID),
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID),
        ClientInboundMessage.Ack.Status.fromWireName(string(payload, "status")),
        ClientPayloadFields.nullableString(payload, "code", 1, 64, FIELD_INVALID));
  }

  private static ClientInboundMessage decodeError(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.CLIENT_ERROR_FIELDS, "CLIENT_ERROR_INVALID");
    return new ClientInboundMessage.Error(
        messageId,
        ClientPayloadFields.uuid(payload, "transferId", true, FIELD_INVALID),
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID),
        string(payload, "code"));
  }

  private static String string(WireJson.ObjectNode parent, String name) {
    return ClientPayloadFields.string(parent, name, 1, 64, FIELD_INVALID);
  }
}
