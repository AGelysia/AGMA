package dev.minecraftagent.paper.transport;

public final class RuntimeConnectionFailure extends RuntimeException {
  private final String code;
  private final String stage;

  public RuntimeConnectionFailure(String code, String stage) {
    super(code, null, false, false);
    this.code = code;
    this.stage = stage;
  }

  public String code() {
    return code;
  }

  public String stage() {
    return stage;
  }
}
