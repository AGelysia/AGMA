package dev.minecraftagent.standalone.core.unpack;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits script source text into statements without interpreting it: string literals (single and
 * double quoted, with backslash escapes), line comments, and block comments are honored so brackets
 * or terminators inside them never break a statement. Used only on already size-bounded files.
 */
final class ScriptStatements {
  private ScriptStatements() {}

  /** One extracted statement and its 1-based source line. */
  record Statement(String text, int line) {}

  /**
   * Extracts balanced {@code event.<method>(...)} calls. Scanning starts at an {@code event.} token
   * not preceded by an identifier character and ends at the parenthesis that closes the first one,
   * tracking nesting across {@code ()}, {@code []}, and {@code {}}. Statements beyond {@code
   * maximumStatements} or longer than {@code maximumLength} are abandoned.
   */
  static List<Statement> eventCalls(String source, int maximumStatements, int maximumLength) {
    var statements = new ArrayList<Statement>();
    var state = new ScanState();
    var index = 0;
    while (index < source.length() && statements.size() < maximumStatements) {
      var character = source.charAt(index);
      if (state.inStringOrComment()) {
        index += state.consume(source, index);
        continue;
      }
      if (state.enterStringOrComment(source, index)) {
        index++;
        continue;
      }
      if (character == '\n') {
        state.line++;
      }
      if (isTokenStart(source, index, "event.")) {
        var end = callEnd(source, index);
        if (end > index) {
          var text = source.substring(index, end);
          if (text.length() <= maximumLength) {
            statements.add(new Statement(text, state.line));
          }
          for (var consumed = index; consumed < end; consumed++) {
            if (source.charAt(consumed) == '\n') {
              state.line++;
            }
          }
          index = end;
          continue;
        }
      }
      index++;
    }
    return statements;
  }

  /**
   * Splits source into semicolon-terminated statements; a semicolon only terminates at nesting
   * depth zero outside strings and comments. Blank statements are dropped.
   */
  static List<Statement> semicolonStatements(
      String source, int maximumStatements, int maximumLength) {
    var statements = new ArrayList<Statement>();
    var state = new ScanState();
    var start = 0;
    var startLine = -1;
    var depth = 0;
    var index = 0;
    while (index < source.length() && statements.size() < maximumStatements) {
      var character = source.charAt(index);
      if (state.inStringOrComment()) {
        index += state.consume(source, index);
        continue;
      }
      if (state.enterStringOrComment(source, index)) {
        index++;
        continue;
      }
      if (startLine < 0 && !Character.isWhitespace(character)) {
        // The statement line is the line of its first non-whitespace character.
        startLine = state.line;
      }
      if (character == '\n') {
        state.line++;
      } else if (character == '(' || character == '[' || character == '{') {
        depth++;
      } else if (character == ')' || character == ']' || character == '}') {
        depth = Math.max(0, depth - 1);
      } else if (character == ';' && depth == 0) {
        var text = source.substring(start, index).strip();
        if (!text.isEmpty() && text.length() <= maximumLength) {
          statements.add(new Statement(text, Math.max(1, startLine)));
        }
        start = index + 1;
        startLine = -1;
      }
      index++;
    }
    var tail = source.substring(start).strip();
    if (!tail.isEmpty()
        && tail.length() <= maximumLength
        && statements.size() < maximumStatements) {
      statements.add(new Statement(tail, Math.max(1, startLine)));
    }
    return statements;
  }

  /** Splits an argument list on top-level commas, keeping nested brackets and strings whole. */
  static List<String> splitTopLevel(String arguments) {
    var parts = new ArrayList<String>();
    var state = new ScanState();
    var depth = 0;
    var start = 0;
    var index = 0;
    while (index < arguments.length()) {
      var character = arguments.charAt(index);
      if (state.inStringOrComment()) {
        index += state.consume(arguments, index);
        continue;
      }
      if (state.enterStringOrComment(arguments, index)) {
        index++;
        continue;
      }
      if (character == '(' || character == '[' || character == '{') {
        depth++;
      } else if (character == ')' || character == ']' || character == '}') {
        depth = Math.max(0, depth - 1);
      } else if (character == ',' && depth == 0) {
        parts.add(arguments.substring(start, index).strip());
        start = index + 1;
      }
      index++;
    }
    var tail = arguments.substring(start).strip();
    if (!tail.isEmpty()) {
      parts.add(tail);
    }
    return parts;
  }

