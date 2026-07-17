package dev.minecraftagent.paper.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewSchemaRegistry;
import dev.minecraftagent.paper.client.ClientViewType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.GZIPOutputStream;
import org.erdtman.jcs.JsonCanonicalizer;

/** Pure deterministic conversion from an immutable region snapshot to a build preview transfer. */
public final class BuildPreviewArtifactFactory {
  private static final String AIR = "minecraft:air";
  private static final int CHUNK_BYTES = 48 * 1024;
  private static final int GZIP_THRESHOLD = 4 * 1024;

  public Artifact create(Request request, List<Cell> snapshot) {
    Objects.requireNonNull(request);
    snapshot = List.copyOf(snapshot);
    if (snapshot.size() != request.bounds().volume()) {
      throw new IllegalArgumentException("BUILD_SNAPSHOT_INCOMPLETE");
    }
    var expected = request.bounds().positions();
    for (var index = 0; index < snapshot.size(); index++) {
      if (!snapshot.get(index).position().equals(expected.get(index))) {
        throw new IllegalArgumentException("BUILD_SNAPSHOT_ORDER_INVALID");
      }
    }

    var targetState = request.pattern() == Pattern.CLEAR ? AIR : request.blockState();
    var targetStates = new ArrayList<String>(snapshot.size());
    var changes = new JsonArray();
    var added = 0;
    var replaced = 0;
    var removed = 0;
    for (var cell : snapshot) {
      var target =
          request.pattern().contains(request.bounds(), cell.position()) ? targetState : AIR;
      targetStates.add(target);
      if (cell.state().equals(target)) {
        continue;
      }
      if (AIR.equals(cell.state())) {
        added++;
      } else if (AIR.equals(target)) {
        removed++;
      } else {
        replaced++;
      }
      var change = positionJson(cell.position());
      change.addProperty("expected", cell.state());
      change.addProperty("target", target);
      changes.add(change);
    }

    var palette = palette(targetStates);
    var paletteIds = new java.util.HashMap<String, Integer>();
    for (var index = 0; index < palette.size(); index++) {
      paletteIds.put(canonicalState(palette.get(index).getAsJsonObject()), index);
    }
    var blocks = new JsonArray();
    for (var index = 0; index < targetStates.size(); index++) {
      var state = targetStates.get(index);
      if (AIR.equals(state)) {
        continue;
      }
      var block = positionJson(snapshot.get(index).position());
      block.addProperty("state", paletteIds.get(state));
      blocks.add(block);
    }
    var inner = new JsonObject();
    inner.add("blocks", blocks);
    inner.addProperty("version", 1);
    var innerBytes = canonical(inner).getBytes(StandardCharsets.UTF_8);
    var contentHash = sha256(innerBytes);
    var paletteHash = sha256(canonical(palette).getBytes(StandardCharsets.UTF_8));

    var regionState = new JsonObject();
    regionState.addProperty("serverId", request.serverId());
    regionState.addProperty("worldUuid", request.worldUuid().toString());
    regionState.addProperty("dimension", request.dimension());
    regionState.add("bounds", request.bounds().json());
    var states = new JsonArray();
    snapshot.forEach(
        cell -> {
          var value = positionJson(cell.position());
          value.addProperty("state", cell.state());
          states.add(value);
        });
    regionState.add("states", states);
    var baseRegionHash = domainHash("minecraft-agent/region-state/v1", regionState);

    var changeSet = new JsonObject();
    changeSet.addProperty("worldUuid", request.worldUuid().toString());
    changeSet.addProperty("dimension", request.dimension());
    changeSet.add("bounds", request.bounds().json());
    changeSet.addProperty("baseRegionHash", baseRegionHash);
    changeSet.add("changes", changes);
    var changeSetHash = domainHash("minecraft-agent/change-set/v1", changeSet);

    byte[] transferBytes = innerBytes;
    var encoding = "identity+base64";
    if (innerBytes.length >= GZIP_THRESHOLD) {
      transferBytes = gzip(innerBytes);
      encoding = "gzip+base64";
    }
    var previewId =
        UUID.nameUUIDFromBytes(
            (request.projectId() + ":" + request.revision() + ":" + changeSetHash)
                .getBytes(StandardCharsets.UTF_8));
    var content =
        transfer(
            request,
            previewId,
            baseRegionHash,
            changeSetHash,
            contentHash,
            paletteHash,
            blocks.size(),
            added,
            replaced,
            removed,
            palette,
            encoding,
            innerBytes.length,
            transferBytes);
    var fallback =
        "Build preview "
            + previewId
            + ": "
            + changes.size()
            + " changes ("
            + added
            + " added, "
            + replaced
            + " replaced, "
            + removed
            + " removed).";
    var view =
        new ClientStructuredView(
            ClientViewSchemaRegistry.VIEW_SCHEMA_V1,
            previewId,
            request.requestId(),
            ClientViewType.BUILD_PREVIEW,
            request.revision(),
            "Build preview",
            fallback,
            false,
            content);
    var result = new JsonObject();
    result.addProperty("previewId", previewId.toString());
    result.addProperty("projectId", request.projectId().toString());
    result.addProperty("revision", request.revision());
    result.addProperty("dimension", request.dimension());
    result.add("bounds", request.bounds().json());
    result.addProperty("baseRegionHash", baseRegionHash);
    result.addProperty("changeSetHash", changeSetHash);
    result.addProperty("targetBlockCount", blocks.size());
    result.addProperty("changeCount", changes.size());
    var difference = new JsonObject();
    difference.addProperty("added", added);
    difference.addProperty("replaced", replaced);
    difference.addProperty("removed", removed);
    result.add("difference", difference);
    result.addProperty("previewStatus", "server_validated");
    result.addProperty("worldWriteEnabled", false);
    return new Artifact(view, result, changeSetHash, baseRegionHash);
  }

