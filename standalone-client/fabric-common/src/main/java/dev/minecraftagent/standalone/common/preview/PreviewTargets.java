package dev.minecraftagent.standalone.common.preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The transformed per-cell targets of one preview and the union of its transformed shape bounds.
 */
public record PreviewTargets(Map<PreviewPosition, String> cells, PreviewBounds unionBounds) {
  public PreviewTargets {
    Objects.requireNonNull(cells, "cells");
    Objects.requireNonNull(unionBounds, "unionBounds");
    cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
  }
}
