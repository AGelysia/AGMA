package dev.minecraftagent.standalone.common;

import java.util.Objects;
import java.util.UUID;

/** Secret-free profile state exposed to version-specific game UI code. */
public record ClientProfileSnapshot(
    ClientLifecycleState state,
    long revision,
    UUID installationId,
    String provider,
    String model,
    String failureCode) {
  public ClientProfileSnapshot {
    Objects.requireNonNull(state, "state");
    if (revision < 0) {
      throw new IllegalArgumentException("Profile revision cannot be negative");
    }
    if (state == ClientLifecycleState.UNCONFIGURED) {
      if (installationId != null || provider != null || model != null || failureCode != null) {
        throw new IllegalArgumentException("Unconfigured profile snapshot carries configured data");
      }
    } else if (installationId == null || provider == null || model == null) {
      throw new IllegalArgumentException("Configured profile snapshot is incomplete");
    }
    if ((state == ClientLifecycleState.ERROR) != (failureCode != null)) {
      throw new IllegalArgumentException("Only an error profile snapshot carries a failure code");
    }
  }
}
