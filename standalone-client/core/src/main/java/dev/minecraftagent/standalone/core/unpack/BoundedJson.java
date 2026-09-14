package dev.minecraftagent.standalone.core.unpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal bounded JSON parser for mod archive data. Objects parse into insertion-ordered {@link
 * LinkedHashMap} instances, arrays into {@link List}, strings into {@link String}, and the literals
 * into {@link Boolean} or {@code null}. Numbers parse into {@link Long} when the literal is
 * integral and fits into a signed 64-bit value; every other number (fractions, exponents,
 * overflowing integers) parses into {@link Double}. Duplicate object keys are rejected.
 *
 * <p>The parser rejects documents deeper than {@value #MAXIMUM_NESTING} levels, documents with more
 * than {@value #MAXIMUM_VALUES} values, and strings longer than {@value #MAXIMUM_STRING_LENGTH}
 * characters, so a hostile or corrupt archive entry cannot exhaust memory or stack.
 */
public final class BoundedJson {
  public static final int MAXIMUM_NESTING = 64;
  public static final int MAXIMUM_VALUES = 65_536;
  public static final int MAXIMUM_STRING_LENGTH = 262_144;

  private BoundedJson() {}

  public static Object parse(String source) {
    var parser = new Parser(Objects.requireNonNull(source, "source"));
    var value = parser.value(0);
    parser.whitespace();
    if (!parser.end()) {
      throw invalid();
    }
    return value;
  }

  /** Parses a document whose root must be an object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String source) {
    var value = parse(source);
    if (!(value instanceof Map)) {
      throw invalid();
    }
    return (Map<String, Object>) value;
  }

  /** Serializes a parsed value back into compact JSON without insignificant whitespace. */
  public static String write(Object value) {
    var output = new StringBuilder();
    writeValue(output, value, 0);
    return output.toString();
  }

  private static void writeValue(StringBuilder output, Object value, int depth) {
    if (depth > MAXIMUM_NESTING) {
      throw invalid();
    }
    if (value == null) {
      output.append("null");
    } else if (value instanceof String text) {
      writeString(output, text);
    } else if (value instanceof Boolean bool) {
      output.append(bool);
    } else if (value instanceof Long || value instanceof Integer || value instanceof Double) {
      output.append(value);
    } else if (value instanceof Map<?, ?> map) {
      output.append('{');
      var first = true;
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid();
        }
        if (!first) {
          output.append(',');
        }
        first = false;
        writeString(output, key);
        output.append(':');
        writeValue(output, entry.getValue(), depth + 1);
      }
      output.append('}');
    } else if (value instanceof List<?> list) {
      output.append('[');
      for (var index = 0; index < list.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        writeValue(output, list.get(index), depth + 1);
      }
      output.append(']');
    } else {
      throw invalid();
    }
  }

  private static void writeString(StringBuilder output, String value) {
    if (value.length() > MAXIMUM_STRING_LENGTH) {
      throw invalid();
    }
    output.append('"');
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      switch (character) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        default -> {
          if (character < 0x20) {
            output.append("\\u").append(String.format("%04x", (int) character));
          } else {
            output.append(character);
          }
        }
      }
    }
    output.append('"');
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("JSON document is invalid");
  }

  private static final class Parser {
    private final String source;
    private int offset;
    private int values;

    private Parser(String source) {
      this.source = source;
    }

    private Object value(int depth) {
      if (depth > MAXIMUM_NESTING || ++values > MAXIMUM_VALUES) {
        throw invalid();
      }
      whitespace();
      if (end()) {
        throw invalid();
      }
      return switch (source.charAt(offset)) {
        case '{' -> object(depth + 1);
        case '[' -> array(depth + 1);
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object(int depth) {
      offset++;
      whitespace();
      var result = new LinkedHashMap<String, Object>();
      if (consume('}')) {
        return result;
      }
      while (true) {
        whitespace();
        if (end() || source.charAt(offset) != '"') {
          throw invalid();
        }
        var key = string();
        whitespace();
        require(':');
        var entry = value(depth);
        if (result.putIfAbsent(key, entry) != null) {
          throw invalid();
        }
        whitespace();
        if (consume('}')) {
          return result;
        }
        require(',');
      }
    }

    private List<Object> array(int depth) {
      offset++;
      whitespace();
      var result = new ArrayList<>();
      if (consume(']')) {
        return result;
      }
      while (true) {
        result.add(value(depth));
        whitespace();
        if (consume(']')) {
          return result;
        }
        require(',');
      }
    }

    private String string() {
      require('"');
      var result = new StringBuilder();
      while (!end()) {
        var character = source.charAt(offset++);
        if (character == '"') {
          return result.toString();
        }
        if (character < 0x20) {
          throw invalid();
        }
        if (character != '\\') {
          result.append(character);
        } else {
          if (end()) {
            throw invalid();
          }
          switch (source.charAt(offset++)) {
            case '"' -> result.append('"');
            case '\\' -> result.append('\\');
            case '/' -> result.append('/');
            case 'b' -> result.append('\b');
            case 'f' -> result.append('\f');
            case 'n' -> result.append('\n');
            case 'r' -> result.append('\r');
            case 't' -> result.append('\t');
            case 'u' -> result.append(unicode());
            default -> throw invalid();
          }
        }
        if (result.length() > MAXIMUM_STRING_LENGTH) {
          throw invalid();
        }
      }
      throw invalid();
    }

    private char unicode() {
      if (offset + 4 > source.length()) {
        throw invalid();
      }
      var value = 0;
      for (var index = 0; index < 4; index++) {
        var digit = Character.digit(source.charAt(offset++), 16);
        if (digit < 0) {
          throw invalid();
        }
        value = value * 16 + digit;
      }
      return (char) value;
    }

    private Object number() {
      var start = offset;
      if (consume('-') && end()) {
        throw invalid();
      }
      if (consume('0')) {
        if (!end() && Character.isDigit(source.charAt(offset))) {
          throw invalid();
        }
      } else {
        digits();
      }
      var integral = true;
      if (consume('.')) {
        integral = false;
        digits();
      }
      if (!end() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
        integral = false;
        offset++;
        if (!end() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
          offset++;
        }
        digits();
      }
      var token = source.substring(start, offset);
      if (token.length() > 128) {
        throw invalid();
      }
      if (integral) {
        try {
          return Long.valueOf(token);
        } catch (NumberFormatException overflow) {
          // Integral literals that exceed 64 bits fall back to a double.
        }
      }
      try {
        var value = Double.parseDouble(token);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
          throw invalid();
        }
        return value;
      } catch (NumberFormatException exception) {
        throw invalid();
      }
    }

    private void digits() {
      var start = offset;
      while (!end() && Character.isDigit(source.charAt(offset))) {
        offset++;
      }
      if (offset == start) {
        throw invalid();
      }
    }

    private Object literal(String literal, Object value) {
      if (!source.startsWith(literal, offset)) {
        throw invalid();
      }
      offset += literal.length();
      return value;
    }

    private void require(char expected) {
      if (!consume(expected)) {
        throw invalid();
      }
    }

    private boolean consume(char expected) {
      if (!end() && source.charAt(offset) == expected) {
        offset++;
        return true;
      }
      return false;
    }

    private void whitespace() {
      while (!end()) {
        var value = source.charAt(offset);
        if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
          return;
        }
        offset++;
      }
    }

    private boolean end() {
      return offset >= source.length();
    }
  }
}
