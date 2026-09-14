package dev.minecraftagent.standalone.core.unpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pattern-based, explicitly best-effort parser for server-side pack scripts that register recipes
 * through an {@code event} object: {@code event.shaped} / {@code event.shapeless}, the cooking and
 * cutting shortcuts ({@code smelting}, {@code blasting}, {@code smoking}, {@code campfire_cooking},
 * {@code stonecutting}), generic typed calls ({@code event.recipes.<ns>.<type>( ...)}), and
 * removals ({@code event.remove}, {@code event.removeAll}). Recognized recipes become synthetic
 * {@link UnpackedRecipe} documents typed {@code kubejs:<kind>} (or the typed call's own {@code
 * <ns>:<type>}); anything not understood is counted and skipped, never thrown.
 */
final class KubeJsScriptParser {
  private static final int MAXIMUM_STATEMENTS = 1024;
  private static final int MAXIMUM_STATEMENT_LENGTH = 16 * 1024;
  private static final int MAXIMUM_ROWS = 9;
  private static final int MAXIMUM_ARGUMENTS = 64;
  private static final int MAXIMUM_RAW_ARGUMENT_LENGTH = 120;

  private static final Pattern SIMPLE_CALL =
      Pattern.compile(
          "^event\\.(shaped|shapeless|smelting|blasting|smoking|campfire_cooking|stonecutting)\\s*\\((?<args>.*)\\)$",
          Pattern.DOTALL);
  private static final Pattern TYPED_CALL =
      Pattern.compile(
          "^event\\.recipes\\.([a-z0-9_.-]+)\\.([a-z0-9_./-]+)\\s*\\((?<args>.*)\\)$",
          Pattern.DOTALL);
  private static final Pattern REMOVE =
      Pattern.compile("^event\\.remove\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern REMOVE_ALL =
      Pattern.compile("^event\\.removeAll\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern ITEM_OF =
      Pattern.compile("^Item\\.of\\(\\s*'([^']+)'\\s*(?:,\\s*([0-9]+))?\\s*\\)$");
  private static final Pattern COUNT_PREFIX = Pattern.compile("^([0-9]+)x\\s+(.+)$");
  private static final Pattern CHANCE_SUFFIX =
      Pattern.compile("^(.+?)\\s*%\\s*([0-9]+(?:\\.[0-9]+)?)$");
  private static final Pattern ROW_STRING = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'");
  private static final Pattern KEY_ENTRY =
      Pattern.compile("^([A-Za-z])\\s*:(?<value>.+)$", Pattern.DOTALL);
  private static final Pattern OBJECT_ENTRY =
      Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*:(?<value>.+)$", Pattern.DOTALL);

  ScriptParseResult parse(String source, String fileLabel, int fileIndex) {
    var recipes = new ArrayList<UnpackedRecipe>();
    var removals = new ArrayList<ScriptRemoval>();
    var skippedUnparsed = 0;
    for (var statement :
        ScriptStatements.eventCalls(source, MAXIMUM_STATEMENTS, MAXIMUM_STATEMENT_LENGTH)) {
      var text = statement.text();
      var simple = SIMPLE_CALL.matcher(text);
      var typed = TYPED_CALL.matcher(text);
      var remove = REMOVE.matcher(text);
      var removeAll = REMOVE_ALL.matcher(text);
      if (simple.matches()) {
        var recipe =
            simpleRecipe(
                simple.group(1), simple.group("args"), fileLabel, fileIndex, statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe);
        }
      } else if (typed.matches()) {
        var recipe =
            typedRecipe(
                typed.group(1) + ":" + typed.group(2),
                typed.group("args"),
                fileLabel,
                fileIndex,
                statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe.recipe());
          skippedUnparsed += recipe.unparsedTokens() > 0 ? 1 : 0;
        }
      } else if (remove.matches()) {
        var parsed = removal(remove.group("args"));
        if (parsed.isEmpty()) {
          skippedUnparsed++;
        } else {
          removals.addAll(parsed);
        }
      } else if (removeAll.matches()) {
        removals.add(ScriptRemoval.all());
      }
      // Statements that match none of the known forms are other event registrations; ignored.
    }
    return new ScriptParseResult(recipes, removals, skippedUnparsed);
  }

