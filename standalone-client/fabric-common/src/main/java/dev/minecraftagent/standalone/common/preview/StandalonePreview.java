package dev.minecraftagent.standalone.common.preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable client build preview artifact: every wire result field plus the transformed non-air
 * target cells, their sorted distinct-state palette for in-world presentation, and the structural
 * analysis feedback computed from the final region state.
 */
public record StandalonePreview(
    UUID previewId,
    UUID projectId,
    int revision,
    PreviewOperation operation,
    String dimension,
    PreviewBounds bounds,
    PreviewPosition origin,
    int rotation,
    PreviewMirror mirror,
    String baseRegionHash,
    String changeSetHash,
    int targetBlockCount,
    int changeCount,
    PreviewDifference difference,
    List<PreviewCell> cells,
    List<String> palette,
    PreviewAnalysis analysis) {
  public StandalonePreview {
    Objects.requireNonNull(previewId, "previewId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(bounds, "bounds");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(mirror, "mirror");
    Objects.requireNonNull(baseRegionHash, "baseRegionHash");
    Objects.requireNonNull(changeSetHash, "changeSetHash");
    Objects.requireNonNull(difference, "difference");
    Objects.requireNonNull(analysis, "analysis");
    if (revision < 1
        || targetBlockCount < 0
        || targetBlockCount > PreviewEngine.MAXIMUM_UNION_VOLUME
        || changeCount < 0
        || changeCount > PreviewEngine.MAXIMUM_UNION_VOLUME
        || difference.total() != changeCount) {
      throw new IllegalArgumentException("preview artifact counts are invalid");
    }
    cells = List.copyOf(cells);
    palette = List.copyOf(palette);
  }

  /**
   * Compatibility constructor for fixtures that assemble a preview artifact without a region
   * snapshot; the analysis degrades to the zeroed {@link PreviewAnalysis#empty()}.
   */
  public StandalonePreview(
      UUID previewId,
      UUID projectId,
      int revision,
      PreviewOperation operation,
      String dimension,
      PreviewBounds bounds,
      PreviewPosition origin,
      int rotation,
      PreviewMirror mirror,
      String baseRegionHash,
      String changeSetHash,
      int targetBlockCount,
      int changeCount,
      PreviewDifference difference,
      List<PreviewCell> cells,
      List<String> palette) {
    this(
        previewId,
        projectId,
        revision,
        operation,
        dimension,
        bounds,
        origin,
        rotation,
        mirror,
        baseRegionHash,
        changeSetHash,
        targetBlockCount,
        changeCount,
        difference,
        cells,
        palette,
        PreviewAnalysis.empty());
  }

  /** Produces exactly the build.preview.create result map defined by the tool contract. */
  public Map<String, Object> toResultMap() {
    var result = new LinkedHashMap<String, Object>();
    result.put("previewId", previewId.toString());
    result.put("projectId", projectId.toString());
    result.put("revision", revision);
    result.put("dimension", dimension);
    result.put("bounds", boundsMap());
    result.put("baseRegionHash", baseRegionHash);
    result.put("changeSetHash", changeSetHash);
    result.put("targetBlockCount", targetBlockCount);
    result.put("changeCount", changeCount);
    var differenceMap = new LinkedHashMap<String, Object>();
    differenceMap.put("added", difference.added());
    differenceMap.put("replaced", difference.replaced());
    differenceMap.put("removed", difference.removed());
    result.put("difference", Collections.unmodifiableMap(differenceMap));
    result.put("previewStatus", "client_validated");
    result.put("worldWriteEnabled", false);
    var analysisMap = new LinkedHashMap<String, Object>();
    analysisMap.put("floatingCells", analysis.floatingCells());
    analysisMap.put("interiorAirCells", analysis.interiorAirCells());
    analysisMap.put("topView", analysis.topView());
    analysisMap.put("topViewLegend", analysis.topViewLegend());
    result.put("analysis", Collections.unmodifiableMap(analysisMap));
    return Collections.unmodifiableMap(result);
  }

  private Map<String, Object> boundsMap() {
    var boundsMap = new LinkedHashMap<String, Object>();
    boundsMap.put("min", positionMap(bounds.min()));
    boundsMap.put("max", positionMap(bounds.max()));
    return Collections.unmodifiableMap(boundsMap);
  }

  private static Map<String, Object> positionMap(PreviewPosition position) {
    var result = new LinkedHashMap<String, Object>();
    result.put("x", position.x());
    result.put("y", position.y());
    result.put("z", position.z());
    return Collections.unmodifiableMap(result);
  }
}