  private static JsonObject transfer(
      Request request,
      UUID previewId,
      String baseRegionHash,
      String changeSetHash,
      String contentHash,
      String paletteHash,
      int blockCount,
      int added,
      int replaced,
      int removed,
      JsonArray palette,
      String encoding,
      int uncompressedBytes,
      byte[] transferBytes) {
    var result = new JsonObject();
    result.addProperty("schemaVersion", "1.0");
    result.addProperty("previewId", previewId.toString());
    result.addProperty("projectId", request.projectId().toString());
    result.addProperty("revision", request.revision());
    result.addProperty("operation", request.operation());
    result.addProperty("dimension", request.dimension());
    result.add("bounds", request.bounds().json());
    result.add("origin", positionJson(request.origin()));
    var transform = new JsonObject();
    transform.addProperty("rotation", request.rotation());
    transform.addProperty("mirror", request.mirror());
    result.add("transform", transform);
    result.addProperty("baseRegionHash", baseRegionHash);
    result.addProperty("changeSetHash", changeSetHash);
    result.addProperty("contentHash", contentHash);
    result.addProperty("paletteHash", paletteHash);
    result.addProperty("contentFormat", "minecraft-agent.palette-v1");
    result.addProperty("encoding", encoding);
    result.addProperty("compressedBytes", transferBytes.length);
    result.addProperty("uncompressedBytes", uncompressedBytes);
    result.addProperty("blockCount", blockCount);
    var difference = new JsonObject();
    difference.addProperty("added", added);
    difference.addProperty("replaced", replaced);
    difference.addProperty("removed", removed);
    result.add("difference", difference);
    result.add("palette", palette.deepCopy());
    var chunks = new JsonArray();
    for (var offset = 0; offset < transferBytes.length; offset += CHUNK_BYTES) {
      var length = Math.min(CHUNK_BYTES, transferBytes.length - offset);
      var bytes = java.util.Arrays.copyOfRange(transferBytes, offset, offset + length);
      var chunk = new JsonObject();
      chunk.addProperty("index", chunks.size());
      chunk.addProperty("byteLength", length);
      chunk.addProperty("sha256", sha256(bytes));
      chunk.addProperty("data", Base64.getEncoder().encodeToString(bytes));
      chunks.add(chunk);
    }
    result.addProperty("chunkCount", chunks.size());
    result.add("chunks", chunks);
    return result;
  }

