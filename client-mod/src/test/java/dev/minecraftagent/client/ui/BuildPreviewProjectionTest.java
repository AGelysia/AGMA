package dev.minecraftagent.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minecraftagent.client.view.BuildPreviewView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BuildPreviewProjectionTest {
  @Test
  void projectsTopmostBlockPerColumnAndCountsLegend() {
    var palette =
        List.of(
            new BuildPreviewView.PaletteEntry(0, "minecraft:air", Map.of()),
            new BuildPreviewView.PaletteEntry(1, "minecraft:stone", Map.of()),
            new BuildPreviewView.PaletteEntry(2, "minecraft:glass", Map.of()));
    var preview =
        preview(
            bounds(0, 0, 0, 1, 3, 0),
            palette,
            List.of(
                new BuildPreviewView.PlacedBlock(1, pos(0, 0, 0)),
                new BuildPreviewView.PlacedBlock(2, pos(0, 2, 0)),
                new BuildPreviewView.PlacedBlock(1, pos(1, 0, 0)),
                new BuildPreviewView.PlacedBlock(1, pos(1, 1, 0)),
                new BuildPreviewView.PlacedBlock(0, pos(1, 2, 0)),
                // Outside the declared bounds: ignored entirely.
                new BuildPreviewView.PlacedBlock(2, pos(9, 9, 9))));

    var projection = BuildPreviewProjection.compute(preview, id -> 0x102030);

    // Column (0,0) tops at glass, column (1,0) tops at stone (air above is ignored for color).
    assertEquals(0x102030, projection.topColors()[0]);
    assertEquals(0x102030, projection.topColors()[1]);
    assertEquals(
        List.of(
            new BuildPreviewProjection.LegendRow("minecraft:stone", 3),
            new BuildPreviewProjection.LegendRow("minecraft:glass", 1)),
        projection.legend());
    assertEquals(4, projection.cell());
    assertEquals(8, projection.mapWidth());
  }

  @Test
  void airColumnsStayEmptyAndCellsShrinkForLargeBounds() {
    var palette =
        List.of(
            new BuildPreviewView.PaletteEntry(0, "minecraft:air", Map.of()),
            new BuildPreviewView.PaletteEntry(1, "minecraft:stone", Map.of()));
    var blocks = new java.util.ArrayList<BuildPreviewView.PlacedBlock>();
    blocks.add(new BuildPreviewView.PlacedBlock(0, pos(3, 5, 3)));
    blocks.add(new BuildPreviewView.PlacedBlock(1, pos(0, 0, 0)));
    var preview = preview(bounds(0, 0, 0, 31, 63, 31), palette, blocks);

    var projection = BuildPreviewProjection.compute(preview, id -> 0xFFFFFF);

    assertEquals(3, projection.cell());
    assertEquals(96, projection.mapWidth());
    assertEquals(0, projection.topColors()[3 * 32 + 3]);
    assertEquals(0xFFFFFF, projection.topColors()[0]);
    assertEquals(
        List.of(new BuildPreviewProjection.LegendRow("minecraft:stone", 1)), projection.legend());
  }

  @Test
  void legendIsLimitedSortedAndSkipsUnknownStates() {
    var palette =
        List.of(
            new BuildPreviewView.PaletteEntry(0, "modded:a", Map.of()),
            new BuildPreviewView.PaletteEntry(1, "modded:b", Map.of()),
            new BuildPreviewView.PaletteEntry(2, "modded:c", Map.of()),
            new BuildPreviewView.PaletteEntry(3, "modded:d", Map.of()),
            new BuildPreviewView.PaletteEntry(4, "modded:e", Map.of()),
            new BuildPreviewView.PaletteEntry(5, "modded:f", Map.of()),
            new BuildPreviewView.PaletteEntry(6, "modded:g", Map.of()));
    var blocks = new java.util.ArrayList<BuildPreviewView.PlacedBlock>();
    // Counts: b=1, c=2, d=3, e=4, f=5, g=6, unknown-state 7 = 100 (excluded), a=1 (tie with b).
    for (var state = 1; state <= 6; state++) {
      for (var index = 0; index < state; index++) {
        blocks.add(new BuildPreviewView.PlacedBlock(state, pos(index % 8, index / 8, state)));
      }
    }
    for (var index = 0; index < 100; index++) {
      blocks.add(new BuildPreviewView.PlacedBlock(7, pos(index % 10, index / 10, 9)));
    }
    blocks.add(new BuildPreviewView.PlacedBlock(0, pos(7, 0, 7)));
    var preview = preview(bounds(0, 0, 0, 9, 63, 9), palette, blocks);

    var legend = BuildPreviewProjection.compute(preview, id -> 0x000001).legend();

    assertEquals(
        List.of("modded:g", "modded:f", "modded:e", "modded:d", "modded:c", "modded:a"),
        legend.stream().map(BuildPreviewProjection.LegendRow::blockId).toList());
    assertEquals(6, legend.size());
  }

  private static BuildPreviewView.Position pos(int x, int y, int z) {
    return new BuildPreviewView.Position(x, y, z);
  }

  private static BuildPreviewView.Bounds bounds(
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    return new BuildPreviewView.Bounds(pos(minX, minY, minZ), pos(maxX, maxY, maxZ));
  }

  private static BuildPreviewView preview(
      BuildPreviewView.Bounds bounds,
      List<BuildPreviewView.PaletteEntry> palette,
      List<BuildPreviewView.PlacedBlock> blocks) {
    return new BuildPreviewView(
        "1.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        BuildPreviewView.Operation.CREATE,
        "minecraft:overworld",
        bounds,
        pos(0, 0, 0),
        new BuildPreviewView.Transform(0, BuildPreviewView.Mirror.NONE),
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        new BuildPreviewView.Difference(blocks.size(), 0, 0),
        palette,
        blocks);
  }
}
