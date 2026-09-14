package dev.minecraftagent.standalone.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockEntityDataSanitizerTest {
  @Test
  void redactsUuidValuesAndSensitiveKeys() {
    var root = new LinkedHashMap<String, Object>();
    root.put("SkullOwner", "Steve");
    root.put("custom_name", "Box");
    root.put("playerProfile", "x");
    root.put("lockToken", "550e8400-e29b-41d4-a716-446655440000");
    root.put("profile", Map.of("id", "minecraft:chest"));
    root.put("note", "550e8400-e29b-41d4-a716");
    root.put("Items", List.of());

    var sanitized = sanitize(root);

    assertEquals("[redacted]", sanitized.get("SkullOwner"));
    assertEquals("[redacted]", sanitized.get("custom_name"));
    assertEquals("[redacted]", sanitized.get("playerProfile"));
    assertEquals("[redacted]", sanitized.get("lockToken"));
    assertEquals("[redacted]", sanitized.get("profile"));
    assertEquals("550e8400-e29b-41d4-a716", sanitized.get("note"));
    assertEquals(List.of(), sanitized.get("Items"));
  }

  @Test
  void capsNestingDepthAtFour() {
    Map<String, Object> deepest = Map.of("leaf", "value");
    var level4 = Map.of("level5", deepest);
    var level3 = Map.of("level4", level4);
    var level2 = Map.of("level3", level3);
    var root = Map.of("level2", level2);

    var sanitized = sanitize(root);
    var l2 = object(sanitized.get("level2"));
    var l3 = object(l2.get("level3"));
    var l4 = object(l3.get("level4"));
    assertEquals("[truncated]", l4.get("level5"));
  }

  @Test
  void capsMapEntriesAndSortsKeysDeterministically() {
    var root = new LinkedHashMap<String, Object>();
    for (var index = 69; index >= 0; index--) {
      root.put("key-" + String.format("%02d", index), index);
    }

    var sanitized = sanitize(root);

    assertEquals(64, sanitized.size());
    var keys = new ArrayList<>(sanitized.keySet());
    assertEquals("key-00", keys.get(0));
    assertEquals("key-63", keys.get(63));
    assertEquals(List.copyOf(keys), keys.stream().sorted().toList());
  }

  @Test
  void capsListsWithATruncationMarker() {
    var longList = new ArrayList<>();
    for (var index = 0; index < 17; index++) {
      longList.add(index);
    }
    var root = Map.of("long", longList, "exact", List.of(1, 2, 3));

    var sanitized = sanitize(root);

    assertEquals(Map.of("__truncated", 17), sanitized.get("long"));
    assertEquals(List.of(1, 2, 3), sanitized.get("exact"));
  }

  @Test
  void capsStringsAndKeepsShortStrings() {
    var root = Map.of("long", "x".repeat(200), "short", "y".repeat(128));

    var sanitized = sanitize(root);

    var truncated = (String) sanitized.get("long");
    assertEquals(128, truncated.codePointCount(0, truncated.length()));
    assertEquals("…", truncated.substring(truncated.length() - 1));
    assertEquals("y".repeat(128), sanitized.get("short"));
  }

  @Test
  void flattensItemStacksAndDropsTheirNestedData() {
    var upper =
        new LinkedHashMap<String, Object>(
            Map.of(
                "id",
                "minecraft:written_book",
                "Count",
                Byte.valueOf((byte) 3),
                "tag",
                Map.of("title", "secret pages")));
    var lower =
        new LinkedHashMap<String, Object>(
            Map.of("id", "minecraft:stone", "count", Short.valueOf((short) 7)));
    var notAnItem = new LinkedHashMap<String, Object>(Map.of("id", "not namespaced", "Count", 1));
    var root = Map.of("Items", List.of(upper, lower, notAnItem));

    var sanitized = sanitize(root);
    var items = list(sanitized.get("Items"));

    assertEquals(Map.of("id", "minecraft:written_book", "count", 3), items.get(0));
    assertEquals(Map.of("id", "minecraft:stone", "count", 7), items.get(1));
    var kept = object(items.get(2));
    assertEquals("not namespaced", kept.get("id"));
    assertEquals(1, kept.get("Count"));
  }

  @Test
  void normalizesNumbersAndKeepsLeaves() {
    var root = new LinkedHashMap<String, Object>();
    root.put("byte", Byte.valueOf((byte) 1));
    root.put("short", Short.valueOf((short) 2));
    root.put("int", 3);
    root.put("long", 4_000_000_000L);
    root.put("float", Float.valueOf(0.5f));
    root.put("double", 0.25);
    root.put("nan", Double.NaN);
    root.put("flag", Boolean.TRUE);
    root.put("missing", null);

    var sanitized = sanitize(root);

    assertEquals(1, sanitized.get("byte"));
    assertEquals(2, sanitized.get("short"));
    assertEquals(3, sanitized.get("int"));
    assertEquals(4_000_000_000L, sanitized.get("long"));
    assertEquals(0.5, (Double) sanitized.get("float"), 0.0);
    assertEquals(0.25, (Double) sanitized.get("double"), 0.0);
    assertEquals("[non-finite number]", sanitized.get("nan"));
    assertEquals(Boolean.TRUE, sanitized.get("flag"));
    assertNull(sanitized.get("missing"));
  }

  private static Map<String, Object> sanitize(Map<String, ?> root) {
    return object(BlockEntityDataSanitizer.sanitize(new LinkedHashMap<>(root)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
