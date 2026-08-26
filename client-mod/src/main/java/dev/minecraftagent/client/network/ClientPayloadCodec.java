package dev.minecraftagent.client.network;

import dev.minecraftagent.protocol.ClientChannelContract;
import dev.minecraftagent.protocol.ClientPayloadFields;
import dev.minecraftagent.protocol.ClientPayloadFrames;
import dev.minecraftagent.protocol.ClientPayloadLimits;
import dev.minecraftagent.protocol.ProtocolViolationException;
import dev.minecraftagent.protocol.WireJson;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Strict codec for the client side of the single custom payload channel.
 *
 * <p>The framing grammar, the strict parse budgets and the typed field access live in the shared
 * {@code protocol:jvm} module so the Fabric client and the Paper plugin cannot drift; this class
 * keeps only what is client specific, namely which messages the client accepts and how it builds
 * the ones it sends.
 */
public final class ClientPayloadCodec {
  public static final String PAYLOAD_VERSION = ClientChannelContract.PAYLOAD_VERSION;
  public static final String HELLO_PROTOCOL_VERSION =
      ClientChannelContract.HELLO_PROTOCOL_VERSION_CURRENT;
  public static final int MAX_INBOUND_BYTES = ClientPayloadLimits.MAX_SERVER_TO_CLIENT_FRAME_BYTES;
  public static final int MAX_OUTBOUND_BYTES = ClientPayloadLimits.MAX_CLIENT_TO_SERVER_FRAME_BYTES;

  private static final String FIELD_INVALID = "CLIENT_FRAME_FIELD_INVALID";
  private static final String UUID_INVALID = "CLIENT_FRAME_UUID_INVALID";
  private static final String HASH_INVALID = "CLIENT_FRAME_HASH_INVALID";

  /**
   * The stable codes this client already publishes for the shared grammar, mapped onto the shared
   * failure vocabulary so no wire-observable code is renamed.
   */
  private static final ClientPayloadFrames FRAMES =
      new ClientPayloadFrames(
          new ClientPayloadFrames.Codes(
              "CLIENT_FRAME_TOO_LARGE",
              "CLIENT_FRAME_UTF8_INVALID",
              "CLIENT_FRAME_JSON_INVALID",
              "CLIENT_FRAME_JSON_LIMIT",
              "CLIENT_FRAME_DUPLICATE_FIELD",
              "CLIENT_FRAME_FIELDS_INVALID",
              FIELD_INVALID,
              UUID_INVALID,
              "CLIENT_PROTOCOL_INCOMPATIBLE",
              "CLIENT_OUTBOUND_FRAME_TOO_LARGE"));

  public ClientServerMessage decodeServer(byte[] bytes) {
    try {
      return decodeFrame(bytes);
    } catch (ProtocolViolationException failure) {
      // The shared grammar carries this client's codes; re-surface them as the mod's own
      // rejection type so the presentation session keeps seeing ClientPayloadException.
      throw new ClientPayloadException(failure.code());
    }
  }

