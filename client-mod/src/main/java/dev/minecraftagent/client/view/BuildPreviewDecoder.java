package dev.minecraftagent.client.view;

import dev.minecraftagent.client.transfer.StrictGzipDecoder;
import dev.minecraftagent.client.view.BuildPreviewView.Bounds;
import dev.minecraftagent.client.view.BuildPreviewView.Difference;
import dev.minecraftagent.client.view.BuildPreviewView.Mirror;
import dev.minecraftagent.client.view.BuildPreviewView.Operation;
import dev.minecraftagent.client.view.BuildPreviewView.PaletteEntry;
import dev.minecraftagent.client.view.BuildPreviewView.PlacedBlock;
import dev.minecraftagent.client.view.BuildPreviewView.Position;
import dev.minecraftagent.client.view.BuildPreviewView.Transform;
import dev.minecraftagent.protocol.StructuredViewContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Decodes build previews: the chunked transfer is reassembled and verified, the palette content is
 * re-parsed, and palette, bounds, blocks and change counts are validated against the client-side
 * build guards.
 */
final class BuildPreviewDecoder {
  static final int MAX_BUILD_COMPRESSED_BYTES = StructuredViewContract.PREVIEW_COMPRESSED_BYTES_MAX;
  static final int MAX_BUILD_UNCOMPRESSED_BYTES =
      StructuredViewContract.PREVIEW_UNCOMPRESSED_BYTES_MAX;
  static final int MAX_BUILD_BLOCKS = StructuredViewContract.PREVIEW_BLOCK_COUNT_MAX;
  static final int MAX_BUILD_PALETTE = StructuredViewContract.PREVIEW_PALETTE_MAX_ITEMS;
  static final int MAX_BUILD_AXIS = 32;
  static final int MAX_BUILD_VOLUME = 4096;
  static final int MAX_BUILD_CHANGES = 4096;

  private final BuildPreviewBlockStateResolver blockStateResolver;

  BuildPreviewDecoder(BuildPreviewBlockStateResolver blockStateResolver) {
    this.blockStateResolver = Objects.requireNonNull(blockStateResolver, "blockStateResolver");
  }

  BuildPreviewView decodeBuildPreview(JsonNode node, UUID outerViewId, int outerRevision)
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
    JsonObject object = JsonValues.closedObject(node, fields, fields);
    String schemaVersion = JsonValues.string(object, "schemaVersion", 3, 3, false);
    if (!StructuredViewContract.VIEW_SCHEMA_VERSION.equals(schemaVersion)) {
      JsonValues.invalidValue();
    }
    UUID previewId = JsonValues.uuid(JsonValues.string(object, "previewId", 36, 36, false));
    UUID projectId = JsonValues.uuid(JsonValues.string(object, "projectId", 36, 36, false));
    int revision = JsonValues.integer(object.fields().get("revision"), 1, Integer.MAX_VALUE);
    if (!previewId.equals(outerViewId) || revision != outerRevision) {
      throw new ViewDecodeException(ViewDecodeException.Code.METADATA_MISMATCH);
    }