  private UnpackedRecipe simpleRecipe(
      String kind, String argsText, String fileLabel, int fileIndex, int line) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.size() < 2 || args.size() > MAXIMUM_ARGUMENTS) {
      return null;
    }
    var output = itemForm(args.get(0));
    if (output == null || output.tag()) {
      return null;
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("type", "kubejs:" + kind);
    json.put("result", output.outputJson());
    switch (kind) {
      case "shaped" -> {
        if (args.size() < 3) {
          return null;
        }
        var rows = patternRows(args.get(1));
        var key = patternKey(args.get(2));
        if (rows == null || key == null || key.isEmpty()) {
          return null;
        }
        json.put("pattern", rows);
        json.put("key", key);
      }
      case "shapeless" -> {
        var ingredients = ingredientList(args.get(1));
        if (ingredients == null || ingredients.isEmpty()) {
          return null;
        }
        json.put("ingredients", ingredients);
      }
      default -> {
        // The cooking and cutting shortcuts take exactly one input.
        var input = itemForm(args.get(1));
        if (input == null) {
          return null;
        }
        json.put("ingredient", input.ingredientJson());
      }
    }
    return new UnpackedRecipe(
        recipeId("kubejs", fileLabel, fileIndex, line), "kubejs:" + kind, json);
  }

  private record TypedRecipe(UnpackedRecipe recipe, int unparsedTokens) {}

  /**
   * Generic typed call: the first argument (or the elements of a first array argument) are the
   * outputs, the remaining item-like arguments are inputs, and everything else is kept as a raw
   * condition so the recipe viewer can show the unmapped context.
   */
  private TypedRecipe typedRecipe(
      String type, String argsText, String fileLabel, int fileIndex, int line) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.isEmpty() || args.size() > MAXIMUM_ARGUMENTS) {
      return null;
    }
    var unparsed = 0;
    var outputs = new ArrayList<ScriptItemForm>();
    var first = args.get(0);
    if (isArray(first)) {
      for (var token : arrayElements(first)) {
        var form = itemForm(token);
        if (form == null || form.tag()) {
          unparsed++;
        } else {
          outputs.add(form);
        }
      }
    } else {
      var form = itemForm(first);
      if (form == null || form.tag()) {
        return null;
      }
      outputs.add(form);
    }
    if (outputs.isEmpty()) {
      return null;
    }
    var inputs = new ArrayList<Map<String, Object>>();
    var rawArguments = new ArrayList<String>();
    for (var index = 1; index < args.size(); index++) {
      var argument = args.get(index);
      if (isArray(argument)) {
        for (var token : arrayElements(argument)) {
          var form = itemForm(token);
          if (form == null) {
            rawArguments.add(bounded(token));
            unparsed++;
          } else if (!isAir(form)) {
            inputs.add(form.ingredientJson());
          }
        }
        continue;
      }
      var form = itemForm(argument);
      if (form == null) {
        rawArguments.add(bounded(argument));
      } else if (!isAir(form)) {
        inputs.add(form.ingredientJson());
      }
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("type", type);
    if (outputs.size() == 1) {
      json.put("result", outputs.get(0).outputJson());
    } else {
      var results = new ArrayList<Map<String, Object>>();
      outputs.forEach(form -> results.add(form.outputJson()));
      json.put("results", results);
    }
    if (!inputs.isEmpty()) {
      json.put("ingredients", inputs);
    }
    if (!rawArguments.isEmpty()) {
      json.put(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY, List.copyOf(rawArguments));
    }
    return new TypedRecipe(
        new UnpackedRecipe(recipeId("kubejs", fileLabel, fileIndex, line), type, json), unparsed);
  }

  /** One {@code event.remove({...})} filter object; only single-key filters are understood. */
  private List<ScriptRemoval> removal(String argsText) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.size() != 1 || !args.get(0).startsWith("{") || !args.get(0).endsWith("}")) {
      return List.of();
    }
    var inner = args.get(0).substring(1, args.get(0).length() - 1);
    var entries = ScriptStatements.splitTopLevel(inner);
    if (entries.size() != 1) {
      return List.of();
    }
    var entry = OBJECT_ENTRY.matcher(entries.get(0));
    if (!entry.matches()) {
      return List.of();
    }
    var value = unquote(entry.group("value").strip());
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return switch (entry.group(1)) {
      case "id" -> List.of(new ScriptRemoval(ScriptRemoval.Kind.ID, bounded(value)));
      case "output" -> List.of(new ScriptRemoval(ScriptRemoval.Kind.OUTPUT, bounded(value)));
      case "input" -> List.of(new ScriptRemoval(ScriptRemoval.Kind.INPUT, bounded(value)));
      default -> List.of();
    };
  }

  /**
   * Parses one item token: {@code 'ns:item'}, {@code '2x ns:item'}, {@code Item.of('ns:item', 2)},
   * {@code '#ns:tag'}, and the output chance suffix {@code 'ns:item % 75'}. Returns {@code null}
   * for anything else.
   */
  static ScriptItemForm itemForm(String token) {
    var text = token.strip();
    var itemOf = ITEM_OF.matcher(text);
    if (itemOf.matches()) {
      var count = itemOf.group(2) == null ? 1 : parseCount(itemOf.group(2));
      return safeItem(itemOf.group(1), count);
    }
    var unquoted = unquote(text);
    if (unquoted == null) {
      return null;
    }
    text = unquoted.strip();
    if (text.startsWith("#")) {
      return safeTag(text.substring(1));
    }
    Double chance = null;
    var chanceSuffix = CHANCE_SUFFIX.matcher(text);
    if (chanceSuffix.matches()) {
      text = chanceSuffix.group(1).strip();
      try {
        chance = Double.parseDouble(chanceSuffix.group(2)) / 100.0;
      } catch (NumberFormatException failure) {
        return null;
      }
    }
    var count = 1L;
    var countPrefix = COUNT_PREFIX.matcher(text);
    if (countPrefix.matches()) {
      count = parseCount(countPrefix.group(1));
      text = countPrefix.group(2).strip();
    }
    var form = safeItem(text, count);
    return form != null && chance != null ? form.withChance(chance) : form;
  }

  private static ScriptItemForm safeItem(String id, long count) {
    try {
      return ScriptItemForm.item(id, count);
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }

  private static ScriptItemForm safeTag(String id) {
    try {
      return ScriptItemForm.tag(id);
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }

  private static long parseCount(String text) {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException failure) {
      return 1;
    }
  }

  /** The pattern rows of a shaped call: an array literal of row strings, bounded to nine rows. */
  private static List<String> patternRows(String token) {
    if (!isArray(token)) {
      return null;
    }
    var rows = new ArrayList<String>();
    var matcher = ROW_STRING.matcher(token);
    while (matcher.find() && rows.size() < MAXIMUM_ROWS) {
      rows.add(matcher.group(1));
    }
    return rows.isEmpty() ? null : rows;
  }

  /** The key object of a shaped call: single-character symbols mapped to item forms. */
  private static Map<String, Object> patternKey(String token) {
    var text = token.strip();
    if (!text.startsWith("{") || !text.endsWith("}")) {
      return null;
    }
    var key = new LinkedHashMap<String, Object>();
    for (var entry : ScriptStatements.splitTopLevel(text.substring(1, text.length() - 1))) {
      var parsed = KEY_ENTRY.matcher(entry);
      if (!parsed.matches()) {
        continue;
      }
      var value = parsed.group("value").strip();
      if (isArray(value)) {
        var alternatives = new ArrayList<Map<String, Object>>();
        for (var element : arrayElements(value)) {
          var form = itemForm(element);
          if (form != null && !isAir(form)) {
            alternatives.add(form.ingredientJson());
          }
        }
        if (!alternatives.isEmpty()) {
          key.put(parsed.group(1), alternatives);
        }
      } else {
        var form = itemForm(value);
        if (form != null && !isAir(form)) {
          key.put(parsed.group(1), form.ingredientJson());
        }
      }
    }
    return key;
  }

  /** A shapeless ingredient array; every element must parse or the whole list fails. */
  private static List<Map<String, Object>> ingredientList(String token) {
    if (!isArray(token)) {
      return null;
    }
    var ingredients = new ArrayList<Map<String, Object>>();
    for (var element : arrayElements(token)) {
      var form = itemForm(element);
      if (form == null) {
        return null;
      }
      if (!isAir(form)) {
        ingredients.add(form.ingredientJson());
      }
    }
    return ingredients;
  }

  private static boolean isArray(String token) {
    var text = token.strip();
    return text.startsWith("[") && text.endsWith("]");
  }

  private static List<String> arrayElements(String array) {
    var text = array.strip();
    return ScriptStatements.splitTopLevel(text.substring(1, text.length() - 1));
  }

  private static boolean isAir(ScriptItemForm form) {
    return !form.tag() && form.id().equals("minecraft:air");
  }

  /** Removes one level of matching single or double quotes; {@code null} when not quoted. */
  private static String unquote(String token) {
    if (token.length() >= 2
        && ((token.startsWith("'") && token.endsWith("'"))
            || (token.startsWith("\"") && token.endsWith("\"")))) {
      return token.substring(1, token.length() - 1);
    }
    return null;
  }

  private static String bounded(String value) {
    return value.length() <= MAXIMUM_RAW_ARGUMENT_LENGTH
        ? value
        : value.substring(0, MAXIMUM_RAW_ARGUMENT_LENGTH);
  }

  static String recipeId(String namespace, String fileLabel, int fileIndex, int line) {
    return namespace
        + ":script/"
        + sanitizeLabel(fileLabel)
        + "_f"
        + fileIndex
        + "_l"
        + Math.max(1, line);
  }

  private static String sanitizeLabel(String label) {
    var lowered = label.toLowerCase(java.util.Locale.ROOT);
    var cleaned = new StringBuilder(lowered.length());
    for (var index = 0; index < lowered.length(); index++) {
      var character = lowered.charAt(index);
      cleaned.append(
          character >= 'a' && character <= 'z'
                  || character >= '0' && character <= '9'
                  || character == '_'
                  || character == '.'
                  || character == '-'
              ? character
              : '_');
    }
    var result = cleaned.length() == 0 ? "script" : cleaned.toString();
    return result.length() <= 64 ? result : result.substring(0, 64);
  }
}