  private static JsonArray palette(List<String> states) {
    var sorted = new java.util.TreeSet<String>();
    states.stream().filter(Predicate.not(AIR::equals)).forEach(sorted::add);
    var result = new JsonArray();
    for (var state : sorted) {
      var parsed = parseState(state);
      var entry = new JsonObject();
      entry.addProperty("id", result.size());
      entry.addProperty("blockId", parsed.blockId());
      var properties = new JsonObject();
      parsed.properties().forEach(properties::addProperty);
      entry.add("properties", properties);
      result.add(entry);
    }
    return result;
  }

  private static ParsedState parseState(String state) {
    var bracket = state.indexOf('[');
    var blockId = bracket < 0 ? state : state.substring(0, bracket);
    var properties = new TreeMap<String, String>();
    if (bracket >= 0) {
      if (!state.endsWith("]")) {
        throw new IllegalArgumentException("BUILD_BLOCK_STATE_INVALID");
      }
      var body = state.substring(bracket + 1, state.length() - 1);
      if (!body.isEmpty()) {
        for (var pair : body.split(",")) {
          var equals = pair.indexOf('=');
          if (equals < 1
              || equals == pair.length() - 1
              || properties.put(pair.substring(0, equals), pair.substring(equals + 1)) != null) {
            throw new IllegalArgumentException("BUILD_BLOCK_STATE_INVALID");
          }
        }
      }
    }
    return new ParsedState(blockId, Map.copyOf(properties));
  }

