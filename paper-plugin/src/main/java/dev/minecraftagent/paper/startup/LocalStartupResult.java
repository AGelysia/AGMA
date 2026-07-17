package dev.minecraftagent.paper.startup;

import java.util.List;
import java.util.Objects;

public record LocalStartupResult(
    StartupHealth health,
    PaperStartupConfig config,
    CoreToolRuntime coreTools,
    List<StartupWarning> warnings) {
  public LocalStartupResult {
    Objects.requireNonNull(health);
    Objects.requireNonNull(config);
    Objects.requireNonNull(coreTools);
    warnings = List.copyOf(warnings);
  }
}
