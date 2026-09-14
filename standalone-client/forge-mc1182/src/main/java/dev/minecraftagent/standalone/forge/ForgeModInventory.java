package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.ui.preview.litematica.ModInventory;
import java.util.Optional;
import net.minecraftforge.fml.ModList;

/** Reads metadata only; it never loads an optional mod's implementation classes. */
public final class ForgeModInventory implements ModInventory {
  @Override
  public Optional<String> version(String modId) {
    return ModList.get()
        .getModContainerById(modId)
        .map(container -> container.getModInfo().getVersion().toString());
  }
}
