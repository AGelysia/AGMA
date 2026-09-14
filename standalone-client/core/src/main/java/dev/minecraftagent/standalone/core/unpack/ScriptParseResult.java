package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Objects;

/** The partial parse of one script file: extracted recipes, removals, and the unparsed count. */
record ScriptParseResult(
    List<UnpackedRecipe> recipes, List<ScriptRemoval> removals, int skippedUnparsed) {
  ScriptParseResult {
    recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
    removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
    if (skippedUnparsed < 0) {
      throw new IllegalArgumentException("skippedUnparsed must not be negative");
    }
  }
}
