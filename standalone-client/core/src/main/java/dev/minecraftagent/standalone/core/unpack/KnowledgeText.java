package dev.minecraftagent.standalone.core.unpack;

import java.util.regex.Pattern;

/**
 * Text hygiene for extracted knowledge documents. {@link #stripMarkup(String)} removes the
 * guide-book inline formatting codes ({@code $(...)}, including link {@code $(l:...)} and keybind
 * {@code $(k:...)} forms) and keeps only the human text between them; {@code $(br)} and {@code
 * $(br2)} become line breaks first so paragraph structure survives. {@link #sanitize(String)} drops
 * control characters and bidirectional or formatting codepoints (U+202A-U+202E, U+2066-U+2069,
 * U+200E, U+200F, U+061C) and unpaired surrogates, mirroring the safety rules the runtime applies
 * to knowledge text it indexes.
 */
public final class KnowledgeText {
  private static final Pattern LINE_BREAK = Pattern.compile("\\$\\(br2?\\)");
  private static final Pattern MARKUP_CODE = Pattern.compile("\\$\\([^)]*\\)");

  private KnowledgeText() {}

  /** Strips guide-book markup codes, keeping plain text. {@code null} becomes an empty string. */
  public static String stripMarkup(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    var withBreaks = LINE_BREAK.matcher(value).replaceAll("\n");
    return MARKUP_CODE.matcher(withBreaks).replaceAll("");
  }

  /**
   * Removes characters that must never reach a rendered document: control characters other than the
   * newline (tabs become spaces), the C1 range, bidi and formatting codepoints, and unpaired
   * surrogates. {@code null} becomes an empty string.
   */
  public static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    var result = new StringBuilder(value.length());
    var index = 0;
    while (index < value.length()) {
      var character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
          var codePoint = Character.toCodePoint(character, value.charAt(index + 1));
          if (isAllowed(codePoint)) {
            result.appendCodePoint(codePoint);
          }
          index += 2;
        } else {
          index++;
        }
        continue;
      }
      if (Character.isLowSurrogate(character)) {
        index++;
        continue;
      }
      if (character == '\t') {
        result.append(' ');
      } else if (isAllowed(character)) {
        result.append(character);
      }
      index++;
    }
    return result.toString();
  }

  /** Sanitizes, normalizes trailing whitespace, and bounds the result to {@code maximum} chars. */
  public static String sanitized(String value, int maximum) {
    var cleaned = sanitize(value).strip();
    return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
  }

  private static boolean isAllowed(int codePoint) {
    if (codePoint == '\n') {
      return true;
    }
    if (codePoint < 0x20 || (codePoint >= 0x7f && codePoint <= 0x9f)) {
      return false;
    }
    return codePoint != 0x061c
        && codePoint != 0x200e
        && codePoint != 0x200f
        && !(codePoint >= 0x202a && codePoint <= 0x202e)
        && !(codePoint >= 0x2066 && codePoint <= 0x2069);
  }
}
