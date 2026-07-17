package dev.minecraftagent.paper.command;

public final class CommandRegistrationFailure extends RuntimeException {
  private final String code;

  public CommandRegistrationFailure(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
