package dev.minecraftagent.standalone.common;

import java.util.Objects;

/** Stable, secret-free Runtime lifecycle failure suitable for the in-game status surface. */
public final class ClientRuntimeException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public ClientRuntimeException(String code, String safeMessage) {
    this(code, safeMessage, null);
  }

  public ClientRuntimeException(String code, String safeMessage, Throwable cause) {
    super(Objects.requireNonNull(safeMessage, "safeMessage"), cause, false, false);
    if (!Objects.requireNonNull(code, "code").matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Client Runtime error code is invalid");
    }
    this.code = code;
  }

  public String code() {
    return code;
  }
}
