package dev.minecraftagent.standalone.core.unpack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One item or tag token parsed from a script argument. Tag forms become {@code {tag: id}}
 * ingredient objects so they flow through the live-first/static-second tag expansion of the process
 * mapper; item forms become {@code {item: id, count: n}} ingredients or {@code {item: id, count: n,
 * chance: p}} outputs.
 */
record ScriptItemForm(boolean tag, String id, long count, Double chance) {
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  static final long MAXIMUM_COUNT = 1_000_000L;

  ScriptItemForm {
    if (!NAMESPACED_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("script item id is not a valid namespaced id");
    }
    count = Math.max(1, Math.min(MAXIMUM_COUNT, count));
    if (chance != null && (chance.isNaN() || chance.isInfinite())) {
      chance = null;
    }
  }

  static ScriptItemForm item(String id, long count) {
    return new ScriptItemForm(false, id, count, null);
  }

  static ScriptItemForm tag(String id) {
    return new ScriptItemForm(true, id, 1, null);
  }

  ScriptItemForm withChance(double parsedChance) {
    var clamped = Math.min(1.0, Math.max(0.0, parsedChance));
    return new ScriptItemForm(tag, id, count, clamped);
  }

  /** An ingredient in the map shape the process mapper already understands. */
  Map<String, Object> ingredientJson() {
    if (tag) {
      return Map.of("tag", id);
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("item", id);
    if (count != 1) {
      json.put("count", count);
    }
    return json;
  }

  /** An output in the map shape the process mapper already understands. */
  Map<String, Object> outputJson() {
    var json = new LinkedHashMap<String, Object>();
    json.put("item", id);
    if (count != 1) {
      json.put("count", count);
    }
    if (chance != null && chance < 1.0) {
      json.put("chance", chance);
    }
    return json;
  }
}
