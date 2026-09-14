package dev.minecraftagent.standalone.common.preview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pure, game-free build preview engine: ordered shape application, mirror/rotation around the
 * origin, diffing against a caller-supplied region snapshot, and the domain-separated SHA-256
 * artifact hashes.
 *
 * <p>Shape bounds are RELATIVE to the request origin: the engine first translates every shape by
 * the origin, then applies mirror/rotation of each cell around the origin. With rotation 0 and no
 * mirror the world cell is exactly {@code origin + relative cell}, which is how the model designs
 * buildings (small non-negative offsets next to the player). Result union bounds are always
 * world-absolute.
 *
 * <p>Hash canonical encoding (UTF-8 text, lines joined with "\n", cells sorted by numeric x, y, z):
 * the region state content is a {@code dimension=} line, a {@code bounds=minX,minY,minZ,maxX,
 * maxY,maxZ} line, then one {@code x,y,z,state} line per cell of the union region; the change set
 * content adds a {@code baseRegionHash=} line and one {@code x,y,z,expected,target} line per
 * changed cell. Each hash is SHA-256 over the domain string bytes, one zero byte, then the content
 * bytes, mirroring the server-line domains "minecraft-agent/region-state/v1" and
 * "minecraft-agent/change-set/v1" from the paper BuildPreviewArtifactFactory.
 */
public final class PreviewEngine {
  public static final int MAXIMUM_SHAPES = 24;
  public static final int MAXIMUM_SHAPE_AXIS = 64;
  public static final int MAXIMUM_SHAPE_VOLUME = 16_384;
  public static final int MAXIMUM_UNION_VOLUME = 16_384;
  public static final String AIR = "minecraft:air";

  private static final String REGION_STATE_DOMAIN = "minecraft-agent/region-state/v1";
  private static final String CHANGE_SET_DOMAIN = "minecraft-agent/change-set/v1";

  private PreviewEngine() {}

  /**
   * Applies shapes in array order (later shapes override earlier ones per cell) and transforms
   * every target cell around the origin, returning the per-cell targets and the union of the
   * transformed shape bounds. Shape bounds are relative to the origin (see class javadoc).
   * Block-state properties are intentionally not rewritten by the transform: the server line
   * carries rotation/mirror as artifact metadata without rotating facing-type property values, and
   * this engine mirrors that choice.
   */
  public static PreviewTargets prepare(PreviewRequest request) {
    Objects.requireNonNull(request, "request");
    var targets = new LinkedHashMap<PreviewPosition, String>();
    PreviewBounds union = null;
    for (var shape : request.shapes()) {
      var bounds = offsetBy(shape.bounds(), request.origin());
      var transformedBounds =
          transform(bounds, request.origin(), request.rotation(), request.mirror());
      union = union == null ? transformedBounds : union.union(transformedBounds);
      // Long math: extreme origins can push union spans past int overflow before the limit check.
      var unionVolume = (long) union.sizeX() * union.sizeY() * union.sizeZ();
      if (unionVolume > MAXIMUM_UNION_VOLUME) {
        throw new PreviewLimitException(
            "the transformed preview bounds exceed the union volume limit");
      }
      var target = shape.pattern() == PreviewPattern.CLEAR ? AIR : shape.blockState();
      for (var y = bounds.min().y(); y <= bounds.max().y(); y++) {
        for (var z = bounds.min().z(); z <= bounds.max().z(); z++) {
          for (var x = bounds.min().x(); x <= bounds.max().x(); x++) {
            var position = new PreviewPosition(x, y, z);
            if (shape.pattern().contains(bounds, position)) {
              targets.put(
                  transform(position, request.origin(), request.rotation(), request.mirror()),
                  target);
            }
          }
        }
      }
    }
    if (union == null) {
      throw new IllegalArgumentException("preview request carries no shapes");
    }
    return new PreviewTargets(targets, union);
  }

  /** Translates relative shape bounds into world-absolute bounds around the origin. */
  private static PreviewBounds offsetBy(PreviewBounds bounds, PreviewPosition origin) {
    return new PreviewBounds(
        new PreviewPosition(
            bounds.min().x() + origin.x(),
            bounds.min().y() + origin.y(),
            bounds.min().z() + origin.z()),
        new PreviewPosition(
            bounds.max().x() + origin.x(),
            bounds.max().y() + origin.y(),
            bounds.max().z() + origin.z()));
  }

