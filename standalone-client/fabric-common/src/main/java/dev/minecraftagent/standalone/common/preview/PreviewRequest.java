package dev.minecraftagent.standalone.common.preview;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed, validated build.preview.create arguments ready for the pure preview engine. */
public record PreviewRequest(
    UUID projectId,
    int revision,
    PreviewOperation operation,
    String dimension,
    PreviewPosition origin,
    int rotation,
    PreviewMirror mirror,
    List<PreviewShape> shapes) {
  public PreviewRequest {
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(mirror, "mirror");
    if (revision < 1 || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
      throw new IllegalArgumentException("preview request identity is invalid");
    }
    shapes = List.copyOf(shapes);
    if (shapes.isEmpty() || shapes.size() > PreviewEngine.MAXIMUM_SHAPES) {
      throw new PreviewLimitException("preview shapes are out of range");
    }
  }
}
