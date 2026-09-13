package dev.minecraftagent.standalone.common.preview;

import java.util.Locale;

/** Cell selection inside one shape bounds; mirrors the server-line pattern semantics. */
public enum PreviewPattern {
  SOLID("solid"),
  HOLLOW("hollow"),
  WALLS("walls"),
  FLOOR("floor"),
  CLEAR("clear");

  private final String wireName;

  PreviewPattern(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static PreviewPattern fromWireName(String wireName) {
    for (var pattern : values()) {
      if (pattern.wireName.equals(wireName)) {
        return pattern;
      }
    }
    throw new IllegalArgumentException("preview pattern is invalid");
  }

  public boolean contains(PreviewBounds bounds, PreviewPosition position) {
    return switch (this) {
      case SOLID -> true;
      case HOLLOW ->
          position.x() == bounds.min().x()
              || position.x() == bounds.max().x()
              || position.y() == bounds.min().y()
              || position.y() == bounds.max().y()
              || position.z() == bounds.min().z()
              || position.z() == bounds.max().z();
      case WALLS ->
          position.x() == bounds.min().x()
              || position.x() == bounds.max().x()
              || position.z() == bounds.min().z()
              || position.z() == bounds.max().z();
      case FLOOR -> position.y() == bounds.min().y();
      case CLEAR -> true;
    };
  }

  @Override
  public String toString() {
    return name().toLowerCase(Locale.ROOT);
  }
}
