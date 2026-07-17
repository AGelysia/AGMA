package dev.minecraftagent.paper.lifecycle;

import java.util.Set;
import java.util.UUID;

/** Immutable identity policy used by the on/off command after the startup config is trusted. */
public record AdminPolicy(Set<UUID> owners, boolean allowOpToggle) {
  public AdminPolicy {
    owners = Set.copyOf(owners);
  }

  public static AdminPolicy locked() {
    return new AdminPolicy(Set.of(), false);
  }
}
