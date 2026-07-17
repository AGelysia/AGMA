package dev.minecraftagent.client.litematica;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;

/** Reads metadata only; it never loads an optional mod's implementation classes. */
public final class FabricModInventory implements ModInventory {
  @Override
  public Optional<String> version(String modId) {
    return FabricLoader.getInstance()
        .getModContainer(modId)
        .map(container -> container.getMetadata().getVersion().getFriendlyString());
  }
}
