package dev.minecraftagent.client.view;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.minecraftagent.client.transfer.StrictGzipDecoder;
import dev.minecraftagent.client.view.BuildPreviewView.Bounds;
import dev.minecraftagent.client.view.BuildPreviewView.Difference;
import dev.minecraftagent.client.view.BuildPreviewView.Mirror;
import dev.minecraftagent.client.view.BuildPreviewView.Operation;
import dev.minecraftagent.client.view.BuildPreviewView.PaletteEntry;
import dev.minecraftagent.client.view.BuildPreviewView.PlacedBlock;
import dev.minecraftagent.client.view.BuildPreviewView.Position;
import dev.minecraftagent.client.view.BuildPreviewView.Transform;
import dev.minecraftagent.client.view.ItemStackView.SafeComponents;
import dev.minecraftagent.client.view.RecipeView.ChoiceType;
import dev.minecraftagent.client.view.RecipeView.GridLayout;
import dev.minecraftagent.client.view.RecipeView.IngredientChoice;
import dev.minecraftagent.client.view.RecipeView.IngredientSlot;
import dev.minecraftagent.client.view.RecipeView.Layout;
import dev.minecraftagent.client.view.RecipeView.Processing;
import dev.minecraftagent.client.view.RecipeView.Query;
import dev.minecraftagent.client.view.RecipeView.QueryMode;
import dev.minecraftagent.client.view.RecipeView.Recipe;
import dev.minecraftagent.client.view.RecipeView.RecipeType;
import dev.minecraftagent.client.view.RecipeView.RemainingItem;
import dev.minecraftagent.client.view.RecipeView.SingleInputLayout;
import dev.minecraftagent.client.view.RecipeView.SmithingLayout;
import dev.minecraftagent.client.view.RecipeView.Source;
import dev.minecraftagent.client.view.RecipeView.SourceKind;
import dev.minecraftagent.client.view.RecipeView.TransmuteLayout;
import dev.minecraftagent.client.view.RecipeView.UnsupportedChoiceReason;
import dev.minecraftagent.client.view.RecipeView.UnsupportedLayout;
import dev.minecraftagent.client.view.RecipeView.UnsupportedLayoutReason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.erdtman.jcs.JsonCanonicalizer;

/** Decodes the fixed view protocol without materializing an unbounded Gson object graph. */
public final class StructuredViewDecoder {
  public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  public static final int MAX_JSON_DEPTH = 24;
  public static final int MAX_JSON_NODES = 32768;
  public static final int MAX_TOTAL_STRING_CHARS = MAX_PAYLOAD_BYTES;
  public static final int MAX_ITEM_STACKS = 2048;

  public static final int MAX_BUILD_COMPRESSED_BYTES = 512 * 1024;
  public static final int MAX_BUILD_UNCOMPRESSED_BYTES = 1024 * 1024;
  public static final int MAX_BUILD_BLOCKS = 4096;
  public static final int MAX_BUILD_PALETTE = 256;
  public static final int MAX_BUILD_AXIS = 32;
  public static final int MAX_BUILD_VOLUME = 4096;
  public static final int MAX_BUILD_CHANGES = 4096;

  private static final int MAX_OBJECT_FIELDS = 32;
  private static final int MAX_ARRAY_ITEMS = 4096;
  private static final int MAX_NUMBER_CHARS = 64;
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");
  private static final Pattern PROPERTY_VALUE = Pattern.compile("[A-Za-z0-9_.-]+");

  private final BuildPreviewBlockStateResolver blockStateResolver;

  public StructuredViewDecoder() {
    this(new MinecraftBlockStateResolver());
  }

  public StructuredViewDecoder(BuildPreviewBlockStateResolver blockStateResolver) {
    this.blockStateResolver = Objects.requireNonNull(blockStateResolver, "blockStateResolver");
  }

