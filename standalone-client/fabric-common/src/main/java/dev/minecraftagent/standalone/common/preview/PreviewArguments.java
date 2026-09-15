package dev.minecraftagent.standalone.common.preview;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Defensive parser from the raw tool argument map to a typed {@link PreviewRequest}. The wire
 * schema already validated the payload; this re-checks types, ranges, and the per-shape limits so
 * the engine only ever sees bounded input.
 */
public final class PreviewArguments {
  private PreviewArguments() {}

  public static PreviewRequest parse(Map<String, Object> arguments) {
    if (arguments == null) {
      throw invalid();
    }
    var projectId = uuid(arguments.get("projectId"));
    var revision = integer(arguments.get("revision"));
    var operation = PreviewOperation.fromWireName(string(arguments.get("operation")));
    var dimension = string(arguments.get("dimension"));
    var origin = position(arguments.get("origin"));
    var rotation = integer(arguments.get("rotation"));
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
      throw new IllegalArgumentException("preview rotation must be one of 0, 90, 180, 270");
    }
    final PreviewMirror mirror;
    try {
      mirror = PreviewMirror.valueOf(string(arguments.get("mirror")));
    } catch (RuntimeException exception) {
      throw invalid();
    }
    if (!(arguments.get("shapes") instanceof List<?> shapeValues)
        || shapeValues.isEmpty()
        || shapeValues.size() > PreviewEngine.MAXIMUM_SHAPES) {
      throw invalid();
    }
    var shapes = new java.util.ArrayList<PreviewShape>(shapeValues.size());
    for (var shapeValue : shapeValues) {
      shapes.add(shape(shapeValue));
    }
    return new PreviewRequest(
        projectId, revision, operation, dimension, origin, rotation, mirror, shapes);
  }

  private static PreviewShape shape(Object value) {
    var shape = object(value);
    var boundsValue = object(shape.get("bounds"));
    var min = position(boundsValue.get("min"));
    var max = position(boundsValue.get("max"));
    final PreviewBounds bounds;
    try {
      bounds = new PreviewBounds(min, max);
    } catch (RuntimeException exception) {
      throw invalid();
    }
    var sizeX = (long) bounds.sizeX();
    var sizeY = (long) bounds.sizeY();
    var sizeZ = (long) bounds.sizeZ();
    if (sizeX > PreviewEngine.MAXIMUM_SHAPE_AXIS
        || sizeY > PreviewEngine.MAXIMUM_SHAPE_AXIS
        || sizeZ > PreviewEngine.MAXIMUM_SHAPE_AXIS) {
      throw new PreviewLimitException("preview shape bounds exceed the per-shape axis limit");
    }
    if (sizeX * sizeY * sizeZ > PreviewEngine.MAXIMUM_SHAPE_VOLUME) {
      throw new PreviewLimitException("preview shape bounds exceed the per-shape volume limit");
    }
    var pattern = PreviewPattern.fromWireName(string(shape.get("pattern")));
    var blockStateValue = shape.get("blockState");
    String blockState = null;
    if (blockStateValue != null) {
      blockState = string(blockStateValue);
    }
    try {
      return new PreviewShape(bounds, pattern, blockState);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static PreviewPosition position(Object value) {
    var position = object(value);
    return new PreviewPosition(
        integer(position.get("x")), integer(position.get("y")), integer(position.get("z")));
  }

  private static UUID uuid(Object value) {
    try {
      var text = string(value);
      var result = UUID.fromString(text);
      if (!result.toString().equals(text)) {
        throw invalid();
      }
      return result;
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static String string(Object value) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    return text;
  }

  private static int integer(Object value) {
    if (!(value instanceof Number number)) {
      throw invalid();
    }
    try {
      return (value instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString()))
          .intValueExact();
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      throw invalid();
    }
    var result = new java.util.LinkedHashMap<String, Object>();
    for (var entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid();
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("build preview arguments are invalid");
  }
}
