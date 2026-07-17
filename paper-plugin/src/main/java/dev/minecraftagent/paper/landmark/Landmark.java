package dev.minecraftagent.paper.landmark;

import java.util.List;
import java.util.Objects;

public record Landmark(
    String id,
    String name,
    List<String> aliases,
    List<String> tags,
    String dimension,
    double x,
    double y,
    double z,
    String permission) {
  public Landmark {
    Objects.requireNonNull(id);
    Objects.requireNonNull(name);
    aliases = List.copyOf(aliases);
    tags = List.copyOf(tags);
    Objects.requireNonNull(dimension);
  }
}
