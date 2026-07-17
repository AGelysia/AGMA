package dev.minecraftagent.standalone.common;

import java.util.Objects;

public record ClientToolError(Status status, String code, String message, boolean retryable)
    implements ClientToolOutcome {
  public ClientToolError {
    Objects.requireNonNull(status, "status");
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("Client tool error code is invalid");
    }
    message = Objects.requireNonNull(message, "message");
    if (message.isEmpty() || message.codePointCount(0, message.length()) > 1024) {
      throw new IllegalArgumentException("Client tool error message is invalid");
    }
  }

  public enum Status {
    FAILED("failed"),
    REJECTED("rejected");

    private final String wireName;

    Status(String wireName) {
      this.wireName = wireName;
    }

    String wireName() {
      return wireName;
    }
  }
}
