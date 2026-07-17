package dev.minecraftagent.standalone.common;

import java.util.Objects;
import java.util.Set;

/** Secret-free aggregate exposed to both Minecraft version shells. */
public record ClientRuntimeView(
    ClientProfileSnapshot profile, Set<String> activeTools, String startupFailureCode) {
  public ClientRuntimeView {
    Objects.requireNonNull(profile, "profile");
    activeTools = Set.copyOf(Objects.requireNonNull(activeTools, "activeTools"));
    if (startupFailureCode != null && !startupFailureCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Startup failure code is invalid");
    }
  }
}
