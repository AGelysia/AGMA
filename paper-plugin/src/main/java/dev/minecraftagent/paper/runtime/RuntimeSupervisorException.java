package dev.minecraftagent.paper.runtime;

public final class RuntimeSupervisorException extends RuntimeException {
  private final String code;

  public RuntimeSupervisorException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
