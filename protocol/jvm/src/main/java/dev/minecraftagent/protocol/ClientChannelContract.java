package dev.minecraftagent.protocol;

import java.util.Set;

/**
 * The string vocabulary of the {@code minecraftagent:client} custom payload channel.
 *
 * <p>Every literal is defined once here and consumed by both the Paper plugin and the Fabric client
 * mod. The authoritative grammar is {@code protocol/schemas/client-payload.schema.json}; the
 * numeric bounds that go with it live in {@link ClientPayloadLimits}.
 */
public final class ClientChannelContract {
  /** The single Bukkit/Fabric channel both ends register. */
  public static final String CHANNEL = "minecraftagent:client";

  /** Namespace half of {@link #CHANNEL}, for APIs that want the pieces separately. */
  public static final String CHANNEL_NAMESPACE = "minecraftagent";

  /** Path half of {@link #CHANNEL}, for APIs that want the pieces separately. */
  public static final String CHANNEL_PATH = "client";

  /** Envelope version declared by every frame. */
  public static final String PAYLOAD_VERSION = "1.0";

  /** Hello protocol version spoken by clients that cannot report diagnostics. */
  public static final String HELLO_PROTOCOL_VERSION_LEGACY = "1.0";

  /** Hello protocol version spoken by clients that report diagnostics. */
  public static final String HELLO_PROTOCOL_VERSION_CURRENT = "1.1";

  /** Structured view schema version negotiated by {@code server.hello}. */
  public static final String VIEW_SCHEMA_VERSION = "1.0";

  // Message types, in client-payload.schema.json enum order.
  public static final String MESSAGE_CLIENT_HELLO = "client.hello";
  public static final String MESSAGE_SERVER_HELLO = "server.hello";
  public static final String MESSAGE_VIEW_BEGIN = "view.begin";
  public static final String MESSAGE_VIEW_CHUNK = "view.chunk";
  public static final String MESSAGE_VIEW_CLEAR = "view.clear";
  public static final String MESSAGE_UI_CONTROL = "ui.control";
  public static final String MESSAGE_CLIENT_ACK = "client.ack";
  public static final String MESSAGE_CLIENT_ERROR = "client.error";

  /** Every message type the channel can carry. */
  public static final Set<String> MESSAGE_TYPES =
      Set.of(
          MESSAGE_CLIENT_HELLO,
          MESSAGE_SERVER_HELLO,
          MESSAGE_VIEW_BEGIN,
          MESSAGE_VIEW_CHUNK,
          MESSAGE_VIEW_CLEAR,
          MESSAGE_UI_CONTROL,
          MESSAGE_CLIENT_ACK,
          MESSAGE_CLIENT_ERROR);

  /** Message types only the client may send. */
  public static final Set<String> CLIENT_TO_SERVER_TYPES =
      Set.of(MESSAGE_CLIENT_HELLO, MESSAGE_CLIENT_ACK, MESSAGE_CLIENT_ERROR);

  /** Message types only the server may send. */
  public static final Set<String> SERVER_TO_CLIENT_TYPES =
      Set.of(
          MESSAGE_SERVER_HELLO,
          MESSAGE_VIEW_BEGIN,
          MESSAGE_VIEW_CHUNK,
          MESSAGE_VIEW_CLEAR,
          MESSAGE_UI_CONTROL);

  // Wire names inside payloads.
  public static final String VIEW_MODE_SHOW = "show";
  public static final String VIEW_MODE_UPDATE = "update";
  public static final String VIEW_ENCODING_IDENTITY = "identity";
  public static final String VIEW_ENCODING_GZIP = "gzip";
  public static final String UI_ACTION_PIN = "pin";
  public static final String UI_ACTION_UNPIN = "unpin";
  public static final String UI_ACTION_CLEAR = "clear";
  public static final String UI_ACTION_LITEMATICA_PREVIEW_LOAD = "litematica.preview.load";
  public static final String UI_ACTION_LITEMATICA_PREVIEW_REMOVE = "litematica.preview.remove";
  public static final String UI_ACTION_LITEMATICA_MATERIAL_LIST_OPEN =
      "litematica.material_list.open";
  public static final String ACK_STATUS_DISPLAYED = "DISPLAYED";
  public static final String ACK_STATUS_REJECTED = "REJECTED";

