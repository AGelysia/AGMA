package dev.minecraftagent.standalone.common.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewAnalysisTest {
  private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String STONE = "minecraft:stone";
  private static final String DIRT = "minecraft:dirt";

  @Test
  void countsChangedBlocksOverAirAsFloating() {
    var preview =
        buildPreview(
            List.of(
                shape(1, 64, 1, 1, 64, 1, PreviewPattern.SOLID, STONE),
                shape(0, 65, 0, 2, 65, 2, PreviewPattern.SOLID, STONE)),
            Map.of());
    var analysis = preview.analysis();
    // The top plate has eight cells over air; the center column is supported, and the cell below
    // the bounds is unobserved rather than air.
    assertEquals(8, analysis.floatingCells());
    assertEquals(0, analysis.interiorAirCells());
    assertEquals(List.of("###", "###", "###"), analysis.topView());
    assertEquals(Map.of("#", STONE), analysis.topViewLegend());
  }

  @Test
  void treatsWorldGroundAsSupportAndSkipsUnchangedCells() {
    var world = new HashMap<PreviewPosition, String>();
    for (var x = 0; x <= 2; x++) {
      for (var z = 0; z <= 2; z++) {
        world.put(new PreviewPosition(x, 64, z), STONE);
      }
    }
    var preview =
        buildPreview(
            List.of(
                shape(1, 64, 1, 1, 64, 1, PreviewPattern.SOLID, STONE),
                shape(0, 65, 0, 2, 65, 2, PreviewPattern.SOLID, STONE)),
            world);
    var analysis = preview.analysis();
    // Every plate cell rests on world stone; the column matches the world and is not a change.
    assertEquals(0, analysis.floatingCells());
    assertEquals(9, preview.changeCount());
  }

  @Test
  void countsCellsAboveCarvedOpeningsAsFloating() {
    var world = new HashMap<PreviewPosition, String>();
    for (var x = 0; x <= 2; x++) {
      for (var z = 0; z <= 2; z++) {
        world.put(new PreviewPosition(x, 64, z), STONE);
      }
    }
    var preview =
        buildPreview(
            List.of(
                shape(0, 64, 0, 2, 65, 2, PreviewPattern.SOLID, STONE),
                shape(1, 64, 1, 1, 64, 1, PreviewPattern.CLEAR, null)),
            world);
    var analysis = preview.analysis();
    // Only the cell directly above the carved block floats.
    assertEquals(1, analysis.floatingCells());
    assertEquals(0, analysis.interiorAirCells());
  }

  @Test
  void countsSealedAirAsInterior() {
    var preview =
        buildPreview(List.of(shape(0, 64, 0, 2, 66, 2, PreviewPattern.HOLLOW, STONE)), Map.of());
    var analysis = preview.analysis();
    assertEquals(1, analysis.interiorAirCells());
    // The roof center sits above the sealed air pocket.
    assertEquals(1, analysis.floatingCells());
    assertEquals(List.of("###", "###", "###"), analysis.topView());
  }

  @Test
  void openingsBreakInteriorEnclosure() {
    var preview =
        buildPreview(
            List.of(
                shape(0, 64, 0, 2, 66, 2, PreviewPattern.HOLLOW, STONE),
                shape(1, 64, 0, 1, 65, 0, PreviewPattern.CLEAR, null)),
            Map.of());
    assertEquals(0, preview.analysis().interiorAirCells());
  }

  @Test
  void assignsSymbolsInPaletteOrderAndRendersEmptyColumns() {
    var preview =
        buildPreview(
            List.of(
                shape(0, 64, 0, 0, 64, 0, PreviewPattern.SOLID, STONE),
                shape(0, 66, 0, 0, 66, 0, PreviewPattern.SOLID, DIRT),
                shape(1, 64, 0, 1, 64, 0, PreviewPattern.SOLID, STONE),
                shape(2, 64, 0, 2, 64, 0, PreviewPattern.CLEAR, null)),
            Map.of());
    var analysis = preview.analysis();
    // minecraft:dirt sorts before minecraft:stone in the palette, so dirt takes '#'.
    assertEquals(List.of("#+."), analysis.topView());
    assertEquals(List.of("#", "+"), List.copyOf(analysis.topViewLegend().keySet()));
    assertEquals(DIRT, analysis.topViewLegend().get("#"));
    assertEquals(STONE, analysis.topViewLegend().get("+"));
  }

  @Test
  void rendersRowsFromMinimumZToMaximumZ() {
    var preview =
        buildPreview(
            List.of(
                shape(0, 64, 0, 0, 64, 0, PreviewPattern.SOLID, STONE),
                shape(0, 64, 1, 0, 64, 1, PreviewPattern.CLEAR, null),
                shape(0, 64, 2, 0, 64, 2, PreviewPattern.SOLID, DIRT)),
            Map.of());
    assertEquals(List.of("+", ".", "#"), preview.analysis().topView());
  }

  @Test
  void stripsBlockStatePropertiesInTheTopView() {
    var preview =
        buildPreview(
            List.of(
                shape(
                    0,
                    64,
                    0,
                    1,
                    64,
                    0,
                    PreviewPattern.SOLID,
                    "minecraft:oak_stairs[facing=north,half=bottom]")),
            Map.of());
    var analysis = preview.analysis();
    assertEquals(List.of("##"), analysis.topView());
    assertEquals(Map.of("#", "minecraft:oak_stairs"), analysis.topViewLegend());
  }

  @Test
  void downsamplesOversizedViewsToTheBounds() {
    var preview =
        buildPreview(List.of(shape(0, 64, 0, 99, 64, 99, PreviewPattern.SOLID, STONE)), Map.of());
    var analysis = preview.analysis();
    assertEquals(34, analysis.topView().size());
    assertTrue(analysis.topView().size() <= PreviewAnalysis.MAXIMUM_TOP_VIEW_ROWS);
    for (var row : analysis.topView()) {
      assertEquals(50, row.length());
      assertTrue(row.length() <= PreviewAnalysis.MAXIMUM_TOP_VIEW_WIDTH);
      assertTrue(row.chars().allMatch(character -> character == '#'));
    }
    assertEquals(Map.of("#", STONE), analysis.topViewLegend());
  }

  @Test
  void analysisIsDeterministicForTheSameInput() {
    var shapes =
        List.of(
            shape(0, 64, 0, 2, 66, 2, PreviewPattern.HOLLOW, STONE),
            shape(1, 64, 0, 1, 65, 0, PreviewPattern.CLEAR, null));
    assertEquals(
        buildPreview(shapes, Map.of()).analysis(), buildPreview(shapes, Map.of()).analysis());
  }

  @Test
  void compatibilityConstructorCarriesTheEmptyAnalysis() {
    var preview =
        new StandalonePreview(
            UUID.nameUUIDFromBytes(new byte[] {1}),
            PROJECT_ID,
            1,
            PreviewOperation.CREATE,
            "minecraft:overworld",
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(0, 64, 0)),
            new PreviewPosition(0, 64, 0),
            0,
            PreviewMirror.NONE,
            "0".repeat(64),
            "1".repeat(64),
            1,
            0,
            new PreviewDifference(0, 0, 0),
            List.of(new PreviewCell(0, 64, 0, STONE)),
            List.of(STONE));
    assertEquals(PreviewAnalysis.empty(), preview.analysis());
    assertEquals(Map.of(), preview.analysis().topViewLegend());
    assertEquals(List.of(), preview.analysis().topView());
  }

  @Test
  void rejectsOutOfContractAnalysisValues() {
    assertThrows(
        IllegalArgumentException.class, () -> new PreviewAnalysis(-1, 0, List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new PreviewAnalysis(0, 16_385, List.of(), Map.of()));
    var tooManyRows = new ArrayList<String>();
    for (var index = 0; index < 49; index++) {
      tooManyRows.add("#");
    }
    assertThrows(
        IllegalArgumentException.class, () -> new PreviewAnalysis(0, 0, tooManyRows, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreviewAnalysis(0, 0, List.of("#".repeat(97)), Map.of()));
    var oversizedLegend = new HashMap<String, String>();
    for (var index = 0; index < 33; index++) {
      oversizedLegend.put(String.valueOf((char) ('a' + index % 26)) + index, "minecraft:stone");
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreviewAnalysis(0, 0, List.of(), oversizedLegend));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreviewAnalysis(0, 0, List.of(), Map.of("##", "minecraft:stone")));
  }

  private static StandalonePreview buildPreview(
      List<PreviewShape> shapes, Map<PreviewPosition, String> world) {
    var request =
        new PreviewRequest(
            PROJECT_ID,
            1,
            PreviewOperation.CREATE,
            "minecraft:overworld",
            new PreviewPosition(0, 0, 0),
            0,
            PreviewMirror.NONE,
            shapes);
    var targets = PreviewEngine.prepare(request);
    var region =
        PreviewEngine.snapshot(
            targets.unionBounds(),
            (x, y, z) -> world.getOrDefault(new PreviewPosition(x, y, z), "minecraft:air"));
    return PreviewEngine.build(request, targets, region);
  }

  private static PreviewShape shape(
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      PreviewPattern pattern,
      String state) {
    return new PreviewShape(
        new PreviewBounds(
            new PreviewPosition(minX, minY, minZ), new PreviewPosition(maxX, maxY, maxZ)),
        pattern,
        state);
  }
}