  private static String canonicalState(JsonObject paletteEntry) {
    var properties = paletteEntry.getAsJsonObject("properties");
    if (properties.isEmpty()) {
      return paletteEntry.get("blockId").getAsString();
    }
    var values = new TreeMap<String, String>();
    properties
        .entrySet()
        .forEach(entry -> values.put(entry.getKey(), entry.getValue().getAsString()));
    return paletteEntry.get("blockId").getAsString()
        + "["
        + values.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  static String canonicalBlockState(String state) {
    var parsed = parseState(state);
    if (parsed.properties().isEmpty()) {
      return parsed.blockId();
    }
    return parsed.blockId()
        + "["
        + new TreeMap<>(parsed.properties())
            .entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private static JsonObject positionJson(Position position) {
    var result = new JsonObject();
    result.addProperty("x", position.x());
    result.addProperty("y", position.y());
    result.addProperty("z", position.z());
    return result;
  }

  private static String domainHash(String domain, JsonObject value) {
    var prefix = domain.getBytes(StandardCharsets.UTF_8);
    var content = canonical(value).getBytes(StandardCharsets.UTF_8);
    var combined = new byte[prefix.length + 1 + content.length];
    System.arraycopy(prefix, 0, combined, 0, prefix.length);
    System.arraycopy(content, 0, combined, prefix.length + 1, content.length);
    return sha256(combined);
  }

  private static String canonical(com.google.gson.JsonElement value) {
    try {
      return new JsonCanonicalizer(value.toString()).getEncodedString();
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("BUILD_CANONICALIZATION_FAILED", error);
    }
  }

  private static byte[] gzip(byte[] content) {
    try {
      var output = new ByteArrayOutputStream();
      try (var gzip = new GZIPOutputStream(output)) {
        gzip.write(content);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Gzip memory write failed", error);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  public record Artifact(
      ClientStructuredView view,
      JsonObject toolResult,
      String changeSetHash,
      String baseRegionHash) {
    public Artifact {
      Objects.requireNonNull(view);
      toolResult = Objects.requireNonNull(toolResult).deepCopy();
      Objects.requireNonNull(changeSetHash);
      Objects.requireNonNull(baseRegionHash);
    }

    @Override
    public JsonObject toolResult() {
      return toolResult.deepCopy();
    }
  }

  public record Request(
      UUID requestId,
      String serverId,
      UUID worldUuid,
      String dimension,
      UUID projectId,
      int revision,
      String operation,
      Bounds bounds,
      Position origin,
      Pattern pattern,
      String blockState,
      int rotation,
      String mirror) {
    public Request {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(serverId);
      Objects.requireNonNull(worldUuid);
      Objects.requireNonNull(dimension);
      Objects.requireNonNull(projectId);
      if (revision < 1 || !SetValues.OPERATIONS.contains(operation)) {
        throw new IllegalArgumentException("BUILD_REQUEST_INVALID");
      }
      Objects.requireNonNull(bounds);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(pattern);
      if (pattern == Pattern.CLEAR
          ? blockState != null
          : blockState == null || AIR.equals(blockState)) {
        throw new IllegalArgumentException("BUILD_BLOCK_STATE_INVALID");
      }
      if (!SetValues.ROTATIONS.contains(rotation) || !SetValues.MIRRORS.contains(mirror)) {
        throw new IllegalArgumentException("BUILD_TRANSFORM_INVALID");
      }
    }
  }

  public record Cell(Position position, String state) {
    public Cell {
      Objects.requireNonNull(position);
      Objects.requireNonNull(state);
    }
  }

  public record Position(int x, int y, int z) {}

  public record Bounds(Position min, Position max) {
    public Bounds {
      Objects.requireNonNull(min);
      Objects.requireNonNull(max);
      if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
        throw new IllegalArgumentException("BUILD_BOUNDS_INVALID");
      }
      var sizeX = max.x() - min.x() + 1;
      var sizeY = max.y() - min.y() + 1;
      var sizeZ = max.z() - min.z() + 1;
      var volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
      if (sizeX > 32 || sizeY > 32 || sizeZ > 32 || volume > 4096) {
        throw new IllegalArgumentException("BUILD_BOUNDS_LIMIT_EXCEEDED");
      }
    }

    public int sizeX() {
      return max.x() - min.x() + 1;
    }

    public int sizeY() {
      return max.y() - min.y() + 1;
    }

    public int sizeZ() {
      return max.z() - min.z() + 1;
    }

    public int volume() {
      return Math.multiplyExact(Math.multiplyExact(sizeX(), sizeY()), sizeZ());
    }

    public boolean contains(Position position) {
      return position.x() >= min.x()
          && position.x() <= max.x()
          && position.y() >= min.y()
          && position.y() <= max.y()
          && position.z() >= min.z()
          && position.z() <= max.z();
    }

    public List<Position> positions() {
      var result = new ArrayList<Position>(volume());
      for (var y = min.y(); y <= max.y(); y++) {
        for (var z = min.z(); z <= max.z(); z++) {
          for (var x = min.x(); x <= max.x(); x++) {
            result.add(new Position(x, y, z));
          }
        }
      }
      return List.copyOf(result);
    }

    public JsonObject json() {
      var result = new JsonObject();
      result.add("min", positionJson(min));
      result.add("max", positionJson(max));
      return result;
    }
  }

  public enum Pattern {
    SOLID,
    HOLLOW,
    WALLS,
    FLOOR,
    CLEAR;

    boolean contains(Bounds bounds, Position position) {
      return switch (this) {
        case SOLID -> true;
        case HOLLOW ->
            position.x() == bounds.min().x()
                || position.x() == bounds.max().x()
                || position.y() == bounds.min().y()
                || position.y() == bounds.max().y()
                || position.z() == bounds.min().z()
                || position.z() == bounds.max().z();
        case WALLS ->
            position.x() == bounds.min().x()
                || position.x() == bounds.max().x()
                || position.z() == bounds.min().z()
                || position.z() == bounds.max().z();
        case FLOOR -> position.y() == bounds.min().y();
        case CLEAR -> true;
      };
    }
  }

  private record ParsedState(String blockId, Map<String, String> properties) {}

  private static final class SetValues {
    private static final java.util.Set<String> OPERATIONS = java.util.Set.of("create", "modify");
    private static final java.util.Set<Integer> ROTATIONS = java.util.Set.of(0, 90, 180, 270);
    private static final java.util.Set<String> MIRRORS =
        java.util.Set.of("NONE", "LEFT_RIGHT", "FRONT_BACK");
  }
}
