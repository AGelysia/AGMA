package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class UnpackProcessMapperTest {
  private static final Predicate<String> ALL_BLOCKS = id -> true;
  private static final Predicate<String> NO_BLOCKS = id -> false;
  private final UnpackProcessMapper mapper = new UnpackProcessMapper();

  @Test
  void createCrushing() {
    var process =
        mapped(
            mapper.map(
                fixture("create/recipes/crushing/aluminum_ore.json"),
                itemTags(Map.of("c:aluminum_ores", "create:aluminum_ore")),
                ALL_BLOCKS));
    assertEquals("create:crushing/aluminum_ore", process.processId());
    assertEquals("create:crushing", process.categoryId());
    assertEquals(1, process.inputs().size());
    assertEquals(
        List.of(
            new UnpackedProcess.Amount(
                UnpackedTag.Kind.ITEM, "create:aluminum_ore", BigDecimal.ONE)),
        process.inputs().get(0).alternatives());
    assertEquals(3, process.outputs().size());
    var primary = process.outputs().get(0);
    assertTrue(primary.primary());
    assertEquals(1.0, primary.chance());
    assertEquals("create:crushed_raw_aluminum", primary.id());
    assertFalse(process.outputs().get(1).primary());
    assertEquals(0.75, process.outputs().get(1).chance());
    assertEquals("create:experience_nugget", process.outputs().get(2).id());
    assertEquals(400L, process.durationTicks());
    assertTrue(
        process.conditions().stream()
            .anyMatch(condition -> condition.contains("fabric:tags_populated")),
        () -> "conditions: " + process.conditions());
    assertTrue(process.warnings().contains("conditional recipe: requirements may not be active"));
    assertEquals("create:crushing_wheel", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void createMechanicalCraftingPattern() {
    var process =
        mapped(
            mapper.map(
                fixture("create/recipes/mechanical_crafting/crushing_wheel.json"),
                itemTags(
                    Map.of(
                        "minecraft:planks", "minecraft:oak_planks",
                        "c:stone", "minecraft:stone")),
                ALL_BLOCKS));
    assertEquals(3, process.inputs().size());
    assertEquals("key_a", process.inputs().get(0).slot());
    assertEquals("create:andesite_alloy", process.inputs().get(0).alternatives().get(0).id());
    assertEquals("key_p", process.inputs().get(1).slot());
    assertEquals("minecraft:oak_planks", process.inputs().get(1).alternatives().get(0).id());
    assertEquals("key_s", process.inputs().get(2).slot());
    assertTrue(process.conditions().contains("shaped: pattern retained in recipe viewer"));
    assertTrue(process.conditions().contains("acceptMirrored=false"));
    assertEquals("create:crushing_wheel", process.outputs().get(0).id());
    assertEquals(new BigDecimal("2"), process.outputs().get(0).amount());
    assertEquals("create:mechanical_crafter", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void createFillingFluidDropletHeuristic() {
    var process =
        mapped(
            mapper.map(
                fixture("create/recipes/filling/blaze_cake.json"), itemTags(Map.of()), ALL_BLOCKS));
    assertEquals(2, process.inputs().size());
    var fluid = process.inputs().get(1).alternatives().get(0);
    assertEquals(UnpackedTag.Kind.FLUID, fluid.kind());
    assertEquals("minecraft:lava", fluid.id());
    // 27000 fabric droplets / 81 = 333.333 millibuckets.
    assertEquals(new BigDecimal("333.333"), fluid.amount());
    assertEquals("create:spout", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void botaniaRunicAltar() {
    var process =
        mapped(
            mapper.map(
                fixture("botania/recipes/runic_altar/air.json"),
                itemTags(
                    Map.of(
                        "botania:mana_dusts", "botania:mana_powder",
                        "botania:manasteel_ingots", "botania:manasteel_ingot",
                        "minecraft:wool_carpets", "minecraft:white_carpet")),
                ALL_BLOCKS));
    assertEquals(5, process.inputs().size());
    assertTrue(process.conditions().contains("mana=5200"));
    var primary = process.outputs().get(0);
    assertEquals("botania:rune_air", primary.id());
    assertEquals(new BigDecimal("2"), primary.amount());
    assertEquals("botania:runic_altar", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void botaniaTerraPlateUsesResultObject() {
    var process =
        mapped(
            mapper.map(
                fixture("botania/recipes/terra_plate/terrasteel_ingot.json"),
                itemTags(Map.of()),
                ALL_BLOCKS));
    assertEquals("botania:terrasteel_ingot", process.outputs().get(0).id());
    assertTrue(process.conditions().contains("mana=500000"));
    assertEquals("botania:terra_plate", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void botaniaManaInfusionWithCatalyst() {
    var process =
        mapped(
            mapper.map(
                fixture("botania/recipes/mana_infusion/acacia_log_to_dark_oak_log.json"),
                itemTags(Map.of()),
                ALL_BLOCKS));
    assertEquals(1, process.inputs().size());
    assertEquals("minecraft:acacia_log", process.inputs().get(0).alternatives().get(0).id());
    assertEquals("minecraft:dark_oak_log", process.outputs().get(0).id());
    assertEquals("botania:alchemy_catalyst", process.catalystId());
    assertTrue(process.conditions().contains("mana=40"));
    assertEquals("botania:mana_pool", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void botaniaBrewHasNoUsableOutput() {
    var outcome =
        mapper.map(fixture("botania/recipes/brew/absorption.json"), itemTags(Map.of()), ALL_BLOCKS);
    assertSkipped(UnpackProcessMapper.SkipReason.NO_ITEM_OUTPUT, outcome);
  }

  @Test
  void ae2InscriberNamedSlots() {
    var process =
        mapped(
            mapper.map(
                fixture("ae2/recipes/inscriber/calculation_processor.json"),
                itemTags(Map.of()),
                ALL_BLOCKS));
    assertEquals(3, process.inputs().size());
    assertEquals("bottom", process.inputs().get(0).slot());
    assertEquals("ae2:printed_silicon", process.inputs().get(0).alternatives().get(0).id());
    assertEquals("middle", process.inputs().get(1).slot());
    assertEquals("top", process.inputs().get(2).slot());
    assertTrue(process.conditions().contains("mode=press"));
    assertEquals("ae2:calculation_processor", process.outputs().get(0).id());
    assertEquals("ae2:inscriber", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void ae2TransformCircumstanceBecomesCondition() {
    var process =
        mapped(
            mapper.map(
                fixture("ae2/recipes/transform/certus_quartz_crystals.json"),
                itemTags(Map.of()),
                ALL_BLOCKS));
    assertTrue(
        process
            .conditions()
            .contains("circumstance={\"type\":\"fluid\",\"tag\":\"minecraft:water\"}"),
        () -> "conditions: " + process.conditions());
    // Not in the known table: falls back to <modid>:<type>, which exists per the predicate.
    assertEquals("ae2:transform", process.workstationId());
    assertEquals(new BigDecimal("2"), process.outputs().get(0).amount());
  }

  @Test
  void adAstraNasaWorkbenchResultIdKey() {
    var process =
        mapped(
            mapper.map(
                fixture("ad_astra/recipes/nasa_workbench/tier_1_rocket_from_nasa_workbench.json"),
                itemTags(Map.of("ad_astra:steel_blocks", "ad_astra:steel_block")),
                ALL_BLOCKS));
    assertEquals(14, process.inputs().size());
    var primary = process.outputs().get(0);
    assertEquals("ad_astra:tier_1_rocket", primary.id());
    assertEquals(BigDecimal.ONE, primary.amount());
    assertEquals("ad_astra:nasa_workbench", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void adAstraCompressing() {
    var process =
        mapped(
            mapper.map(
                fixture(
                    "ad_astra/recipes/compressing/calorite_plate_from_compressing_calorite_blocks.json"),
                itemTags(Map.of("ad_astra:calorite_blocks", "ad_astra:calorite_block")),
                ALL_BLOCKS));
    assertEquals(1, process.inputs().size());
    assertEquals(800L, process.durationTicks());
    assertTrue(process.conditions().contains("energy=20"));
    var primary = process.outputs().get(0);
    assertEquals("ad_astra:calorite_plate", primary.id());
    assertEquals(new BigDecimal("9"), primary.amount());
    assertEquals("ad_astra:compressor", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void adAstraRefiningUnwrapsFluidInput() {
    var process =
        mapped(
            mapper.map(
                fixture("ad_astra/recipes/refining/fuel_from_refining_oil.json"),
                (kind, id) ->
                    kind == UnpackedTag.Kind.FLUID && id.equals("ad_astra:oil")
                        ? List.of(new UnpackedTag.Entry("ad_astra:oil", true))
                        : null,
                ALL_BLOCKS));
    assertEquals(1, process.inputs().size());
    var input = process.inputs().get(0).alternatives().get(0);
    assertEquals(UnpackedTag.Kind.FLUID, input.kind());
    assertEquals("ad_astra:oil", input.id());
    assertEquals(new BigDecimal("5"), input.amount());
    var primary = process.outputs().get(0);
    assertEquals(UnpackedTag.Kind.FLUID, primary.kind());
    assertEquals("ad_astra:fuel", primary.id());
    assertEquals(new BigDecimal("5"), primary.amount());
    assertEquals(1L, process.durationTicks());
    assertTrue(process.conditions().contains("energy=30"));
    assertEquals("ad_astra:fuel_refinery", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void techrebornAlloySmelter() {
    var process =
        mapped(
            mapper.map(
                fixture("techreborn/recipes/alloy_smelter/brass_ingot.json"),
                itemTags(Map.of("c:zinc_ingots", "techreborn:zinc_ingot")),
                ALL_BLOCKS));
    assertEquals(2, process.inputs().size());
    assertEquals(new BigDecimal("3"), process.inputs().get(0).alternatives().get(0).amount());
    assertTrue(process.conditions().contains("power=6"));
    assertEquals(200L, process.durationTicks());
    var primary = process.outputs().get(0);
    assertEquals("techreborn:brass_ingot", primary.id());
    assertEquals(new BigDecimal("4"), primary.amount());
    // The techreborn rule maps any type to the workstation of the same id.
    assertEquals("techreborn:alloy_smelter", process.workstationId());
    assertTrue(process.plannable());
  }

  @Test
  void techrebornBlastFurnaceHeatCondition() {
    var process =
        mapped(
            mapper.map(
                fixture("techreborn/recipes/blast_furnace/aluminum_ingot.json"),
                itemTags(Map.of("c:aluminum_dusts", "techreborn:aluminum_dust")),
                ALL_BLOCKS));
    assertTrue(process.conditions().contains("heat=1700"));
    assertTrue(process.conditions().contains("power=128"));
    assertEquals(200L, process.durationTicks());
    assertTrue(process.plannable());
  }

  @Test
  void tagExpansionHonorsNestedRefsAndRequiredFalse() {
    Map<String, List<UnpackedTag.Entry>> itemTags =
        Map.of(
            "a:outer",
            List.of(
                new UnpackedTag.Entry("#a:inner", true), new UnpackedTag.Entry("a:dropped", false)),
            "a:inner",
            List.of(new UnpackedTag.Entry("a:item", true)));
    UnpackProcessMapper.TagLookup lookup = (kind, id) -> itemTags.get(id);
    var recipe =
        new UnpackedRecipe(
            "a:recipe",
            "a:machine",
            Map.of(
                "type", "a:machine",
                "ingredients", List.of(Map.of("tag", "a:outer")),
                "result", Map.of("item", "a:out")));
    var process = mapped(mapper.map(recipe, lookup, ALL_BLOCKS));
    assertEquals(
        List.of(new UnpackedProcess.Amount(UnpackedTag.Kind.ITEM, "a:item", BigDecimal.ONE)),
        process.inputs().get(0).alternatives());
    assertTrue(process.plannable());
  }

  @Test
  void tagExpansionIsCycleSafe() {
    Map<String, List<UnpackedTag.Entry>> itemTags =
        Map.of(
            "a:x",
                List.of(new UnpackedTag.Entry("#a:y", true), new UnpackedTag.Entry("a:one", true)),
            "a:y",
                List.of(new UnpackedTag.Entry("#a:x", true), new UnpackedTag.Entry("a:two", true)));
    UnpackProcessMapper.TagLookup lookup = (kind, id) -> itemTags.get(id);
    var recipe =
        new UnpackedRecipe(
            "a:recipe",
            "a:machine",
            Map.of(
                "type", "a:machine",
                "ingredients", List.of(Map.of("tag", "a:x")),
                "result", Map.of("item", "a:out")));
    var process = mapped(mapper.map(recipe, lookup, ALL_BLOCKS));
    var ids =
        process.inputs().get(0).alternatives().stream().map(UnpackedProcess.Amount::id).toList();
    assertEquals(List.of("a:two", "a:one"), ids);
  }

  @Test
  void missingTagMakesRecipeUnplannable() {
    var recipe =
        new UnpackedRecipe(
            "a:recipe",
            "a:machine",
            Map.of(
                "type", "a:machine",
                "ingredients", List.of(Map.of("tag", "a:missing")),
                "result", Map.of("item", "a:out")));
    var process = mapped(mapper.map(recipe, (kind, id) -> null, ALL_BLOCKS));
    assertFalse(process.plannable());
    assertTrue(
        process.warnings().stream().anyMatch(warning -> warning.contains("a:missing")),
        () -> "warnings: " + process.warnings());
  }

  @Test
  void skipRules() {
    assertSkipped(
        UnpackProcessMapper.SkipReason.VANILLA_TYPE,
        mapper.map(
            recipe("minecraft:crafting_shaped", Map.of("type", "minecraft:crafting_shaped")),
            itemTags(Map.of()),
            ALL_BLOCKS));
    assertSkipped(
        UnpackProcessMapper.SkipReason.DYNAMIC_TYPE,
        mapper.map(
            recipe("create:item_copying", Map.of("type", "create:item_copying")),
            itemTags(Map.of()),
            ALL_BLOCKS));
    assertSkipped(
        UnpackProcessMapper.SkipReason.DYNAMIC_TYPE,
        mapper.map(
            recipe("somemod:dynamic/copy", Map.of("type", "somemod:dynamic/copy")),
            itemTags(Map.of()),
            ALL_BLOCKS));
    assertSkipped(
        UnpackProcessMapper.SkipReason.INVALID_TYPE,
        mapper.map(
            recipe("a:b", Map.of("result", Map.of("item", "a:x"))),
            itemTags(Map.of()),
            ALL_BLOCKS));
  }

  @Test
  void workstationFallsBackToTypeIdAndThenToNull() {
    var recipe =
        new UnpackedRecipe(
            "a:recipe",
            "unknownmod:machine",
            Map.of(
                "type", "unknownmod:machine",
                "ingredients", List.of(Map.of("item", "a:in")),
                "result", Map.of("item", "a:out")));
    var fallback =
        mapped(mapper.map(recipe, itemTags(Map.of()), Set.of("unknownmod:machine")::contains));
    assertEquals("unknownmod:machine", fallback.workstationId());
    var absent = mapped(mapper.map(recipe, itemTags(Map.of()), NO_BLOCKS));
    assertNull(absent.workstationId());
  }

  private static UnpackedProcess mapped(UnpackProcessMapper.Outcome outcome) {
    assertInstanceOf(UnpackProcessMapper.Outcome.Mapped.class, outcome);
    return ((UnpackProcessMapper.Outcome.Mapped) outcome).process();
  }

  private static void assertSkipped(
      UnpackProcessMapper.SkipReason reason, UnpackProcessMapper.Outcome outcome) {
    assertInstanceOf(UnpackProcessMapper.Outcome.Skipped.class, outcome);
    assertEquals(reason, ((UnpackProcessMapper.Outcome.Skipped) outcome).reason());
  }

  private static UnpackProcessMapper.TagLookup itemTags(Map<String, String> tags) {
    return (kind, id) -> {
      if (kind != UnpackedTag.Kind.ITEM) {
        return null;
      }
      var value = tags.get(id);
      return value == null ? null : List.of(new UnpackedTag.Entry(value, true));
    };
  }

  private static UnpackedRecipe recipe(String id, Map<String, Object> json) {
    var type = json.get("type") instanceof String value ? value : "";
    return new UnpackedRecipe(id, type, json);
  }

  private static UnpackedRecipe fixture(String path) {
    var resource = "/unpack-fixtures/data/" + path;
    try (var stream = UnpackProcessMapperTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing fixture " + resource);
      }
      var json = BoundedJson.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      var withoutExtension = path.substring(0, path.length() - ".json".length());
      var namespace = withoutExtension.substring(0, withoutExtension.indexOf('/'));
      var recipePath = withoutExtension.substring(withoutExtension.indexOf('/') + 1);
      recipePath = recipePath.substring("recipes/".length());
      var type = json.get("type") instanceof String value ? value : "";
      return new UnpackedRecipe(namespace + ":" + recipePath, type, json);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
