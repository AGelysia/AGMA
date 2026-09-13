package dev.minecraftagent.standalone.common.preview;

import java.util.Objects;

/** One transformed non-air target cell, or one observed world cell, with a canonical state. */
public record PreviewCell(int x, int y, int z, String state) implements Comparable<PreviewCell> {
  public PreviewCell {
    Objects.requireNonNull(state, "state");
  }

  public PreviewPosition position() {
    return new PreviewPosition(x, y, z);
  }

  @Override
  public int compareTo(PreviewCell other) {
    var order = Integer.compare(x, other.x);
    if (order != 0) {
      return order;
    }
    order = Integer.compare(y, other.y);
    return order != 0 ? order : Integer.compare(z, other.z);
  }
}
