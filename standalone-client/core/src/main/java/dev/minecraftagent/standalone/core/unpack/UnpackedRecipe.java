package dev.minecraftagent.standalone.core.unpack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One recipe file read from a mod archive. {@code id} is the namespaced recipe id derived from the
 * file path ({@code data/<namespace>/recipes/<path>.json} becomes {@code <namespace>:<path>}),
 * {@code type} is the raw recipe serializer type, and {@code json} is the parsed root object as
 * produced by {@link BoundedJson}.
 */
public record UnpackedRecipe(String id, String type, Map<String, Object> json) {
  public UnpackedRecipe {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    // Null JSON values are legal inside a recipe document, so a plain defensive copy is kept
    // instead of a null-rejecting immutable map.
    json = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(json, "json")));
  }
}
