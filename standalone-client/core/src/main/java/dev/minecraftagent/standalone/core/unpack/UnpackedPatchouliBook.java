package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One in-game guide book read from a mod archive ({@code
 * data/<namespace>/patchouli_books/<book>/}). {@code definition} is the root {@code book.json}
 * (empty when the archive omitted it) and {@code files} holds the localized category and entry
 * documents for the locales the scanner collects. All JSON values are parsed roots as produced by
 * {@link BoundedJson}.
 */
public record UnpackedPatchouliBook(
    String id, Map<String, Object> definition, List<BookFile> files) {
  public UnpackedPatchouliBook {
    Objects.requireNonNull(id, "id");
    definition =
        java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(Objects.requireNonNull(definition, "definition")));
    files = List.copyOf(Objects.requireNonNull(files, "files"));
  }

  /** One localized category or entry document of a book. */
  public record BookFile(String locale, boolean category, String path, Map<String, Object> json) {
    public BookFile {
      Objects.requireNonNull(locale, "locale");
      Objects.requireNonNull(path, "path");
      json =
          java.util.Collections.unmodifiableMap(
              new java.util.LinkedHashMap<>(Objects.requireNonNull(json, "json")));
    }
  }
}
