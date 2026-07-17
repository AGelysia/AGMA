package dev.minecraftagent.client.litematica;

import java.util.Optional;

@FunctionalInterface
public interface ModInventory {
  Optional<String> version(String modId);
}
