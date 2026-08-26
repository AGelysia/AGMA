package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.ui.ModMetadata;
import dev.minecraftagent.standalone.ui.ModMetadataSource;
import java.util.List;
import java.util.Optional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

/** Forge loader adapter behind the shared Minecraft 1.18.2 catalog surfaces. */
public final class ForgeModMetadataSource implements ModMetadataSource {
  @Override
  public Optional<ModMetadata> find(String namespace) {
    return ModList.get().getMods().stream()
        .filter(info -> info.getModId().equals(namespace) || info.getNamespace().equals(namespace))
        .findFirst()
        .map(ForgeModMetadataSource::metadata);
  }

  @Override
  public Optional<String> installedVersion(String modId) {
    return ModList.get()
        .getModContainerById(modId)
        .map(container -> container.getModInfo().getVersion().toString());
  }

  @Override
  public List<ModMetadata> all() {
    return ModList.get().getMods().stream().map(ForgeModMetadataSource::metadata).toList();
  }

  private static ModMetadata metadata(IModInfo metadata) {
    return new ModMetadata(
        metadata.getModId(),
        bounded(metadata.getDisplayName(), 128),
        bounded(metadata.getVersion().toString(), 128));
  }

  private static String bounded(String value, int maximum) {
    var normalized = value == null || value.isBlank() ? "unknown" : value.strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }
}
