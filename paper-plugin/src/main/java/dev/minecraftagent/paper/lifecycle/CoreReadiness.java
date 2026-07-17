package dev.minecraftagent.paper.lifecycle;

import dev.minecraftagent.paper.state.DesiredModeStore;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.util.List;
import java.util.Objects;

public record CoreReadiness(
    RuntimeConnectionSettings runtimeSettings,
    List<String> warningCodes,
    DesiredMode desiredMode,
    DesiredModeStore desiredModeStore,
    AdminPolicy adminPolicy,
    Publication publication) {
  public interface Publication {
    void publish();

    void discard();
  }

  private static final Publication NO_PUBLICATION =
      new Publication() {
        @Override
        public void publish() {}

        @Override
        public void discard() {}
      };

  public CoreReadiness(
      RuntimeConnectionSettings runtimeSettings,
      List<String> warningCodes,
      DesiredMode desiredMode,
      DesiredModeStore desiredModeStore,
      AdminPolicy adminPolicy) {
    this(runtimeSettings, warningCodes, desiredMode, desiredModeStore, adminPolicy, NO_PUBLICATION);
  }

  public CoreReadiness {
    Objects.requireNonNull(runtimeSettings);
    warningCodes = List.copyOf(warningCodes);
    Objects.requireNonNull(desiredMode);
    Objects.requireNonNull(desiredModeStore);
    Objects.requireNonNull(adminPolicy);
    Objects.requireNonNull(publication);
  }
}