  /** True when {@code token} starts at {@code index} and is not part of a longer identifier. */
  private static boolean isTokenStart(String source, int index, String token) {
    if (!source.startsWith(token, index)) {
      return false;
    }
    if (index > 0) {
      var previous = source.charAt(index - 1);
      if (Character.isJavaIdentifierPart(previous) || previous == '.') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the index just past the closing parenthesis of the call starting at {@code start}, or
   * {@code -1} when the call is unbalanced or the method chain never opens a parenthesis.
   */
  private static int callEnd(String source, int start) {
    var state = new ScanState();
    var depth = 0;
    var opened = false;
    var index = start;
    while (index < source.length()) {
      var character = source.charAt(index);
      if (state.inStringOrComment()) {
        index += state.consume(source, index);
        continue;
      }
      if (state.enterStringOrComment(source, index)) {
        index++;
        continue;
      }
      if (character == '(') {
        depth++;
        opened = true;
      } else if (character == ')') {
        depth--;
        if (opened && depth == 0) {
          return index + 1;
        }
      } else if (character == '[' || character == '{') {
        depth++;
      } else if (character == ']' || character == '}') {
        depth--;
        if (depth < 0) {
          return -1;
        }
      } else if (!opened
          && !(Character.isJavaIdentifierPart(character)
              || character == '.'
              || Character.isWhitespace(character))) {
        return -1;
      }
      index++;
    }
    return -1;
  }

  /** Tracks string literal and comment state for a single left-to-right scan. */
  private static final class ScanState {
    private static final int NORMAL = 0;
    private static final int SINGLE_QUOTE = 1;
    private static final int DOUBLE_QUOTE = 2;
    private static final int LINE_COMMENT = 3;
    private static final int BLOCK_COMMENT = 4;

    private int state = NORMAL;
    private int line = 1;

    private boolean inStringOrComment() {
      return state != NORMAL;
    }

    /** Enters a string literal or comment when one starts at {@code index}. */
    private boolean enterStringOrComment(String source, int index) {
      if (state != NORMAL) {
        return false;
      }
      var character = source.charAt(index);
      if (character == '\'') {
        state = SINGLE_QUOTE;
      } else if (character == '"') {
        state = DOUBLE_QUOTE;
      } else if (character == '/' && index + 1 < source.length()) {
        var next = source.charAt(index + 1);
        if (next == '/') {
          state = LINE_COMMENT;
        } else if (next == '*') {
          state = BLOCK_COMMENT;
        } else {
          return false;
        }
      } else {
        return false;
      }
      return true;
    }

    /**
     * Consumes one character (or an escaped pair) inside a string literal or comment and returns
     * the number of characters to advance. Must only be called in a non-normal state.
     */
    private int consume(String source, int index) {
      var character = source.charAt(index);
      if (character == '\n') {
        line++;
      }
      switch (state) {
        case SINGLE_QUOTE, DOUBLE_QUOTE -> {
          if (character == '\\' && index + 1 < source.length()) {
            return 2;
          }
          if ((state == SINGLE_QUOTE && character == '\'')
              || (state == DOUBLE_QUOTE && character == '"')) {
            state = NORMAL;
          }
          return 1;
        }
        case LINE_COMMENT -> {
          if (character == '\n') {
            state = NORMAL;
          }
          return 1;
        }
        case BLOCK_COMMENT -> {
          if (character == '*' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
            state = NORMAL;
            return 2;
          }
          return 1;
        }
        default -> {
          return 1;
        }
      }
    }
  }
}
