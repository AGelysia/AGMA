package dev.minecraftagent.standalone.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded JSON parser and writer that rejects duplicate keys and non-canonical JSON values. */
final class StrictJson {
  static final int MAXIMUM_NESTING = 32;
  static final int MAXIMUM_VALUES = 16_384;
  static final int MAXIMUM_STRING_LENGTH = 65_536;

  private StrictJson() {}

  static Object parse(String source) {
    var parser = new Parser(Objects.requireNonNull(source, "source"));
    var value = parser.value(0);
    parser.whitespace();
    if (!parser.end()) {
      throw invalid();
    }
    return value;
  }

  static String write(Object value) {
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
    } else if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      output.append(value);
    } else if (value instanceof BigDecimal decimal) {
      var normalized = decimal.stripTrailingZeros();
      if (normalized.precision() > 1000 || Math.abs(normalized.scale()) > 10_000) {
        throw invalid();
      }
      output.append(normalized.toPlainString());
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
    validateUnicode(value);
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

  private static void validateUnicode(String value) {
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          throw invalid();
        }
      } else if (Character.isLowSurrogate(character)) {
        throw invalid();
      }
    }
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
        if (result.containsKey(key)) {
          throw invalid();
        }
        result.put(key, entry);
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
          var value = result.toString();
          validateUnicode(value);
          return value;
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
      if (consume('.')) {
        digits();
      }
      if (!end() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
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
      try {
        var value = new BigDecimal(token);
        if (value.precision() > 1000 || Math.abs(value.scale()) > 10_000) {
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
