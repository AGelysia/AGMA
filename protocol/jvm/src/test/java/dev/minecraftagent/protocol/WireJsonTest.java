package dev.minecraftagent.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class WireJsonTest {
  private static final WireJsonReader.Budget BUDGET = ClientPayloadFrames.BUDGET;

  @Test
  void parsesEveryValueKindAndRoundTripsIt() {
    for (var source :
        List.of(
            "{}",
            "[]",
            "null",
            "true",
            "false",
            "0",
            "-0",
            "123456789012345678901234567890",
            "-42",
            "0.5",
            "-0.25",
            "1e10",
            "1E+2",
            "2.5e-3",
            "\"\"",
            "\"text\"",
            "\"quote\\\"slash\\\\newline\\ntab\\t\"",
            "\"\\u0007\\u001f\\u007f\"",
            "\"é😀\"",
            "{\"a\":1}",
            "{\"a\":{\"b\":[1,2,{\"c\":null}]},\"d\":true}",
            " { \"a\" : [ 1 , 2 ] } ")) {
      var value = WireJsonReader.parse(source, BUDGET);
      var encoded = value.toJsonText();
      assertEquals(value, WireJsonReader.parse(encoded, BUDGET), () -> "round trip of " + source);
      if (value instanceof WireJson.ObjectNode object) {
        assertEquals(
            object.fields(),
            ((WireJson.ObjectNode) WireJsonReader.parse(encoded, BUDGET)).fields());
      }
    }
  }

  @Test
  void writesCompactGsonCompatibleEscaping() {
    var object =
        new WireJson.ObjectNode()
            .putString("quote", "\"")
            .putString("solidus", "\\")
            .putString("controls", "\b\f\n\r\t")
            .putString("other", "\u0001\u001f\u007f")
            .putString("surrogate", "😀")
            .putBoolean("yes", true)
            .putNull("nothing");
    assertEquals(
        "{\"quote\":\"\\\"\",\"solidus\":\"\\\\\",\"controls\":\"\\b\\f\\n\\r\\t\","
            + "\"other\":\"\\u0001\\u001f\",\"surrogate\":\"\\ud83d\\ude00\","
            + "\"yes\":true,\"nothing\":null}",
        object.toJsonText());
  }

  @Test
  void keepsNumbersAsExactLiterals() {
    var number =
        (WireJson.NumberNode) WireJsonReader.parse("123456789012345678901234567890", BUDGET);
    assertEquals("123456789012345678901234567890", number.literal());
    assertEquals("123456789012345678901234567890", number.toJsonText());
  }

  @Test
  void rejectsEveryNonStrictDocument() {
    for (var source :
        List.of(
            "",
            "   ",
            "{",
            "}",
            "{\"a\"}",
            "{a:1}",
            "{'a':1}",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\":01}",
            "{\"a\":+1}",
            "{\"a\":.5}",
            "{\"a\":1.}",
            "{\"a\":-}",
            "{\"a\":NaN}",
            "{\"a\":Infinity}",
            "{\"a\":undefined}",
            "// comment\n{\"a\":1}",
            "{\"a\":1}/* trailing */",
            "{\"a\":1} {\"b\":2}",
            "{\"a\":1} trailing",
            "nul",
            "tru",
            "{\"a\":\"raw\ncontrol\"}",
            "\"unterminated",
            "{\"a\":\"bad\\escape\"}",
            "{\"a\":\"bad\\u12g4\"}",
            "[1 2]")) {
      var failure =
          assertThrows(
              WireJsonException.class,
              () -> WireJsonReader.parse(source, BUDGET),
              () -> "expected rejection of <" + source + ">");
      assertEquals(WireJsonException.Reason.JSON_INVALID, failure.reason());
    }
  }

  @Test
  void rejectsRepeatedFieldNamesPerObject() {
    var failure =
        assertThrows(
            WireJsonException.class, () -> WireJsonReader.parse("{\"a\":1,\"a\":2}", BUDGET));
    assertEquals(WireJsonException.Reason.DUPLICATE_FIELD, failure.reason());

    // The same name at the same depth in sibling objects is not a duplicate.
    var siblings =
        (WireJson.ObjectNode) WireJsonReader.parse("{\"a\":{\"x\":1},\"b\":{\"x\":2}}", BUDGET);
    assertEquals(
        "1", ((WireJson.NumberNode) ((WireJson.ObjectNode) siblings.get("a")).get("x")).literal());
  }

  @Test
  void rejectsDocumentsThatExceedTheBudget() {
    var tooDeep =
        "{\"a\":".repeat(ClientPayloadFrames.BUDGET.maxDepth() + 1)
            + "1"
            + "}".repeat(ClientPayloadFrames.BUDGET.maxDepth() + 1);
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(WireJsonException.class, () -> WireJsonReader.parse(tooDeep, BUDGET))
            .reason());

    var tooManyNodes =
        "["
            + IntStream.range(0, BUDGET.maxNodes() + 1)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","))
            + "]";
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(WireJsonException.class, () -> WireJsonReader.parse(tooManyNodes, BUDGET))
            .reason());

    var tooManyItems =
        "["
            + IntStream.range(0, BUDGET.maxArrayItems() + 1)
                .mapToObj(ignored -> "\"x\"")
                .collect(Collectors.joining(","))
            + "]";
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(WireJsonException.class, () -> WireJsonReader.parse(tooManyItems, BUDGET))
            .reason());

    var tooManyFields =
        IntStream.rangeClosed(1, BUDGET.maxObjectFields() + 1)
            .mapToObj(index -> "\"f" + index + "\":" + index)
            .collect(Collectors.joining(",", "{", "}"));
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(WireJsonException.class, () -> WireJsonReader.parse(tooManyFields, BUDGET))
            .reason());

    var tooManyStringChars = "{\"a\":\"" + "x".repeat(BUDGET.maxStringChars()) + "\",\"b\":\"y\"}";
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(
                WireJsonException.class, () -> WireJsonReader.parse(tooManyStringChars, BUDGET))
            .reason());

    var exactlyAtTheStringBudget = "{\"aaaa\":\"" + "x".repeat(BUDGET.maxStringChars() - 4) + "\"}";
    WireJsonReader.parse(exactlyAtTheStringBudget, BUDGET);

    // Field names are charged too: a long name with a short value still exceeds the budget.
    var heavyName = "{\"" + "n".repeat(BUDGET.maxStringChars() + 1) + "\":\"\"}";
    assertEquals(
        WireJsonException.Reason.JSON_LIMIT_EXCEEDED,
        assertThrows(WireJsonException.class, () -> WireJsonReader.parse(heavyName, BUDGET))
            .reason());
  }

  @Test
  void rejectsNonPositiveBudgets() {
    assertThrows(IllegalArgumentException.class, () -> new WireJsonReader.Budget(0, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new WireJsonReader.Budget(1, 1, 1, 1, 0));
  }

  @Test
  void deepCopiesMutableTrees() {
    var inner = new WireJson.ObjectNode().putString("a", "b");
    var array = new WireJson.ArrayNode().add(inner);
    var original = new WireJson.ObjectNode().put("list", array).putLong("n", 7);
    var copy = (WireJson.ObjectNode) WireJson.deepCopy(original);
    inner.putString("a", "mutated");
    array.add(new WireJson.TextNode("x"));
    assertEquals("{\"list\":[{\"a\":\"b\"}],\"n\":7}", copy.toJsonText());
    assertEquals(original.toJsonText(), original.deepCopy().toJsonText());
  }
}
