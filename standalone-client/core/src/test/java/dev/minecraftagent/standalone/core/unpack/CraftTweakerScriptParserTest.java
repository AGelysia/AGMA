package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CraftTweakerScriptParserTest {
  private final CraftTweakerScriptParser parser = new CraftTweakerScriptParser();

  @Test
  void parsesAddShapedWithCountAndTagForms() {
    var result =
        parser.parse(
            "craftingTable.addShaped(\"gear\", <item:testmod:gear> * 2, [\n"
                + "  [<item:minecraft:iron_ingot>, <tag:items/forge:ingots/steel>],\n"
                + "  [<item:minecraft:air>, <tag:forge:gems>]\n"
                + "]);\n",
            "recipes.zs",
            0);
    assertEquals(1, result.recipes().size());
    var recipe = result.recipes().get(0);
    assertEquals("crafttweaker:shaped", recipe.type());
    assertEquals("crafttweaker:script/gear_f0_l1", recipe.id());
    @SuppressWarnings("unchecked")
    var out = (Map<String, Object>) recipe.json().get("result");
    assertEquals("testmod:gear", out.get("item"));
    assertEquals(2L, out.get("count"));
    @SuppressWarnings("unchecked")
    var ingredients = (List<Map<String, Object>>) recipe.json().get("ingredients");
    // Air cells drop out; both tag forms resolve.
    assertEquals(3, ingredients.size());
    assertEquals(Map.of("item", "minecraft:iron_ingot"), ingredients.get(0));
    assertEquals(Map.of("tag", "forge:ingots/steel"), ingredients.get(1));
    assertEquals(Map.of("tag", "forge:gems"), ingredients.get(2));
    assertEquals(0, result.skippedUnparsed());
  }

  @Test
  void parsesAddShapelessAndCookingManagers() {
    var result =
        parser.parse(
            "craftingTable.addShapeless(\"mix\", <item:testmod:mix>, [<item:minecraft:dirt>, <item:minecraft:sand>]);\n"
                + "furnace.addRecipe(\"glass\", <item:minecraft:glass>, <item:minecraft:sand>, 0.35, 200);\n"
                + "blastFurnace.addRecipe(\"steel\", <item:testmod:steel>, <item:minecraft:iron_ingot>, 0.7, 400);\n"
                + "smoker.addRecipe(\"jerky\", <item:minecraft:cooked_beef>, <item:minecraft:beef>, 0.35, 100);\n"
                + "campfire.addRecipe(\"toast\", <item:testmod:toast>, <item:minecraft:bread>, 0.35, 600);\n",
            "b.zs",
            2);
    assertEquals(5, result.recipes().size());
    assertEquals("crafttweaker:shapeless", result.recipes().get(0).type());
    var furnace = result.recipes().get(1);
    assertEquals("crafttweaker:smelting", furnace.type());
    assertEquals(200L, furnace.json().get("time"));
    @SuppressWarnings("unchecked")
    var conditions = (List<String>) furnace.json().get(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY);
    assertEquals(List.of("xp=0.35"), conditions);
    assertEquals(Map.of("item", "minecraft:sand"), furnace.json().get("ingredient"));
    assertEquals("crafttweaker:blasting", result.recipes().get(2).type());
    assertEquals("crafttweaker:smoking", result.recipes().get(3).type());
    assertEquals("crafttweaker:campfire_cooking", result.recipes().get(4).type());
    assertEquals("crafttweaker:script/glass_f2_l2", furnace.id());
  }

  @Test
  void parsesGenericRecipeTypeCalls() {
    var result =
        parser.parse(
            "<recipetype:create:pressing>.addRecipe(\"plate\", <item:testmod:plate>, [<item:testmod:ingot>]);\n"
                + "<recipetype:testmod:infuser>.addRecipe(\"infused\", <item:testmod:infused>, [<item:testmod:gem>], 20, 5);\n",
            "c.zs",
            1);
    assertEquals(2, result.recipes().size());
    assertEquals("create:pressing", result.recipes().get(0).type());
    var infuser = result.recipes().get(1);
    assertEquals("testmod:infuser", infuser.type());
    @SuppressWarnings("unchecked")
    var raw = (List<String>) infuser.json().get(UnpackProcessMapper.SCRIPT_ARGUMENTS_KEY);
    assertEquals(List.of("20", "5"), raw);
  }

  @Test
  void parsesRemovals() {
    var result =
        parser.parse(
            "craftingTable.remove(<item:testmod:gear>);\n"
                + "furnace.removeByName(\"minecraft:iron_ore\");\n"
                + "craftingTable.removeAll();\n",
            "d.zs",
            0);
    assertEquals(3, result.removals().size());
    assertEquals(ScriptRemoval.Kind.OUTPUT, result.removals().get(0).kind());
    assertEquals("testmod:gear", result.removals().get(0).pattern());
    assertEquals(ScriptRemoval.Kind.ID, result.removals().get(1).kind());
    assertEquals("minecraft:iron_ore", result.removals().get(1).pattern());
    assertEquals(ScriptRemoval.Kind.ALL, result.removals().get(2).kind());
  }

  @Test
  void parsesItemForms() {
    assertEquals("testmod:gem", CraftTweakerScriptParser.itemForm("<item:testmod:gem>").id());
    assertEquals(4L, CraftTweakerScriptParser.itemForm("<item:testmod:gem> * 4").count());
    assertEquals("forge:gems", CraftTweakerScriptParser.itemForm("<tag:items/forge:gems>").id());
    assertEquals("forge:gems", CraftTweakerScriptParser.itemForm("<tag:forge:gems>").id());
    assertNull(CraftTweakerScriptParser.itemForm("<fluid:minecraft:water>"));
    assertNull(CraftTweakerScriptParser.itemForm("42"));
  }

  @Test
  void countsUnparseableAndIgnoresOtherLogic() {
    var result =
        parser.parse(
            "craftingTable.addShaped(\"broken\");\n"
                + "var stone = <item:minecraft:stone>;\n"
                + "print(\"hello\");\n"
                + "craftingTable.remove(\"not an item bracket\");\n",
            "e.zs",
            0);
    assertEquals(0, result.recipes().size());
    assertEquals(0, result.removals().size());
    assertEquals(2, result.skippedUnparsed());
  }
}
