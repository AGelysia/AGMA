package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class UnpackCatalogMapperScriptTest {
  private static final String GENERATION = "test-generation-scripts";
  private final UnpackCatalogMapper mapper = new UnpackCatalogMapper();

  @Test
  void scriptRecipesBecomeGapFillCandidates() {
    var scripts =
        scriptData("event.shapeless('2x testmod:mix', ['minecraft:dirt', 'minecraft:sand'])");
    var result =
        mapper.map(
            emptyModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            scripts);
    assertEquals(1, result.report().scriptRecipesSeen());
    assertEquals(1, result.report().processesAdded());
    var process = result.contribution().processes().get(0);
    assertEquals("kubejs:shapeless", process.categoryId());
    assertEquals(UnpackCatalogMapper.SCRIPT_PROVIDER_ID, process.source().providerId());
    assertEquals(ResourceRef.Layer.LOCAL_RESOURCE_PACK, process.source().layer());
    assertEquals(ResourceRef.Trust.L2, process.source().trust());
    assertTrue(process.plannable());
    // Script kinds map to the same station as their vanilla twin.
    assertEquals("minecraft:crafting_table", process.workstations().get(0).id());
    assertEquals(2, process.inputs().size());
    var primary = process.outputs().get(0).resource();
    assertEquals("testmod:mix", primary.id());
    assertEquals(2, primary.amount().intValue());
  }

  @Test
  void scriptTagIngredientsExpandLiveFirstStaticSecond() {
    var scripts = scriptData("event.shapeless('testmod:alloy', ['#c:ingots'])");
    var live =
        mapper.map(
            emptyModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            tagId -> tagId.equals("c:ingots") ? List.of("live:ingot") : null,
            null,
            scripts);
    assertEquals(List.of("live:ingot"), inputIds(live.contribution().processes().get(0)));
    // With no live answer the merged archive tags resolve the script ingredient.
    var staticOnly =
        mapper.map(
            modpackWithTags(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            scripts);
    assertEquals(List.of("static:ingot"), inputIds(staticOnly.contribution().processes().get(0)));
  }

  @Test
  void idRemovalsSuppressArchiveCandidatesExactlyAndByGlob() {
    var exact =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            removals(new ScriptRemoval(ScriptRemoval.Kind.ID, "create:milling/iron_ore")));
    assertEquals(1, exact.report().suppressedByRemovals());
    assertEquals(1, exact.report().processesAdded());
    assertEquals(
        "create:crushing/aluminum_ore", exact.contribution().processes().get(0).processId());

    var glob =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            removals(new ScriptRemoval(ScriptRemoval.Kind.ID, "create:milling/*")));
    assertEquals(1, glob.report().suppressedByRemovals());
    assertEquals(1, glob.report().processesAdded());
  }

  @Test
  void outputRemovalsSuppressByPrimaryOutputId() {
    var result =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            removals(new ScriptRemoval(ScriptRemoval.Kind.OUTPUT, "create:crushed_raw_aluminum")));
    assertEquals(1, result.report().suppressedByRemovals());
    assertEquals(1, result.report().processesAdded());
    assertEquals("create:milling/iron_ore", result.contribution().processes().get(0).processId());
  }

  @Test
  void removeAllSuppressesArchivesButNeverScripts() {
    var scripts =
        new ScriptRecipeData(
            new KubeJsScriptParser()
                .parse("event.shapeless('testmod:mix', ['minecraft:dirt'])", "a.js", 0)
                .recipes(),
            List.of(ScriptRemoval.all()),
            new ScriptRecipeData.Stats(1, 0, 64L, 1, 1, 0));
    var result =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            scripts);
    assertEquals(1, result.report().removeAllRemovals());
    // Both archive recipes are suppressed; the script recipe still joins.
    assertEquals(2, result.report().suppressedByRemovals());
    assertEquals(1, result.report().processesAdded());
    assertEquals(
        UnpackCatalogMapper.SCRIPT_PROVIDER_ID,
        result.contribution().processes().get(0).source().providerId());
  }

  @Test
  void liveBaseProcessesAreNeverSuppressed() {
    // The base process set is the live truth: removals cannot touch it, and the archive candidate
    // still loses by id like always.
    var result =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of("create:milling/iron_ore"),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            removals(new ScriptRemoval(ScriptRemoval.Kind.ID, "create:*")));
    assertEquals(1, result.report().skippedDuplicate());
    // Only the crushing recipe was suppressed; the milling recipe lost to the live base.
    assertEquals(1, result.report().suppressedByRemovals());
    assertEquals(0, result.report().processesAdded());
  }

  @Test
  void reportAccountsScriptsAndSuppressionsSeparately() {
    var scripts =
        new ScriptRecipeData(
            new KubeJsScriptParser()
                .parse(
                    "event.shapeless('testmod:mix', ['minecraft:dirt'])\n"
                        + "event.remove({output: 'create:crushed_raw_aluminum'})",
                    "a.js",
                    0)
                .recipes(),
            List.of(new ScriptRemoval(ScriptRemoval.Kind.OUTPUT, "create:crushed_raw_aluminum")),
            new ScriptRecipeData.Stats(1, 0, 64L, 1, 1, 0));
    var result =
        mapper.map(
            archiveModpack(),
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            null,
            null,
            scripts);
    assertEquals(2, result.report().recipesSeen());
    assertEquals(1, result.report().scriptRecipesSeen());
    assertEquals(1, result.report().suppressedByRemovals());
    // milling + the script recipe.
    assertEquals(2, result.report().processesAdded());
    assertEquals(0, result.report().skippedTotal());
  }

  private static List<String> inputIds(ProcessRecord process) {
    return process.inputs().get(0).alternatives().stream().map(ResourceRef::id).toList();
  }

  private static ScriptRecipeData scriptData(String source) {
    var parsed = new KubeJsScriptParser().parse(source, "a.js", 0);
    return new ScriptRecipeData(
        parsed.recipes(),
        parsed.removals(),
        new ScriptRecipeData.Stats(1, 0, source.length(), parsed.recipes().size(), 0, 0));
  }

  private static ScriptRecipeData removals(ScriptRemoval... removals) {
    return new ScriptRecipeData(
        List.of(), List.of(removals), new ScriptRecipeData.Stats(1, 0, 64L, 0, removals.length, 0));
  }

  private static UnpackedModpack emptyModpack() {
    return new UnpackedModpack(List.of(), ScanReport.empty());
  }

  private static UnpackedModpack modpackWithTags() {
    var tag =
        new UnpackedTag(
            UnpackedTag.Kind.ITEM,
            "c:ingots",
            false,
            List.of(new UnpackedTag.Entry("static:ingot", true)));
    var mod =
        new UnpackedMod("static", "Static", "1.0", "static.jar", List.of(), List.of(tag), Map.of());
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  /** Two archive recipes: the crushing fixture plus an inline milling recipe. */
  private static UnpackedModpack archiveModpack() {
    var crushing =
        new UnpackedRecipe(
            "create:crushing/aluminum_ore",
            "create:crushing",
            Map.of(
                "type", "create:crushing",
                "ingredients", List.of(Map.of("item", "create:aluminum_ore")),
                "results", List.of(Map.of("item", "create:crushed_raw_aluminum"))));
    var milling =
        new UnpackedRecipe(
            "create:milling/iron_ore",
            "create:milling",
            Map.of(
                "type", "create:milling",
                "ingredients", List.of(Map.of("item", "minecraft:iron_ore")),
                "result", Map.of("item", "create:crushed_iron")));
    var mod =
        new UnpackedMod(
            "create",
            "Create",
            "0.5",
            "create.jar",
            List.of(crushing, milling),
            List.of(),
            Map.of());
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }
}