  public StructuredView decode(byte[] payload) throws ViewDecodeException {
    if (payload == null || payload.length == 0) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    }
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new ViewDecodeException(ViewDecodeException.Code.PAYLOAD_TOO_LARGE);
    }

    String json = decodeUtf8(payload);
    JsonNode root = parse(json);
    DecodeBudget budget = new DecodeBudget();
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

  private static String decodeUtf8(byte[] payload) throws ViewDecodeException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_UTF8, exception);
    }
  }

  private static JsonNode parse(String json) throws ViewDecodeException {
    ParseBudget budget = new ParseBudget();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      JsonNode node = readNode(reader, budget, 1);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
      }
      return node;
    } catch (ViewDecodeException exception) {
      throw exception;
    } catch (IOException | IllegalStateException | NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON, exception);
    }
  }

  private static JsonNode readNode(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    budget.addNode(depth);
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, budget, depth);
      case BEGIN_ARRAY -> readArray(reader, budget, depth);
      case STRING -> {
        String value = reader.nextString();
        budget.addString(value);
        yield new JsonString(value);
      }
      case NUMBER -> {
        String value = reader.nextString();
        if (value.length() > MAX_NUMBER_CHARS) {
          throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
        }
        yield new JsonNumber(value);
      }
      case BOOLEAN -> new JsonBoolean(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    };
  }

  private static JsonObject readObject(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginObject();
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    while (reader.hasNext()) {
      if (fields.size() >= MAX_OBJECT_FIELDS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      String name = reader.nextName();
      budget.addString(name);
      if (fields.containsKey(name)) {
        throw new ViewDecodeException(ViewDecodeException.Code.DUPLICATE_FIELD);
      }
      fields.put(name, readNode(reader, budget, depth + 1));
    }
    reader.endObject();
    return new JsonObject(Map.copyOf(fields));
  }

  private static JsonArray readArray(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginArray();
    List<JsonNode> values = new ArrayList<>();
    while (reader.hasNext()) {
      if (values.size() >= MAX_ARRAY_ITEMS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      values.add(readNode(reader, budget, depth + 1));
    }
    reader.endArray();
    return new JsonArray(List.copyOf(values));
  }

  private StructuredView decodeView(JsonNode node, DecodeBudget budget) throws ViewDecodeException {
    JsonObject object =
        closedObject(
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
    String schemaVersion = string(object, "viewSchemaVersion", 3, 3, false);
    if (!"1.0".equals(schemaVersion)) {
      invalidValue();
    }
    UUID viewId = uuid(string(object, "viewId", 36, 36, false));
    UUID requestId = uuid(string(object, "requestId", 36, 36, false));
    String wireType = string(object, "viewType", 1, 32, false);
    ViewType viewType;
    try {
      viewType = ViewType.fromWireName(wireType);
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.UNSUPPORTED_VIEW_TYPE, exception);
    }
    int revision = integer(object.fields().get("revision"), 1, Integer.MAX_VALUE);
    String title = string(object, "title", 1, 128, false);
    String fallback = string(object, "fallbackText", 1, 8192, true);
    boolean pinnable = bool(object.fields().get("pinnable"));
    ViewContent content =
        switch (viewType) {
          case TEXT -> decodeText(object.fields().get("content"));
          case ITEM_STACK -> decodeItemStack(object.fields().get("content"), budget);
          case ITEM_LIST -> decodeItemList(object.fields().get("content"), budget);
          case RECIPE -> decodeRecipeView(object.fields().get("content"), budget);
          case BUILD_PREVIEW ->
              decodeBuildPreview(object.fields().get("content"), viewId, revision);
        };
    return new StructuredView(
        schemaVersion, viewId, requestId, viewType, revision, title, fallback, pinnable, content);
  }

  private static TextView decodeText(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("text"), Set.of("text"));
    return new TextView(string(object, "text", 1, 32768, true));
  }

  private BuildPreviewView decodeBuildPreview(JsonNode node, UUID outerViewId, int outerRevision)
      throws ViewDecodeException {
    Set<String> fields =
        Set.of(
            "schemaVersion",
            "previewId",
            "projectId",
            "revision",
            "operation",
            "dimension",
            "bounds",
            "origin",
            "transform",
            "baseRegionHash",
            "changeSetHash",
            "contentHash",
            "paletteHash",
            "contentFormat",
            "encoding",
            "compressedBytes",
            "uncompressedBytes",
            "blockCount",
            "difference",
            "palette",
            "chunkCount",
            "chunks");
    JsonObject object = closedObject(node, fields, fields);
    String schemaVersion = string(object, "schemaVersion", 3, 3, false);
    if (!"1.0".equals(schemaVersion)) {
      invalidValue();
    }
    UUID previewId = uuid(string(object, "previewId", 36, 36, false));
    UUID projectId = uuid(string(object, "projectId", 36, 36, false));
    int revision = integer(object.fields().get("revision"), 1, Integer.MAX_VALUE);
    if (!previewId.equals(outerViewId) || revision != outerRevision) {
      throw new ViewDecodeException(ViewDecodeException.Code.METADATA_MISMATCH);
    }

    Operation operation = enumValue(string(object, "operation", 1, 8, false), Operation.class);
    String dimension = namespacedId(string(object, "dimension", 3, 256, false));
    Bounds bounds = decodeBounds(object.fields().get("bounds"));
    Position origin = decodeBuildPosition(object.fields().get("origin"));
    Transform transform = decodeTransform(object.fields().get("transform"));
    String baseRegionHash = hashString(object, "baseRegionHash");
    String changeSetHash = hashString(object, "changeSetHash");
    String contentHash = hashString(object, "contentHash");
    String paletteHash = hashString(object, "paletteHash");
    if (!"minecraft-agent.palette-v1".equals(string(object, "contentFormat", 1, 64, false))) {
      invalidValue();
    }
    String encoding = string(object, "encoding", 1, 32, false);
    if (!encoding.equals("identity+base64") && !encoding.equals("gzip+base64")) {
      invalidValue();
    }
    int compressedBytes =
        integer(object.fields().get("compressedBytes"), 1, MAX_BUILD_COMPRESSED_BYTES);
    int uncompressedBytes =
        integer(object.fields().get("uncompressedBytes"), 1, MAX_BUILD_UNCOMPRESSED_BYTES);
    int blockCount = integer(object.fields().get("blockCount"), 0, MAX_BUILD_BLOCKS);
    Difference difference = decodeDifference(object.fields().get("difference"));
    JsonNode paletteNode = object.fields().get("palette");
    List<PaletteEntry> palette = decodePalette(paletteNode);
    int chunkCount = integer(object.fields().get("chunkCount"), 1, 256);
    List<BuildChunk> chunks = decodeBuildChunks(object.fields().get("chunks"));

    byte[] content =
        verifyBuildTransfer(
            chunks, chunkCount, compressedBytes, uncompressedBytes, encoding, contentHash);
    JsonNode contentNode = parseBuildContent(content);
    requireCanonicalContent(content);
    List<PlacedBlock> blocks = decodePaletteContent(contentNode);

    if (!hash(canonicalize(jsonBytes(paletteNode))).equals(paletteHash)) {
      throw new ViewDecodeException(ViewDecodeException.Code.PALETTE_HASH_MISMATCH);
    }
    validatePalette(palette);
    validateBounds(bounds, origin);
    validateBlocks(blocks, blockCount, palette.size(), bounds);
    validateDifference(difference, bounds.volume());

    // Rotation and mirror describe planning provenance. Coordinates are already final and absolute.
    return new BuildPreviewView(
        schemaVersion,
        previewId,
        projectId,
        revision,
        operation,
        dimension,
        bounds,
        origin,
        transform,
        baseRegionHash,
        changeSetHash,
        contentHash,
        paletteHash,
        difference,
        palette,
        blocks);
  }

  private static Bounds decodeBounds(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("min", "max"), Set.of("min", "max"));
    return new Bounds(
        decodeBuildPosition(object.fields().get("min")),
        decodeBuildPosition(object.fields().get("max")));
  }

  private static Position decodeBuildPosition(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("x", "y", "z"), Set.of("x", "y", "z"));
    return new Position(
        integer(object.fields().get("x"), -30_000_000, 30_000_000),
        integer(object.fields().get("y"), -2048, 2048),
        integer(object.fields().get("z"), -30_000_000, 30_000_000));
  }

  private static Transform decodeTransform(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(node, Set.of("rotation", "mirror"), Set.of("rotation", "mirror"));
    int rotation = integer(object.fields().get("rotation"), 0, 270);
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
      invalidValue();
    }
    return new Transform(rotation, enumValue(string(object, "mirror", 1, 16, false), Mirror.class));
  }

  private static Difference decodeDifference(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node, Set.of("added", "replaced", "removed"), Set.of("added", "replaced", "removed"));
    return new Difference(
        integer(object.fields().get("added"), 0, 250_000),
        integer(object.fields().get("replaced"), 0, 250_000),
        integer(object.fields().get("removed"), 0, 250_000));
  }

  private static List<PaletteEntry> decodePalette(JsonNode node) throws ViewDecodeException {
    List<JsonNode> values = array(node, 0, MAX_BUILD_PALETTE);
    List<PaletteEntry> palette = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      JsonObject object =
          closedObject(
              value, Set.of("id", "blockId", "properties"), Set.of("id", "blockId", "properties"));
      int id = integer(object.fields().get("id"), 0, MAX_BUILD_PALETTE - 1);
      String blockId = namespacedId(string(object, "blockId", 3, 256, false));
      JsonObject propertyObject =
          closedObject(object.fields().get("properties"), Set.of(), Set.of(), true);
      if (propertyObject.fields().size() > 32) {
        invalidValue();
      }
      Map<String, String> properties = new TreeMap<>();
      for (Map.Entry<String, JsonNode> property : propertyObject.fields().entrySet()) {
        if (!PROPERTY_NAME.matcher(property.getKey()).matches()) {
          invalidValue();
        }
        String propertyValue = visibleString(property.getValue(), 1, 64, false);
        if (!PROPERTY_VALUE.matcher(propertyValue).matches()) {
          invalidValue();
        }
        properties.put(property.getKey(), propertyValue);
      }
      palette.add(new PaletteEntry(id, blockId, properties));
    }
    return List.copyOf(palette);
  }

  private static List<BuildChunk> decodeBuildChunks(JsonNode node) throws ViewDecodeException {
    List<JsonNode> values = array(node, 1, 256);
    List<BuildChunk> chunks = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      JsonObject object =
          closedObject(
              value,
              Set.of("index", "byteLength", "sha256", "data"),
              Set.of("index", "byteLength", "sha256", "data"));
      chunks.add(
          new BuildChunk(
              integer(object.fields().get("index"), 0, 255),
              integer(object.fields().get("byteLength"), 1, 1024 * 1024),
              hashString(object, "sha256"),
              string(object, "data", 4, 1_398_104, false)));
    }
    return List.copyOf(chunks);
  }

  private static byte[] verifyBuildTransfer(
      List<BuildChunk> chunks,
      int chunkCount,
      int compressedBytes,
      int uncompressedBytes,
      String encoding,
      String contentHash)
      throws ViewDecodeException {
    Set<Integer> indexes = new HashSet<>();
    for (BuildChunk chunk : chunks) {
      if (!indexes.add(chunk.index())) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_INDEX_DUPLICATE);
      }
    }
    if (chunks.size() != chunkCount) {
      throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_SET_INCOMPLETE);
    }
    for (int index = 0; index < chunkCount; index++) {
      if (!indexes.contains(index)) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_SET_INCOMPLETE);
      }
    }

    BuildChunk[] ordered = new BuildChunk[chunkCount];
    for (BuildChunk chunk : chunks) {
      ordered[chunk.index()] = chunk;
    }
    var compressed = new ByteArrayOutputStream(Math.min(compressedBytes, 8192));
    for (BuildChunk chunk : ordered) {
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(chunk.data());
      } catch (IllegalArgumentException exception) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_BASE64_INVALID, exception);
      }
      if (!Base64.getEncoder().encodeToString(decoded).equals(chunk.data())) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_BASE64_INVALID);
      }
      if (decoded.length != chunk.byteLength()) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_LENGTH_MISMATCH);
      }
      if (!hash(decoded).equals(chunk.sha256())) {
        throw new ViewDecodeException(ViewDecodeException.Code.CHUNK_HASH_MISMATCH);
      }
      if ((long) compressed.size() + decoded.length > MAX_BUILD_COMPRESSED_BYTES) {
        throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_COMPRESSED_LENGTH_MISMATCH);
      }
      compressed.writeBytes(decoded);
    }
    if (compressed.size() != compressedBytes) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_COMPRESSED_LENGTH_MISMATCH);
    }

    byte[] content;
    if (encoding.equals("identity+base64")) {
      content = compressed.toByteArray();
    } else {
      try {
        content = StrictGzipDecoder.decode(compressed.toByteArray(), uncompressedBytes);
      } catch (IOException | ArithmeticException exception) {
        throw new ViewDecodeException(
            ViewDecodeException.Code.CONTENT_DECOMPRESSION_FAILED, exception);
      }
    }
    if (content.length != uncompressedBytes) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_UNCOMPRESSED_LENGTH_MISMATCH);
    }
    if (!hash(content).equals(contentHash)) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_HASH_MISMATCH);
    }
    return content;
  }

  private static JsonNode parseBuildContent(byte[] content) throws ViewDecodeException {
    try {
      return parse(decodeUtf8(content));
    } catch (ViewDecodeException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_JSON_INVALID, exception);
    }
  }

  private static void requireCanonicalContent(byte[] content) throws ViewDecodeException {
    byte[] canonical;
    try {
      canonical = canonicalize(content);
    } catch (ViewDecodeException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_JSON_INVALID, exception);
    }
    if (!Arrays.equals(content, canonical)) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_NOT_CANONICAL);
    }
  }

  private static List<PlacedBlock> decodePaletteContent(JsonNode node) throws ViewDecodeException {
    try {
      JsonObject object =
          closedObject(node, Set.of("blocks", "version"), Set.of("blocks", "version"));
      if (integer(object.fields().get("version"), 1, 1) != 1) {
        invalidValue();
      }
      List<JsonNode> values = array(object.fields().get("blocks"), 0, MAX_BUILD_BLOCKS);
      List<PlacedBlock> blocks = new ArrayList<>(values.size());
      for (JsonNode value : values) {
        JsonObject block =
            closedObject(value, Set.of("state", "x", "y", "z"), Set.of("state", "x", "y", "z"));
        blocks.add(
            new PlacedBlock(
                integer(block.fields().get("state"), 0, MAX_BUILD_PALETTE - 1),
                new Position(
                    integer(block.fields().get("x"), -30_000_000, 30_000_000),
                    integer(block.fields().get("y"), -2048, 2048),
                    integer(block.fields().get("z"), -30_000_000, 30_000_000))));
      }
      return List.copyOf(blocks);
    } catch (ViewDecodeException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.CONTENT_SHAPE_INVALID, exception);
    }
  }

  private void validatePalette(List<PaletteEntry> palette) throws ViewDecodeException {
    Set<String> states = new HashSet<>();
    String previous = null;
    for (int index = 0; index < palette.size(); index++) {
      PaletteEntry entry = palette.get(index);
      String canonicalState = entry.canonicalState();
      if (entry.id() != index
          || !states.add(canonicalState)
          || previous != null && previous.compareTo(canonicalState) >= 0) {
        throw new ViewDecodeException(ViewDecodeException.Code.PALETTE_INVALID);
      }
      try {
        blockStateResolver.validate(entry.blockId(), entry.properties());
      } catch (RuntimeException | LinkageError exception) {
        throw new ViewDecodeException(ViewDecodeException.Code.PALETTE_INVALID, exception);
      }
      previous = canonicalState;
    }
  }

  private static void validateBounds(Bounds bounds, Position origin) throws ViewDecodeException {
    try {
      if (bounds.min().x() > bounds.max().x()
          || bounds.min().y() > bounds.max().y()
          || bounds.min().z() > bounds.max().z()
          || !bounds.contains(origin)
          || bounds.sizeX() > MAX_BUILD_AXIS
          || bounds.sizeY() > MAX_BUILD_AXIS
          || bounds.sizeZ() > MAX_BUILD_AXIS
          || bounds.volume() > MAX_BUILD_VOLUME) {
        throw new ViewDecodeException(ViewDecodeException.Code.BOUNDS_INVALID);
      }
    } catch (ArithmeticException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.BOUNDS_INVALID, exception);
    }
  }

  private static void validateBlocks(
      List<PlacedBlock> blocks, int blockCount, int paletteSize, Bounds bounds)
      throws ViewDecodeException {
    if (blocks.size() != blockCount) {
      throw new ViewDecodeException(ViewDecodeException.Code.BLOCK_CONTENT_INVALID);
    }
    Comparator<Position> order =
        Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::z)
            .thenComparingInt(Position::x);
    Position previous = null;
    for (PlacedBlock block : blocks) {
      if (block.state() < 0
          || block.state() >= paletteSize
          || !bounds.contains(block.position())
          || previous != null && order.compare(previous, block.position()) >= 0) {
        throw new ViewDecodeException(ViewDecodeException.Code.BLOCK_CONTENT_INVALID);
      }
      previous = block.position();
    }
  }

  private static void validateDifference(Difference difference, int volume)
      throws ViewDecodeException {
    long changes = (long) difference.added() + difference.replaced() + difference.removed();
    if (changes > MAX_BUILD_CHANGES || changes > volume) {
      throw new ViewDecodeException(ViewDecodeException.Code.CHANGE_LIMIT_EXCEEDED);
    }
  }

  private static String hashString(JsonObject object, String field) throws ViewDecodeException {
    String value = string(object, field, 64, 64, false);
    if (!SHA_256.matcher(value).matches()) {
      invalidValue();
    }
    return value;
  }

  private static byte[] canonicalize(byte[] json) throws ViewDecodeException {
    try {
      return new JsonCanonicalizer(json).getEncodedUTF8();
    } catch (IOException | RuntimeException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON, exception);
    }
  }

  private static String hash(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static ItemListView decodeItemList(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("items"), Set.of("items"));
    List<JsonNode> values = array(object.fields().get("items"), 1, 128);
    List<ItemStackView> items = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      items.add(decodeItemStack(value, budget));
    }
    return new ItemListView(items);
  }

  private static ItemStackView decodeItemStack(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    budget.addItemStack();
    JsonObject object =
        closedObject(
            node, Set.of("itemId", "count", "components"), Set.of("itemId", "count", "components"));
    String itemId = namespacedId(string(object, "itemId", 3, 256, false));
    int count = integer(object.fields().get("count"), 1, 999999);
    return new ItemStackView(itemId, count, decodeComponents(object.fields().get("components")));
  }

  private static SafeComponents decodeComponents(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "customName", "lore", "damage", "maxDamage", "customModelData", "enchantmentGlint"),
            Set.of());
    Optional<String> customName = optionalString(object, "customName", 0, 512, false);
    List<String> lore = new ArrayList<>();
    if (object.fields().containsKey("lore")) {
      for (JsonNode line : array(object.fields().get("lore"), 0, 32)) {
        lore.add(visibleString(line, 0, 512, false));
      }
    }
    Optional<Integer> damage = optionalInteger(object, "damage", 0, Integer.MAX_VALUE);
    Optional<Integer> maxDamage = optionalInteger(object, "maxDamage", 1, Integer.MAX_VALUE);
    if (damage.isPresent() && maxDamage.isPresent() && damage.get() > maxDamage.get()) {
      invalidValue();
    }
    Optional<Integer> customModelData =
        optionalInteger(object, "customModelData", 0, Integer.MAX_VALUE);
    Optional<Boolean> enchantmentGlint = optionalBoolean(object, "enchantmentGlint");
    return new SafeComponents(
        customName, lore, damage, maxDamage, customModelData, enchantmentGlint);
  }

  private static RecipeView decodeRecipeView(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "schemaVersion", "query", "selectedRecipe", "totalMatches", "truncated", "recipes"),
            Set.of(
                "schemaVersion",
                "query",
                "selectedRecipe",
                "totalMatches",
                "truncated",
                "recipes"));
    String schemaVersion = string(object, "schemaVersion", 3, 3, false);
    if (!"2.0".equals(schemaVersion)) {
      invalidValue();
    }
    Query query = decodeQuery(object.fields().get("query"));
    int selectedRecipe = integer(object.fields().get("selectedRecipe"), 0, 15);
    int totalMatches = integer(object.fields().get("totalMatches"), 1, 1_000_000);
    boolean truncated = bool(object.fields().get("truncated"));
    List<JsonNode> values = array(object.fields().get("recipes"), 1, 16);
    if (selectedRecipe >= values.size()) {
      invalidValue();
    }
    List<Recipe> recipes = new ArrayList<>(values.size());
    Set<String> recipeIds = new HashSet<>();
    for (JsonNode value : values) {
      Recipe recipe = decodeRecipe(value, budget);
      if (!recipeIds.add(recipe.recipeId())) {
        invalidValue();
      }
      recipes.add(recipe);
    }
    if (totalMatches < recipes.size() || (!truncated && totalMatches != recipes.size())) {
      invalidValue();
    }
    return new RecipeView(schemaVersion, query, selectedRecipe, totalMatches, truncated, recipes);
  }

  private static Query decodeQuery(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("mode", "itemId"), Set.of("mode", "itemId"));
    QueryMode mode = enumValue(string(object, "mode", 1, 16, false), QueryMode.class);
    return new Query(mode, namespacedId(string(object, "itemId", 3, 256, false)));
  }

  private static Recipe decodeRecipe(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "recipeId",
                "recipeType",
                "source",
                "result",
                "layout",
                "remainingItems",
                "processing"),
            Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems"));
    String recipeId = namespacedId(string(object, "recipeId", 3, 256, false));
    RecipeType recipeType = enumValue(string(object, "recipeType", 1, 32, false), RecipeType.class);
    Source source = decodeSource(object.fields().get("source"));
    Optional<ItemStackView> result = Optional.empty();
    if (object.fields().get("result") != JsonNull.INSTANCE) {
      result = Optional.of(decodeItemStack(object.fields().get("result"), budget));
    }
    Set<Integer> logicalSlots = new HashSet<>();
    Layout layout = decodeLayout(object.fields().get("layout"), budget, recipeType, logicalSlots);
    List<RemainingItem> remainingItems =
        decodeRemainingItems(object.fields().get("remainingItems"), budget, logicalSlots);
    Optional<Processing> processing = Optional.empty();
    if (object.fields().containsKey("processing")) {
      processing = Optional.of(decodeProcessing(object.fields().get("processing")));
    }
    if (isCooking(recipeType) && processing.isEmpty()) {
      throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
    }
    if (!isCooking(recipeType) && processing.isPresent()) {
      invalidValue();
    }
    return new Recipe(recipeId, recipeType, source, result, layout, remainingItems, processing);
  }

  private static Source decodeSource(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(node, Set.of("kind", "providerId"), Set.of("kind", "providerId"));
    SourceKind kind = enumValue(string(object, "kind", 1, 32, false), SourceKind.class);
    JsonNode provider = object.fields().get("providerId");
    Optional<String> providerId;
    if (provider == JsonNull.INSTANCE) {
      providerId = Optional.empty();
    } else {
      providerId = Optional.of(namespacedId(visibleString(provider, 3, 256, false)));
    }
    if ((kind == SourceKind.SERVER_REGISTRY) == providerId.isPresent()) {
      invalidValue();
    }
    return new Source(kind, providerId);
  }

  private static Layout decodeLayout(
      JsonNode node, DecodeBudget budget, RecipeType recipeType, Set<Integer> logicalSlots)
      throws ViewDecodeException {
    if (!(node instanceof JsonObject object)) {
      invalidValue();
      throw new AssertionError();
    }
    String kind = string(object, "kind", 1, 16, false);
    if (!expectedLayoutKind(recipeType).equals(kind)) {
      invalidValue();
    }
    return switch (kind) {
      case "grid" -> decodeGridLayout(object, budget, logicalSlots);
      case "single_input" -> {
        JsonObject exact =
            closedObject(object, Set.of("kind", "ingredient"), Set.of("kind", "ingredient"));
        logicalSlots.add(0);
        yield new SingleInputLayout(
            decodeIngredientChoice(exact.fields().get("ingredient"), budget));
      }
      case "smithing" -> {
        JsonObject exact =
            closedObject(
                object,
                Set.of("kind", "template", "base", "addition"),
                Set.of("kind", "template", "base", "addition"));
        logicalSlots.addAll(Set.of(0, 1, 2));
        yield new SmithingLayout(
            decodeIngredientChoice(exact.fields().get("template"), budget),
            decodeIngredientChoice(exact.fields().get("base"), budget),
            decodeIngredientChoice(exact.fields().get("addition"), budget));
      }
      case "transmute" -> {
        JsonObject exact =
            closedObject(
                object, Set.of("kind", "input", "material"), Set.of("kind", "input", "material"));
        logicalSlots.addAll(Set.of(0, 1));
        yield new TransmuteLayout(
            decodeIngredientChoice(exact.fields().get("input"), budget),
            decodeIngredientChoice(exact.fields().get("material"), budget));
      }
      case "unsupported" -> {
        JsonObject exact = closedObject(object, Set.of("kind", "reason"), Set.of("kind", "reason"));
        yield new UnsupportedLayout(
            enumValue(string(exact, "reason", 1, 64, false), UnsupportedLayoutReason.class));
      }
      default -> throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE);
    };
  }

  private static GridLayout decodeGridLayout(
      JsonObject input, DecodeBudget budget, Set<Integer> logicalSlots) throws ViewDecodeException {
    JsonObject object =
        closedObject(
            input,
            Set.of("kind", "width", "height", "ingredients"),
            Set.of("kind", "width", "height", "ingredients"));
    int width = integer(object.fields().get("width"), 1, 3);
    int height = integer(object.fields().get("height"), 1, 3);
    List<JsonNode> values = array(object.fields().get("ingredients"), 1, 9);
    List<IngredientSlot> ingredients = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    Set<Integer> positions = new HashSet<>();
    for (JsonNode value : values) {
      IngredientSlot ingredient = decodeIngredientSlot(value, budget);
      if (ingredient.x() >= width
          || ingredient.y() >= height
          || ingredient.slot() != ingredient.y() * width + ingredient.x()) {
        invalidValue();
      }
      if (!slots.add(ingredient.slot()) || !positions.add(ingredient.y() * 3 + ingredient.x())) {
        invalidValue();
      }
      logicalSlots.add(ingredient.slot());
      ingredients.add(ingredient);
    }
    return new GridLayout(width, height, ingredients);
  }

  private static IngredientSlot decodeIngredientSlot(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node, Set.of("slot", "x", "y", "ingredient"), Set.of("slot", "x", "y", "ingredient"));
    return new IngredientSlot(
        integer(object.fields().get("slot"), 0, 8),
        integer(object.fields().get("x"), 0, 2),
        integer(object.fields().get("y"), 0, 2),
        decodeIngredientChoice(object.fields().get("ingredient"), budget));
  }

  private static IngredientChoice decodeIngredientChoice(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of("choiceType", "tagId", "reason", "alternatives"),
            Set.of("choiceType", "alternatives"));
    ChoiceType choiceType = enumValue(string(object, "choiceType", 1, 16, false), ChoiceType.class);
    Optional<String> tagId = Optional.empty();
    if (object.fields().containsKey("tagId")) {
      tagId = Optional.of(namespacedId(string(object, "tagId", 3, 256, false)));
    }
    Optional<UnsupportedChoiceReason> reason = Optional.empty();
    if (object.fields().containsKey("reason")) {
      reason =
          Optional.of(
              enumValue(string(object, "reason", 1, 64, false), UnsupportedChoiceReason.class));
    }
    boolean unsupported = choiceType == ChoiceType.UNSUPPORTED;
    if ((choiceType == ChoiceType.TAG) != tagId.isPresent()
        || unsupported != reason.isPresent()
        || unsupported && tagId.isPresent()) {
      invalidValue();
    }
    List<JsonNode> values =
        array(object.fields().get("alternatives"), unsupported ? 0 : 1, unsupported ? 0 : 64);
    List<ItemStackView> alternatives = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      alternatives.add(decodeItemStack(value, budget));
    }
    return new IngredientChoice(choiceType, tagId, reason, alternatives);
  }

  private static List<RemainingItem> decodeRemainingItems(
      JsonNode node, DecodeBudget budget, Set<Integer> logicalSlots) throws ViewDecodeException {
    List<JsonNode> values = array(node, 0, 9);
    List<RemainingItem> result = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    for (JsonNode value : values) {
      JsonObject object = closedObject(value, Set.of("slot", "item"), Set.of("slot", "item"));
      int slot = integer(object.fields().get("slot"), 0, 8);
      if (!logicalSlots.contains(slot) || !slots.add(slot)) {
        invalidValue();
      }
      result.add(new RemainingItem(slot, decodeItemStack(object.fields().get("item"), budget)));
    }
    return List.copyOf(result);
  }

  private static Processing decodeProcessing(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(node, Set.of("timeTicks", "experience"), Set.of("timeTicks", "experience"));
    return new Processing(
        integer(object.fields().get("timeTicks"), 0, 120000),
        decimal(object.fields().get("experience"), BigDecimal.ZERO, new BigDecimal("1000000")));
  }

  private static String expectedLayoutKind(RecipeType type) {
    return switch (type) {
      case SHAPED, SHAPELESS -> "grid";
      case SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING, STONECUTTING -> "single_input";
      case SMITHING_TRANSFORM, SMITHING_TRIM -> "smithing";
      case TRANSMUTE -> "transmute";
      case COMPLEX, CUSTOM -> "unsupported";
    };
  }

  private static boolean isCooking(RecipeType type) {
    return type == RecipeType.SMELTING
        || type == RecipeType.BLASTING
        || type == RecipeType.SMOKING
        || type == RecipeType.CAMPFIRE_COOKING;
  }

  private static JsonObject closedObject(JsonNode node, Set<String> allowed, Set<String> required)
      throws ViewDecodeException {
    return closedObject(node, allowed, required, false);
  }

  private static JsonObject closedObject(
      JsonNode node, Set<String> allowed, Set<String> required, boolean allowAdditional)
      throws ViewDecodeException {
    if (!(node instanceof JsonObject object)) {
      invalidValue();
      throw new AssertionError();
    }
    for (String field : object.fields().keySet()) {
      if (!allowAdditional && !allowed.contains(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.UNKNOWN_FIELD);
      }
    }
    for (String field : required) {
      if (!object.fields().containsKey(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
      }
    }
    return object;
  }

  private static byte[] jsonBytes(JsonNode node) {
    var json = new StringBuilder();
    appendJson(json, node);
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendJson(StringBuilder target, JsonNode node) {
    switch (node) {
      case JsonObject object -> {
        target.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonNode> field : object.fields().entrySet()) {
          if (!first) {
            target.append(',');
          }
          appendJsonString(target, field.getKey());
          target.append(':');
          appendJson(target, field.getValue());
          first = false;
        }
        target.append('}');
      }
      case JsonArray array -> {
        target.append('[');
        for (int index = 0; index < array.values().size(); index++) {
          if (index > 0) {
            target.append(',');
          }
          appendJson(target, array.values().get(index));
        }
        target.append(']');
      }
      case JsonString string -> appendJsonString(target, string.value());
      case JsonNumber number -> target.append(number.value());
      case JsonBoolean bool -> target.append(bool.value());
      case JsonNull ignored -> target.append("null");
    }
  }

  private static void appendJsonString(StringBuilder target, String value) {
    target.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> target.append("\\\"");
        case '\\' -> target.append("\\\\");
        case '\b' -> target.append("\\b");
        case '\f' -> target.append("\\f");
        case '\n' -> target.append("\\n");
        case '\r' -> target.append("\\r");
        case '\t' -> target.append("\\t");
        default -> {
          if (character < 0x20) {
            target.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            target.append(character);
          }
        }
      }
    }
    target.append('"');
  }

  private static String string(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    return visibleString(object.fields().get(field), minimum, maximum, allowLineBreaks);
  }

  private static Optional<String> optionalString(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(string(object, field, minimum, maximum, allowLineBreaks));
  }

  private static String visibleString(
      JsonNode node, int minimum, int maximum, boolean allowLineBreaks) throws ViewDecodeException {
    if (!(node instanceof JsonString jsonString)) {
      invalidValue();
      throw new AssertionError();
    }
    String value = jsonString.value();
    int length = value.codePointCount(0, value.length());
    if (length < minimum || length > maximum) {
      invalidValue();
    }
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        invalidValue();
      }
      if (((codePoint == '\n' || codePoint == '\t') && allowLineBreaks)
          || codePoint >= 0x20 && !Character.isISOControl(codePoint)) {
        if (isBidirectionalControl(codePoint)) {
          invalidValue();
        }
      } else {
        invalidValue();
      }
      index += Character.charCount(codePoint);
    }
    return value;
  }

  private static boolean isBidirectionalControl(int codePoint) {
    return codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || codePoint >= 0x202a && codePoint <= 0x202e
        || codePoint >= 0x2066 && codePoint <= 0x2069;
  }

  private static UUID uuid(String value) throws ViewDecodeException {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        invalidValue();
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static String namespacedId(String value) throws ViewDecodeException {
    if (!NAMESPACED_ID.matcher(value).matches()) {
      invalidValue();
    }
    return value;
  }

  private static List<JsonNode> array(JsonNode node, int minimum, int maximum)
      throws ViewDecodeException {
    if (!(node instanceof JsonArray array)) {
      invalidValue();
      throw new AssertionError();
    }
    if (array.values().size() < minimum || array.values().size() > maximum) {
      invalidValue();
    }
    return array.values();
  }

  private static boolean bool(JsonNode node) throws ViewDecodeException {
    if (!(node instanceof JsonBoolean value)) {
      invalidValue();
      throw new AssertionError();
    }
    return value.value();
  }

  private static Optional<Boolean> optionalBoolean(JsonObject object, String field)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(bool(object.fields().get(field)));
  }

  private static int integer(JsonNode node, int minimum, int maximum) throws ViewDecodeException {
    if (!(node instanceof JsonNumber value) || !INTEGER.matcher(value.value()).matches()) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      long number = Long.parseLong(value.value());
      if (number < minimum || number > maximum) {
        invalidValue();
      }
      return (int) number;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static Optional<Integer> optionalInteger(
      JsonObject object, String field, int minimum, int maximum) throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(integer(object.fields().get(field), minimum, maximum));
  }

  private static double decimal(JsonNode node, BigDecimal minimum, BigDecimal maximum)
      throws ViewDecodeException {
    if (!(node instanceof JsonNumber value)) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      BigDecimal number = new BigDecimal(value.value());
      if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
        invalidValue();
      }
      double result = number.doubleValue();
      if (!Double.isFinite(result)) {
        invalidValue();
      }
      return result;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static <E extends Enum<E>> E enumValue(String wireValue, Class<E> type)
      throws ViewDecodeException {
    try {
      return Enum.valueOf(type, wireValue.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static void invalidValue() throws ViewDecodeException {
    throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE);
  }

  private sealed interface JsonNode
      permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {}

  private record JsonObject(Map<String, JsonNode> fields) implements JsonNode {}

  private record JsonArray(List<JsonNode> values) implements JsonNode {}

  private record JsonString(String value) implements JsonNode {}

  private record JsonNumber(String value) implements JsonNode {}

  private record JsonBoolean(boolean value) implements JsonNode {}

  private enum JsonNull implements JsonNode {
    INSTANCE
  }

  private record BuildChunk(int index, int byteLength, String sha256, String data) {}

  private static final class ParseBudget {
    private int nodes;
    private int stringChars;

    private void addNode(int depth) throws ViewDecodeException {
      if (depth > MAX_JSON_DEPTH || ++nodes > MAX_JSON_NODES) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }

    private void addString(String value) throws ViewDecodeException {
      if ((long) stringChars + value.length() > MAX_TOTAL_STRING_CHARS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      stringChars += value.length();
    }
  }

  private static final class DecodeBudget {
    private int itemStacks;

    private void addItemStack() throws ViewDecodeException {
      if (++itemStacks > MAX_ITEM_STACKS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }
  }
}
