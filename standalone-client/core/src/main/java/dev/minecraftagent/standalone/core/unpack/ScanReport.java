package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Objects;

/**
 * Counters describing one mod archive scan. All counts are best-effort: archives or entries that
 * could not be read are counted and skipped, never fatal.
 */
public record ScanReport(
    int jarsConsidered,
    int jarsScanned,
    int jarsSkippedOversize,
    int jarsSkippedNoMetadata,
    int jarsUnreadable,
    int entriesSkippedOversize,
    int entriesMalformed,
    int totalRecipes,
    int totalTags,
    int totalLangEntries,
    int totalPatchouliBooks,
    int totalAdvancements,
    boolean recipeLimitReached,
    List<ModSummary> mods) {
  public ScanReport {
    if (jarsConsidered < 0
        || jarsScanned < 0
        || jarsSkippedOversize < 0
        || jarsSkippedNoMetadata < 0
        || jarsUnreadable < 0
        || entriesSkippedOversize < 0
        || entriesMalformed < 0
        || totalRecipes < 0
        || totalTags < 0
        || totalLangEntries < 0
        || totalPatchouliBooks < 0
        || totalAdvancements < 0) {
      throw new IllegalArgumentException("scan report counters must not be negative");
    }
    mods = List.copyOf(Objects.requireNonNull(mods, "mods"));
  }

  public static ScanReport empty() {
    return new ScanReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of());
  }

  public record ModSummary(String modId, String jarName, int recipes, int tags, int langEntries) {
    public ModSummary {
      Objects.requireNonNull(modId, "modId");
      Objects.requireNonNull(jarName, "jarName");
      if (recipes < 0 || tags < 0 || langEntries < 0) {
        throw new IllegalArgumentException("mod summary counters must not be negative");
      }
    }
  }
}
