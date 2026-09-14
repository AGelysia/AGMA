package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class KubeJsScriptParserTest {
  private final KubeJsScriptParser parser = new KubeJsScriptParser();

  @Test
  void parsesMultilineShapedWithTagKey() {
    var result =
        parser.parse(
            "onEvent('recipes', event => {\n"
                + "  event.shaped('2x testmod:gear', [\n"
                + "    'ABA',\n"
                + "    'B B'\n"
                + "  ], {\n"
                + "    A: 'minecraft:iron_ingot',\n"
                + "    B: '#forge:ingots/steel'\n"
                + "  })\n"
                + "})",
            "recipes.js",
            0);
    assertEquals(1, result.recipes().size());
    var recipe = result.recipes().get(0);
    assertEquals("kubejs:shaped", recipe.type());
    assertEquals("kubejs:script/recipes.js_f0_l2", recipe.id());
    assertEquals(List.of("ABA", "B B"), recipe.json().get("pattern"));
    @SuppressWarnings("unchecked")
    var key = (Map<String, Object>) recipe.json().get("key");
    assertEquals(Map.of("item", "minecraft:iron_ingot"), key.get("A"));
    assertEquals(Map.of("tag", "forge:ingots/steel"), key.get("B"));
    @SuppressWarnings("unchecked")
    var out = (Map<String, Object>) recipe.json().get("result");
    assertEquals("testmod:gear", out.get("item"));
    assertEquals(2L, out.get("count"));
    assertEquals(0, result.skippedUnparsed());
  }

  @Test
  void parsesShapelessAndCookingShortcuts() {
    var result =
        parser.parse(
            "event.shapeless('testmod:mix', ['minecraft:dirt', '2x minecraft:sand'])\n"
                + "event.smelting('testmod:glass', 'minecraft:sand')\n"
                + "event.blasting('testmod:ingot', '#forge:dusts/iron')\n"
                + "event.smoking('testmod:jerky', 'minecraft:beef')\n"
                + "event.campfire_cooking('testmod:toast', 'minecraft:bread')\n"
                + "event.stonecutting('testmod:bricks', 'minecraft:stone')",
            "a.js",
            3);
    assertEquals(6, result.recipes().size());
    var shapeless = result.recipes().get(0);
    assertEquals("kubejs:shapeless", shapeless.type());
    assertEquals("kubejs:script/a.js_f3_l1", shapeless.id());
    @SuppressWarnings("unchecked")
    var ingredients = (List<Map<String, Object>>) shapeless.json().get("ingredients");
    assertEquals(Map.of("item", "minecraft:dirt"), ingredients.get(0));
    assertEquals(Map.of("item", "minecraft:sand", "count", 2L), ingredients.get(1));
    assertEquals("kubejs:smelting", result.recipes().get(1).type());
    assertEquals(
        Map.of("item", "minecraft:sand"), result.recipes().get(1).json().get("ingredient"));
    assertEquals(
        Map.of("tag", "forge:dusts/iron"), result.recipes().get(2).json().get("ingredient"));
    assertEquals("kubejs:smoking", result.recipes().get(3).type());
    assertEquals("kubejs:campfire_cooking", result.recipes().get(4).type());
    assertEquals("kubejs:stonecutting", result.recipes().get(5).type());
  }

  @Test
  void parsesTypedCallsWithArraysChancesAndRawArguments() {
    var result =
        parser.parse(
            "event.recipes.create.crushing(['testmod:dust', 'testmod:dust % 75'], 'testmod:ore')\n"
                + "event.recipes.create.mixing('testmod:dough', ['minecraft:wheat', 'minecraft:water_bucket'], 200, 'heated')",
            "b.js", 1);
    assertEquals(2, result.recipes().size());
    var crushing = result.recipes().get(0);
    assertEquals("create:crushing", crushing.type());
    @SuppressWarnings("unchecked")
    var results = (List<Map<String, Object>>) crushing.json().get("results");
    assertEquals(2, results.size());
    assertEquals("testmod:dust", results.get(0).get("item"));
    assertEquals(0.75, (Double) results.get(1).get("chance"), 0.0001);
    @SuppressWarnings("unchecked")
    var crushingInputs = (List<Map<String, Object>>) crushing.json().get("ingredients");
    assertEquals(List.of(Map.of("item", "testmod:ore")), crushingInputs);

    var mixing = result.recipes().get(1);
    assertEquals("create:mixing", mixing.type());
    @SuppressWarnings("unchecked")
    var raw = (List<String>) mixing.json().get(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY);
    assertEquals(List.of("200", "'heated'"), raw);
  }

  @Test
  void parsesItemOfAndCountAndChanceForms() {
    assertEquals(
        Map.of("item", "testmod:gem", "count", 3L),
        KubeJsScriptParser.itemForm("Item.of('testmod:gem', 3)").ingredientJson());
    assertEquals(
        Map.of("item", "testmod:gem", "count", 1L).get("item"),
        KubeJsScriptParser.itemForm("Item.of('testmod:gem')").ingredientJson().get("item"));
    assertEquals(5L, KubeJsScriptParser.itemForm("'5x testmod:gem'").count());
    assertEquals("forge:gems", KubeJsScriptParser.itemForm("'#forge:gems'").id());
    var chance = KubeJsScriptParser.itemForm("'testmod:gem % 50'");
    assertEquals(0.5, chance.chance(), 0.0001);
    assertNull(KubeJsScriptParser.itemForm("200"));
    assertNull(KubeJsScriptParser.itemForm("'Not An Item'"));
  }

  @Test
  void parsesRemovals() {
    var result =
        parser.parse(
            "event.remove({id: 'create:crushing/aluminum_ore'})\n"
                + "event.remove({id: 'create:crushing/*'})\n"
                + "event.remove({output: 'testmod:ingot'})\n"
                + "event.remove({input: 'minecraft:dirt'})\n"
                + "event.removeAll()",
            "c.js",
            0);
    assertEquals(5, result.removals().size());
    assertEquals(ScriptRemoval.Kind.ID, result.removals().get(0).kind());
    assertEquals("create:crushing/aluminum_ore", result.removals().get(0).pattern());
    assertEquals("create:crushing/*", result.removals().get(1).pattern());
    assertEquals(ScriptRemoval.Kind.OUTPUT, result.removals().get(2).kind());
    assertEquals("testmod:ingot", result.removals().get(2).pattern());
    assertEquals(ScriptRemoval.Kind.INPUT, result.removals().get(3).kind());
    assertEquals(ScriptRemoval.Kind.ALL, result.removals().get(4).kind());
    assertEquals(0, result.skippedUnparsed());
  }

  @Test
  void countsUnparseableAndIgnoresUnknownCalls() {
    var result =
        parser.parse(
            "event.shaped()\n"
                + "event.shaped('testmod:onlyoutput')\n"
                + "event.custom({\"type\":\"testmod:other\"})\n"
                + "event.recipes.create.mixing()\n",
            "d.js",
            0);
    assertEquals(0, result.recipes().size());
    assertEquals(3, result.skippedUnparsed());
  }

  @Test
  void ignoresCommentsAndStringContents() {
    var result =
        parser.parse(
            "// event.shaped('testmod:fake', ['A'], {A: 'minecraft:dirt'})\n"
                + "/* event.shaped('testmod:fake2', ['A'], {A: 'minecraft:dirt'}) */\n"
                + "console.info('event.shaped inside a string')\n"
                + "event.shapeless('testmod:real', ['minecraft:dirt'])",
            "e.js",
            0);
    assertEquals(1, result.recipes().size());
    assertEquals(
        "testmod:real", ((Map<?, ?>) result.recipes().get(0).json().get("result")).get("item"));
  }
}