  private static ClientServerMessage decodeFrame(byte[] bytes) {
    var frame = FRAMES.decode(bytes, MAX_INBOUND_BYTES);
    return switch (frame.type()) {
      case ClientChannelContract.MESSAGE_SERVER_HELLO ->
          decodeServerHello(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_VIEW_BEGIN ->
          decodeViewBegin(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_VIEW_CHUNK ->
          decodeViewChunk(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_VIEW_CLEAR ->
          decodeViewClear(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_UI_CONTROL ->
          decodeUiControl(frame.messageId(), frame.payload());
      case ClientChannelContract.MESSAGE_CLIENT_HELLO,
          ClientChannelContract.MESSAGE_CLIENT_ACK,
          ClientChannelContract.MESSAGE_CLIENT_ERROR ->
          throw new ClientPayloadException("CLIENT_MESSAGE_DIRECTION_INVALID");
      default -> throw new ClientPayloadException("CLIENT_MESSAGE_TYPE_INVALID");
    };
  }

  public byte[] encodeHello(UUID messageId, ClientHandshakeAdvertisement advertisement) {
    Objects.requireNonNull(advertisement);
    var capabilities = new WireJson.ObjectNode();
    capabilities.putLong(ClientChannelContract.FEATURE_OVERLAY, advertisement.overlay());
    capabilities.putLong(ClientChannelContract.FEATURE_ITEM_ICONS, advertisement.itemIcons());
    capabilities.putLong(ClientChannelContract.FEATURE_RECIPE_VIEW, advertisement.recipeView());
    capabilities.putLong(
        ClientChannelContract.FEATURE_LITEMATICA_PREVIEW, advertisement.litematicaPreview());
    capabilities.putLong(
        ClientChannelContract.FEATURE_LITEMATICA_MATERIAL_LIST,
        advertisement.litematicaMaterialList());

    var dependencies = new WireJson.ObjectNode();
    putNullableString(
        dependencies,
        ClientChannelContract.DEPENDENCY_LITEMATICA,
        advertisement.litematicaVersion().orElse(null));
    putNullableString(
        dependencies,
        ClientChannelContract.DEPENDENCY_MALILIB,
        advertisement.malilibVersion().orElse(null));

    var diagnostic = advertisement.litematicaAdapterDiagnostic();
    var litematicaAdapter = new WireJson.ObjectNode();
    litematicaAdapter.putString("status", diagnostic.status().name());
    litematicaAdapter.putString("minecraftVersion", diagnostic.minecraftVersion());
    litematicaAdapter.putString("fabricLoaderVersion", diagnostic.fabricLoaderVersion());
    putNullableString(
        litematicaAdapter, "litematicaVersion", diagnostic.litematicaVersion().orElse(null));
    putNullableString(
        litematicaAdapter, "malilibVersion", diagnostic.malilibVersion().orElse(null));
    putNullableString(litematicaAdapter, "adapterId", diagnostic.adapterId().orElse(null));
    var diagnostics = new WireJson.ObjectNode();
    diagnostics.put("litematicaAdapter", litematicaAdapter);

    var payload = new WireJson.ObjectNode();
    payload.putString("clientProtocolVersion", HELLO_PROTOCOL_VERSION);
    payload.putString("modVersion", advertisement.modVersion());
    payload.put("capabilities", capabilities);
    payload.put("dependencies", dependencies);
    payload.put("diagnostics", diagnostics);
    return encode(messageId, ClientChannelContract.MESSAGE_CLIENT_HELLO, payload);
  }

  public byte[] encodeAck(
      UUID messageId, long generation, UUID transferId, boolean displayed, String code) {
    requireGeneration(generation);
    requireCode(code);
    var payload = new WireJson.ObjectNode();
    payload.putLong("generation", generation);
    payload.putString("transferId", Objects.requireNonNull(transferId).toString());
    payload.putString(
        "status",
        displayed
            ? ClientChannelContract.ACK_STATUS_DISPLAYED
            : ClientChannelContract.ACK_STATUS_REJECTED);
    payload.putString("code", code);
    return encode(messageId, ClientChannelContract.MESSAGE_CLIENT_ACK, payload);
  }

  public byte[] encodeError(UUID messageId, long generation, UUID transferId, String code) {
    requireGeneration(generation);
    requireCode(code);
    var payload = new WireJson.ObjectNode();
    payload.putLong("generation", generation);
    putNullableString(payload, "transferId", transferId == null ? null : transferId.toString());
    payload.putString("code", code);
    return encode(messageId, ClientChannelContract.MESSAGE_CLIENT_ERROR, payload);
  }

  private static ClientServerMessage decodeServerHello(
      UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.SERVER_HELLO_FIELDS, "CLIENT_FRAME_FIELDS_INVALID");
    long generation =
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID);
    boolean accepted = ClientPayloadFields.bool(payload, "accepted", FIELD_INVALID);
    String viewSchemaVersion =
        ClientPayloadFields.nullableString(payload, "viewSchemaVersion", 1, 3, FIELD_INVALID);
    if ((accepted && !ClientChannelContract.VIEW_SCHEMA_VERSION.equals(viewSchemaVersion))
        || (!accepted && viewSchemaVersion != null)) {
      throw new ClientPayloadException("CLIENT_SERVER_HELLO_INVALID");
    }
    return new ClientServerMessage.ServerHello(messageId, generation, accepted, viewSchemaVersion);
  }

  private static ClientServerMessage decodeViewBegin(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.VIEW_BEGIN_FIELDS, "CLIENT_FRAME_FIELDS_INVALID");
    long generation =
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID);
    int compressedBytes =
        ClientPayloadFields.integer(
            payload, "compressedBytes", 1, ClientPayloadLimits.MAX_TRANSFER_BYTES, FIELD_INVALID);
    int uncompressedBytes =
        ClientPayloadFields.integer(
            payload, "uncompressedBytes", 1, ClientPayloadLimits.MAX_TRANSFER_BYTES, FIELD_INVALID);
    int chunkCount =
        ClientPayloadFields.integer(
            payload,
            "chunkCount",
            ClientPayloadLimits.CHUNK_COUNT_MIN,
            ClientPayloadLimits.CHUNK_COUNT_MAX,
            FIELD_INVALID);
    if (chunkCount != ClientPayloadLimits.expectedChunkCount(compressedBytes)) {
      throw new ClientPayloadException("CLIENT_TRANSFER_DESCRIPTOR_INVALID");
    }
    ClientServerMessage.Encoding encoding =
        switch (ClientPayloadFields.string(payload, "encoding", 1, 8, FIELD_INVALID)) {
          case ClientChannelContract.VIEW_ENCODING_IDENTITY ->
              ClientServerMessage.Encoding.IDENTITY;
          case ClientChannelContract.VIEW_ENCODING_GZIP -> ClientServerMessage.Encoding.GZIP;
          default -> throw new ClientPayloadException("CLIENT_TRANSFER_ENCODING_INVALID");
        };
    if (encoding == ClientServerMessage.Encoding.IDENTITY && compressedBytes != uncompressedBytes) {
      throw new ClientPayloadException("CLIENT_TRANSFER_DESCRIPTOR_INVALID");
    }
    ClientServerMessage.Mode mode =
        switch (ClientPayloadFields.string(payload, "mode", 1, 8, FIELD_INVALID)) {
          case ClientChannelContract.VIEW_MODE_SHOW -> ClientServerMessage.Mode.SHOW;
          case ClientChannelContract.VIEW_MODE_UPDATE -> ClientServerMessage.Mode.UPDATE;
          default -> throw new ClientPayloadException("CLIENT_VIEW_MODE_INVALID");
        };
    return new ClientServerMessage.ViewBegin(
        messageId,
        generation,
        ClientPayloadFields.uuid(payload, "transferId", false, FIELD_INVALID, UUID_INVALID),
        ClientPayloadFields.uuid(payload, "viewId", false, FIELD_INVALID, UUID_INVALID),
        ClientPayloadFields.uuid(payload, "requestId", false, FIELD_INVALID, UUID_INVALID),
        ClientPayloadFields.integer(
            payload,
            "revision",
            ClientPayloadLimits.REVISION_MIN,
            ClientPayloadLimits.REVISION_MAX,
            FIELD_INVALID),
        mode,
        encoding,
        compressedBytes,
        uncompressedBytes,
        chunkCount,
        hash(payload, "contentSha256"));
  }