  /** Mirror (around the origin, before rotation) then rotation of one cell in the XZ plane. */
  public static PreviewPosition transform(
      PreviewPosition position, PreviewPosition origin, int rotation, PreviewMirror mirror) {
    var dx = position.x() - origin.x();
    var dz = position.z() - origin.z();
    switch (mirror) {
      case LEFT_RIGHT -> dx = -dx;
      case FRONT_BACK -> dz = -dz;
      case NONE -> {}
    }
    return switch (rotation) {
      case 0 -> new PreviewPosition(origin.x() + dx, position.y(), origin.z() + dz);
      case 90 -> new PreviewPosition(origin.x() + dz, position.y(), origin.z() - dx);
      case 180 -> new PreviewPosition(origin.x() - dx, position.y(), origin.z() - dz);
      case 270 -> new PreviewPosition(origin.x() - dz, position.y(), origin.z() + dx);
      default -> throw new IllegalArgumentException("preview rotation is invalid");
    };
  }

  /** Transforms bounds by transforming all eight corners and re-fitting the axis-aligned box. */
  public static PreviewBounds transform(
      PreviewBounds bounds, PreviewPosition origin, int rotation, PreviewMirror mirror) {
    var minX = Integer.MAX_VALUE;
    var minY = Integer.MAX_VALUE;
    var minZ = Integer.MAX_VALUE;
    var maxX = Integer.MIN_VALUE;
    var maxY = Integer.MIN_VALUE;
    var maxZ = Integer.MIN_VALUE;
    for (var x = 0; x <= 1; x++) {
      for (var y = 0; y <= 1; y++) {
        for (var z = 0; z <= 1; z++) {
          var corner =
              transform(
                  new PreviewPosition(
                      x == 0 ? bounds.min().x() : bounds.max().x(),
                      y == 0 ? bounds.min().y() : bounds.max().y(),
                      z == 0 ? bounds.min().z() : bounds.max().z()),
                  origin,
                  rotation,
                  mirror);
          minX = Math.min(minX, corner.x());
          minY = Math.min(minY, corner.y());
          minZ = Math.min(minZ, corner.z());
          maxX = Math.max(maxX, corner.x());
          maxY = Math.max(maxY, corner.y());
          maxZ = Math.max(maxZ, corner.z());
        }
      }
    }
    return new PreviewBounds(
        new PreviewPosition(minX, minY, minZ), new PreviewPosition(maxX, maxY, maxZ));
  }

  /**
   * Reads every cell of the region through the lookup in a deterministic order. Must be called on
   * the thread that owns the world; a null lookup result aborts with {@link
   * PreviewChunkUnavailableException}.
   */
  public static List<PreviewCell> snapshot(PreviewBounds region, BlockLookup lookup) {
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(lookup, "lookup");
    var cells = new ArrayList<PreviewCell>(region.volume());
    for (var y = region.min().y(); y <= region.max().y(); y++) {
      for (var z = region.min().z(); z <= region.max().z(); z++) {
        for (var x = region.min().x(); x <= region.max().x(); x++) {
          var state = lookup.stateAt(x, y, z);
          if (state == null) {
            throw new PreviewChunkUnavailableException("a region cell is in an unavailable chunk");
          }
          cells.add(new PreviewCell(x, y, z, state));
        }
      }
    }
    return List.copyOf(cells);
  }

  /**
   * Computes the immutable preview artifact from the transformed targets and the observed region
   * snapshot. Pure: safe to run on any worker thread once the snapshot exists.
   */
  public static StandalonePreview build(
      PreviewRequest request, PreviewTargets targets, List<PreviewCell> regionCells) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(targets, "targets");
    regionCells = List.copyOf(regionCells);
    var region = targets.unionBounds();
    var current = new HashMap<PreviewPosition, String>(regionCells.size() * 2);
    for (var cell : regionCells) {
      current.put(cell.position(), cell.state());
    }
    if (current.size() != regionCells.size() || current.size() != region.volume()) {
      throw new IllegalArgumentException("preview region snapshot is incomplete");
    }

