package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlockInspectToolPayloadsTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TOOL_CALL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SUBJECT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void acceptsEmptyAndExplicitPositionArguments() {
    assertEquals("game.block.inspect", call(Map.of()).tool());
    assertDoesNotThrow(
        () -> call(Map.of("position", Map.of("x", -30_000_000, "y", -2_048, "z", 30_000_000))));
  }

  @Test
  void rejectsMalformedArguments() {
    assertRejectsArguments(Map.of("label", "chest"));
    assertRejectsArguments(Map.of("position", Map.of("x", 1, "y", 64)));
    assertRejectsArguments(Map.of("position", Map.of("x", 1, "y", 64, "z", 2, "face", "up")));
    assertRejectsArguments(Map.of("position", Map.of("x", 30_000_001, "y", 64, "z", 0)));
    assertRejectsArguments(Map.of("position", Map.of("x", 0, "y", 2_049, "z", 0)));
    assertRejectsArguments(Map.of("position", Map.of("x", 1.5, "y", 64, "z", 0)));
    assertRejectsArguments(Map.of("position", "1,64,2"));
  }

  @Test
  void acceptsFoundResultsWithAndWithoutABlockEntity() {
    var plain = result(false);
    assertDoesNotThrow(() -> ClientToolPayloads.validateResult("game.block.inspect", plain));

    var withEntity = result(true);
    withEntity.put(
        "blockEntity",
        Map.of(
            "data",
            Map.of(
                "Items",
                List.of(Map.of("id", "minecraft:stone", "count", 3)),
                "SkullOwner",
                "[redacted]")));
    assertDoesNotThrow(() -> ClientToolPayloads.validateResult("game.block.inspect", withEntity));
  }

  @Test
  void rejectsIncoherentOrUnboundedResults() {
    assertRejectsResult(result(true));

    var unexpectedEntity = result(false);
    unexpectedEntity.put("blockEntity", Map.of("data", Map.of()));
    assertRejectsResult(unexpectedEntity);

    var badBlock = result(false);
    badBlock.put("blockId", "Chest");
    assertRejectsResult(badBlock);

    var badPosition = result(false);
    badPosition.put("position", Map.of("x", 0, "y", 64, "z", 30_000_001));
    assertRejectsResult(badPosition);

    var extraField = result(false);
    extraField.put("dimension", "minecraft:overworld");
    assertRejectsResult(extraField);

    var deep = result(true);
    deep.put(
        "blockEntity",
        Map.of(
            "data",
            Map.of("l1", Map.of("l2", Map.of("l3", Map.of("l4", Map.of("l5", "too deep")))))));
    assertRejectsResult(deep);

    var longList = new ArrayList<>();
    for (var index = 0; index < 17; index++) {
      longList.add(index);
    }
    var bigList = result(true);
    bigList.put("blockEntity", Map.of("data", Map.of("entries", longList)));
    assertRejectsResult(bigList);

    var longString = result(true);
    longString.put("blockEntity", Map.of("data", Map.of("text", "x".repeat(129))));
    assertRejectsResult(longString);
  }

  private static LinkedHashMap<String, Object> result(boolean hasBlockEntity) {
    var result = new LinkedHashMap<String, Object>();
    result.put("found", true);
    result.put("blockId", "minecraft:chest");
    result.put("position", Map.of("x", 10, "y", 64, "z", -4));
    result.put("hasBlockEntity", hasBlockEntity);
    return result;
  }

  private static ClientToolCall call(Map<String, Object> arguments) {
    return new ClientToolCall(
        REQUEST_ID, TOOL_CALL_ID, SUBJECT_ID, "game.block.inspect", 0, arguments);
  }

  private static void assertRejectsArguments(Map<String, Object> arguments) {
    assertThrows(IllegalArgumentException.class, () -> call(arguments));
  }

  private static void assertRejectsResult(Map<String, Object> result) {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientToolPayloads.validateResult("game.block.inspect", result));
  }
}
