package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BoundedJsonTest {
  @Test
  void parsesAllValueShapes() {
    var root =
        BoundedJson.parseObject(
            "{\"text\":\"hi\",\"long\":42,\"negative\":-7,\"double\":2.5,\"exp\":1e3,"
                + "\"yes\":true,\"no\":false,\"nil\":null,"
                + "\"list\":[1,\"two\",null],\"object\":{\"a\":1}}");
    assertEquals("hi", root.get("text"));
    assertEquals(42L, root.get("long"));
    assertEquals(-7L, root.get("negative"));
    assertEquals(2.5, root.get("double"));
    assertEquals(1000.0, root.get("exp"));
    assertEquals(Boolean.TRUE, root.get("yes"));
    assertEquals(Boolean.FALSE, root.get("no"));
    assertNull(root.get("nil"));
    assertEquals(java.util.Arrays.asList(1L, "two", null), root.get("list"));
    assertEquals(Map.of("a", 1L), root.get("object"));
  }

  @Test
  void integralOverflowFallsBackToDouble() {
    var root = BoundedJson.parseObject("{\"big\":99999999999999999999999}");
    assertInstanceOf(Double.class, root.get("big"));
  }

  @Test
  void rejectsDuplicateKeys() {
    assertThrows(
        IllegalArgumentException.class, () -> BoundedJson.parse("{\"a\":1,\"b\":2,\"a\":3}"));
  }

  @Test
  void rejectsNestingBeyondLimit() {
    var deep =
        "[".repeat(BoundedJson.MAXIMUM_NESTING + 2) + "]".repeat(BoundedJson.MAXIMUM_NESTING + 2);
    assertThrows(IllegalArgumentException.class, () -> BoundedJson.parse(deep));
    var accepted =
        "[".repeat(BoundedJson.MAXIMUM_NESTING) + "1" + "]".repeat(BoundedJson.MAXIMUM_NESTING);
    assertInstanceOf(List.class, BoundedJson.parse(accepted));
  }

  @Test
  void rejectsTooManyValues() {
    var source = new StringBuilder("[");
    for (var index = 0; index < BoundedJson.MAXIMUM_VALUES + 1; index++) {
      if (index > 0) {
        source.append(',');
      }
      source.append('1');
    }
    source.append(']');
    assertThrows(IllegalArgumentException.class, () -> BoundedJson.parse(source.toString()));
  }

  @Test
  void rejectsOverlongStrings() {
    var source = "\"" + "a".repeat(BoundedJson.MAXIMUM_STRING_LENGTH + 1) + "\"";
    assertThrows(IllegalArgumentException.class, () -> BoundedJson.parse(source));
  }

  @Test
  void rejectsMalformedInput() {
    for (var source :
        List.of(
            "",
            "{",
            "{\"a\":}",
            "{\"a\":1,}",
            "[1,]",
            "{'a':1}",
            "{\"a\":01}",
            "{\"a\":.5}",
            "{\"a\":1} garbage",
            "\"unterminated",
            "{\"a\" 1}",
            "nul",
            "[null")) {
      assertThrows(
          IllegalArgumentException.class, () -> BoundedJson.parse(source), "source: " + source);
    }
  }

  @Test
  void rejectsNonFiniteNumbers() {
    assertThrows(IllegalArgumentException.class, () -> BoundedJson.parse("1e999"));
    assertThrows(IllegalArgumentException.class, () -> BoundedJson.parse("-1e999"));
  }

  @Test
  void roundTripsCompactly() {
    var source = "{\"a\":[1,2.5,\"x\",true,null],\"b\":{\"c\":\"d\"}}";
    assertEquals(source, BoundedJson.write(BoundedJson.parse(source)));
    assertTrue(BoundedJson.parseObject(source).get("a") instanceof List);
  }

  @Test
  void unescapesStrings() {
    var root = BoundedJson.parseObject("{\"v\":\"a\\nb\\u0041\\\\\\\"\"}");
    assertEquals("a\nbA\\\"", root.get("v"));
  }
}
