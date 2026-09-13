package dev.minecraftagent.standalone.common.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewProjectionTest {
  private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String STONE = "minecraft:stone";
  private static final String DIRT = "minecraft:dirt";

  @Test
  void projectsTheTopmostNonAirBlockPerColumn() {
    var preview =
        preview(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 66, 0)),
            List.of(
                new PreviewCell(0, 64, 0, STONE),
                new PreviewCell(0, 66, 0, DIRT),
                new PreviewCell(1, 64, 0, STONE)));
    var colors = Map.of(STONE, 0xff111111, DIRT, 0xff222222);
    var projection = PreviewProjection.compute(preview, id -> colors.getOrDefault(id, 0));
    assertEquals(2, projection.sizeX());
    assertEquals(1, projection.sizeZ());
    assertEquals(4, projection.cell());
    assertEquals(8, projection.mapWidth());
    assertEquals(4, projection.mapHeight());
    assertEquals(0xff222222, projection.topColors()[0]);
    assertEquals(0xff111111, projection.topColors()[1]);
    assertEquals(
        List.of(
            new PreviewProjection.LegendRow(STONE, 2), new PreviewProjection.LegendRow(DIRT, 1)),
        projection.legend());
  }

  @Test
  void skipsAirCellsInColumnsAndLegend() {
    var preview =
        preview(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(0, 66, 0)),
            List.of(new PreviewCell(0, 64, 0, STONE), new PreviewCell(0, 66, 0, "minecraft:air")));
    var projection = PreviewProjection.compute(preview, ignored -> 0xff010203);
    assertEquals(0xff010203, projection.topColors()[0]);
    assertEquals(List.of(new PreviewProjection.LegendRow(STONE, 1)), projection.legend());
  }

  @Test
  void shrinksCellsBeyondTwentyFourColumns() {
    var cells = new java.util.ArrayList<PreviewCell>();
    for (var x = 0; x < 25; x++) {
      cells.add(new PreviewCell(x, 64, 0, STONE));
    }
    var preview =
        preview(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(24, 64, 0)),
            cells);
    var projection = PreviewProjection.compute(preview, ignored -> 0xff0000ff);
    assertEquals(3, projection.cell());
    assertEquals(75, projection.mapWidth());
    assertEquals(3, projection.mapHeight());
    assertEquals(List.of(new PreviewProjection.LegendRow(STONE, 25)), projection.legend());
  }

  @Test
  void limitsTheLegendToSixEntriesOrderedByCountThenBlockId() {
    var cells = new java.util.ArrayList<PreviewCell>();
    for (var index = 0; index < 7; index++) {
      var state = "minecraft:block_" + (char) ('a' + index);
      for (var count = 0; count <= index; count++) {
        cells.add(new PreviewCell(cells.size(), 64, 0, state));
      }
    }
    var preview =
        preview(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(27, 64, 0)),
            cells);
    var projection = PreviewProjection.compute(preview, ignored -> 0xffffffff);
    assertEquals(PreviewProjection.LEGEND_LIMIT, projection.legend().size());
    assertEquals(
        new PreviewProjection.LegendRow("minecraft:block_g", 7), projection.legend().get(0));
    assertEquals(
        new PreviewProjection.LegendRow("minecraft:block_b", 2), projection.legend().get(5));
    assertTrue(
        projection.legend().stream().noneMatch(row -> row.blockId().equals("minecraft:block_a")));
  }

  @Test
  void stripsBlockStatePropertiesForColorsAndLegend() {
    var preview =
        preview(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(0, 64, 0)),
            List.of(new PreviewCell(0, 64, 0, "minecraft:oak_stairs[facing=north,half=bottom]")));
    var projection =
        PreviewProjection.compute(
            preview, blockId -> "minecraft:oak_stairs".equals(blockId) ? 7 : 0);
    assertEquals(7, projection.topColors()[0]);
    assertEquals(
        List.of(new PreviewProjection.LegendRow("minecraft:oak_stairs", 1)), projection.legend());
  }

  private static StandalonePreview preview(PreviewBounds bounds, List<PreviewCell> cells) {
    var palette = cells.stream().map(PreviewCell::state).distinct().sorted().toList();
    return new StandalonePreview(
        UUID.nameUUIDFromBytes(new byte[] {1}),
        PROJECT_ID,
        1,
        PreviewOperation.CREATE,
        "minecraft:overworld",
        bounds,
        new PreviewPosition(0, 64, 0),
        0,
        PreviewMirror.NONE,
        "0".repeat(64),
        "1".repeat(64),
        cells.size(),
        0,
        new PreviewDifference(0, 0, 0),
        cells,
        palette);
  }
}
