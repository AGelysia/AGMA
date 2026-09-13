package dev.minecraftagent.standalone.common.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Game-free top-view projection of a build preview: the topmost placed block per X/Z column as a
 * color grid, plus the per-block legend counts. Colors come from a resolver so the computation
 * itself never touches the game registry. Ported from the server line's client-mod
 * BuildPreviewProjection; the air-palette filtering stays even though preview cells only hold
 * non-air targets, keeping the projection safe for any caller.
 */
public final class PreviewProjection {
  public static final int LEGEND_LIMIT = 6;

  public record LegendRow(String blockId, int count) {}

  public record Projection(
      int cell,
      int sizeX,
      int sizeZ,
      int mapWidth,
      int mapHeight,
      int[] topColors,
      List<LegendRow> legend) {}

  private PreviewProjection() {}

  public static Projection compute(StandalonePreview preview, ToIntFunction<String> blockColor) {
    Objects.requireNonNull(preview, "preview");
    Objects.requireNonNull(blockColor, "blockColor");
    var bounds = preview.bounds();
    var sizeX = bounds.sizeX();
    var sizeZ = bounds.sizeZ();
    var topY = new int[sizeX * sizeZ];
    Arrays.fill(topY, Integer.MIN_VALUE);
    var topBlock = new String[sizeX * sizeZ];
    Map<String, Integer> counts = new HashMap<>();
    for (var cell : preview.cells()) {
      var cellX = cell.x() - bounds.min().x();
      var cellZ = cell.z() - bounds.min().z();
      if (cellX < 0 || cellX >= sizeX || cellZ < 0 || cellZ >= sizeZ) {
        continue;
      }
      var blockId = blockId(cell.state());
      if (isAir(blockId)) {
        continue;
      }
      counts.merge(blockId, 1, Integer::sum);
      var index = cellZ * sizeX + cellX;
      if (cell.y() > topY[index]) {
        topY[index] = cell.y();
        topBlock[index] = blockId;
      }
    }
    var topColors = new int[sizeX * sizeZ];
    for (var index = 0; index < topBlock.length; index++) {
      if (topBlock[index] != null) {
        topColors[index] = blockColor.applyAsInt(topBlock[index]);
      }
    }
    List<LegendRow> legend = new ArrayList<>();
    counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(LEGEND_LIMIT)
        .forEach(entry -> legend.add(new LegendRow(entry.getKey(), entry.getValue())));
    var cell = Math.max(sizeX, sizeZ) > 24 ? 3 : 4;
    return new Projection(
        cell, sizeX, sizeZ, sizeX * cell, sizeZ * cell, topColors, List.copyOf(legend));
  }

  static String blockId(String state) {
    var bracket = state.indexOf('[');
    return bracket < 0 ? state : state.substring(0, bracket);
  }

  private static boolean isAir(String blockId) {
    return "minecraft:air".equals(blockId)
        || "minecraft:cave_air".equals(blockId)
        || "minecraft:void_air".equals(blockId);
  }
}
