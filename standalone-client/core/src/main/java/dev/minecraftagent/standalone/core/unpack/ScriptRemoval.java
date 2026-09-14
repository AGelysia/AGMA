package dev.minecraftagent.standalone.core.unpack;

import java.util.Objects;

/**
 * One recipe removal declared by an instance script. Removals suppress archive-derived gap-fill
 * candidates during mapping: {@link Kind#ID} matches the recipe id (exact, or as a prefix when the
 * pattern ends with {@code *}), {@link Kind#OUTPUT} matches the primary output resource id the same
 * way, and {@link Kind#ALL} suppresses every archive-derived candidate. {@link Kind#INPUT} removals
 * are recorded for the report but suppress nothing, because input filtering cannot be decided
 * safely from static data. Live base processes are never suppressed.
 */
public record ScriptRemoval(ScriptRemoval.Kind kind, String pattern) {
  public enum Kind {
    ID,
    OUTPUT,
    INPUT,
    ALL
  }

  public ScriptRemoval {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(pattern, "pattern");
    if (pattern.isBlank() || pattern.length() > 256) {
      throw new IllegalArgumentException("removal pattern must be bounded non-blank text");
    }
  }

  public static ScriptRemoval all() {
    return new ScriptRemoval(Kind.ALL, "*");
  }

  /** Exact match, or prefix match when the pattern ends with {@code *}. */
  boolean matches(String id) {
    if (kind == Kind.ALL) {
      return true;
    }
    if (pattern.endsWith("*")) {
      return id.startsWith(pattern.substring(0, pattern.length() - 1));
    }
    return pattern.equals(id);
  }
}
