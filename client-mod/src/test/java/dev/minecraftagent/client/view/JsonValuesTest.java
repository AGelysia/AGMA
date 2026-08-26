package dev.minecraftagent.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.client.view.BuildPreviewView.Mirror;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

final class JsonValuesTest {
  @Test
  void closedObjectRejectsUnknownMissingAndNonObjectShapes() throws Exception {
    JsonObject object = JsonValues.closedObject(parse("{\"a\":1}"), Set.of("a", "b"), Set.of("a"));
    assertEquals(1, object.fields().size());
    JsonValues.closedObject(parse("{\"a\":1,\"extra\":true}"), Set.of("a"), Set.of("a"), true);

    assertCode(
        ViewDecodeException.Code.UNKNOWN_FIELD,
        () -> JsonValues.closedObject(parse("{\"a\":1,\"c\":2}"), Set.of("a"), Set.of("a")));
    assertCode(
        ViewDecodeException.Code.MISSING_FIELD,
        () -> JsonValues.closedObject(parse("{\"a\":1}"), Set.of("a"), Set.of("a", "b")));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.closedObject(parse("[1]"), Set.of("a"), Set.of("a")));
  }

  @Test
  void integerRejectsNonIntegersStringsAndOutOfRangeValues() throws Exception {
    assertEquals(7, JsonValues.integer(parse("7"), 0, 10));
    assertEquals(-7, JsonValues.integer(parse("-7"), -10, 10));

    assertCode(
        ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.integer(parse("1.5"), 0, 10));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.integer(parse("\"7\""), 0, 10));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.integer(parse("11"), 0, 10));
  }

  @Test
  void decimalRejectsOutOfRangeAndNonFiniteValues() throws Exception {
    BigDecimal maximum = new BigDecimal("1000000");
    assertEquals(1.5, JsonValues.decimal(parse("1.5"), BigDecimal.ZERO, maximum));

    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.decimal(parse("1000001"), BigDecimal.ZERO, maximum));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.decimal(parse("1e999999"), BigDecimal.ZERO, maximum));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.decimal(parse("\"1.5\""), BigDecimal.ZERO, maximum));
  }

  @Test
  void visibleStringRejectsSurrogatesBidirectionalControlsAndControlCharacters() throws Exception {
    assertEquals("plain", JsonValues.visibleString(parse("\"plain\""), 1, 32, false));
    assertEquals("a\nb", JsonValues.visibleString(parse("\"a\\nb\""), 1, 32, true));

    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.visibleString(parse("\"a\\u202eb\""), 1, 32, false));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.visibleString(parse("\"bad\\ud800\""), 1, 32, false));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.visibleString(parse("\"a\\u0001b\""), 1, 32, false));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.visibleString(parse("\"a\\nb\""), 1, 32, false));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.visibleString(parse("\"ab\""), 3, 32, false));
  }

  @Test
  void uuidNamespacedIdEnumValueAndHashStringRejectNonCanonicalForms() throws Exception {
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        JsonValues.uuid("00000000-0000-0000-0000-000000000001"));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.uuid("00000000-0000-0000-0000-000000000001X"));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.uuid("not-a-uuid"));

    assertEquals("minecraft:stone", JsonValues.namespacedId("minecraft:stone"));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.namespacedId("Stone"));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.namespacedId("stone"));

    assertEquals(Mirror.FRONT_BACK, JsonValues.enumValue("front_back", Mirror.class));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        () -> JsonValues.enumValue("diagonal", Mirror.class));

    JsonObject hashes =
        (JsonObject)
            parse(
                "{\"ok\":\""
                    + "a".repeat(64)
                    + "\",\"bad\":\""
                    + "g".repeat(64)
                    + "\",\"short\":\""
                    + "a".repeat(63)
                    + "\"}");
    assertEquals("a".repeat(64), JsonValues.hashString(hashes, "ok"));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.hashString(hashes, "bad"));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.hashString(hashes, "short"));
  }

  @Test
  void arrayAndBoolRequireTheExactJsonType() throws Exception {
    assertEquals(2, JsonValues.array(parse("[1,2]"), 1, 2).size());
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.array(parse("[1]"), 2, 2));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.array(parse("1"), 1, 2));

    assertEquals(true, JsonValues.bool(parse("true")));
    assertCode(ViewDecodeException.Code.INVALID_VALUE, () -> JsonValues.bool(parse("\"true\"")));
  }

  private static JsonNode parse(String json) throws Exception {
    return StrictJsonParser.parse(json);
  }

  private static void assertCode(ViewDecodeException.Code code, Executable executable) {
    ViewDecodeException exception = assertThrows(ViewDecodeException.class, executable);
    assertEquals(code, exception.code());
  }
}
