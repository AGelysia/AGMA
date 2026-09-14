package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class UnpackCatalogMapperTest {
  private static final String GENERATION = "test-generation-1";
  private final UnpackCatalogMapper mapper = new UnpackCatalogMapper();

  @Test
  void mapsFixtureModpackIntoGapFillContribution() {
    var modpack = createModpack();
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100);
    var report = result.report();
    assertEquals(2, report.recipesSeen());
    assertEquals(1, report.processesAdded());
    // The vanilla-typed recipe never reaches the catalog.
    assertEquals(1, report.skippedVanilla());
    assertEquals(1, report.skippedTotal());

    var contribution = result.contribution();
    assertEquals(UnpackCatalogMapper.ADAPTER_ID, contribution.adapterId());
    var process = contribution.processes().get(0);
    assertEquals("create:crushing/aluminum_ore", process.processId());
    assertEquals("create:crushing", process.categoryId());
    assertTrue(process.plannable());
    assertEquals(ResourceRef.Completeness.COMPLETE, process.source().completeness());
    assertEquals(ResourceRef.Trust.L2, process.source().trust());
    assertEquals(ResourceRef.Layer.LOCAL_RESOURCE_PACK, process.source().layer());
    // Lang display names flow into resources and the process display name.
    var primary = process.outputs().get(0).resource();
    assertEquals("Crushed Raw Aluminum", primary.displayName());
    assertEquals("Crushed Raw Aluminum recipe (Crushing Wheel)", process.displayName());
    assertEquals("create:crushing_wheel", process.workstations().get(0).id());
    assertEquals(400L, process.durationTicks());
    // Synthesized resources use the live item unit and the scanning mod's metadata.
    assertEquals("item", primary.unit());
    assertEquals("create", primary.modId());
    assertEquals("Create", primary.modName());
    assertEquals("0.5", primary.modVersion());
    assertTrue(report.synthesizedResources() > 0);
    // The gap resources cover every id the process references.
    var synthesizedIds =
        contribution.resources().stream()
            .map(ResourceRef::id)
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(synthesizedIds.contains("create:crushed_raw_aluminum"));
    assertTrue(synthesizedIds.contains("create:crushing_wheel"));
    assertTrue(synthesizedIds.contains("create:aluminum_ore"));
  }

  @Test
  void prefersGameLocaleBeforeEnUsFallback() {
    var modpack = createModpack();
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "zh_cn",
            GENERATION,
            100,
            100);
    var process = result.contribution().processes().get(0);
    assertEquals("碎铝", process.outputs().get(0).resource().displayName());
  }

  @Test
  void baseProcessIdsAlwaysWin() {
    var modpack = createModpack();
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of("create:crushing/aluminum_ore"),
            "en_us",
            GENERATION,
            100,
            100);
    assertEquals(0, result.report().processesAdded());
    assertEquals(1, result.report().skippedDuplicate());
    assertTrue(result.contribution().processes().isEmpty());
  }

  @Test
  void reusesBaseResourceMetadata() {
    var modpack = createModpack();
    var base = baseItem("create:crushed_raw_aluminum", "Live Crushed Aluminum");
    var result =
        mapper.map(
            modpack,
            (kind, id) ->
                kind == ResourceRef.Kind.ITEM && id.equals(base.id())
                    ? Optional.of(base)
                    : Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100);
    var process = result.contribution().processes().get(0);
    var primary = process.outputs().get(0).resource();
    assertEquals("Live Crushed Aluminum", primary.displayName());
    assertEquals("livemod", primary.modId());
    // The reused base resource is not re-emitted as a gap resource.
    assertTrue(result.contribution().resources().stream().noneMatch(r -> r.id().equals(base.id())));
  }

  @Test
  void unplannableProcessesStayPartial() {
    // Without the input tag the crushing recipe cannot resolve its inputs.
    var modpack = createModpackWithoutTags();
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100);
    var process = result.contribution().processes().get(0);
    assertEquals(ResourceRef.Completeness.PARTIAL, process.source().completeness());
    assertTrue(
        process.warnings().stream().anyMatch(warning -> warning.contains("c:aluminum_ores")));
  }

  @Test
  void budgetsBoundTheContribution() {
    var modpack = createModpack();
    var noProcessBudget =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            0,
            100);
    assertEquals(0, noProcessBudget.report().processesAdded());
    assertEquals(1, noProcessBudget.report().skippedBudget());

    var noResourceBudget =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            0);
    assertEquals(0, noResourceBudget.report().processesAdded());
    assertEquals(1, noResourceBudget.report().skippedBudget());
    assertTrue(noResourceBudget.contribution().resources().isEmpty());
  }

  @Test
  void fluidResourcesUseMillibuckets() {
    var recipe =
        new UnpackedRecipe(
            "ad_astra:refining/fuel",
            "ad_astra:refining",
            Map.of(
                "type", "ad_astra:refining",
                "input", Map.of("ingredient", Map.of("fluid", "ad_astra:oil"), "millibuckets", 5),
                "result", Map.of("fluid", "ad_astra:fuel", "millibuckets", 5)));
    var mod =
        new UnpackedMod(
            "ad_astra", "Ad Astra", "1.0", "adastra.jar", List.of(recipe), List.of(), Map.of());
    var modpack = new UnpackedModpack(List.of(mod), ScanReport.empty());
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100);
    var process = result.contribution().processes().get(0);
    var fluid = process.inputs().get(0).alternatives().get(0);
    assertEquals(ResourceRef.Kind.FLUID, fluid.kind());
    assertEquals("millibucket", fluid.unit());
    assertEquals(new BigDecimal("5"), fluid.amount());
    assertEquals(ResourceRef.Kind.FLUID, process.outputs().get(0).resource().kind());
  }

  @Test
  void liveTagLookupWinsOverStatic() {
    var modpack =
        tagModpack(Map.of("c:ingots", List.of(new UnpackedTag.Entry("static:ingot", true))));
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            tagId -> tagId.equals("c:ingots") ? List.of("live:ingot") : null,
            null);
    var process = result.contribution().processes().get(0);
    assertEquals(List.of("live:ingot"), inputIds(process));
    assertTrue(process.plannable());
  }

  @Test
  void staticTagsAreTheFallbackWhenLiveIsEmptyOrAbsent() {
    var modpack =
        tagModpack(Map.of("c:ingots", List.of(new UnpackedTag.Entry("static:ingot", true))));
    var emptyLive =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            tagId -> List.of(),
            null);
    assertEquals(List.of("static:ingot"), inputIds(emptyLive.contribution().processes().get(0)));
    var nullLive =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            tagId -> null,
            null);
    assertEquals(List.of("static:ingot"), inputIds(nullLive.contribution().processes().get(0)));
  }

  @Test
  void nestedRefsThroughLiveResultsAreFollowed() {
    // Live outer -> live inner, and live -> static-only reference.
    var modpack =
        tagModpack(Map.of("c:static_only", List.of(new UnpackedTag.Entry("static:gem", true))));
    UnpackCatalogMapper.LiveTagLookup live =
        tagId ->
            switch (tagId) {
              case "c:ingots" -> List.of("#c:live_inner", "#c:static_only");
              case "c:live_inner" -> List.of("live:ingot");
              default -> null;
            };
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            live,
            null);
    var process = result.contribution().processes().get(0);
    assertEquals(List.of("live:ingot", "static:gem"), inputIds(process));
    assertTrue(process.plannable());
  }

  @Test
  void liveTagCyclesStayBoundedAndUnresolvedTagsFailClosed() {
    var modpack = tagModpack(Map.of());
    UnpackCatalogMapper.LiveTagLookup cyclic =
        tagId ->
            switch (tagId) {
              case "c:ingots" -> List.of("#c:other");
              case "c:other" -> List.of("#c:ingots");
              default -> null;
            };
    var result =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            cyclic,
            null);
    var process = result.contribution().processes().get(0);
    // The cycle terminates (bounded, cycle-safe) but resolves to no alternatives, so the recipe
    // fails closed without hanging.
    assertTrue(process.inputs().isEmpty());
    assertFalse(process.plannable());
    assertTrue(
        process.warnings().stream()
            .anyMatch(warning -> warning.contains("no resolvable item or fluid alternative")),
        () -> "warnings: " + process.warnings());

    // Neither live nor static knows the tag: named as unresolved in the warnings.
    var unresolved =
        mapper.map(
            modpack,
            (kind, id) -> Optional.empty(),
            id -> true,
            Set.of(),
            "en_us",
            GENERATION,
            100,
            100,
            tagId -> null,
            null);
    var unresolvedProcess = unresolved.contribution().processes().get(0);
    assertFalse(unresolvedProcess.plannable());
    assertTrue(
        unresolvedProcess.warnings().stream().anyMatch(warning -> warning.contains("c:ingots")),
        () -> "warnings: " + unresolvedProcess.warnings());
  }

  private static UnpackedModpack tagModpack(Map<String, List<UnpackedTag.Entry>> staticItemTags) {
    var recipe =
        new UnpackedRecipe(
            "test:machine/alloy",
            "test:machine",
            Map.of(
                "type", "test:machine",
                "ingredients", List.of(Map.of("tag", "c:ingots")),
                "result", Map.of("item", "test:alloy")));
    var tags =
        staticItemTags.entrySet().stream()
            .map(
                entry ->
                    new UnpackedTag(UnpackedTag.Kind.ITEM, entry.getKey(), false, entry.getValue()))
            .toList();
    var mod = new UnpackedMod("test", "Test", "1.0", "test.jar", List.of(recipe), tags, Map.of());
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  private static List<String> inputIds(ProcessRecord process) {
    return process.inputs().get(0).alternatives().stream().map(ResourceRef::id).toList();
  }

  private static UnpackedModpack createModpack() {
    var crushing =
        fixture("create/recipes/crushing/aluminum_ore.json", "create:crushing/aluminum_ore");
    var vanilla =
        new UnpackedRecipe(
            "create:crafting/scoria",
            "minecraft:crafting_shaped",
            Map.of("type", "minecraft:crafting_shaped"));
    var tags =
        List.of(
            new UnpackedTag(
                UnpackedTag.Kind.ITEM,
                "c:aluminum_ores",
                false,
                List.of(new UnpackedTag.Entry("create:aluminum_ore", true))));
    var lang =
        Map.of(
            "en_us",
            Map.of(
                "item.create.crushed_raw_aluminum", "Crushed Raw Aluminum",
                "block.create.crushing_wheel", "Crushing Wheel"),
            "zh_cn",
            Map.of("item.create.crushed_raw_aluminum", "碎铝"));
    var mod =
        new UnpackedMod(
            "create", "Create", "0.5", "create.jar", List.of(crushing, vanilla), tags, lang);
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  private static UnpackedModpack createModpackWithoutTags() {
    var crushing =
        fixture("create/recipes/crushing/aluminum_ore.json", "create:crushing/aluminum_ore");
    var mod =
        new UnpackedMod(
            "create", "Create", "0.5", "create.jar", List.of(crushing), List.of(), Map.of());
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  private static ResourceRef baseItem(String id, String displayName) {
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id,
        null,
        displayName,
        "item." + id.replace(':', '.'),
        "livemod",
        "Live Mod",
        "9.9",
        BigDecimal.ONE,
        "item",
        new ResourceRef.Source(
            ResourceRef.Layer.CLIENT_REGISTRY,
            "vanilla_client",
            ResourceRef.Trust.L0B,
            ResourceRef.Completeness.COMPLETE,
            GENERATION));
  }

  private static UnpackedRecipe fixture(String path, String id) {
    var resource = "/unpack-fixtures/data/" + path;
    try (var stream = UnpackCatalogMapperTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing fixture " + resource);
      }
      var json = BoundedJson.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      var type = json.get("type") instanceof String value ? value : "";
      return new UnpackedRecipe(id, type, json);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
