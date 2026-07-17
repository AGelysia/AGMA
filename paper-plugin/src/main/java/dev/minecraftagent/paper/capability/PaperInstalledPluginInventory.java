package dev.minecraftagent.paper.capability;

import dev.minecraftagent.paper.capability.load.InstalledPluginInventory;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/** Main-thread Bukkit events update the immutable inventory read by the startup worker. */
public final class PaperInstalledPluginInventory implements InstalledPluginInventory, Listener {
  private volatile List<InstalledPlugin> snapshot;

  public PaperInstalledPluginInventory(Collection<? extends Plugin> plugins) {
    Objects.requireNonNull(plugins);
    snapshot = plugins.stream().map(PaperInstalledPluginInventory::installedPlugin).toList();
  }

  @Override
  public Collection<InstalledPlugin> snapshot() {
    return snapshot;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPluginEnable(PluginEnableEvent event) {
    update(event.getPlugin(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPluginDisable(PluginDisableEvent event) {
    update(event.getPlugin(), false);
  }

  synchronized void update(Plugin plugin, boolean enabled) {
    Objects.requireNonNull(plugin);
    var replacement =
        new InstalledPlugin(plugin.getName(), plugin.getPluginMeta().getVersion(), enabled);
    var updated = new java.util.ArrayList<InstalledPlugin>(snapshot.size() + 1);
    for (var installed : snapshot) {
      if (!installed.name().equalsIgnoreCase(replacement.name())) {
        updated.add(installed);
      }
    }
    updated.add(replacement);
    snapshot = List.copyOf(updated);
  }

  private static InstalledPlugin installedPlugin(Plugin plugin) {
    Objects.requireNonNull(plugin);
    return new InstalledPlugin(
        plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled());
  }
}
