package dev.minecraftagent.standalone.common;

import java.util.Objects;

/** Safe configuration failure that never embeds a secret or untrusted document value. */
public final class ClientConfigurationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final String field;

  public ClientConfigurationException(String code, String field, String safeMessage) {
    this(code, field, safeMessage, null);
  }

  public ClientConfigurationException(
      String code, String field, String safeMessage, Throwable cause) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"), cause, false, false);
    this.code = requireCode(code);
    this.field = Objects.requireNonNull(field, "field");
  }

  public String code() {
    return code;
  }

  public String field() {
    return field;
  }

  private static String requireCode(String value) {
    Objects.requireNonNull(value, "code");
    if (!value.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Configuration error code is invalid");
    }
    return value;
  }
}
