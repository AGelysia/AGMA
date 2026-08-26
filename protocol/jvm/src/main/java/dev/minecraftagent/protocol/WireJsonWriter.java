package dev.minecraftagent.protocol;

import java.util.Locale;
import java.util.Map;

/** Compact JSON writer used by both ends when they emit a client-channel frame. */
public final class WireJsonWriter {
  private WireJsonWriter() {}

  /** Writes one value as compact JSON without introducing whitespace. */
  public static String write(WireJson value) {
    var target = new StringBuilder();
    append(target, value);
    return target.toString();
  }

  private static void append(StringBuilder target, WireJson value) {
    switch (value) {
      case WireJson.ObjectNode object -> {
        target.append('{');
        var first = true;
        for (Map.Entry<String, WireJson> field : object.fields().entrySet()) {
          if (!first) {
            target.append(',');
          }
          appendString(target, field.getKey());
          target.append(':');
          append(target, field.getValue());
          first = false;
        }
        target.append('}');
      }
      case WireJson.ArrayNode array -> {
        target.append('[');
        for (var index = 0; index < array.size(); index++) {
          if (index > 0) {
            target.append(',');
          }
          append(target, array.get(index));
        }
        target.append(']');
      }
      case WireJson.TextNode text -> appendString(target, text.value());
      case WireJson.NumberNode number -> target.append(number.literal());
      case WireJson.BooleanNode bool -> target.append(bool.toJsonText());
      case WireJson.NullNode ignored -> target.append("null");
    }
  }

  private static void appendString(StringBuilder target, String value) {
    target.append('"');
    for (var index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> target.append("\\\"");
        case '\\' -> target.append("\\\\");
        case '\n' -> target.append("\\n");
        case '\r' -> target.append("\\r");
        case '\t' -> target.append("\\t");
        case '\b' -> target.append("\\b");
        case '\f' -> target.append("\\f");
        default -> {
          if (character < 0x20 || character >= Character.MIN_SURROGATE) {
            target.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            target.append(character);
          }
        }
      }
    }
    target.append('"');
  }
}
