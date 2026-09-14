package dev.minecraftagent.standalone.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared-logic sanitizer for block entity NBT that a per-version converter turned into plain Java
 * values. Identical source is compiled into each version shell; the bounds are the wire contract of
 * game.block.inspect: nesting depth at most 4, at most 64 map entries, at most 16 list entries
 * (longer lists collapse to a {@code {"__truncated": n}} marker), strings capped at 128 characters,
 * and only primitives, maps, and lists survive. Values that parse as UUIDs and entries whose key
 * ends in uuid/owner/player/profile/name are redacted, and item-stack-shaped maps flatten to {@code
 * {id, count}}.
 */
final class BlockEntityDataSanitizer {
  static final int MAXIMUM_DEPTH = 4;
  static final int MAXIMUM_MAP_ENTRIES = 64;
  static final int MAXIMUM_LIST_LENGTH = 16;
  static final int MAXIMUM_STRING_CHARACTERS = 128;
  static final String REDACTED = "[redacted]";
  static final String TRUNCATED = "[truncated]";

  private static final Pattern SENSITIVE_KEY =
      Pattern.compile("(?i).*(uuid|owner|player|profile|name)$");
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

  private BlockEntityDataSanitizer() {}

  static Object sanitize(Map<String, Object> root) {
    return sanitizeMap(root, 0);
  }

  private static Object sanitizeValue(Object value, int depth) {
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number number) {
      return normalizeNumber(number);
    }
    if (value instanceof String text) {
      return sanitizeString(text);
    }
    if (depth >= MAXIMUM_DEPTH) {
      return TRUNCATED;
    }
    if (value instanceof Map<?, ?> map) {
      return sanitizeMap(stringKeys(map), depth);
    }
    if (value instanceof List<?> list) {
      return sanitizeList(list, depth);
    }
    return TRUNCATED;
  }

  private static Map<String, Object> sanitizeMap(Map<String, Object> map, int depth) {
    var flattened = flattenItemStack(map);
    if (flattened != null) {
      return flattened;
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : new TreeMap<>(map).entrySet()) {
      if (result.size() >= MAXIMUM_MAP_ENTRIES) {
        break;
      }
      if (SENSITIVE_KEY.matcher(entry.getKey()).matches()) {
        result.put(entry.getKey(), REDACTED);
      } else {
        result.put(entry.getKey(), sanitizeValue(entry.getValue(), depth + 1));
      }
    }
    return result;
  }

  private static Object sanitizeList(List<?> list, int depth) {
    if (list.size() > MAXIMUM_LIST_LENGTH) {
      return Map.of("__truncated", list.size());
    }
    var result = new ArrayList<>(list.size());
    for (var entry : list) {
      result.add(sanitizeValue(entry, depth + 1));
    }
    return result;
  }

  private static Map<String, Object> flattenItemStack(Map<String, Object> map) {
    if (!(map.get("id") instanceof String id) || !NAMESPACED_ID.matcher(id).matches()) {
      return null;
    }
    var count = map.get("Count") instanceof Number upper ? upper : map.get("count");
    if (!(count instanceof Number countNumber)) {
      return null;
    }
    return Map.of("id", id, "count", normalizeNumber(countNumber));
  }

  private static String sanitizeString(String value) {
    if (parsesAsUuid(value)) {
      return REDACTED;
    }
    if (value.codePointCount(0, value.length()) <= MAXIMUM_STRING_CHARACTERS) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, MAXIMUM_STRING_CHARACTERS - 1)) + "…";
  }

  private static boolean parsesAsUuid(String value) {
    if (value.length() != 36) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static Object normalizeNumber(Number value) {
    if (value instanceof Byte || value instanceof Short) {
      return Integer.valueOf(value.intValue());
    }
    if (value instanceof Integer || value instanceof Long) {
      return value;
    }
    if (value instanceof Float || value instanceof Double) {
      var doubleValue = value.doubleValue();
      return Double.isFinite(doubleValue) ? Double.valueOf(doubleValue) : "[non-finite number]";
    }
    return value.toString();
  }

  private static Map<String, Object> stringKeys(Map<?, ?> map) {
    var result = new LinkedHashMap<String, Object>();
    for (var entry : map.entrySet()) {
      if (entry.getKey() instanceof String key) {
        result.put(key, entry.getValue());
      }
    }
    return result;
  }
}
