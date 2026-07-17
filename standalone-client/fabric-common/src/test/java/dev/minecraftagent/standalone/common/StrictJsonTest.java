package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictJsonTest {
  @Test
  void rejectsDuplicateKeysEvenWhenTheFirstValueIsNull() {
    assertThrows(IllegalArgumentException.class, () -> StrictJson.parse("{\"a\":null,\"a\":1}"));
  }

  @Test
  void rejectsInvalidUnicodeAndExcessiveNesting() {
    assertThrows(IllegalArgumentException.class, () -> StrictJson.parse("\"\\ud800\""));
    assertThrows(
        IllegalArgumentException.class,
        () -> StrictJson.parse("[".repeat(34) + "0" + "]".repeat(34)));
  }

  @Test
  void writesAndReadsSupportedValuesWithoutChangingTheirShape() {
    var document = Map.of("text", "line\nvalue", "values", List.of(1, true, "ok"));
    assertEquals(document.toString(), StrictJson.parse(StrictJson.write(document)).toString());
  }
}
