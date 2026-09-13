package dev.minecraftagent.standalone.common.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewEngineTest {
  private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String STONE = "minecraft:stone";
  private static final String DIRT = "minecraft:dirt";
  private static final String GRASS = "minecraft:grass_block";

  @Test
  void solidPatternFillsEveryCellOfTheBounds() {
    var targets =
        PreviewEngine.prepare(
            request(List.of(shape(0, 64, 0, 1, 65, 1, PreviewPattern.SOLID, STONE))));
    assertEquals(8, targets.cells().size());
    assertTrue(targets.cells().values().stream().allMatch(STONE::equals));
    assertEquals(
        new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 65, 1)),
        targets.unionBounds());
  }

  @Test
  void hollowPatternKeepsOnlyTheShell() {
    var targets =
        PreviewEngine.prepare(
            request(List.of(shape(0, 64, 0, 2, 66, 2, PreviewPattern.HOLLOW, STONE))));
    assertEquals(26, targets.cells().size());
    assertFalse(targets.cells().containsKey(new PreviewPosition(1, 65, 1)));
    assertTrue(targets.cells().containsKey(new PreviewPosition(1, 64, 1)));
    assertTrue(targets.cells().containsKey(new PreviewPosition(1, 66, 1)));
    assertTrue(targets.cells().containsKey(new PreviewPosition(0, 65, 1)));
  }

  @Test
  void wallsPatternKeepsTheSidesWithoutFloorAndCeiling() {
    var targets =
        PreviewEngine.prepare(
            request(List.of(shape(0, 64, 0, 2, 66, 2, PreviewPattern.WALLS, STONE))));
    assertEquals(24, targets.cells().size());
    assertFalse(targets.cells().containsKey(new PreviewPosition(1, 64, 1)));
    assertFalse(targets.cells().containsKey(new PreviewPosition(1, 65, 1)));
    assertFalse(targets.cells().containsKey(new PreviewPosition(1, 66, 1)));
    assertTrue(targets.cells().containsKey(new PreviewPosition(1, 64, 0)));
    assertTrue(targets.cells().containsKey(new PreviewPosition(1, 66, 2)));
  }

  @Test
  void floorPatternKeepsOnlyTheBottomLayer() {
    var targets =
        PreviewEngine.prepare(
            request(List.of(shape(0, 64, 0, 2, 66, 2, PreviewPattern.FLOOR, STONE))));
    assertEquals(9, targets.cells().size());
    assertTrue(targets.cells().keySet().stream().allMatch(position -> position.y() == 64));
  }

  @Test
  void clearPatternTargetsAirWithoutABlockState() {
    var clear =
        new PreviewShape(
            new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 64, 1)),
            PreviewPattern.CLEAR,
            null);
    var targets = PreviewEngine.prepare(request(List.of(clear)));
    assertEquals(4, targets.cells().size());
    assertTrue(targets.cells().values().stream().allMatch(PreviewEngine.AIR::equals));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreviewShape(
                new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 64, 1)),
                PreviewPattern.CLEAR,
                STONE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreviewShape(
                new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 64, 1)),
                PreviewPattern.SOLID,
                null));
  }

  @Test
  void laterShapesOverrideEarlierShapesPerCell() {
    var shapes =
        List.of(
            shape(0, 64, 0, 1, 65, 1, PreviewPattern.SOLID, STONE),
            shape(0, 64, 0, 1, 64, 1, PreviewPattern.SOLID, DIRT),
            new PreviewShape(
                new PreviewBounds(new PreviewPosition(0, 65, 0), new PreviewPosition(0, 65, 0)),
                PreviewPattern.CLEAR,
                null));
    var targets = PreviewEngine.prepare(request(shapes));
    assertEquals(8, targets.cells().size());
    assertEquals(DIRT, targets.cells().get(new PreviewPosition(0, 64, 0)));
    assertEquals(DIRT, targets.cells().get(new PreviewPosition(1, 64, 1)));
    assertEquals(PreviewEngine.AIR, targets.cells().get(new PreviewPosition(0, 65, 0)));
    assertEquals(STONE, targets.cells().get(new PreviewPosition(1, 65, 0)));
  }

  @Test
  void rotationAndMirrorTransformCellsAroundTheOrigin() {
    var origin = new PreviewPosition(10, 64, 20);
    var cell = new PreviewPosition(12, 65, 21);
    assertEquals(
        new PreviewPosition(11, 65, 18),
        PreviewEngine.transform(cell, origin, 90, PreviewMirror.NONE));
    assertEquals(
        new PreviewPosition(8, 65, 19),
        PreviewEngine.transform(cell, origin, 180, PreviewMirror.NONE));
    assertEquals(
        new PreviewPosition(9, 65, 22),
        PreviewEngine.transform(cell, origin, 270, PreviewMirror.NONE));
    assertEquals(cell, PreviewEngine.transform(cell, origin, 0, PreviewMirror.NONE));
    // Mirror is applied before rotation: LEFT_RIGHT negates dx, FRONT_BACK negates dz.
    assertEquals(
        new PreviewPosition(11, 65, 22),
        PreviewEngine.transform(cell, origin, 90, PreviewMirror.LEFT_RIGHT));
    assertEquals(
        new PreviewPosition(12, 65, 19),
        PreviewEngine.transform(cell, origin, 0, PreviewMirror.FRONT_BACK));
    assertEquals(
        new PreviewPosition(9, 65, 18),
        PreviewEngine.transform(cell, origin, 270, PreviewMirror.LEFT_RIGHT));
  }

  @Test
  void prepareTransformsCellsAndUnionBounds() {
    // Shape bounds are relative to the origin: (2,0,1)-(3,1,2) around (10,64,20) with a 90°
    // rotation lands exactly where the absolute-design equivalent would.
    var request =
        new PreviewRequest(
            PROJECT_ID,
            3,
            PreviewOperation.MODIFY,
            "minecraft:overworld",
            new PreviewPosition(10, 64, 20),
            90,
            PreviewMirror.NONE,
            List.of(shape(2, 0, 1, 3, 1, 2, PreviewPattern.SOLID, STONE)));
    var targets = PreviewEngine.prepare(request);
    assertEquals(8, targets.cells().size());
    assertTrue(targets.cells().containsKey(new PreviewPosition(11, 64, 18)));
    assertEquals(
        new PreviewBounds(new PreviewPosition(11, 64, 17), new PreviewPosition(12, 65, 18)),
        targets.unionBounds());
  }

  @Test
  void diffCountsAddedReplacedAndRemovedAgainstTheWorld() {
    var shapes =
        List.of(
            shape(0, 64, 0, 1, 64, 1, PreviewPattern.SOLID, STONE),
            new PreviewShape(
                new PreviewBounds(new PreviewPosition(1, 64, 1), new PreviewPosition(1, 64, 1)),
                PreviewPattern.CLEAR,
                null));
    var request = request(shapes);
    var targets = PreviewEngine.prepare(request);
    var world = new HashMap<PreviewPosition, String>();
    world.put(new PreviewPosition(1, 64, 0), DIRT);
    world.put(new PreviewPosition(0, 64, 1), STONE);
    world.put(new PreviewPosition(1, 64, 1), GRASS);
    var region = PreviewEngine.snapshot(targets.unionBounds(), lookup(world));
    var preview = PreviewEngine.build(request, targets, region);
    assertEquals(new PreviewDifference(1, 1, 1), preview.difference());
    assertEquals(3, preview.changeCount());
    assertEquals(3, preview.targetBlockCount());
    assertEquals(List.of(STONE), preview.palette());
    assertEquals(3, preview.cells().size());
  }

  @Test
  void unchangedWorldProducesAnEmptyChangeSet() {
    var request = request(List.of(shape(0, 64, 0, 1, 64, 0, PreviewPattern.SOLID, STONE)));
    var targets = PreviewEngine.prepare(request);
    var world = new HashMap<PreviewPosition, String>();
    world.put(new PreviewPosition(0, 64, 0), STONE);
    world.put(new PreviewPosition(1, 64, 0), STONE);
    var region = PreviewEngine.snapshot(targets.unionBounds(), lookup(world));
    var preview = PreviewEngine.build(request, targets, region);
    assertEquals(new PreviewDifference(0, 0, 0), preview.difference());
    assertEquals(0, preview.changeCount());
    assertEquals(2, preview.targetBlockCount());
  }

  @Test
  void rejectsTooManyShapes() {
    var shapes = new ArrayList<PreviewShape>();
    for (var index = 0; index < 25; index++) {
      shapes.add(shape(0, 64, index, 0, 64, index, PreviewPattern.SOLID, STONE));
    }
    assertThrows(PreviewLimitException.class, () -> request(shapes));
  }

  @Test
  void rejectsOversizedShapeBounds() {
    var arguments = validArguments();
    arguments.put("shapes", List.of(shapeMap(0, 64, 0, 64, 64, 0, "solid", STONE)));
    assertThrows(PreviewLimitException.class, () -> PreviewArguments.parse(arguments));

    arguments.put("shapes", List.of(shapeMap(0, 64, 0, 63, 68, 63, "solid", STONE)));
    assertThrows(PreviewLimitException.class, () -> PreviewArguments.parse(arguments));
  }

  @Test
  void rejectsAnOversizedTransformedUnion() {
    var shapes =
        List.of(
            shape(0, 64, 0, 63, 64, 63, PreviewPattern.SOLID, STONE),
            shape(128, 64, 64, 191, 64, 127, PreviewPattern.SOLID, DIRT));
    var request =
        new PreviewRequest(
            PROJECT_ID,
            1,
            PreviewOperation.CREATE,
            "minecraft:overworld",
            new PreviewPosition(0, 64, 0),
            180,
            PreviewMirror.NONE,
            shapes);
    assertThrows(PreviewLimitException.class, () -> PreviewEngine.prepare(request));
  }

  @Test
  void snapshotRejectsUnavailableChunks() {
    var region = new PreviewBounds(new PreviewPosition(0, 64, 0), new PreviewPosition(1, 64, 0));
    assertThrows(
        PreviewChunkUnavailableException.class,
        () -> PreviewEngine.snapshot(region, (x, y, z) -> x == 0 ? "minecraft:air" : null));
  }

  @Test
  void hashesAreStableDomainSeparatedAndWorldSensitive() {
    var request = request(List.of(shape(0, 64, 0, 1, 65, 1, PreviewPattern.SOLID, STONE)));
    var targets = PreviewEngine.prepare(request);
    var first =
        PreviewEngine.build(
            request, targets, PreviewEngine.snapshot(targets.unionBounds(), lookup(Map.of())));
    var second =
        PreviewEngine.build(
            request, targets, PreviewEngine.snapshot(targets.unionBounds(), lookup(Map.of())));
    assertEquals(first.previewId(), second.previewId());
    assertEquals(first.baseRegionHash(), second.baseRegionHash());
    assertEquals(first.changeSetHash(), second.changeSetHash());
    assertNotEquals(first.baseRegionHash(), first.changeSetHash());
    assertTrue(first.baseRegionHash().matches("[0-9a-f]{64}"));
    assertTrue(first.changeSetHash().matches("[0-9a-f]{64}"));

    var changed = new HashMap<PreviewPosition, String>();
    changed.put(new PreviewPosition(0, 64, 0), STONE);
    var third =
        PreviewEngine.build(
            request, targets, PreviewEngine.snapshot(targets.unionBounds(), lookup(changed)));
    assertNotEquals(first.baseRegionHash(), third.baseRegionHash());
    assertNotEquals(first.changeSetHash(), third.changeSetHash());
    assertNotEquals(first.previewId(), third.previewId());

    var otherRevision =
        new PreviewRequest(
            PROJECT_ID,
            2,
            PreviewOperation.CREATE,
            "minecraft:overworld",
            new PreviewPosition(0, 64, 0),
            0,
            PreviewMirror.NONE,
            request.shapes());
    var fourth =
        PreviewEngine.build(
            otherRevision,
            targets,
            PreviewEngine.snapshot(targets.unionBounds(), lookup(Map.of())));
    assertEquals(first.changeSetHash(), fourth.changeSetHash());
    assertNotEquals(first.previewId(), fourth.previewId());
  }

  @Test
  void resultMapMatchesTheWireContract() {
    var request =
        new PreviewRequest(
            PROJECT_ID,
            7,
            PreviewOperation.MODIFY,
            "minecraft:overworld",
            new PreviewPosition(0, 64, 0),
            90,
            PreviewMirror.LEFT_RIGHT,
            List.of(shape(0, 0, 0, 1, 0, 1, PreviewPattern.SOLID, STONE)));
    var targets = PreviewEngine.prepare(request);
    var preview =
        PreviewEngine.build(
            request, targets, PreviewEngine.snapshot(targets.unionBounds(), lookup(Map.of())));
    var result = preview.toResultMap();
    assertEquals(
        Set.of(
            "previewId",
            "projectId",
            "revision",
            "dimension",
            "bounds",
            "baseRegionHash",
            "changeSetHash",
            "targetBlockCount",
            "changeCount",
            "difference",
            "previewStatus",
            "worldWriteEnabled"),
        result.keySet());
    assertEquals(preview.previewId().toString(), result.get("previewId"));
    assertEquals(PROJECT_ID.toString(), result.get("projectId"));
    assertEquals(7, result.get("revision"));
    assertEquals("minecraft:overworld", result.get("dimension"));
    assertEquals(preview.baseRegionHash(), result.get("baseRegionHash"));
    assertEquals(preview.changeSetHash(), result.get("changeSetHash"));
    assertEquals(4, result.get("targetBlockCount"));
    assertEquals(4, result.get("changeCount"));
    assertEquals(Map.of("added", 4, "replaced", 0, "removed", 0), result.get("difference"));
    assertEquals("client_validated", result.get("previewStatus"));
    assertEquals(false, result.get("worldWriteEnabled"));

    @SuppressWarnings("unchecked")
    var bounds = (Map<String, Object>) result.get("bounds");
    assertEquals(Map.of("x", 0, "y", 64, "z", 0), bounds.get("min"));
    assertEquals(Map.of("x", 1, "y", 64, "z", 1), bounds.get("max"));
  }

  private static PreviewRequest request(List<PreviewShape> shapes) {
    return new PreviewRequest(
        PROJECT_ID,
        1,
        PreviewOperation.CREATE,
        "minecraft:overworld",
        new PreviewPosition(0, 0, 0),
        0,
        PreviewMirror.NONE,
        shapes);
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

  private static BlockLookup lookup(Map<PreviewPosition, String> world) {
    return (x, y, z) -> world.getOrDefault(new PreviewPosition(x, y, z), "minecraft:air");
  }

  private static Map<String, Object> validArguments() {
    var arguments = new HashMap<String, Object>();
    arguments.put("projectId", PROJECT_ID.toString());
    arguments.put("revision", 1);
    arguments.put("operation", "create");
    arguments.put("dimension", "minecraft:overworld");
    arguments.put("origin", Map.of("x", 0, "y", 64, "z", 0));
    arguments.put("rotation", 0);
    arguments.put("mirror", "NONE");
    arguments.put("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", STONE)));
    return arguments;
  }

  private static Map<String, Object> shapeMap(
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String pattern, String state) {
    var shape = new HashMap<String, Object>();
    shape.put(
        "bounds",
        Map.of(
            "min", Map.of("x", minX, "y", minY, "z", minZ),
            "max", Map.of("x", maxX, "y", maxY, "z", maxZ)));
    shape.put("pattern", pattern);
    shape.put("blockState", state);
    return shape;
  }
}
