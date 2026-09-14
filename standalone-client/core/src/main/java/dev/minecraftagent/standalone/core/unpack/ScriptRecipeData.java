package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Objects;

/**
 * The best-effort result of parsing the instance script directories: synthetic recipe documents in
 * the same shape the archive scanner produces (so they flow through the same mapping and tag
 * expansion pipeline), the declared removals, and accounting counters. Lines a parser cannot
 * understand are counted as {@code skippedUnparsed} and never thrown.
 */
public record ScriptRecipeData(
    List<UnpackedRecipe> recipes, List<ScriptRemoval> removals, ScriptRecipeData.Stats stats) {
  public ScriptRecipeData {
    recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
    removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
    Objects.requireNonNull(stats, "stats");
  }

  public static ScriptRecipeData empty() {
    return new ScriptRecipeData(List.of(), List.of(), new Stats(0, 0, 0L, 0, 0, 0));
  }

  public record Stats(
      int filesScanned,
      int filesSkipped,
      long bytesScanned,
      int recipesExtracted,
      int removalsExtracted,
      int skippedUnparsed) {
    public Stats {
      if (filesScanned < 0
          || filesSkipped < 0
          || bytesScanned < 0
          || recipesExtracted < 0
          || removalsExtracted < 0
          || skippedUnparsed < 0) {
        throw new IllegalArgumentException("script extraction counters must not be negative");
      }
    }
  }
}
