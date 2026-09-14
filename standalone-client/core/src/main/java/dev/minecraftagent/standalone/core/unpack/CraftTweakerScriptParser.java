package dev.minecraftagent.standalone.core.unpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pattern-based, explicitly best-effort parser for CraftTweaker {@code .zs} scripts: {@code
 * craftingTable.addShaped} / {@code addShapeless}, the cooking managers ({@code furnace}, {@code
 * blastFurnace}, {@code smoker}, {@code campfire}) with their {@code addRecipe} form, generic
 * recipe-type calls ({@code <recipetype:ns:type>.addRecipe(...)}), and removals ({@code .remove(
 * <item:...>)}, {@code .removeByName("...")}, {@code .removeAll()}). Recognized recipes become
 * synthetic {@link UnpackedRecipe} documents typed {@code crafttweaker:<kind>} (or the recipe
 * type's own {@code ns:type}); anything not understood is counted and skipped, never thrown.
 */
final class CraftTweakerScriptParser {
  private static final int MAXIMUM_STATEMENTS = 1024;
  private static final int MAXIMUM_STATEMENT_LENGTH = 16 * 1024;
  private static final int MAXIMUM_ARGUMENTS = 64;
  private static final int MAXIMUM_RAW_ARGUMENT_LENGTH = 120;

  private static final Pattern ADD_SHAPED =
      Pattern.compile("^craftingTable\\.addShaped\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern ADD_SHAPELESS =
      Pattern.compile("^craftingTable\\.addShapeless\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern COOKING =
      Pattern.compile(
          "^(furnace|blastFurnace|smoker|campfire)\\.addRecipe\\s*\\((?<args>.*)\\)$",
          Pattern.DOTALL);
  private static final Pattern RECIPE_TYPE =
      Pattern.compile(
          "^<recipetype:([a-z0-9_.-]+:[a-z0-9_./-]+)>\\s*\\.\\s*addRecipe\\s*\\((?<args>.*)\\)$",
          Pattern.DOTALL);
  private static final Pattern REMOVE_ITEM =
      Pattern.compile("^[a-zA-Z]+\\.remove\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern REMOVE_BY_NAME =
      Pattern.compile("^[a-zA-Z]+\\.removeByName\\s*\\((?<args>.*)\\)$", Pattern.DOTALL);
  private static final Pattern REMOVE_ALL =
      Pattern.compile("^[a-zA-Z]+\\.removeAll\\s*\\(\\s*\\)$");
  private static final Pattern ITEM =
      Pattern.compile("^<item:([a-z0-9_.-]+:[a-z0-9_./-]+)>(?:\\s*\\*\\s*([0-9]+))?$");
  private static final Pattern TAG =
      Pattern.compile("^<tag:(?:items/)?([a-z0-9_.-]+:[a-z0-9_./-]+)>$");
  private static final Pattern NUMBER = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");
  private static final Pattern QUOTED = Pattern.compile("^\"([^\"]*)\"$");

  private static final Map<String, String> COOKING_KINDS =
      Map.of(
          "furnace", "smelting",
          "blastFurnace", "blasting",
          "smoker", "smoking",
          "campfire", "campfire_cooking");

  ScriptParseResult parse(String source, String fileLabel, int fileIndex) {
    var recipes = new ArrayList<UnpackedRecipe>();
    var removals = new ArrayList<ScriptRemoval>();
    var skippedUnparsed = 0;
    for (var statement :
        ScriptStatements.semicolonStatements(
            source, MAXIMUM_STATEMENTS, MAXIMUM_STATEMENT_LENGTH)) {
      var text = statement.text();
      var shaped = ADD_SHAPED.matcher(text);
      var shapeless = ADD_SHAPELESS.matcher(text);
      var cooking = COOKING.matcher(text);
      var recipeType = RECIPE_TYPE.matcher(text);
      var removeItem = REMOVE_ITEM.matcher(text);
      var removeByName = REMOVE_BY_NAME.matcher(text);
      if (shaped.matches()) {
        var recipe =
            craftingRecipe("shaped", shaped.group("args"), fileLabel, fileIndex, statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe);
        }
      } else if (shapeless.matches()) {
        var recipe =
            craftingRecipe(
                "shapeless", shapeless.group("args"), fileLabel, fileIndex, statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe);
        }
      } else if (cooking.matches()) {
        var recipe =
            cookingRecipe(
                COOKING_KINDS.get(cooking.group(1)),
                cooking.group("args"),
                fileLabel,
                fileIndex,
                statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe);
        }
      } else if (recipeType.matches()) {
        var recipe =
            typedRecipe(
                recipeType.group(1),
                recipeType.group("args"),
                fileLabel,
                fileIndex,
                statement.line());
        if (recipe == null) {
          skippedUnparsed++;
        } else {
          recipes.add(recipe);
        }
      } else if (removeItem.matches()) {
        var form = itemForm(removeItem.group("args").strip());
        if (form == null || form.tag()) {
          skippedUnparsed++;
        } else {
          removals.add(new ScriptRemoval(ScriptRemoval.Kind.OUTPUT, form.id()));
        }
      } else if (removeByName.matches()) {
        var name = QUOTED.matcher(removeByName.group("args").strip());
        if (!name.matches() || name.group(1).isBlank()) {
          skippedUnparsed++;
        } else {
          removals.add(new ScriptRemoval(ScriptRemoval.Kind.ID, name.group(1).strip()));
        }
      } else if (REMOVE_ALL.matcher(text).matches()) {
        removals.add(ScriptRemoval.all());
      }
      // Statements that match none of the known forms are other script logic; ignored.
    }
    return new ScriptParseResult(recipes, removals, skippedUnparsed);
  }

  /**
   * {@code addShaped("name", <item:out> * n, [[<item:a>, ...], ...])} and {@code
   * addShapeless("name", <item:out>, [<item:a>, ...])}: the shaped grid is flattened into the
   * ingredient list because the catalog tracks inputs, not layout.
   */
  private UnpackedRecipe craftingRecipe(
      String kind, String argsText, String fileLabel, int fileIndex, int line) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.size() < 3 || args.size() > MAXIMUM_ARGUMENTS) {
      return null;
    }
    var name = recipeName(args.get(0));
    var output = itemForm(args.get(1));
    if (name == null || output == null || output.tag()) {
      return null;
    }
    var ingredients = new ArrayList<Map<String, Object>>();
    if (kind.equals("shaped")) {
      if (!isArray(args.get(2))) {
        return null;
      }
      for (var row : arrayElements(args.get(2))) {
        if (!isArray(row)) {
          return null;
        }
        for (var cell : arrayElements(row)) {
          var form = itemForm(cell);
          if (form == null) {
            return null;
          }
          if (!isAir(form)) {
            ingredients.add(form.ingredientJson());
          }
        }
      }
    } else {
      if (!isArray(args.get(2))) {
        return null;
      }
      for (var element : arrayElements(args.get(2))) {
        var form = itemForm(element);
        if (form == null) {
          return null;
        }
        if (!isAir(form)) {
          ingredients.add(form.ingredientJson());
        }
      }
    }
    if (ingredients.isEmpty()) {
      return null;
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("type", "crafttweaker:" + kind);
    json.put("ingredients", ingredients);
    json.put("result", output.outputJson());
    return new UnpackedRecipe(recipeId(name, fileIndex, line), "crafttweaker:" + kind, json);
  }

  /**
   * {@code <manager>.addRecipe("name", <item:out>, <item:in>, xp, time)}: experience is kept as a
   * condition and the cooking time becomes the process duration.
   */
  private UnpackedRecipe cookingRecipe(
      String kind, String argsText, String fileLabel, int fileIndex, int line) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.size() < 3 || args.size() > MAXIMUM_ARGUMENTS) {
      return null;
    }
    var name = recipeName(args.get(0));
    var output = itemForm(args.get(1));
    var input = itemForm(args.get(2));
    if (name == null || output == null || output.tag() || input == null) {
      return null;
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("type", "crafttweaker:" + kind);
    json.put("ingredient", input.ingredientJson());
    json.put("result", output.outputJson());
    var conditions = new ArrayList<String>();
    if (args.size() > 3 && NUMBER.matcher(args.get(3)).matches()) {
      conditions.add(bounded("xp=" + args.get(3)));
    }
    if (args.size() > 4 && NUMBER.matcher(args.get(4)).matches()) {
      try {
        json.put("time", Math.max(0, Long.parseLong(args.get(4))));
      } catch (NumberFormatException ignored) {
        // A fractional or overflowing time is simply not a duration.
      }
    }
    if (!conditions.isEmpty()) {
      json.put(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY, List.copyOf(conditions));
    }
    return new UnpackedRecipe(recipeId(name, fileIndex, line), "crafttweaker:" + kind, json);
  }

  /** Generic {@code <recipetype:ns:type>.addRecipe("name", <item:out>, [<item:in>, ...], ...)}. */
  private UnpackedRecipe typedRecipe(
      String type, String argsText, String fileLabel, int fileIndex, int line) {
    var args = ScriptStatements.splitTopLevel(argsText);
    if (args.size() < 3 || args.size() > MAXIMUM_ARGUMENTS) {
      return null;
    }
    var name = recipeName(args.get(0));
    var output = itemForm(args.get(1));
    if (name == null || output == null || output.tag()) {
      return null;
    }
    var inputs = new ArrayList<Map<String, Object>>();
    if (isArray(args.get(2))) {
      for (var element : arrayElements(args.get(2))) {
        var form = itemForm(element);
        if (form == null) {
          return null;
        }
        if (!isAir(form)) {
          inputs.add(form.ingredientJson());
        }
      }
    } else {
      var form = itemForm(args.get(2));
      if (form == null) {
        return null;
      }
      if (!isAir(form)) {
        inputs.add(form.ingredientJson());
      }
    }
    var rawArguments = new ArrayList<String>();
    for (var index = 3; index < args.size(); index++) {
      rawArguments.add(bounded(args.get(index)));
    }
    var json = new LinkedHashMap<String, Object>();
    json.put("type", type);
    if (!inputs.isEmpty()) {
      json.put("ingredients", inputs);
    }
    json.put("result", output.outputJson());
    if (!rawArguments.isEmpty()) {
      json.put(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY, List.copyOf(rawArguments));
    }
    return new UnpackedRecipe(recipeId(name, fileIndex, line), type, json);
  }

  /**
   * Parses one bracket item form: {@code <item:ns:id>}, {@code <item:ns:id> * n}, {@code
   * <tag:items/ns:tag>}, and {@code <tag:ns:tag>}. Returns {@code null} for anything else.
   */
  static ScriptItemForm itemForm(String token) {
    var item = ITEM.matcher(token.strip());
    if (item.matches()) {
      var count = item.group(2) == null ? 1 : parseCount(item.group(2));
      try {
        return ScriptItemForm.item(item.group(1), count);
      } catch (IllegalArgumentException failure) {
        return null;
      }
    }
    var tag = TAG.matcher(token.strip());
    if (tag.matches()) {
      try {
        return ScriptItemForm.tag(tag.group(1));
      } catch (IllegalArgumentException failure) {
        return null;
      }
    }
    return null;
  }

  private static long parseCount(String text) {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException failure) {
      return 1;
    }
  }

  /** The quoted recipe name; {@code null} when the first argument is not a plain string. */
  private static String recipeName(String token) {
    var quoted = QUOTED.matcher(token.strip());
    return quoted.matches() ? quoted.group(1) : null;
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

  private static String bounded(String value) {
    return value.length() <= MAXIMUM_RAW_ARGUMENT_LENGTH
        ? value
        : value.substring(0, MAXIMUM_RAW_ARGUMENT_LENGTH);
  }

  private static String recipeId(String name, int fileIndex, int line) {
    var lowered = name.toLowerCase(java.util.Locale.ROOT);
    var cleaned = new StringBuilder(lowered.length());
    for (var index = 0; index < lowered.length(); index++) {
      var character = lowered.charAt(index);
      cleaned.append(
          character >= 'a' && character <= 'z'
                  || character >= '0' && character <= '9'
                  || character == '_'
                  || character == '.'
                  || character == '-'
                  || character == '/'
              ? character
              : '_');
    }
    var sanitized = cleaned.length() == 0 ? "recipe" : cleaned.toString();
    sanitized = sanitized.length() <= 64 ? sanitized : sanitized.substring(0, 64);
    return "crafttweaker:script/" + sanitized + "_f" + fileIndex + "_l" + Math.max(1, line);
  }
}