    var added = 0;
    var replaced = 0;
    var removed = 0;
    var changes = new ArrayList<Change>();
    var nonAirTargets = new ArrayList<PreviewCell>();
    for (var entry : targets.cells().entrySet()) {
      var position = entry.getKey();
      var target = entry.getValue();
      var currentState = current.get(position);
      if (currentState == null) {
        throw new IllegalArgumentException("preview target lies outside the region snapshot");
      }
      if (!AIR.equals(target)) {
        nonAirTargets.add(new PreviewCell(position.x(), position.y(), position.z(), target));
      }
      if (currentState.equals(target)) {
        continue;
      }
      if (AIR.equals(currentState)) {
        added++;
      } else if (AIR.equals(target)) {
        removed++;
      } else {
        replaced++;
      }
      changes.add(new Change(position.x(), position.y(), position.z(), currentState, target));
    }
    nonAirTargets.sort(Comparator.naturalOrder());
    changes.sort(Comparator.naturalOrder());
    var palette = new TreeSet<>(nonAirTargets.stream().map(PreviewCell::state).toList());
    var sortedRegion = new ArrayList<>(regionCells);
    sortedRegion.sort(Comparator.naturalOrder());

    var baseRegionHash =
        domainHash(REGION_STATE_DOMAIN, regionContent(request.dimension(), region, sortedRegion));
    var changeSetHash =
        domainHash(
            CHANGE_SET_DOMAIN,
            changeSetContent(request.dimension(), region, baseRegionHash, changes));
    var previewId =
        UUID.nameUUIDFromBytes(
            (request.projectId() + ":" + request.revision() + ":" + changeSetHash)
                .getBytes(StandardCharsets.UTF_8));
    var analysis = PreviewAnalysis.compute(targets, regionCells);
    return new StandalonePreview(
        previewId,
        request.projectId(),
        request.revision(),
        request.operation(),
        request.dimension(),
        region,
        request.origin(),
        request.rotation(),
        request.mirror(),
        baseRegionHash,
        changeSetHash,
        nonAirTargets.size(),
        changes.size(),
        new PreviewDifference(added, replaced, removed),
        nonAirTargets,
        List.copyOf(palette),
        analysis);
  }

  private static String regionContent(
      String dimension, PreviewBounds region, List<PreviewCell> sortedCells) {
    var content = new StringBuilder();
    content.append("dimension=").append(dimension).append('\n');
    content.append("bounds=").append(boundsLine(region));
    for (var cell : sortedCells) {
      content
          .append('\n')
          .append(cell.x())
          .append(',')
          .append(cell.y())
          .append(',')
          .append(cell.z())
          .append(',')
          .append(cell.state());
    }
    return content.toString();
  }

  private static String changeSetContent(
      String dimension, PreviewBounds region, String baseRegionHash, List<Change> sortedChanges) {
    var content = new StringBuilder();
    content.append("dimension=").append(dimension).append('\n');
    content.append("bounds=").append(boundsLine(region)).append('\n');
    content.append("baseRegionHash=").append(baseRegionHash);
    for (var change : sortedChanges) {
      content
          .append('\n')
          .append(change.x())
          .append(',')
          .append(change.y())
          .append(',')
          .append(change.z())
          .append(',')
          .append(change.expected())
          .append(',')
          .append(change.target());
    }
    return content.toString();
  }

  private static String boundsLine(PreviewBounds bounds) {
    return bounds.min().x()
        + ","
        + bounds.min().y()
        + ","
        + bounds.min().z()
        + ","
        + bounds.max().x()
        + ","
        + bounds.max().y()
        + ","
        + bounds.max().z();
  }

  private static String domainHash(String domain, String content) {
    var prefix = domain.getBytes(StandardCharsets.UTF_8);
    var body = content.getBytes(StandardCharsets.UTF_8);
    var combined = new byte[prefix.length + 1 + body.length];
    System.arraycopy(prefix, 0, combined, 0, prefix.length);
    System.arraycopy(body, 0, combined, prefix.length + 1, body.length);
    return sha256(combined);
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM does not provide SHA-256", error);
    }
  }

  private record Change(int x, int y, int z, String expected, String target)
      implements Comparable<Change> {
    @Override
    public int compareTo(Change other) {
      var order = Integer.compare(x, other.x);
      if (order != 0) {
        return order;
      }
      order = Integer.compare(y, other.y);
      return order != 0 ? order : Integer.compare(z, other.z);
    }
  }
}
