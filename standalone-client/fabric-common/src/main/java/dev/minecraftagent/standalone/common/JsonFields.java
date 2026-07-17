package dev.minecraftagent.standalone.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class JsonFields {
  private JsonFields() {}

  static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> source)) {
      throw invalid(field);
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid(field);
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  static Map<String, Object> exactObject(Object value, String field, String... requiredProperties) {
    var result = object(value, field);
    if (!result.keySet().equals(Set.of(requiredProperties))) {
      throw invalid(field);
    }
    return result;
  }

  static List<Object> array(Object value, String field, int maximum) {
    if (!(value instanceof List<?> source) || source.size() > maximum) {
      throw invalid(field);
    }
    return new ArrayList<>(source);
  }

  static List<String> stringArray(Object value, String field, int maximum) {
    var result = new ArrayList<String>();
    for (var entry : array(value, field, maximum)) {
      result.add(string(entry, field, 256));
    }
    return List.copyOf(result);
  }

  static String string(Object value, String field, int maximumCodePoints) {
    if (!(value instanceof String text)
        || text.isEmpty()
        || text.codePointCount(0, text.length()) > maximumCodePoints) {
      throw invalid(field);
    }
    return text;
  }

  static String nullableString(Object value, String field, int maximumCodePoints) {
    return value == null ? null : string(value, field, maximumCodePoints);
  }

  static boolean bool(Object value, String field) {
    if (!(value instanceof Boolean bool)) {
      throw invalid(field);
    }
    return bool;
  }

  static int integer(Object value, String field) {
    var number = decimal(value, field);
    try {
      return number.intValueExact();
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  static long longInteger(Object value, String field) {
    var number = decimal(value, field);
    try {
      return number.longValueExact();
    } catch (ArithmeticException exception) {
      throw invalid(field);
    }
  }

  static UUID uuid(Object value, String field) {
    var text = string(value, field, 36);
    try {
      var uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text)) {
        throw invalid(field);
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw invalid(field);
    }
  }

  static UUID nullableUuid(Object value, String field) {
    return value == null ? null : uuid(value, field);
  }

  static Instant instant(Object value, String field) {
    try {
      return Instant.parse(string(value, field, 64));
    } catch (DateTimeParseException exception) {
      throw invalid(field);
    }
  }

  static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("Invalid JSON field: " + field);
  }

  private static BigDecimal decimal(Object value, String field) {
    if (!(value instanceof BigDecimal number)) {
      throw invalid(field);
    }
    return number;
  }
}
