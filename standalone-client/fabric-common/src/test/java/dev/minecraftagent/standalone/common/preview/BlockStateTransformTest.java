package dev.minecraftagent.standalone.common.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlockStateTransformTest {
  private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

  @Test
  void stairsRotateTheirFacingWithTheBuild() {
    // The engine convention: rotation 90 maps +x (east) to -z (north), like the cell transform.
    var straight = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]";
    assertEquals(
        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
        BlockStateTransform.transform(straight, 90, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_stairs[facing=west,half=bottom,shape=straight]",
        BlockStateTransform.transform(straight, 180, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]",
        BlockStateTransform.transform(straight, 270, PreviewMirror.NONE));
    assertEquals(straight, BlockStateTransform.transform(straight, 0, PreviewMirror.NONE));
  }

  @Test
  void mirrorsSwapDoorHingesAndHorizontalFacing() {
    var door = "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]";
    assertEquals(
        "minecraft:oak_door[facing=west,half=lower,hinge=right,open=false,powered=false]",
        BlockStateTransform.transform(door, 0, PreviewMirror.LEFT_RIGHT));
    var northDoor =
        "minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=false]";
    assertEquals(
        "minecraft:oak_door[facing=south,half=upper,hinge=right,open=true,powered=false]",
        BlockStateTransform.transform(northDoor, 0, PreviewMirror.FRONT_BACK));
    // Rotation keeps the hinge relative to the rotated facing: hinge and shape stay untouched.
    assertEquals(
        "minecraft:oak_door[facing=west,half=upper,hinge=left,open=true,powered=false]",
        BlockStateTransform.transform(northDoor, 90, PreviewMirror.NONE));
  }

  @Test
  void mirrorAppliesBeforeRotationLikeTheCellTransform() {
    var furnace = "minecraft:furnace[facing=east,lit=true]";
    // LEFT_RIGHT negates dx first (east -> west), then rotation 90 (west -> south).
    assertEquals(
        "minecraft:furnace[facing=south,lit=true]",
        BlockStateTransform.transform(furnace, 90, PreviewMirror.LEFT_RIGHT));
    var cell = new PreviewPosition(12, 65, 21);
    var origin = new PreviewPosition(10, 64, 20);
    assertEquals(
        new PreviewPosition(11, 65, 22),
        PreviewEngine.transform(cell, origin, 90, PreviewMirror.LEFT_RIGHT));
  }

  @Test
  void numericRotationFollowsTheSameConvention() {
    // rotation 12 faces east, 8 north, 4 west, 0 south; steps turn towards the west.
    var skull = "minecraft:skeleton_skull[rotation=12]";
    assertEquals(
        "minecraft:skeleton_skull[rotation=8]",
        BlockStateTransform.transform(skull, 90, PreviewMirror.NONE));
    assertEquals(
        "minecraft:skeleton_skull[rotation=4]",
        BlockStateTransform.transform(skull, 180, PreviewMirror.NONE));
    assertEquals(
        "minecraft:skeleton_skull[rotation=0]",
        BlockStateTransform.transform(skull, 270, PreviewMirror.NONE));
    assertEquals(
        "minecraft:skeleton_skull[rotation=4]",
        BlockStateTransform.transform(skull, 0, PreviewMirror.LEFT_RIGHT));
    assertEquals(
        "minecraft:skeleton_skull[rotation=12]",
        BlockStateTransform.transform(skull, 0, PreviewMirror.FRONT_BACK));
    // Non-cardinal values rotate by the same angular step.
    var sign = "minecraft:oak_sign[rotation=1,waterlogged=false]";
    assertEquals(
        "minecraft:oak_sign[rotation=13,waterlogged=false]",
        BlockStateTransform.transform(sign, 90, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_sign[rotation=15,waterlogged=false]",
        BlockStateTransform.transform(sign, 0, PreviewMirror.LEFT_RIGHT));
  }

  @Test
  void axesSwapOnlyOnQuarterTurns() {
    var log = "minecraft:oak_log[axis=x]";
    assertEquals(
        "minecraft:oak_log[axis=z]", BlockStateTransform.transform(log, 90, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_log[axis=z]", BlockStateTransform.transform(log, 270, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_log[axis=x]", BlockStateTransform.transform(log, 180, PreviewMirror.NONE));
    assertEquals(
        "minecraft:oak_log[axis=x]",
        BlockStateTransform.transform(log, 0, PreviewMirror.FRONT_BACK));
    var upright = "minecraft:oak_log[axis=y]";
    assertEquals(upright, BlockStateTransform.transform(upright, 90, PreviewMirror.LEFT_RIGHT));
  }

  @Test
  void stairShapesMirrorChiralityButSurviveRotation() {
    var corner = "minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left]";
    assertEquals(
        "minecraft:oak_stairs[facing=south,half=bottom,shape=inner_right]",
        BlockStateTransform.transform(corner, 0, PreviewMirror.FRONT_BACK));
    assertEquals(
        "minecraft:oak_stairs[facing=west,half=bottom,shape=inner_left]",
        BlockStateTransform.transform(corner, 90, PreviewMirror.NONE));
    var outer = "minecraft:oak_stairs[facing=east,half=top,shape=outer_right]";
    assertEquals(
        "minecraft:oak_stairs[facing=west,half=top,shape=outer_left]",
        BlockStateTransform.transform(outer, 0, PreviewMirror.LEFT_RIGHT));
  }

  @Test
  void multipartConnectionKeysFollowTheTransform() {
    var wall =
        "minecraft:cobblestone_wall[east=low,north=none,south=tall,up=true,waterlogged=false,west=none]";
    // Rotation 90 moves east->north, north->west, south->east, west->south; values stay put.
    assertEquals(
        "minecraft:cobblestone_wall[east=tall,north=low,south=none,up=true,waterlogged=false,west=none]",
        BlockStateTransform.transform(wall, 90, PreviewMirror.NONE));
    assertEquals(
        "minecraft:cobblestone_wall[east=none,north=none,south=tall,up=true,waterlogged=false,west=low]",
        BlockStateTransform.transform(wall, 0, PreviewMirror.LEFT_RIGHT));
    assertEquals(
        "minecraft:cobblestone_wall[east=low,north=tall,south=none,up=true,waterlogged=false,west=none]",
        BlockStateTransform.transform(wall, 0, PreviewMirror.FRONT_BACK));
  }

  @Test
  void verticalAndUnknownPropertiesPassThrough() {
    // half/type are horizontal-transform invariants; up/down facings are not horizontal.
    var upperStair = "minecraft:oak_stairs[facing=east,half=top,shape=straight]";
    assertEquals(
        "minecraft:oak_stairs[facing=north,half=top,shape=straight]",
        BlockStateTransform.transform(upperStair, 90, PreviewMirror.NONE));
    var slab = "minecraft:oak_slab[type=bottom,waterlogged=false]";
    assertEquals(slab, BlockStateTransform.transform(slab, 180, PreviewMirror.LEFT_RIGHT));
    var piston = "minecraft:piston[extended=false,facing=up]";
    assertEquals(piston, BlockStateTransform.transform(piston, 90, PreviewMirror.NONE));
    // States without properties and unknown properties are returned untouched.
    assertEquals(
        "minecraft:stone",
        BlockStateTransform.transform("minecraft:stone", 90, PreviewMirror.LEFT_RIGHT));
    var modded = "testmod:machine[facing=south,lit=true,progress=7]";
    assertEquals(
        "testmod:machine[facing=west,lit=true,progress=7]",
        BlockStateTransform.transform(modded, 270, PreviewMirror.NONE));
  }

  @Test
  void prepareRewritesTargetStatesTogetherWithPositions() {
    var request =
        new PreviewRequest(
            PROJECT_ID,
            1,
            PreviewOperation.CREATE,
            "minecraft:overworld",
            new PreviewPosition(0, 64, 0),
            90,
            PreviewMirror.NONE,
            List.of(
                new PreviewShape(
                    new PreviewBounds(new PreviewPosition(0, 0, 0), new PreviewPosition(0, 0, 0)),
                    PreviewPattern.SOLID,
                    "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]")));
    var targets = PreviewEngine.prepare(request);
    assertEquals(
        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
        targets.cells().get(new PreviewPosition(0, 64, 0)));
  }
}
