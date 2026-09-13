package dev.minecraftagent.client.ui;

import dev.minecraftagent.client.view.BuildPreviewView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Game-free top-view projection of a build preview: the topmost placed block per column, palette
 * entry counts for the legend, and the cell size of the rendered map. Colors come from a resolver
 * so the computation itself never touches the game registry.
 */
final class BuildPreviewProjection {
  static final int LEGEND_LIMIT = 6;

  record LegendRow(String blockId, int count) {}

  record Projection(
      int cell,
      int sizeX,
      int sizeZ,
      int mapWidth,
      int mapHeight,
      int[] topColors,
      List<LegendRow> legend) {}

  private BuildPreviewProjection() {}

  static Projection compute(BuildPreviewView preview, ToIntFunction<String> blockColor) {
    BuildPreviewView.Bounds bounds = preview.bounds();
    int sizeX = bounds.sizeX();
    int sizeZ = bounds.sizeZ();
    var airStates = new java.util.HashSet<Integer>();
    for (BuildPreviewView.PaletteEntry entry : preview.palette()) {
      if (isAir(entry.blockId())) {
        airStates.add(entry.id());
      }
    }
    int[] topState = new int[sizeX * sizeZ];
    Arrays.fill(topState, -1);
    int[] topY = new int[sizeX * sizeZ];
    Arrays.fill(topY, Integer.MIN_VALUE);
    Map<Integer, Integer> counts = new HashMap<>();
    for (BuildPreviewView.PlacedBlock block : preview.blocks()) {
      int cellX = block.position().x() - bounds.min().x();
      int cellZ = block.position().z() - bounds.min().z();
      if (cellX < 0 || cellX >= sizeX || cellZ < 0 || cellZ >= sizeZ) {
        continue;
      }
      counts.merge(block.state(), 1, Integer::sum);
      if (airStates.contains(block.state())) {
        continue;
      }
      int index = cellZ * sizeX + cellX;
      if (block.position().y() > topY[index]) {
        topY[index] = block.position().y();
        topState[index] = block.state();
      }
    }
    int[] topColors = new int[sizeX * sizeZ];
    for (int index = 0; index < topState.length; index++) {
      if (topState[index] >= 0) {
        String blockId = paletteBlockId(preview, topState[index]);
        if (blockId != null && !isAir(blockId)) {
          topColors[index] = blockColor.applyAsInt(blockId);
        }
      }
    }
    List<LegendRow> legend = new ArrayList<>();
    counts.entrySet().stream()
        .filter(entry -> paletteBlockId(preview, entry.getKey()) != null)
        .filter(entry -> !isAir(paletteBlockId(preview, entry.getKey())))
        .sorted(
            Map.Entry.<Integer, Integer>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(LEGEND_LIMIT)
        .forEach(
            entry ->
                legend.add(
                    new LegendRow(paletteBlockId(preview, entry.getKey()), entry.getValue())));
    int cell = Math.max(sizeX, sizeZ) > 24 ? 3 : 4;
    return new Projection(
        cell, sizeX, sizeZ, sizeX * cell, sizeZ * cell, topColors, List.copyOf(legend));
  }

  private static String paletteBlockId(BuildPreviewView preview, int state) {
    for (BuildPreviewView.PaletteEntry entry : preview.palette()) {
      if (entry.id() == state) {
        return entry.blockId();
      }
    }
    return null;
  }

  private static boolean isAir(String blockId) {
    return "minecraft:air".equals(blockId)
        || "minecraft:cave_air".equals(blockId)
        || "minecraft:void_air".equals(blockId);
  }
}
