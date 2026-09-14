package dev.minecraftagent.standalone.core.unpack;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One extracted markdown knowledge document ready to be written next to the client runtime state.
 * {@code fileName} is a sanitized, lowercased, bounded {@code agma-modbook-*.md} or {@code
 * agma-modadv-*.md} name; {@code title} is the human document title; {@code markdown} is the full
 * document body, already sanitized and bounded by the producing extractor.
 */
public record KnowledgeDocument(String fileName, String title, String markdown) {
  /** Bounds the generated file name, extension included. */
  public static final int MAXIMUM_FILE_NAME_LENGTH = 128;

  /** Bounds the document body so a hostile archive cannot produce unbounded files. */
  public static final int MAXIMUM_MARKDOWN_CHARACTERS = 256 * 1024;

  private static final Pattern FILE_NAME =
      Pattern.compile("^agma-(?:modbook|modadv)-[a-z0-9-]{1,118}\\.md$");
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[a-z0-9]+");

  public KnowledgeDocument {
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(markdown, "markdown");
    if (!FILE_NAME.matcher(fileName).matches() || fileName.length() > MAXIMUM_FILE_NAME_LENGTH) {
      throw new IllegalArgumentException("knowledge document file name is invalid");
    }
    if (title.isBlank() || title.length() > 256) {
      throw new IllegalArgumentException("knowledge document title must be bounded non-blank text");
    }
    if (markdown.length() > MAXIMUM_MARKDOWN_CHARACTERS) {
      throw new IllegalArgumentException("knowledge document markdown exceeds the size bound");
    }
  }

  /**
   * Builds a managed file name of the form {@code agma-<prefix>-<segments>.md}. Every segment is
   * lowercased, reduced to {@code [a-z0-9-]} runs (anything else collapses into a single dash), and
   * bounded; the whole name stays within {@link #MAXIMUM_FILE_NAME_LENGTH}.
   */
  public static String fileName(String prefix, String... segments) {
    if (!prefix.equals("modbook") && !prefix.equals("modadv")) {
      throw new IllegalArgumentException("unknown knowledge document prefix");
    }
    var head = "agma-" + prefix + "-";
    var middle = new StringBuilder();
    for (var segment : segments) {
      if (middle.length() > 0) {
        middle.append('-');
      }
      middle.append(sanitizeSegment(segment));
    }
    var trimmed = middle.toString();
    var maximumMiddle = MAXIMUM_FILE_NAME_LENGTH - head.length() - ".md".length();
    if (trimmed.length() > maximumMiddle) {
      trimmed = trimmed.substring(0, maximumMiddle);
    }
    while (trimmed.endsWith("-")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return head + (trimmed.isEmpty() ? "x" : trimmed) + ".md";
  }

  private static String sanitizeSegment(String segment) {
    var lowered = segment == null ? "" : segment.toLowerCase(Locale.ROOT);
    var cleaned = new StringBuilder(lowered.length());
    var dash = false;
    for (var index = 0; index < lowered.length(); index++) {
      var character = lowered.charAt(index);
      if (SAFE_SEGMENT.matcher(String.valueOf(character)).matches()) {
        cleaned.append(character);
        dash = false;
      } else if (!dash && cleaned.length() > 0) {
        cleaned.append('-');
        dash = true;
      }
    }
    while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '-') {
      cleaned.setLength(cleaned.length() - 1);
    }
    if (cleaned.length() == 0) {
      return "x";
    }
    var bounded = cleaned.length() <= 48 ? cleaned.toString() : cleaned.substring(0, 48);
    while (bounded.endsWith("-")) {
      bounded = bounded.substring(0, bounded.length() - 1);
    }
    return bounded.isEmpty() ? "x" : bounded;
  }
}
