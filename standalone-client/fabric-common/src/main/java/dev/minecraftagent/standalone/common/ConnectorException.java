package dev.minecraftagent.standalone.common;

import java.util.Objects;

/** Safe connector failure with no remote payload, URL, token, or request text in its message. */
public final class ConnectorException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public ConnectorException(String code, String safeMessage) {
    this(code, safeMessage, null);
  }

  public ConnectorException(String code, String safeMessage, Throwable cause) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"), cause, false, false);
    this.code = requireCode(code);
  }

  public String code() {
    return code;
  }

  private static String requireCode(String value) {
    Objects.requireNonNull(value, "code");
    if (!value.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Connector error code is invalid");
    }
    return value;
  }
}
