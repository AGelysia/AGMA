package dev.minecraftagent.standalone.fabric;

import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric loader adapter behind the Minecraft 1.20.1 catalog surfaces. */
public final class FabricModMetadataSource implements ModMetadataSource {
  @Override
  public Optional<ModMetadata> find(String namespace) {
    return FabricLoader.getInstance()
        .getModContainer(namespace)
        .map(FabricModMetadataSource::metadata);
  }

  @Override
  public Optional<String> installedVersion(String modId) {
    return FabricLoader.getInstance()
        .getModContainer(modId)
        .map(container -> container.getMetadata().getVersion().getFriendlyString());
  }

  @Override
  public List<ModMetadata> all() {
    return FabricLoader.getInstance().getAllMods().stream()
        .map(FabricModMetadataSource::metadata)
        .toList();
  }

  private static ModMetadata metadata(ModContainer container) {
    var metadata = container.getMetadata();
    return new ModMetadata(
        metadata.getId(),
        bounded(metadata.getName(), 128),
        bounded(metadata.getVersion().getFriendlyString(), 128));
  }

  private static String bounded(String value, int maximum) {
    var normalized = value == null || value.isBlank() ? "unknown" : value.strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }
}
