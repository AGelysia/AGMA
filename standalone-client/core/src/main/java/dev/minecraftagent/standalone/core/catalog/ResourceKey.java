package dev.minecraftagent.standalone.core.catalog;

import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.util.Objects;
import java.util.regex.Pattern;

public record ResourceKey(ResourceRef.Kind kind, String id, String componentsFingerprint)
    implements Comparable<ResourceKey> {
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

  public ResourceKey {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    if (!NAMESPACED_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("resource key id must be namespaced");
    }
    if (componentsFingerprint != null && !SHA_256.matcher(componentsFingerprint).matches()) {
      throw new IllegalArgumentException("resource key fingerprint must be lowercase SHA-256");
    }
  }

  public static ResourceKey from(ResourceRef resource) {
    Objects.requireNonNull(resource, "resource");
    return new ResourceKey(resource.kind(), resource.id(), resource.componentsFingerprint());
  }

  @Override
  public int compareTo(ResourceKey other) {
    var comparison = kind.name().compareTo(other.kind.name());
    if (comparison != 0) {
      return comparison;
    }
    comparison = id.compareTo(other.id);
    if (comparison != 0) {
      return comparison;
    }
    if (componentsFingerprint == null) {
      return other.componentsFingerprint == null ? 0 : -1;
    }
    return other.componentsFingerprint == null
        ? 1
        : componentsFingerprint.compareTo(other.componentsFingerprint);
  }
}
