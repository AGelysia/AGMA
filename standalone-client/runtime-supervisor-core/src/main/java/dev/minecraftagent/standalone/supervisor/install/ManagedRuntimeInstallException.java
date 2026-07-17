package dev.minecraftagent.standalone.supervisor.install;

/** Stable, path-free installation failure suitable for UI diagnostics. */
public final class ManagedRuntimeInstallException extends Exception {
  private final String code;

  ManagedRuntimeInstallException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
