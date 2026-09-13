package dev.minecraftagent.standalone.common.preview;

import java.util.Objects;

/** One ordered build shape: a bounds, a cell-selection pattern, and its target block state. */
public record PreviewShape(PreviewBounds bounds, PreviewPattern pattern, String blockState) {
  public PreviewShape {
    Objects.requireNonNull(bounds, "bounds");
    Objects.requireNonNull(pattern, "pattern");
    // A clear shape targets air and must not carry a state; every other pattern requires one.
    if (pattern == PreviewPattern.CLEAR ? blockState != null : blockState == null) {
      throw new IllegalArgumentException("preview shape block state is invalid");
    }
  }
}
