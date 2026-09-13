package dev.minecraftagent.standalone.common.preview;

import java.util.Objects;

/** Inclusive axis-aligned bounds for one build preview shape or union region. */
public record PreviewBounds(PreviewPosition min, PreviewPosition max) {
  public PreviewBounds {
    Objects.requireNonNull(min, "min");
    Objects.requireNonNull(max, "max");
    if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
      throw new IllegalArgumentException("preview bounds are invalid");
    }
  }

  public int sizeX() {
    return max.x() - min.x() + 1;
  }

  public int sizeY() {
    return max.y() - min.y() + 1;
  }

  public int sizeZ() {
    return max.z() - min.z() + 1;
  }

  public int volume() {
    return Math.multiplyExact(Math.multiplyExact(sizeX(), sizeY()), sizeZ());
  }

  public boolean contains(PreviewPosition position) {
    return position.x() >= min.x()
        && position.x() <= max.x()
        && position.y() >= min.y()
        && position.y() <= max.y()
        && position.z() >= min.z()
        && position.z() <= max.z();
  }

  public PreviewBounds union(PreviewBounds other) {
    Objects.requireNonNull(other, "other");
    return new PreviewBounds(
        new PreviewPosition(
            Math.min(min.x(), other.min().x()),
            Math.min(min.y(), other.min().y()),
            Math.min(min.z(), other.min().z())),
        new PreviewPosition(
            Math.max(max.x(), other.max().x()),
            Math.max(max.y(), other.max().y()),
            Math.max(max.z(), other.max().z())));
  }
}
