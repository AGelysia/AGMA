package dev.minecraftagent.standalone.common.preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The model-facing structural feedback of one build preview: how many changed blocks float over
 * air, how many air cells are sealed inside the build, and a bounded ASCII top view with its symbol
 * legend. Computed from the FINAL state (the region snapshot with the target overrides applied);
 * cells outside the preview bounds count as non-air, so the analysis only claims what the observed
 * region proves.
 */
public record PreviewAnalysis(
    int floatingCells,
    int interiorAirCells,
    List<String> topView,
    Map<String, String> topViewLegend) {
  public static final int MAXIMUM_TOP_VIEW_ROWS = 48;
  public static final int MAXIMUM_TOP_VIEW_WIDTH = 96;
  public static final int MAXIMUM_LEGEND_ENTRIES = 32;

  private static final char EMPTY_COLUMN = '.';
  private static final char UNMAPPED_BLOCK = '?';
  private static final String SYMBOLS =
      "#+%oabcdefghijklmnpqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

  public PreviewAnalysis {
    Objects.requireNonNull(topView, "topView");
    Objects.requireNonNull(topViewLegend, "topViewLegend");
    if (floatingCells < 0
        || floatingCells > PreviewEngine.MAXIMUM_UNION_VOLUME
        || interiorAirCells < 0
        || interiorAirCells > PreviewEngine.MAXIMUM_UNION_VOLUME
        || topView.size() > MAXIMUM_TOP_VIEW_ROWS
        || topView.stream().anyMatch(row -> row.isEmpty() || row.length() > MAXIMUM_TOP_VIEW_WIDTH)
        || topViewLegend.size() > MAXIMUM_LEGEND_ENTRIES
        || topViewLegend.keySet().stream().anyMatch(symbol -> symbol.length() != 1)) {
      throw new IllegalArgumentException("preview analysis is invalid");
    }
    topView = List.copyOf(topView);
    topViewLegend = Collections.unmodifiableMap(new LinkedHashMap<>(topViewLegend));
  }

  /** The zeroed analysis of a preview artifact that carries no region snapshot. */
  public static PreviewAnalysis empty() {
    return new PreviewAnalysis(0, 0, List.of(), Map.of());
  }

  /**
   * Computes the analysis from the transformed targets and the observed region snapshot. The
   * non-air selection and the palette ordering mirror {@link PreviewEngine#build}, and the top view
   * reuses {@link PreviewProjection}'s topmost-block-per-column projection.
   */
  static PreviewAnalysis compute(PreviewTargets targets, List<PreviewCell> regionCells) {
    Objects.requireNonNull(targets, "targets");
    Objects.requireNonNull(regionCells, "regionCells");
    var bounds = targets.unionBounds();
    Map<PreviewPosition, String> snapshot = new HashMap<>(regionCells.size() * 2);
    for (var cell : regionCells) {
      snapshot.put(cell.position(), cell.state());
    }
    Map<PreviewPosition, String> finalState = new HashMap<>(snapshot);
    finalState.putAll(targets.cells());
    var nonAirTargets = new ArrayList<PreviewCell>();
    var palette = new TreeSet<String>();
    for (var entry : targets.cells().entrySet()) {
      var position = entry.getKey();
      var target = entry.getValue();
      if (!PreviewEngine.AIR.equals(target)) {
        nonAirTargets.add(new PreviewCell(position.x(), position.y(), position.z(), target));
        palette.add(target);
      }
    }
    var topBlock = PreviewProjection.topBlockIds(bounds, nonAirTargets);
    var symbols = symbolAssignments(topBlock, palette);
    return new PreviewAnalysis(
        floatingCells(targets, snapshot, finalState),
        interiorAirCells(bounds, finalState),
        asciiRows(bounds, topBlock, symbols),
        legend(symbols));
  }

  /**
   * Changed non-air target cells whose below-neighbor is air in the final state. A cell below the
   * preview bounds is unobserved, so it never counts as floating.
   */
  private static int floatingCells(
      PreviewTargets targets,
      Map<PreviewPosition, String> snapshot,
      Map<PreviewPosition, String> finalState) {
    var floating = 0;
    for (var entry : targets.cells().entrySet()) {
      var target = entry.getValue();
      var position = entry.getKey();
      if (PreviewEngine.AIR.equals(target) || target.equals(snapshot.get(position))) {
        continue;
      }
      var below = finalState.get(new PreviewPosition(position.x(), position.y() - 1, position.z()));
      if (below != null && PreviewProjection.isAir(PreviewProjection.blockId(below))) {
        floating++;
      }
    }
    return floating;
  }

  /**
   * Air cells of the final state whose six direct neighbors are all non-air final cells. A neighbor
   * outside the preview bounds is unobserved, so the cell never counts as interior.
   */
  private static int interiorAirCells(
      PreviewBounds bounds, Map<PreviewPosition, String> finalState) {
    var interior = 0;
    for (var y = bounds.min().y(); y <= bounds.max().y(); y++) {
      for (var z = bounds.min().z(); z <= bounds.max().z(); z++) {
        for (var x = bounds.min().x(); x <= bounds.max().x(); x++) {
          var position = new PreviewPosition(x, y, z);
          var state = finalState.get(position);
          if (state == null || !PreviewProjection.isAir(PreviewProjection.blockId(state))) {
            continue;
          }
          if (isEnclosed(position, finalState)) {
            interior++;
          }
        }
      }
    }
    return interior;
  }

  private static boolean isEnclosed(
      PreviewPosition position, Map<PreviewPosition, String> finalState) {
    int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (var offset : offsets) {
      var neighbor =
          finalState.get(
              new PreviewPosition(
                  position.x() + offset[0], position.y() + offset[1], position.z() + offset[2]));
      if (neighbor == null || PreviewProjection.isAir(PreviewProjection.blockId(neighbor))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Assigns one symbol per shown block id in palette order (block ids absent from the palette last,
   * in id order), capped at the legend limit. Insertion order is the assignment order.
   */
  private static Map<String, Character> symbolAssignments(
      String[] topBlock, TreeSet<String> palette) {
    var shown = new TreeSet<String>();
    for (var blockId : topBlock) {
      if (blockId != null) {
        shown.add(blockId);
      }
    }
    Map<String, Character> symbols = new LinkedHashMap<>();
    for (var state : palette) {
      if (symbols.size() >= MAXIMUM_LEGEND_ENTRIES) {
        break;
      }
      var blockId = PreviewProjection.blockId(state);
      if (shown.contains(blockId) && !symbols.containsKey(blockId)) {
        symbols.put(blockId, SYMBOLS.charAt(symbols.size()));
      }
    }
    for (var blockId : shown) {
      if (symbols.size() >= MAXIMUM_LEGEND_ENTRIES) {
        break;
      }
      symbols.putIfAbsent(blockId, SYMBOLS.charAt(symbols.size()));
    }
    return symbols;
  }

  /**
   * The ASCII top view: one row per z line, one character per x column, downsampled by a whole cell
   * stride when the bounds exceed the view limits. Empty columns render as '.' and shown blocks
   * past the legend limit as '?'.
   */
  private static List<String> asciiRows(
      PreviewBounds bounds, String[] topBlock, Map<String, Character> symbols) {
    var sizeX = bounds.sizeX();
    var sizeZ = bounds.sizeZ();
    var stepX = (sizeX + MAXIMUM_TOP_VIEW_WIDTH - 1) / MAXIMUM_TOP_VIEW_WIDTH;
    var stepZ = (sizeZ + MAXIMUM_TOP_VIEW_ROWS - 1) / MAXIMUM_TOP_VIEW_ROWS;
    var rows = new ArrayList<String>((sizeZ + stepZ - 1) / stepZ);
    for (var z = 0; z < sizeZ; z += stepZ) {
      var row = new StringBuilder((sizeX + stepX - 1) / stepX);
      for (var x = 0; x < sizeX; x += stepX) {
        var blockId = topBlock[z * sizeX + x];
        if (blockId == null) {
          row.append(EMPTY_COLUMN);
        } else {
          var symbol = symbols.get(blockId);
          row.append(symbol == null ? UNMAPPED_BLOCK : symbol.charValue());
        }
      }
      rows.add(row.toString());
    }
    return rows;
  }

  private static Map<String, String> legend(Map<String, Character> symbols) {
    Map<String, String> legend = new LinkedHashMap<>();
    symbols.forEach((blockId, symbol) -> legend.put(String.valueOf(symbol), blockId));
    return legend;
  }
}
