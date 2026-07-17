package dev.minecraftagent.standalone.supervisor;

import java.util.Objects;

/**
 * A bounded supervisor failure whose message is safe to show without process arguments or secrets.
 */
public final class SupervisorException extends RuntimeException {
  private final String code;

  public SupervisorException(String code, String message) {
    super(Objects.requireNonNull(message, "message"), null, false, false);
    this.code = requireCode(code);
  }

  public SupervisorException(String code, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause, false, false);
    this.code = requireCode(code);
  }

  public String code() {
    return code;
  }

  private static String requireCode(String value) {
    Objects.requireNonNull(value, "code");
    if (!value.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Supervisor error code is invalid");
    }
    return value;
  }
}
