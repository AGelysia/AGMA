package dev.minecraftagent.standalone.core.unpack;

import java.util.Map;
import java.util.Objects;

/**
 * One advancement file read from a mod archive ({@code data/<namespace>/advancements/<path>.json}).
 * {@code id} is the namespaced advancement id derived from the file path and {@code json} is the
 * parsed root object as produced by {@link BoundedJson}.
 */
public record UnpackedAdvancement(String id, Map<String, Object> json) {
  public UnpackedAdvancement {
    Objects.requireNonNull(id, "id");
    json =
        java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(Objects.requireNonNull(json, "json")));
  }
}
