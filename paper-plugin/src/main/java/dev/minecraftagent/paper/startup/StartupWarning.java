package dev.minecraftagent.paper.startup;

import java.util.Objects;

public record StartupWarning(Code code, Stage stage) {
  public enum Code {
    OPTIONAL_CAPABILITY_UNAVAILABLE,
    CAPABILITY_CATALOG_UNAVAILABLE,
    CAPABILITY_PACK_DISABLED
  }

  public enum Stage {
    OPTIONAL_CAPABILITIES
  }

  public StartupWarning {
    Objects.requireNonNull(code);
    Objects.requireNonNull(stage);
  }
}
