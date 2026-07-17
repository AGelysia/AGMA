package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A registry-validated, transform-baked Palette-v1 build preview. */
public record BuildPreviewView(
    String schemaVersion,
    UUID previewId,
    UUID projectId,
    int revision,
    Operation operation,
    String dimension,
    Bounds bounds,
    Position origin,
    Transform transform,
    String baseRegionHash,
    String changeSetHash,
    String contentHash,
    String paletteHash,
    Difference difference,
    List<PaletteEntry> palette,
    List<PlacedBlock> blocks)
    implements ViewContent {

  public BuildPreviewView {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(previewId, "previewId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(bounds, "bounds");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(transform, "transform");
    Objects.requireNonNull(baseRegionHash, "baseRegionHash");
    Objects.requireNonNull(changeSetHash, "changeSetHash");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(paletteHash, "paletteHash");
    Objects.requireNonNull(difference, "difference");
    palette = List.copyOf(palette);
    blocks = List.copyOf(blocks);
  }

  public enum Operation {
    CREATE,
    MODIFY
  }

  public enum Mirror {
    NONE,
    LEFT_RIGHT,
    FRONT_BACK
  }

  public record Position(int x, int y, int z) {}

  public record Bounds(Position min, Position max) {
    public Bounds {
      Objects.requireNonNull(min, "min");
      Objects.requireNonNull(max, "max");
    }

    public int sizeX() {
      return Math.addExact(Math.subtractExact(max.x(), min.x()), 1);
    }

    public int sizeY() {
      return Math.addExact(Math.subtractExact(max.y(), min.y()), 1);
    }

    public int sizeZ() {
      return Math.addExact(Math.subtractExact(max.z(), min.z()), 1);
    }

    public int volume() {
      return Math.multiplyExact(Math.multiplyExact(sizeX(), sizeY()), sizeZ());
    }

    public boolean contains(Position position) {
      return position.x() >= min.x()
          && position.x() <= max.x()
          && position.y() >= min.y()
          && position.y() <= max.y()
          && position.z() >= min.z()
          && position.z() <= max.z();
    }
  }

  public record Transform(int rotation, Mirror mirror) {
    public Transform {
      Objects.requireNonNull(mirror, "mirror");
    }
  }

  public record Difference(int added, int replaced, int removed) {}

  public record PaletteEntry(int id, String blockId, Map<String, String> properties) {
    public PaletteEntry {
      Objects.requireNonNull(blockId, "blockId");
      properties = Map.copyOf(properties);
    }

    public String canonicalState() {
      if (properties.isEmpty()) {
        return blockId;
      }
      var state = new StringBuilder(blockId).append('[');
      properties.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                if (state.charAt(state.length() - 1) != '[') {
                  state.append(',');
                }
                state.append(entry.getKey()).append('=').append(entry.getValue());
              });
      return state.append(']').toString();
    }
  }

  public record PlacedBlock(int state, Position position) {
    public PlacedBlock {
      Objects.requireNonNull(position, "position");
    }
  }
}
