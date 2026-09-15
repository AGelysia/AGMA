package dev.minecraftagent.standalone.common.preview;

import java.util.Objects;
import java.util.TreeMap;

/**
 * Rewrites the orientation-carrying properties of one canonical block state so they stay consistent
 * with the mirror-then-rotation the preview engine applies to cell positions. The direction
 * convention matches {@link PreviewEngine#transform(PreviewPosition, PreviewPosition, int,
 * PreviewMirror)}: rotation 90 moves an east-facing feature to north (then west, then south),
 * LEFT_RIGHT negates dx (east/west swap), and FRONT_BACK negates dz (north/south swap).
 *
 * <p>Covered properties: horizontal {@code facing}, the 16-step numeric {@code rotation}, {@code
 * axis} (x/z swap on quarter turns), door {@code hinge} and stair {@code shape} (mirror chirality
 * swaps), and the {@code north}/{@code east}/{@code south}/{@code west} connection keys of
 * multipart blocks. Vertical properties ({@code half}, {@code type}, up/down facings) are
 * horizontal-transform invariants and pass through, as does every unknown property or value.
 */
public final class BlockStateTransform {
  private static final String[] COMPASS = {"north", "east", "south", "west"};

  private BlockStateTransform() {}

  /**
   * Applies the mirror-then-rotation to one canonical {@code id[key=value,...]} state, keeping the
   * canonical sorted-key form. Malformed states pass through unchanged; canonical validation
   * remains the caller's job.
   */
  public static String transform(String canonicalState, int rotation, PreviewMirror mirror) {
    Objects.requireNonNull(canonicalState, "canonicalState");
    Objects.requireNonNull(mirror, "mirror");
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
      throw new IllegalArgumentException("preview rotation must be one of 0, 90, 180, 270");
    }
    var bracket = canonicalState.indexOf('[');
    if ((rotation == 0 && mirror == PreviewMirror.NONE) || bracket < 0) {
      return canonicalState;
    }
    if (bracket == 0 || !canonicalState.endsWith("]")) {
      return canonicalState;
    }
    var properties = new TreeMap<String, String>();
    var body = canonicalState.substring(bracket + 1, canonicalState.length() - 1);
    for (var pair : body.split(",", -1)) {
      var separator = pair.indexOf('=');
      if (separator < 1
          || separator != pair.lastIndexOf('=')
          || properties.put(pair.substring(0, separator), pair.substring(separator + 1)) != null) {
        return canonicalState;
      }
    }
    var rewritten = new TreeMap<String, String>();
    for (var entry : properties.entrySet()) {
      var key = entry.getKey();
      var value = entry.getValue();
      switch (key) {
        case "facing" -> value = compass(value, rotation, mirror);
        case "rotation" -> value = rotation16(value, rotation, mirror);
        case "axis" -> value = axis(value, rotation);
        case "hinge" -> value = mirror == PreviewMirror.NONE ? value : hinge(value);
        case "shape" -> value = mirror == PreviewMirror.NONE ? value : stairShape(value);
        case "north", "east", "south", "west" -> key = compass(key, rotation, mirror);
        default -> {}
      }
      rewritten.put(key, value);
    }
    var result = new StringBuilder(canonicalState.substring(0, bracket)).append('[');
    for (var entry : rewritten.entrySet()) {
      if (result.charAt(result.length() - 1) != '[') {
        result.append(',');
      }
      result.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return result.append(']').toString();
  }

  /** Mirrors then rotates one horizontal compass direction; other values pass through. */
  private static String compass(String direction, int rotation, PreviewMirror mirror) {
    var index =
        switch (direction) {
          case "north" -> 0;
          case "east" -> 1;
          case "south" -> 2;
          case "west" -> 3;
          default -> -1;
        };
    if (index < 0) {
      return direction;
    }
    index =
        switch (mirror) {
          case NONE -> index;
          case LEFT_RIGHT -> (4 - index) & 3;
          case FRONT_BACK -> (6 - index) & 3;
        };
    index =
        switch (rotation) {
          case 0 -> index;
          case 90 -> (index + 3) & 3;
          case 180 -> (index + 2) & 3;
          case 270 -> (index + 1) & 3;
          default -> throw new IllegalArgumentException("preview rotation is invalid");
        };
    return COMPASS[index];
  }

  /**
   * Mirrors then rotates the 0-15 numeric rotation of skulls, banners, and signs. Value 0 faces
   * south and every step turns 22.5 degrees towards the west, so the transform is the compass one
   * above rescaled to sixteenths.
   */
  private static String rotation16(String value, int rotation, PreviewMirror mirror) {
    final int index;
    try {
      index = Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      return value;
    }
    if (index < 0 || index > 15) {
      return value;
    }
    var mirrored =
        switch (mirror) {
          case NONE -> index;
          case LEFT_RIGHT -> (16 - index) & 15;
          case FRONT_BACK -> (8 - index + 16) & 15;
        };
    var rotated =
        switch (rotation) {
          case 0 -> mirrored;
          case 90 -> (mirrored + 12) & 15;
          case 180 -> (mirrored + 8) & 15;
          case 270 -> (mirrored + 4) & 15;
          default -> throw new IllegalArgumentException("preview rotation is invalid");
        };
    return Integer.toString(rotated);
  }

  /** Quarter turns swap the x and z axes; mirrors and half turns keep every axis. */
  private static String axis(String value, int rotation) {
    var quarterTurn = rotation == 90 || rotation == 270;
    return switch (value) {
      case "x" -> quarterTurn ? "z" : "x";
      case "z" -> quarterTurn ? "x" : "z";
      default -> value;
    };
  }

  /** A mirror flips the door hinge side; rotations keep it relative to the rotated facing. */
  private static String hinge(String value) {
    return switch (value) {
      case "left" -> "right";
      case "right" -> "left";
      default -> value;
    };
  }

  /** A mirror swaps the stair corner chirality; rotations keep the shape relative to facing. */
  private static String stairShape(String value) {
    return switch (value) {
      case "inner_left" -> "inner_right";
      case "inner_right" -> "inner_left";
      case "outer_left" -> "outer_right";
      case "outer_right" -> "outer_left";
      default -> value;
    };
  }
}
