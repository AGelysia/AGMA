package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One mod archive that declared mod metadata. {@code lang} maps a locale code (for example {@code
 * en_us}) to its translation map; only the locales the scanner collects are present. {@code
 * patchouliBooks} and {@code advancements} hold the scanned guide book and advancement documents
 * and are empty for archives without that content.
 */
public record UnpackedMod(
    String modId,
    String modName,
    String modVersion,
    String jarName,
    List<UnpackedRecipe> recipes,
    List<UnpackedTag> tags,
    Map<String, Map<String, String>> lang,
    List<UnpackedPatchouliBook> patchouliBooks,
    List<UnpackedAdvancement> advancements) {
  public UnpackedMod {
    Objects.requireNonNull(modId, "modId");
    Objects.requireNonNull(modName, "modName");
    Objects.requireNonNull(modVersion, "modVersion");
    Objects.requireNonNull(jarName, "jarName");
    recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    Objects.requireNonNull(lang, "lang");
    var copy = new java.util.LinkedHashMap<String, Map<String, String>>();
    lang.forEach(
        (locale, translations) ->
            copy.put(
                Objects.requireNonNull(locale, "locale"),
                java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(
                        Objects.requireNonNull(translations, "translations")))));
    lang = java.util.Collections.unmodifiableMap(copy);
    patchouliBooks = List.copyOf(Objects.requireNonNull(patchouliBooks, "patchouliBooks"));
    advancements = List.copyOf(Objects.requireNonNull(advancements, "advancements"));
  }

  /** Backwards-compatible shape without guide book or advancement content. */
  public UnpackedMod(
      String modId,
      String modName,
      String modVersion,
      String jarName,
      List<UnpackedRecipe> recipes,
      List<UnpackedTag> tags,
      Map<String, Map<String, String>> lang) {
    this(modId, modName, modVersion, jarName, recipes, tags, lang, List.of(), List.of());
  }
}
