package dev.minecraftagent.paper.runtime.install;

import java.util.Objects;

/** An installation failure whose message is safe to write to the server log. */
public final class ManagedRuntimeInstallException extends Exception {
  private final String code;

  ManagedRuntimeInstallException(String code) {
    super(
        "Managed runtime installation failed: " + Objects.requireNonNull(code), null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