    Operation operation =
        JsonValues.enumValue(JsonValues.string(object, "operation", 1, 8, false), Operation.class);
    String dimension =
        JsonValues.namespacedId(JsonValues.string(object, "dimension", 3, 256, false));
    Bounds bounds = decodeBounds(object.fields().get("bounds"));
    Position origin = decodeBuildPosition(object.fields().get("origin"));
    Transform transform = decodeTransform(object.fields().get("transform"));
    String baseRegionHash = JsonValues.hashString(object, "baseRegionHash");
    String changeSetHash = JsonValues.hashString(object, "changeSetHash");
    String contentHash = JsonValues.hashString(object, "contentHash");
    String paletteHash = JsonValues.hashString(object, "paletteHash");
    if (!StructuredViewContract.PREVIEW_CONTENT_FORMAT.equals(
        JsonValues.string(object, "contentFormat", 1, 64, false))) {
      JsonValues.invalidValue();
    }
    String encoding = JsonValues.string(object, "encoding", 1, 32, false);
    if (!StructuredViewContract.PREVIEW_ENCODINGS.contains(encoding)) {
      JsonValues.invalidValue();
    }
    int compressedBytes =
        JsonValues.integer(object.fields().get("compressedBytes"), 1, MAX_BUILD_COMPRESSED_BYTES);
    int uncompressedBytes =
        JsonValues.integer(
            object.fields().get("uncompressedBytes"), 1, MAX_BUILD_UNCOMPRESSED_BYTES);
    int blockCount = JsonValues.integer(object.fields().get("blockCount"), 0, MAX_BUILD_BLOCKS);
    Difference difference = decodeDifference(object.fields().get("difference"));
    JsonNode paletteNode = object.fields().get("palette");
    List<PaletteEntry> palette = decodePalette(paletteNode);
    int chunkCount =
        JsonValues.integer(
            object.fields().get("chunkCount"),
            StructuredViewContract.PREVIEW_CHUNK_MIN_ITEMS,
            StructuredViewContract.PREVIEW_CHUNK_MAX_ITEMS);
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
    JsonObject object = JsonValues.closedObject(node, Set.of("min", "max"), Set.of("min", "max"));
    return new Bounds(
        decodeBuildPosition(object.fields().get("min")),
        decodeBuildPosition(object.fields().get("max")));
  }

  private static Position decodeBuildPosition(JsonNode node) throws ViewDecodeException {
    JsonObject object = JsonValues.closedObject(node, Set.of("x", "y", "z"), Set.of("x", "y", "z"));
    return new Position(
        JsonValues.integer(object.fields().get("x"), -30_000_000, 30_000_000),
        JsonValues.integer(object.fields().get("y"), -2048, 2048),
        JsonValues.integer(object.fields().get("z"), -30_000_000, 30_000_000));
  }

  private static Transform decodeTransform(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(node, Set.of("rotation", "mirror"), Set.of("rotation", "mirror"));
    int rotation = JsonValues.integer(object.fields().get("rotation"), 0, 270);
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
      JsonValues.invalidValue();
    }
    return new Transform(
        rotation,
        JsonValues.enumValue(JsonValues.string(object, "mirror", 1, 16, false), Mirror.class));
  }

  private static Difference decodeDifference(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node, Set.of("added", "replaced", "removed"), Set.of("added", "replaced", "removed"));
    return new Difference(
        JsonValues.integer(
            object.fields().get("added"),
            StructuredViewContract.PREVIEW_CHANGE_MIN,
            StructuredViewContract.PREVIEW_CHANGE_MAX),
        JsonValues.integer(
            object.fields().get("replaced"),
            StructuredViewContract.PREVIEW_CHANGE_MIN,
            StructuredViewContract.PREVIEW_CHANGE_MAX),
        JsonValues.integer(
            object.fields().get("removed"),
            StructuredViewContract.PREVIEW_CHANGE_MIN,
            StructuredViewContract.PREVIEW_CHANGE_MAX));
  }

  private static List<PaletteEntry> decodePalette(JsonNode node) throws ViewDecodeException {
    List<JsonNode> values = JsonValues.array(node, 0, MAX_BUILD_PALETTE);
    List<PaletteEntry> palette = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      JsonObject object =
          JsonValues.closedObject(
              value, Set.of("id", "blockId", "properties"), Set.of("id", "blockId", "properties"));
      int id =
          JsonValues.integer(
              object.fields().get("id"),
              StructuredViewContract.PREVIEW_PALETTE_ID_MIN,
              StructuredViewContract.PREVIEW_PALETTE_ID_MAX);
      String blockId = JsonValues.namespacedId(JsonValues.string(object, "blockId", 3, 256, false));
      JsonObject propertyObject =
          JsonValues.closedObject(object.fields().get("properties"), Set.of(), Set.of(), true);
      if (propertyObject.fields().size() > StructuredViewContract.PREVIEW_PALETTE_MAX_PROPERTIES) {
        JsonValues.invalidValue();
      }
      Map<String, String> properties = new TreeMap<>();
      for (Map.Entry<String, JsonNode> property : propertyObject.fields().entrySet()) {
        if (!StructuredViewContract.BLOCK_PROPERTY_NAME.matcher(property.getKey()).matches()) {
          JsonValues.invalidValue();
        }
        String propertyValue = JsonValues.visibleString(property.getValue(), 1, 64, false);
        if (!StructuredViewContract.BLOCK_PROPERTY_VALUE.matcher(propertyValue).matches()) {
          JsonValues.invalidValue();
        }
        properties.put(property.getKey(), propertyValue);
      }
      palette.add(new PaletteEntry(id, blockId, properties));
    }
    return List.copyOf(palette);
  }

  private static List<BuildChunk> decodeBuildChunks(JsonNode node) throws ViewDecodeException {
    List<JsonNode> values = JsonValues.array(node, 1, 256);
    List<BuildChunk> chunks = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      JsonObject object =
          JsonValues.closedObject(
              value,
              Set.of("index", "byteLength", "sha256", "data"),
              Set.of("index", "byteLength", "sha256", "data"));
      chunks.add(
          new BuildChunk(
              JsonValues.integer(object.fields().get("index"), 0, 255),
              JsonValues.integer(object.fields().get("byteLength"), 1, 1024 * 1024),
              JsonValues.hashString(object, "sha256"),
              JsonValues.string(object, "data", 4, 1_398_104, false)));
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
      return StrictJsonParser.parse(StrictJsonParser.decodeUtf8(content));
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
          JsonValues.closedObject(node, Set.of("blocks", "version"), Set.of("blocks", "version"));
      if (JsonValues.integer(object.fields().get("version"), 1, 1) != 1) {
        JsonValues.invalidValue();
      }
      List<JsonNode> values = JsonValues.array(object.fields().get("blocks"), 0, MAX_BUILD_BLOCKS);
      List<PlacedBlock> blocks = new ArrayList<>(values.size());
      for (JsonNode value : values) {
        JsonObject block =
            JsonValues.closedObject(
                value, Set.of("state", "x", "y", "z"), Set.of("state", "x", "y", "z"));
        blocks.add(
            new PlacedBlock(
                JsonValues.integer(
                    block.fields().get("state"),
                    StructuredViewContract.PREVIEW_PALETTE_ID_MIN,
                    StructuredViewContract.PREVIEW_PALETTE_ID_MAX),
                new Position(
                    JsonValues.integer(block.fields().get("x"), -30_000_000, 30_000_000),
                    JsonValues.integer(block.fields().get("y"), -2048, 2048),
                    JsonValues.integer(block.fields().get("z"), -30_000_000, 30_000_000))));
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

  private record BuildChunk(int index, int byteLength, String sha256, String data) {}
}
