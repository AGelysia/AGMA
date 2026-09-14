package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.standalone.common.preview.PreviewArguments;
import dev.minecraftagent.standalone.common.preview.PreviewEngine;
import dev.minecraftagent.standalone.common.preview.PreviewLimitException;
import dev.minecraftagent.standalone.common.preview.PreviewMirror;
import dev.minecraftagent.standalone.common.preview.PreviewOperation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildPreviewToolPayloadsTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TOOL_CALL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SUBJECT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String PROJECT_ID = "123e4567-e89b-42d3-a456-426614174000";
  private static final String STONE = "minecraft:stone_bricks";

  @Test
  void acceptsAValidCreateCall() {
    var call = call(validArguments(), 63);
    assertEquals("build.preview.create", call.tool());
    var request = PreviewArguments.parse(call.arguments());
    assertEquals(UUID.fromString(PROJECT_ID), request.projectId());
    assertEquals(1, request.revision());
    assertEquals(PreviewOperation.CREATE, request.operation());
    assertEquals("minecraft:overworld", request.dimension());
    assertEquals(90, request.rotation());
    assertEquals(PreviewMirror.NONE, request.mirror());
    assertEquals(1, request.shapes().size());
    assertEquals(STONE, request.shapes().get(0).blockState());
  }

  @Test
  void acceptsPropertyBearingAndClearShapes() {
    var arguments = validArguments();
    var propertyShape =
        shapeMap(0, 64, 0, 1, 64, 1, "solid", "minecraft:oak_stairs[facing=north,half=bottom]");
    var clearShape = shapeMap(0, 64, 0, 1, 64, 1, "clear", null);
    arguments.put("shapes", List.of(propertyShape, clearShape));
    assertDoesNotThrow(() -> call(arguments, 0));
  }

  @Test
  void rejectsMalformedArguments() {
    assertRejects(mutate("projectId", "not-a-uuid"));
    assertRejects(mutate("projectId", "123E4567-E89B-42D3-A456-426614174000"));
    assertRejects(mutate("revision", 0));
    assertRejects(mutate("revision", 1.5));
    assertRejects(mutate("operation", "delete"));
    assertRejects(mutate("dimension", "Overworld"));
    assertRejects(mutate("rotation", 45));
    assertRejects(mutate("rotation", -90));
    assertRejects(mutate("mirror", "none"));
    assertRejects(mutate("mirror", "SIDE"));
    assertRejects(mutate("origin", Map.of("x", 0, "y", 64)));
    assertRejects(mutate("origin", Map.of("x", 30_000_001, "y", 64, "z", 0)));
    assertRejects(mutate("origin", Map.of("x", 0, "y", 2_049, "z", 0)));
    assertRejects(mutate("shapes", List.of()));

    var missing = validArguments();
    missing.remove("mirror");
    assertRejects(missing);

    var extra = validArguments();
    extra.put("label", "house");
    assertRejects(extra);
  }

  @Test
  void rejectsShapeViolations() {
    var tooManyShapes = new ArrayList<Map<String, Object>>();
    for (var index = 0; index < 25; index++) {
      tooManyShapes.add(shapeMap(0, 64, index, 0, 64, index, "solid", STONE));
    }
    assertRejects(mutate("shapes", tooManyShapes));
    assertRejects(mutate("shapes", List.of(shapeMap(0, 64, 0, 64, 64, 0, "solid", STONE))));
    assertRejects(mutate("shapes", List.of(shapeMap(0, 64, 0, 63, 68, 63, "solid", STONE))));
    assertRejects(mutate("shapes", List.of(shapeMap(1, 64, 0, 0, 64, 0, "solid", STONE))));
    assertRejects(mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "clear", STONE))));
    assertRejects(mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", null))));
    assertRejects(mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "box", STONE))));
    assertRejects(
        mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", "Minecraft:stone"))));
    assertRejects(
        mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", "minecraft:stone[]"))));
    assertRejects(
        mutate("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", "minecraft:stone["))));
  }

  @Test
  void parsesShapeLimitsDefensively() {
    var oversized = mutate("shapes", List.of(shapeMap(0, 64, 0, 64, 64, 0, "solid", STONE)));
    assertThrows(
        PreviewLimitException.class, () -> PreviewArguments.parse(new HashMap<>(oversized)));
  }

  @Test
  void boundsSequenceToTheContract() {
    assertDoesNotThrow(() -> call(validArguments(), 63));
    assertThrows(IllegalArgumentException.class, () -> call(validArguments(), 64));
    assertThrows(IllegalArgumentException.class, () -> call(validArguments(), -1));
  }

  @Test
  void acceptsAValidResultMap() {
    var result = validResult();
    assertDoesNotThrow(() -> ClientToolPayloads.validateResult("build.preview.create", result));
    assertDoesNotThrow(() -> new ClientToolResult(result));
  }

  @Test
  void acceptsAResultWithAnalysis() {
    var result = validResult();
    result.put("analysis", validAnalysis());
    assertDoesNotThrow(() -> ClientToolPayloads.validateResult("build.preview.create", result));
    assertDoesNotThrow(() -> new ClientToolResult(result));
  }

  @Test
  void rejectsMalformedAnalysis() {
    assertResultRejects(withAnalysis(mutateAnalysis("floatingCells", -1)));
    assertResultRejects(withAnalysis(mutateAnalysis("interiorAirCells", 16_385)));
    assertResultRejects(withAnalysis(mutateAnalysis("topView", List.of("#".repeat(97)))));
    assertResultRejects(withAnalysis(mutateAnalysis("topView", List.of(""))));
    assertResultRejects(withAnalysis(mutateAnalysis("topView", List.of("bad char~"))));
    var tooManyRows = new ArrayList<String>();
    for (var index = 0; index < 49; index++) {
      tooManyRows.add("#");
    }
    assertResultRejects(withAnalysis(mutateAnalysis("topView", tooManyRows)));
    var oversizedLegend = new LinkedHashMap<String, Object>();
    for (var index = 0; index < 33; index++) {
      oversizedLegend.put(String.valueOf((char) ('a' + (index % 26))) + index, "minecraft:stone");
    }
    assertResultRejects(withAnalysis(mutateAnalysis("topViewLegend", oversizedLegend)));
    assertResultRejects(
        withAnalysis(mutateAnalysis("topViewLegend", Map.of("##", "minecraft:stone"))));
    assertResultRejects(withAnalysis(mutateAnalysis("topViewLegend", Map.of("#", "stone"))));

    var missing = validAnalysis();
    missing.remove("topView");
    assertResultRejects(withAnalysis(missing));

    var extra = validAnalysis();
    extra.put("note", "looks load-bearing");
    assertResultRejects(withAnalysis(extra));

    var notAnObject = validResult();
    notAnObject.put("analysis", List.of());
    assertResultRejects(notAnObject);
  }

  @Test
  void rejectsMalformedResults() {
    assertResultRejects(mutateResult("worldWriteEnabled", true));
    assertResultRejects(mutateResult("previewStatus", "server_validated"));
    assertResultRejects(mutateResult("baseRegionHash", "xyz"));
    assertResultRejects(mutateResult("changeSetHash", "A".repeat(64)));
    assertResultRejects(mutateResult("changeCount", 16_385));
    assertResultRejects(mutateResult("targetBlockCount", -1));
    assertResultRejects(mutateResult("revision", 0));
    assertResultRejects(mutateResult("dimension", "overworld"));
    assertResultRejects(
        mutateResult(
            "bounds",
            Map.of(
                "min", Map.of("x", 1, "y", 64, "z", 0),
                "max", Map.of("x", 0, "y", 64, "z", 1))));
    assertResultRejects(
        mutateResult("difference", Map.of("added", 1, "replaced", 0, "removed", 16_385)));

    var missing = validResult();
    missing.remove("difference");
    assertResultRejects(missing);

    var extra = validResult();
    extra.put("source", "client");
    assertResultRejects(extra);
  }

  @Test
  void engineResultsPassWireValidation() {
    var arguments = PreviewArguments.parse(validArguments());
    var targets = PreviewEngine.prepare(arguments);
    var region = PreviewEngine.snapshot(targets.unionBounds(), (x, y, z) -> "minecraft:air");
    var preview = PreviewEngine.build(arguments, targets, region);
    var result = preview.toResultMap();
    assertDoesNotThrow(() -> ClientToolPayloads.validateResult("build.preview.create", result));
    assertDoesNotThrow(() -> new ClientToolResult(result));
    // The engine always attaches the structural analysis, and it must pass wire validation too.
    // The single-layer build sits at the region bottom, so the supporting cell is unobserved.
    @SuppressWarnings("unchecked")
    var analysis = (Map<String, Object>) result.get("analysis");
    assertEquals(0, analysis.get("floatingCells"));
    assertEquals(0, analysis.get("interiorAirCells"));
    assertEquals(List.of("##", "##"), analysis.get("topView"));
    assertEquals(Map.of("#", STONE), analysis.get("topViewLegend"));
  }

  private static Map<String, Object> withAnalysis(Map<String, Object> analysis) {
    var result = validResult();
    result.put("analysis", analysis);
    return result;
  }

  private static Map<String, Object> mutateAnalysis(String field, Object value) {
    var analysis = validAnalysis();
    analysis.put(field, value);
    return analysis;
  }

  private static Map<String, Object> validAnalysis() {
    var analysis = new LinkedHashMap<String, Object>();
    analysis.put("floatingCells", 2);
    analysis.put("interiorAirCells", 6);
    analysis.put("topView", List.of("##+", "##+"));
    analysis.put(
        "topViewLegend", Map.of("#", "minecraft:stone_bricks", "+", "minecraft:oak_planks"));
    return analysis;
  }

  private static void assertRejects(Map<String, Object> arguments) {
    assertThrows(IllegalArgumentException.class, () -> call(arguments, 0));
  }

  private static void assertResultRejects(Map<String, Object> result) {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientToolPayloads.validateResult("build.preview.create", result));
  }

  private static ClientToolCall call(Map<String, Object> arguments, int sequence) {
    return new ClientToolCall(
        REQUEST_ID, TOOL_CALL_ID, SUBJECT_ID, "build.preview.create", sequence, arguments);
  }

  private static Map<String, Object> mutate(String field, Object value) {
    var arguments = validArguments();
    arguments.put(field, value);
    return arguments;
  }

  private static Map<String, Object> mutateResult(String field, Object value) {
    var result = validResult();
    result.put(field, value);
    return result;
  }

  private static Map<String, Object> validArguments() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("projectId", PROJECT_ID);
    arguments.put("revision", 1);
    arguments.put("operation", "create");
    arguments.put("dimension", "minecraft:overworld");
    arguments.put("origin", Map.of("x", 0, "y", 64, "z", 0));
    arguments.put("rotation", 90);
    arguments.put("mirror", "NONE");
    arguments.put("shapes", List.of(shapeMap(0, 64, 0, 1, 64, 1, "solid", STONE)));
    return arguments;
  }

  private static Map<String, Object> validResult() {
    var result = new LinkedHashMap<String, Object>();
    result.put("previewId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    result.put("projectId", PROJECT_ID);
    result.put("revision", 2);
    result.put("dimension", "minecraft:overworld");
    result.put(
        "bounds",
        Map.of(
            "min", Map.of("x", 0, "y", 64, "z", 0),
            "max", Map.of("x", 1, "y", 64, "z", 1)));
    result.put("baseRegionHash", "a".repeat(64));
    result.put("changeSetHash", "b".repeat(64));
    result.put("targetBlockCount", 4);
    result.put("changeCount", 4);
    result.put("difference", Map.of("added", 4, "replaced", 0, "removed", 0));
    result.put("previewStatus", "client_validated");
    result.put("worldWriteEnabled", false);
    return result;
  }

  private static Map<String, Object> shapeMap(
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String pattern, String state) {
    var shape = new LinkedHashMap<String, Object>();
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
