package dev.minecraftagent.paper.capability.load;

import java.util.Collection;
import java.util.Objects;

/** Pure Java port used to obtain a point-in-time installed-plugin inventory. */
@FunctionalInterface
public interface InstalledPluginInventory {
  Collection<InstalledPlugin> snapshot();

  record InstalledPlugin(String name, String version, boolean enabled) {
    public InstalledPlugin {
      Objects.requireNonNull(name);
      Objects.requireNonNull(version);
    }
  }
}
