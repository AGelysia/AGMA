package dev.minecraftagent.paper.state;

import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.startup.StartupFailure;

public interface DesiredModeStore {
  DesiredMode load() throws StartupFailure;

  void save(DesiredMode desiredMode) throws StartupFailure;
}
