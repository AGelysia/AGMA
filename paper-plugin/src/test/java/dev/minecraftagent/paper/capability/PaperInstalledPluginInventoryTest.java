package dev.minecraftagent.paper.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.minecraftagent.paper.capability.load.InstalledPluginInventory.InstalledPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.List;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperInstalledPluginInventoryTest {
  @Test
  void publishesImmutableEnabledStateWithoutReadingBukkitFromTheWorker() {
    var target = plugin("WorldEdit", "7.3.1", true);
    var inventory = new PaperInstalledPluginInventory(List.of(target));

    assertEquals(
        List.of(new InstalledPlugin("WorldEdit", "7.3.1", true)),
        List.copyOf(inventory.snapshot()));

    inventory.update(target, false);

    assertEquals(
        List.of(new InstalledPlugin("WorldEdit", "7.3.1", false)),
        List.copyOf(inventory.snapshot()));
  }

  @Test
  void pluginNamesAreReplacedCaseInsensitively() {
    var original = plugin("Example", "1.0", true);
    var replacement = plugin("example", "2.0", true);
    var inventory = new PaperInstalledPluginInventory(List.of(original));

    inventory.update(replacement, true);

    assertEquals(
        List.of(new InstalledPlugin("example", "2.0", true)), List.copyOf(inventory.snapshot()));
  }

  private static Plugin plugin(String name, String version, boolean enabled) {
    var metadata = mock(PluginMeta.class);
    when(metadata.getVersion()).thenReturn(version);
    var plugin = mock(Plugin.class);
    when(plugin.getName()).thenReturn(name);
    when(plugin.getPluginMeta()).thenReturn(metadata);
    when(plugin.isEnabled()).thenReturn(enabled);
    return plugin;
  }
}
