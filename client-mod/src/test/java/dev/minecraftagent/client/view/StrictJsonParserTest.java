package dev.minecraftagent.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class StrictJsonParserTest {
  @Test
  void parsesAScalarDocumentAndRejectsTrailingContent() throws Exception {
    assertInstanceOf(JsonNumber.class, StrictJsonParser.parse("12"));

    ViewDecodeException trailing =
        assertThrows(
            ViewDecodeException.class, () -> StrictJsonParser.parse("{\"a\":1} {\"b\":2}"));
    assertEquals(ViewDecodeException.Code.INVALID_JSON, trailing.code());
  }

  @Test
  void rejectsNonStrictJsonAndDuplicateFieldNames() {
    assertCode(ViewDecodeException.Code.INVALID_JSON, "{'a':1}");
    assertCode(ViewDecodeException.Code.INVALID_JSON, "{a:1}");
    assertCode(ViewDecodeException.Code.INVALID_JSON, "{\"a\":01}");
    assertCode(ViewDecodeException.Code.DUPLICATE_FIELD, "{\"a\":1,\"a\":2}");
  }

  @Test
  void rejectsInvalidUtf8BeforeParsing() {
    ViewDecodeException exception =
        assertThrows(
            ViewDecodeException.class,
            () -> StrictJsonParser.decodeUtf8(new byte[] {(byte) 0xc3, 0x28}));
    assertEquals(ViewDecodeException.Code.INVALID_UTF8, exception.code());
  }

  @Test
  void enforcesTheDepthBudget() throws Exception {
    // nested(23) puts the leaf scalar exactly at depth MAX_JSON_DEPTH.
    assertInstanceOf(JsonObject.class, StrictJsonParser.parse(nested(23)));
    assertCode(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED, nested(24));
  }

  @Test
  void enforcesTheNumberLengthBudget() throws Exception {
    assertInstanceOf(JsonNumber.class, StrictJsonParser.parse("1".repeat(64)));
    assertCode(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED, "1".repeat(65));
  }

  @Test
  void enforcesTheNodeBudget() throws Exception {
    // Each nested array holds MAX_ARRAY_ITEMS (4096) booleans, so nine of them exceed
    // MAX_JSON_NODES while every individual array stays within its own bound.
    assertInstanceOf(JsonArray.class, StrictJsonParser.parse(nestedArrays(7)));
    assertCode(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED, nestedArrays(9));
  }

  private static void assertCode(ViewDecodeException.Code code, String json) {
    ViewDecodeException exception =
        assertThrows(ViewDecodeException.class, () -> StrictJsonParser.parse(json));
    assertEquals(code, exception.code());
  }

  private static String nested(int depth) {
    StringBuilder json = new StringBuilder();
    for (int index = 0; index < depth; index++) {
      json.append("{\"a\":");
    }
    json.append('1');
    for (int index = 0; index < depth; index++) {
      json.append('}');
    }
    return json.toString();
  }

  private static String nestedArrays(int count) {
    String array = "[".concat("false,".repeat(4095)).concat("false]");
    return "[".concat((array + ",").repeat(count - 1)).concat(array).concat("]");
  }
}
