package dev.minecraftagent.protocol;

import java.util.HashSet;

/**
 * Strict JSON reader for the client channel.
 *
 * <p>The reader is deliberately stricter than a general purpose parser: unquoted names, single
 * quotes, leading zeroes, {@code NaN}, comments, trailing commas, raw control characters, trailing
 * content and repeated field names are all rejected, and every document is bounded by a budget so
 * that neither end can be asked to materialize an unbounded tree.
 */
public final class WireJsonReader {
  /** Bounds applied while a document is being read. */
  public record Budget(
      int maxDepth, int maxNodes, int maxStringChars, int maxObjectFields, int maxArrayItems) {
    public Budget {
      require(maxDepth >= 1, "maxDepth");
      require(maxNodes >= 1, "maxNodes");
      require(maxStringChars >= 1, "maxStringChars");
      require(maxObjectFields >= 1, "maxObjectFields");
      require(maxArrayItems >= 1, "maxArrayItems");
    }

    private static void require(boolean condition, String name) {
      if (!condition) {
        throw new IllegalArgumentException(name + " must be positive");
      }
    }
  }

  private final String text;
  private final Budget budget;
  private int index;
  private int nodes;
  private int stringChars;

  private WireJsonReader(String text, Budget budget) {
    this.text = text;
    this.budget = budget;
  }

  /**
   * Reads exactly one strict JSON document.
   *
   * @throws WireJsonException when the document is not strict JSON or breaches {@code budget}
   */
  public static WireJson parse(String text, Budget budget) {
    var reader = new WireJsonReader(text, budget);
    reader.skipWhitespace();
    WireJson value = reader.readElement(1);
    reader.skipWhitespace();
    if (reader.index != reader.text.length()) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    return value;
  }

  private WireJson readElement(int depth) {
    if (depth > budget.maxDepth() || ++nodes > budget.maxNodes) {
      throw new WireJsonException(WireJsonException.Reason.JSON_LIMIT_EXCEEDED);
    }
    if (index >= text.length()) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    var character = text.charAt(index);
    return switch (character) {
      case '{' -> readObject(depth);
      case '[' -> readArray(depth);
      case '"' -> readString();
      case 't' -> literal("true", new WireJson.BooleanNode(true));
      case 'f' -> literal("false", new WireJson.BooleanNode(false));
      case 'n' -> literal("null", WireJson.NullNode.INSTANCE);
      default -> {
        if (character == '-' || character >= '0' && character <= '9') {
          yield readNumber();
        }
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
    };
  }

  private WireJson literal(String expected, WireJson value) {
    if (!text.startsWith(expected, index)) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    index += expected.length();
    return value;
  }

  private WireJson.ObjectNode readObject(int depth) {
    index++;
    var object = new WireJson.ObjectNode();
    var names = new HashSet<String>();
    skipWhitespace();
    if (index < text.length() && text.charAt(index) == '}') {
      index++;
      return object;
    }
    while (true) {
      skipWhitespace();
      if (index >= text.length() || text.charAt(index) != '"') {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      if (object.size() >= budget.maxObjectFields()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_LIMIT_EXCEEDED);
      }
      var name = readString().value();
      if (!names.add(name)) {
        throw new WireJsonException(WireJsonException.Reason.DUPLICATE_FIELD);
      }
      skipWhitespace();
      if (index >= text.length() || text.charAt(index) != ':') {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      index++;
      skipWhitespace();
      object.put(name, readElement(depth + 1));
      skipWhitespace();
      if (index >= text.length()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      var separator = text.charAt(index);
      index++;
      if (separator == '}') {
        return object;
      }
      if (separator != ',') {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
    }
  }

  private WireJson.ArrayNode readArray(int depth) {
    index++;
    var array = new WireJson.ArrayNode();
    skipWhitespace();
    if (index < text.length() && text.charAt(index) == ']') {
      index++;
      return array;
    }
    while (true) {
      if (array.size() >= budget.maxArrayItems()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_LIMIT_EXCEEDED);
      }
      skipWhitespace();
      array.add(readElement(depth + 1));
      skipWhitespace();
      if (index >= text.length()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      var separator = text.charAt(index);
      index++;
      if (separator == ']') {
        return array;
      }
      if (separator != ',') {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
    }
  }

  private WireJson.TextNode readString() {
    index++;
    var value = new StringBuilder();
    while (true) {
      if (index >= text.length()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      var character = text.charAt(index);
      if (character == '"') {
        index++;
        var result = value.toString();
        chargeString(result);
        return new WireJson.TextNode(result);
      }
      if (character < 0x20) {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      if (character != '\\') {
        value.append(character);
        index++;
        continue;
      }
      index++;
      if (index >= text.length()) {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      var escape = text.charAt(index);
      index++;
      switch (escape) {
        case '"' -> value.append('"');
        case '\\' -> value.append('\\');
        case '/' -> value.append('/');
        case 'b' -> value.append('\b');
        case 'f' -> value.append('\f');
        case 'n' -> value.append('\n');
        case 'r' -> value.append('\r');
        case 't' -> value.append('\t');
        case 'u' -> value.append(readUnicodeEscape());
        default -> throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
    }
  }

  private char readUnicodeEscape() {
    if (index + 4 > text.length()) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    var digits = text.substring(index, index + 4);
    index += 4;
    try {
      return (char) Integer.parseInt(digits, 16);
    } catch (NumberFormatException error) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
  }

  private WireJson.NumberNode readNumber() {
    var start = index;
    if (text.charAt(index) == '-') {
      index++;
    }
    readDigits(true);
    if (index < text.length() && text.charAt(index) == '.') {
      index++;
      readDigits(false);
    }
    if (index < text.length()) {
      var exponent = text.charAt(index);
      if (exponent == 'e' || exponent == 'E') {
        index++;
        if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
          index++;
        }
        readDigits(false);
      }
    }
    return new WireJson.NumberNode(text.substring(start, index));
  }

  private void readDigits(boolean leadingZeroTerminates) {
    if (index >= text.length()) {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    var first = text.charAt(index);
    if (first == '0') {
      index++;
      if (leadingZeroTerminates
          && index < text.length()
          && text.charAt(index) >= '0'
          && text.charAt(index) <= '9') {
        throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
      }
      return;
    }
    if (first < '1' || first > '9') {
      throw new WireJsonException(WireJsonException.Reason.JSON_INVALID);
    }
    while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
      index++;
    }
  }

  private void skipWhitespace() {
    while (index < text.length()) {
      var character = text.charAt(index);
      if (character != ' ' && character != '\t' && character != '\n' && character != '\r') {
        return;
      }
      index++;
    }
  }

  private void chargeString(String value) {
    stringChars = Math.addExact(stringChars, value.length());
    if (stringChars > budget.maxStringChars()) {
      throw new WireJsonException(WireJsonException.Reason.JSON_LIMIT_EXCEEDED);
    }
  }
}