  /** {@code view.begin} mode values. */
  public static final Set<String> VIEW_MODES = Set.of(VIEW_MODE_SHOW, VIEW_MODE_UPDATE);

  /** {@code view.begin} encoding values. */
  public static final Set<String> VIEW_ENCODINGS =
      Set.of(VIEW_ENCODING_IDENTITY, VIEW_ENCODING_GZIP);

  /** {@code ui.control} action values. */
  public static final Set<String> UI_ACTIONS =
      Set.of(
          UI_ACTION_PIN,
          UI_ACTION_UNPIN,
          UI_ACTION_CLEAR,
          UI_ACTION_LITEMATICA_PREVIEW_LOAD,
          UI_ACTION_LITEMATICA_PREVIEW_REMOVE,
          UI_ACTION_LITEMATICA_MATERIAL_LIST_OPEN);

  /** {@code client.ack} status values. */
  public static final Set<String> ACK_STATUSES = Set.of(ACK_STATUS_DISPLAYED, ACK_STATUS_REJECTED);

  // Client feature capability wire names.
  public static final String FEATURE_OVERLAY = "overlay";
  public static final String FEATURE_ITEM_ICONS = "itemIcons";
  public static final String FEATURE_RECIPE_VIEW = "recipeView";
  public static final String FEATURE_LITEMATICA_PREVIEW = "litematicaPreview";
  public static final String FEATURE_LITEMATICA_MATERIAL_LIST = "litematicaMaterialList";

  /** Capability object fields, and therefore the feature wire names. */
  public static final Set<String> CAPABILITY_FIELDS =
      Set.of(
          FEATURE_OVERLAY,
          FEATURE_ITEM_ICONS,
          FEATURE_RECIPE_VIEW,
          FEATURE_LITEMATICA_PREVIEW,
          FEATURE_LITEMATICA_MATERIAL_LIST);

  /** Mod dependencies the client reports versions for. */
  public static final String DEPENDENCY_LITEMATICA = "litematica";

  /** Mod dependencies the client reports versions for. */
  public static final String DEPENDENCY_MALILIB = "malilib";

  /** Dependency object fields. */
  public static final Set<String> DEPENDENCY_FIELDS =
      Set.of(DEPENDENCY_LITEMATICA, DEPENDENCY_MALILIB);

  // Exact field sets. Every payload is closed: the decoded key set must equal the expected set.
  public static final Set<String> ENVELOPE_FIELDS =
      Set.of("clientPayloadVersion", "messageId", "type", "payload");
  public static final Set<String> LEGACY_HELLO_FIELDS =
      Set.of("clientProtocolVersion", "modVersion", "capabilities", "dependencies");
  public static final Set<String> CURRENT_HELLO_FIELDS =
      Set.of("clientProtocolVersion", "modVersion", "capabilities", "dependencies", "diagnostics");
  public static final Set<String> DIAGNOSTIC_FIELDS = Set.of("litematicaAdapter");
  public static final Set<String> LITEMATICA_ADAPTER_FIELDS =
      Set.of(
          "status",
          "minecraftVersion",
          "fabricLoaderVersion",
          "litematicaVersion",
          "malilibVersion",
          "adapterId");
  public static final Set<String> SERVER_HELLO_FIELDS =
      Set.of("generation", "accepted", "viewSchemaVersion");
  public static final Set<String> VIEW_BEGIN_FIELDS =
      Set.of(
          "generation",
          "transferId",
          "viewId",
          "requestId",
          "revision",
          "mode",
          "encoding",
          "compressedBytes",
          "uncompressedBytes",
          "chunkCount",
          "contentSha256");
  public static final Set<String> VIEW_CHUNK_FIELDS =
      Set.of("generation", "transferId", "index", "byteLength", "sha256", "data");
  public static final Set<String> VIEW_CLEAR_FIELDS = Set.of("generation", "viewId");
  public static final Set<String> UI_CONTROL_FIELDS = Set.of("generation", "action", "viewId");
  public static final Set<String> CLIENT_ACK_FIELDS =
      Set.of("transferId", "generation", "status", "code");
  public static final Set<String> CLIENT_ERROR_FIELDS = Set.of("transferId", "generation", "code");

  private ClientChannelContract() {}
}
