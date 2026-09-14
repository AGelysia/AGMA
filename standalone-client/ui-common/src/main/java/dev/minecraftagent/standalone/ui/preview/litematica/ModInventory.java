package dev.minecraftagent.standalone.ui.preview.litematica;

import java.util.Optional;

@FunctionalInterface
public interface ModInventory {
  Optional<String> version(String modId);
}