  private static ClientServerMessage decodeViewChunk(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.VIEW_CHUNK_FIELDS, "CLIENT_FRAME_FIELDS_INVALID");
    int byteLength =
        ClientPayloadFields.integer(
            payload,
            "byteLength",
            ClientPayloadLimits.CHUNK_BYTES_MIN,
            ClientPayloadLimits.MAX_CHUNK_BYTES,
            FIELD_INVALID);
    String encoded =
        ClientPayloadFields.string(
            payload,
            "data",
            ClientPayloadLimits.CHUNK_BASE64_CHARS_MIN,
            ClientPayloadLimits.MAX_CHUNK_BASE64_CHARS,
            FIELD_INVALID);
    byte[] data;
    try {
      data = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new ClientPayloadException("CLIENT_CHUNK_BASE64_INVALID");
    }
    if (data.length != byteLength || !Base64.getEncoder().encodeToString(data).equals(encoded)) {
      throw new ClientPayloadException("CLIENT_CHUNK_BASE64_INVALID");
    }
    String contentHash = hash(payload, "sha256");
    if (!contentHash.equals(sha256(data))) {
      throw new ClientPayloadException("CLIENT_CHUNK_HASH_MISMATCH");
    }
    return new ClientServerMessage.ViewChunk(
        messageId,
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID),
        ClientPayloadFields.uuid(payload, "transferId", false, FIELD_INVALID, UUID_INVALID),
        ClientPayloadFields.integer(
            payload,
            "index",
            ClientPayloadLimits.CHUNK_INDEX_MIN,
            ClientPayloadLimits.CHUNK_INDEX_MAX,
            FIELD_INVALID),
        byteLength,
        contentHash,
        data);
  }

  private static ClientServerMessage decodeViewClear(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.VIEW_CLEAR_FIELDS, "CLIENT_FRAME_FIELDS_INVALID");
    return new ClientServerMessage.ViewClear(
        messageId,
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID),
        ClientPayloadFields.uuid(payload, "viewId", true, FIELD_INVALID, UUID_INVALID));
  }

  private static ClientServerMessage decodeUiControl(UUID messageId, WireJson.ObjectNode payload) {
    ClientPayloadFields.requireFields(
        payload, ClientChannelContract.UI_CONTROL_FIELDS, "CLIENT_FRAME_FIELDS_INVALID");
    ClientServerMessage.Action action =
        switch (ClientPayloadFields.string(payload, "action", 1, 64, FIELD_INVALID)) {
          case ClientChannelContract.UI_ACTION_PIN -> ClientServerMessage.Action.PIN;
          case ClientChannelContract.UI_ACTION_UNPIN -> ClientServerMessage.Action.UNPIN;
          case ClientChannelContract.UI_ACTION_CLEAR -> ClientServerMessage.Action.CLEAR;
          case ClientChannelContract.UI_ACTION_LITEMATICA_PREVIEW_LOAD ->
              ClientServerMessage.Action.LITEMATICA_PREVIEW_LOAD;
          case ClientChannelContract.UI_ACTION_LITEMATICA_PREVIEW_REMOVE ->
              ClientServerMessage.Action.LITEMATICA_PREVIEW_REMOVE;
          case ClientChannelContract.UI_ACTION_LITEMATICA_MATERIAL_LIST_OPEN ->
              ClientServerMessage.Action.LITEMATICA_MATERIAL_LIST_OPEN;
          default -> throw new ClientPayloadException("CLIENT_UI_ACTION_INVALID");
        };
    return new ClientServerMessage.UiControl(
        messageId,
        ClientPayloadFields.longValue(
            payload,
            "generation",
            ClientPayloadLimits.GENERATION_MIN,
            ClientPayloadLimits.GENERATION_MAX,
            FIELD_INVALID),
        action,
        ClientPayloadFields.uuid(payload, "viewId", true, FIELD_INVALID, UUID_INVALID));
  }

  private static byte[] encode(UUID messageId, String type, WireJson.ObjectNode payload) {
    try {
      return FRAMES.encode(messageId, type, payload, MAX_OUTBOUND_BYTES);
    } catch (ProtocolViolationException failure) {
      throw new ClientPayloadException(failure.code());
    }
  }

  private static String hash(WireJson.ObjectNode payload, String name) {
    String value =
        ClientPayloadFields.string(
            payload, name, 1, ClientPayloadLimits.SHA256_CHARS, FIELD_INVALID);
    if (!ClientPayloadLimits.SHA256.matcher(value).matches()) {
      throw new ClientPayloadException(HASH_INVALID);
    }
    return value;
  }

  private static void requireGeneration(long generation) {
    if (generation < ClientPayloadLimits.GENERATION_MIN
        || generation > ClientPayloadLimits.GENERATION_MAX) {
      throw new IllegalArgumentException("Invalid client connection generation");
    }
  }

  private static void requireCode(String code) {
    if (code == null || !ClientPayloadLimits.STABLE_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Invalid client status code");
    }
  }

  private static void putNullableString(WireJson.ObjectNode object, String name, String value) {
    if (value == null) {
      object.putNull(name);
    } else {
      object.putString(name, value);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
