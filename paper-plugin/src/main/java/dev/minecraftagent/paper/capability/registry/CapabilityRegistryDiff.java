package dev.minecraftagent.paper.capability.registry;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic ID-level diff between the current and proposed effective catalogs. */
public record CapabilityRegistryDiff(
    Set<String> added, Set<String> removed, Set<String> changed, Set<String> unchanged) {
  public CapabilityRegistryDiff {
    added = immutable(added);
    removed = immutable(removed);
    changed = immutable(changed);
    unchanged = immutable(unchanged);
  }

  private static Set<String> immutable(Set<String> values) {
    return Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(values)));
  }
}
