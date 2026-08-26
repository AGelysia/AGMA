package dev.minecraftagent.client.view;

import dev.minecraftagent.protocol.ClientPayloadLimits;
import dev.minecraftagent.protocol.StructuredViewContract;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads typed, validated values out of a {@link JsonNode} tree.
 *
 * <p>Every reader enforces the shape bound it names and throws {@link ViewDecodeException} with the
 * protocol error code instead of returning a default, so callers never see a half-validated value.
 */
final class JsonValues {
  private static final Pattern NAMESPACED_ID = StructuredViewContract.NAMESPACED_ID;
  private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
  private static final Pattern SHA_256 = ClientPayloadLimits.SHA256;

  private JsonValues() {}

  static JsonObject closedObject(JsonNode node, Set<String> allowed, Set<String> required)
      throws ViewDecodeException {
    return closedObject(node, allowed, required, false);
  }

  static JsonObject closedObject(
      JsonNode node, Set<String> allowed, Set<String> required, boolean allowAdditional)
      throws ViewDecodeException {
    if (!(node instanceof JsonObject object)) {
      invalidValue();
      throw new AssertionError();
    }
    for (String field : object.fields().keySet()) {
      if (!allowAdditional && !allowed.contains(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.UNKNOWN_FIELD);
      }
    }
    for (String field : required) {
      if (!object.fields().containsKey(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
      }
    }
    return object;
  }

  static String string(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    return visibleString(object.fields().get(field), minimum, maximum, allowLineBreaks);
  }

  static Optional<String> optionalString(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(string(object, field, minimum, maximum, allowLineBreaks));
  }

  static String visibleString(JsonNode node, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    if (!(node instanceof JsonString jsonString)) {
      invalidValue();
      throw new AssertionError();
    }
    String value = jsonString.value();
    int length = value.codePointCount(0, value.length());
    if (length < minimum || length > maximum) {
      invalidValue();
    }
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        invalidValue();
      }
      if (((codePoint == '\n' || codePoint == '\t') && allowLineBreaks)
          || codePoint >= 0x20 && !Character.isISOControl(codePoint)) {
        if (isBidirectionalControl(codePoint)) {
          invalidValue();
        }
      } else {
        invalidValue();
      }
      index += Character.charCount(codePoint);
    }
    return value;
  }

  private static boolean isBidirectionalControl(int codePoint) {
    return codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || codePoint >= 0x202a && codePoint <= 0x202e
        || codePoint >= 0x2066 && codePoint <= 0x2069;
  }

  static UUID uuid(String value) throws ViewDecodeException {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        invalidValue();
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  static String namespacedId(String value) throws ViewDecodeException {
    if (!NAMESPACED_ID.matcher(value).matches()) {
      invalidValue();
    }
    return value;
  }

  static List<JsonNode> array(JsonNode node, int minimum, int maximum) throws ViewDecodeException {
    if (!(node instanceof JsonArray array)) {
      invalidValue();
      throw new AssertionError();
    }
    if (array.values().size() < minimum || array.values().size() > maximum) {
      invalidValue();
    }
    return array.values();
  }

  static boolean bool(JsonNode node) throws ViewDecodeException {
    if (!(node instanceof JsonBoolean value)) {
      invalidValue();
      throw new AssertionError();
    }
    return value.value();
  }

  static Optional<Boolean> optionalBoolean(JsonObject object, String field)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(bool(object.fields().get(field)));
  }

  static int integer(JsonNode node, int minimum, int maximum) throws ViewDecodeException {
    if (!(node instanceof JsonNumber value) || !INTEGER.matcher(value.value()).matches()) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      long number = Long.parseLong(value.value());
      if (number < minimum || number > maximum) {
        invalidValue();
      }
      return (int) number;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  static Optional<Integer> optionalInteger(
      JsonObject object, String field, int minimum, int maximum) throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(integer(object.fields().get(field), minimum, maximum));
  }

  static double decimal(JsonNode node, BigDecimal minimum, BigDecimal maximum)
      throws ViewDecodeException {
    if (!(node instanceof JsonNumber value)) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      BigDecimal number = new BigDecimal(value.value());
      if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
        invalidValue();
      }
      double result = number.doubleValue();
      if (!Double.isFinite(result)) {
        invalidValue();
      }
      return result;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  static <E extends Enum<E>> E enumValue(String wireValue, Class<E> type)
      throws ViewDecodeException {
    try {
      return Enum.valueOf(type, wireValue.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  static String hashString(JsonObject object, String field) throws ViewDecodeException {
    String value = string(object, field, 64, 64, false);
    if (!SHA_256.matcher(value).matches()) {
      invalidValue();
    }
    return value;
  }

  static void invalidValue() throws ViewDecodeException {
    throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE);
  }
}
