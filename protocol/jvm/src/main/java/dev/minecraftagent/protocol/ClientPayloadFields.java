package dev.minecraftagent.protocol;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Typed field access for a decoded client-channel payload.
 *
 * <p>Every accessor reports a failure with the code its caller supplies, so the shared grammar can
 * keep the stable code vocabulary each end already publishes instead of inventing a new one.
 */
public final class ClientPayloadFields {
  private ClientPayloadFields() {}

  /** Rejects an object whose key set is not exactly {@code expected}. */
  public static void requireFields(WireJson.ObjectNode object, Set<String> expected, String code) {
    if (!object.names().equals(expected)) {
      throw new ProtocolViolationException(code);
    }
  }

  /** Reads a required object field. */
  public static WireJson.ObjectNode object(WireJson.ObjectNode parent, String name, String code) {
    var value = parent.get(name);
    if (!(value instanceof WireJson.ObjectNode object)) {
      throw new ProtocolViolationException(code);
    }
    return object;
  }

  /** Reads a required, non-empty, NUL-free string field of bounded length. */
  public static String string(
      WireJson.ObjectNode parent, String name, int minimumChars, int maximumChars, String code) {
    var value = parent.get(name);
    if (!(value instanceof WireJson.TextNode text)) {
      throw new ProtocolViolationException(code);
    }
    var result = text.value();
    var length = result.codePointCount(0, result.length());
    if (length < minimumChars || length > maximumChars || result.indexOf('\0') >= 0) {
      throw new ProtocolViolationException(code);
    }
    return result;
  }

  /** Reads a string field that may be JSON {@code null}, yielding {@code null}. */
  public static String nullableString(
      WireJson.ObjectNode parent, String name, int minimumChars, int maximumChars, String code) {
    if (parent.get(name) instanceof WireJson.NullNode) {
      return null;
    }
    return string(parent, name, minimumChars, maximumChars, code);
  }

  /** Reads a required boolean field. */
  public static boolean bool(WireJson.ObjectNode parent, String name, String code) {
    if (parent.get(name) instanceof WireJson.BooleanNode value) {
      return value.value();
    }
    throw new ProtocolViolationException(code);
  }

  /** Reads a required integer field and bounds it. */
  public static int integer(
      WireJson.ObjectNode parent, String name, int minimum, int maximum, String code) {
    var value = parent.get(name);
    if (!(value instanceof WireJson.NumberNode number)) {
      throw new ProtocolViolationException(code);
    }
    try {
      var result = new BigDecimal(number.literal()).intValueExact();
      if (result < minimum || result > maximum) {
        throw new ProtocolViolationException(code);
      }
      return result;
    } catch (NumberFormatException | ArithmeticException error) {
      throw new ProtocolViolationException(code);
    }
  }

  /** Reads a required long field and bounds it. */
  public static long longValue(
      WireJson.ObjectNode parent, String name, long minimum, long maximum, String code) {
    var value = parent.get(name);
    if (!(value instanceof WireJson.NumberNode number)) {
      throw new ProtocolViolationException(code);
    }
    try {
      var result = new BigDecimal(number.literal()).longValueExact();
      if (result < minimum || result > maximum) {
        throw new ProtocolViolationException(code);
      }
      return result;
    } catch (NumberFormatException | ArithmeticException error) {
      throw new ProtocolViolationException(code);
    }
  }

  /** Reads a required canonical lowercase UUID, optionally JSON {@code null}. */
  public static UUID uuid(WireJson.ObjectNode parent, String name, boolean nullable, String code) {
    return uuid(parent, name, nullable, code, code);
  }

  /**
   * Reads a required canonical lowercase UUID, optionally JSON {@code null}, reporting shape
   * failures and canonicality failures with separate codes where an end distinguishes them.
   */
  public static UUID uuid(
      WireJson.ObjectNode parent,
      String name,
      boolean nullable,
      String fieldCode,
      String uuidCode) {
    if (nullable && parent.get(name) instanceof WireJson.NullNode) {
      return null;
    }
    var source = string(parent, name, 1, ClientPayloadLimits.UUID_CHARS, fieldCode);
    try {
      var parsed = UUID.fromString(source);
      if (!parsed.toString().equals(source)) {
        throw new ProtocolViolationException(uuidCode);
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new ProtocolViolationException(uuidCode);
    }
  }
}
