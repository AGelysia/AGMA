package dev.minecraftagent.client.view;

import dev.minecraftagent.protocol.StructuredViewContract;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Decodes a structured view payload into the client-owned render models.
 *
 * <p>The envelope (identity, revision, view type) is decoded here and the content is delegated to
 * the per-view-type decoders; the document itself is parsed by {@link StrictJsonParser} so no
 * unbounded Gson object graph is ever materialized.
 */
public final class StructuredViewDecoder {
  public static final int MAX_PAYLOAD_BYTES = StrictJsonParser.MAX_PAYLOAD_BYTES;
  public static final int MAX_JSON_DEPTH = StrictJsonParser.MAX_JSON_DEPTH;
  public static final int MAX_JSON_NODES = StrictJsonParser.MAX_JSON_NODES;
  public static final int MAX_TOTAL_STRING_CHARS = StrictJsonParser.MAX_TOTAL_STRING_CHARS;
  public static final int MAX_ITEM_STACKS = ItemStackDecoder.MAX_ITEM_STACKS;

  public static final int MAX_BUILD_COMPRESSED_BYTES =
      BuildPreviewDecoder.MAX_BUILD_COMPRESSED_BYTES;
  public static final int MAX_BUILD_UNCOMPRESSED_BYTES =
      BuildPreviewDecoder.MAX_BUILD_UNCOMPRESSED_BYTES;
  public static final int MAX_BUILD_BLOCKS = BuildPreviewDecoder.MAX_BUILD_BLOCKS;
  public static final int MAX_BUILD_PALETTE = BuildPreviewDecoder.MAX_BUILD_PALETTE;
  public static final int MAX_BUILD_AXIS = BuildPreviewDecoder.MAX_BUILD_AXIS;
  public static final int MAX_BUILD_VOLUME = BuildPreviewDecoder.MAX_BUILD_VOLUME;
  public static final int MAX_BUILD_CHANGES = BuildPreviewDecoder.MAX_BUILD_CHANGES;

  private final BuildPreviewDecoder buildPreviewDecoder;

  public StructuredViewDecoder() {
    this(new MinecraftBlockStateResolver());
  }

  public StructuredViewDecoder(BuildPreviewBlockStateResolver blockStateResolver) {
    Objects.requireNonNull(blockStateResolver, "blockStateResolver");
    this.buildPreviewDecoder = new BuildPreviewDecoder(blockStateResolver);
  }

  public StructuredView decode(byte[] payload) throws ViewDecodeException {
    if (payload == null || payload.length == 0) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    }
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new ViewDecodeException(ViewDecodeException.Code.PAYLOAD_TOO_LARGE);
    }

    String json = StrictJsonParser.decodeUtf8(payload);
    JsonNode root = StrictJsonParser.parse(json);
    ItemStackDecoder.DecodeBudget budget = new ItemStackDecoder.DecodeBudget();
    return decodeView(root, budget);
  }

  /** Decodes and binds a body to the authenticated transfer descriptor. */
  public StructuredView decode(
      byte[] payload, UUID expectedViewId, UUID expectedRequestId, int expectedRevision)
      throws ViewDecodeException {
    Objects.requireNonNull(expectedViewId, "expectedViewId");
    Objects.requireNonNull(expectedRequestId, "expectedRequestId");
    StructuredView view = decode(payload);
    if (!view.viewId().equals(expectedViewId)
        || !view.requestId().equals(expectedRequestId)
        || view.revision() != expectedRevision) {
      throw new ViewDecodeException(ViewDecodeException.Code.METADATA_MISMATCH);
    }
    return view;
  }

  private StructuredView decodeView(JsonNode node, ItemStackDecoder.DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node,
            Set.of(
                "viewSchemaVersion",
                "viewId",
                "requestId",
                "viewType",
                "revision",
                "title",
                "fallbackText",
                "pinnable",
                "content"),
            Set.of(
                "viewSchemaVersion",
                "viewId",
                "requestId",
                "viewType",
                "revision",
                "title",
                "fallbackText",
                "pinnable",
                "content"));
    String schemaVersion = JsonValues.string(object, "viewSchemaVersion", 3, 3, false);
    if (!StructuredViewContract.VIEW_SCHEMA_VERSION.equals(schemaVersion)) {
      JsonValues.invalidValue();
    }
    UUID viewId = JsonValues.uuid(JsonValues.string(object, "viewId", 36, 36, false));
    UUID requestId = JsonValues.uuid(JsonValues.string(object, "requestId", 36, 36, false));
    String wireType = JsonValues.string(object, "viewType", 1, 32, false);
    ViewType viewType;
    try {
      viewType = ViewType.fromWireName(wireType);
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.UNSUPPORTED_VIEW_TYPE, exception);
    }
    int revision = JsonValues.integer(object.fields().get("revision"), 1, Integer.MAX_VALUE);
    String title = JsonValues.string(object, "title", 1, 128, false);
    String fallback = JsonValues.string(object, "fallbackText", 1, 8192, true);
    boolean pinnable = JsonValues.bool(object.fields().get("pinnable"));
    ViewContent content =
        switch (viewType) {
          case TEXT -> decodeText(object.fields().get("content"));
          case ITEM_STACK ->
              ItemStackDecoder.decodeItemStack(object.fields().get("content"), budget);
          case ITEM_LIST -> ItemStackDecoder.decodeItemList(object.fields().get("content"), budget);
          case RECIPE -> RecipeViewDecoder.decodeRecipeView(object.fields().get("content"), budget);
          case BUILD_PREVIEW ->
              buildPreviewDecoder.decodeBuildPreview(
                  object.fields().get("content"), viewId, revision);
        };
    return new StructuredView(
        schemaVersion, viewId, requestId, viewType, revision, title, fallback, pinnable, content);
  }

  private static TextView decodeText(JsonNode node) throws ViewDecodeException {
    JsonObject object = JsonValues.closedObject(node, Set.of("text"), Set.of("text"));
    return new TextView(JsonValues.string(object, "text", 1, 32768, true));
  }
}
