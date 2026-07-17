package dev.minecraftagent.standalone.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.core.planning.PlannerBudget;
import dev.minecraftagent.standalone.core.planning.ProcessPlanner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class CatalogAndPlannerTest {
  private static final String GENERATION = "generation-catalog-001";
  private static final String FINGERPRINT = "1".repeat(64);

  @Test
  void snapshotPinsOneGenerationAndRejectsDanglingResources() {
    var stone = item("minecraft:stone", "Stone", 1);
    var stale =
        new ResourceRef(
            ResourceRef.Kind.ITEM,
            "minecraft:dirt",
            null,
            "Dirt",
            "item.minecraft.dirt",
            "minecraft",
            "Minecraft",
            "1.21.11",
            BigDecimal.ONE,
            "item",
            registrySource("other-generation"));
    assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(stone, stale), List.of()));

    var output = item("minecraft:polished_andesite", "Polished Andesite", 1);
    var process =
        process("fixture:polish", output, List.of(item("minecraft:andesite", "Andesite", 1)));
    assertThrows(
        IllegalArgumentException.class, () -> snapshot(List.of(stone, output), List.of(process)));
  }

  @Test
  void searchNormalizesUnicodeButOnlyExactIdsResolveAutomatically() {
    var sword = item("fixture:boss_sword", "Epee du Boss", 1);
    var accented =
        new ResourceRef(
            sword.kind(),
            sword.id(),
            sword.componentsFingerprint(),
            "Epee du Boss".replace("Epee", "Epee\u0301"),
            sword.translationKey(),
            sword.modId(),
            sword.modName(),
            sword.modVersion(),
            sword.amount(),
            sword.unit(),
            sword.source());
    var copperA = item("fixture:copper_gear", "Copper Gear", 1);
    var copperB = item("other:copper_gear", "Copper Gear", 1);
    var copperVariant =
        new ResourceRef(
            copperA.kind(),
            copperA.id(),
            "2".repeat(64),
            "Named Copper Gear",
            copperA.translationKey(),
            copperA.modId(),
            copperA.modName(),
            copperA.modVersion(),
            copperA.amount(),
            copperA.unit(),
            copperA.source());
    var snapshot = snapshot(List.of(accented, copperA, copperVariant, copperB), List.of());
    var search = new ResourceSearchIndex(snapshot);

    var exact = search.search(GENERATION, "fixture:boss_sword", 10);
    assertEquals(ResourceSearchIndex.Resolution.EXACT, exact.resolution());
    assertFalse(exact.requiresPlayerSelection());

    var normalized = search.search(GENERATION, "epee du boss", 10);
    assertEquals(ResourceSearchIndex.Resolution.AMBIGUOUS, normalized.resolution());
    assertTrue(normalized.requiresPlayerSelection());
    assertEquals("fixture:boss_sword", normalized.candidates().get(0).resource().id());

    var ambiguous = search.search(GENERATION, "Copper Gear", 10);
    assertEquals(ResourceSearchIndex.Resolution.AMBIGUOUS, ambiguous.resolution());
    assertEquals(
        List.of("fixture:copper_gear", "other:copper_gear", "fixture:copper_gear"),
        ambiguous.candidates().stream().map(candidate -> candidate.resource().id()).toList());
    var exactVariant = search.search(GENERATION, "fixture:copper_gear", 10);
    assertEquals(ResourceSearchIndex.Resolution.AMBIGUOUS, exactVariant.resolution());
    assertEquals(2, exactVariant.candidates().size());
    assertThrows(
        IllegalArgumentException.class, () -> search.search("stale-generation", "copper", 10));
  }

  @Test
  void plannerComputesBatchesAndPrefersInventoryForOrGroups() {
    var table = item("minecraft:crafting_table", "Crafting Table", 1);
    var log = item("minecraft:oak_log", "Oak Log", 1);
    var planks = item("minecraft:oak_planks", "Oak Planks", 1);
    var stick = item("minecraft:stick", "Stick", 1);
    var bamboo = item("minecraft:bamboo", "Bamboo", 1);
    var iron = item("minecraft:iron_ingot", "Iron Ingot", 1);
    var pickaxe = item("minecraft:iron_pickaxe", "Iron Pickaxe", 1);
    var handle = item("fixture:handle", "Flexible Handle", 1);

    var plankProcess =
        process("minecraft:planks", itemAmount(planks, 4), List.of(itemAmount(log, 1)));
    var stickProcess =
        process("minecraft:sticks", itemAmount(stick, 4), List.of(itemAmount(planks, 2)));
    var pickaxeProcess =
        processWithStation(
            "minecraft:iron_pickaxe",
            itemAmount(pickaxe, 1),
            table,
            List.of(
                new ProcessRecord.InputGroup("iron", List.of(itemAmount(iron, 3))),
                new ProcessRecord.InputGroup("handle", List.of(itemAmount(stick, 2)))));
    var handleProcess =
        processWithGroups(
            "fixture:flexible_handle",
            itemAmount(handle, 1),
            List.of(
                new ProcessRecord.InputGroup(
                    "material", List.of(itemAmount(stick, 2), itemAmount(bamboo, 2)))));
    var snapshot =
        snapshot(
            List.of(table, log, planks, stick, bamboo, iron, pickaxe, handle),
            List.of(plankProcess, stickProcess, pickaxeProcess, handleProcess));
    var planner = new ProcessPlanner(snapshot);

    var pickaxePlan =
        planner.plan(
            GENERATION,
            ResourceKey.from(pickaxe),
            new BigDecimal("2"),
            Map.of(ResourceKey.from(table), BigDecimal.ONE),
            PlannerBudget.DEFAULT);
    assertEquals(ProcessPlanner.Status.COMPLETE, pickaxePlan.status());
    var pickaxeRoute = pickaxePlan.routes().get(0);
    assertEquals(new BigDecimal("6"), pickaxeRoute.materials().get(ResourceKey.from(iron)));
    assertEquals(BigDecimal.ONE, pickaxeRoute.materials().get(ResourceKey.from(log)));
    assertFalse(pickaxeRoute.inventoryUsed().containsKey(ResourceKey.from(table)));
    assertTrue(pickaxeRoute.workstations().contains(ResourceKey.from(table)));
    var pickaxeStep =
        pickaxeRoute.steps().stream()
            .filter(step -> step.processId().equals("minecraft:iron_pickaxe"))
            .findFirst()
            .orElseThrow();
    assertEquals(new BigDecimal("6"), pickaxeStep.inputs().get(ResourceKey.from(iron)));
    assertEquals(new BigDecimal("4"), pickaxeStep.inputs().get(ResourceKey.from(stick)));
    assertEquals(new BigDecimal("2"), pickaxeStep.outputs().get(ResourceKey.from(pickaxe)));

    var handlePlan =
        planner.plan(
            GENERATION,
            ResourceKey.from(handle),
            BigDecimal.ONE,
            Map.of(ResourceKey.from(bamboo), new BigDecimal("2")),
            PlannerBudget.DEFAULT);
    assertEquals(ProcessPlanner.Status.COMPLETE, handlePlan.status());
    assertEquals(
        new BigDecimal("2"),
        handlePlan.routes().get(0).inventoryUsed().get(ResourceKey.from(bamboo)));
    assertFalse(handlePlan.routes().get(0).materials().containsKey(ResourceKey.from(stick)));
    assertEquals(
        new BigDecimal("2"),
        handlePlan.routes().get(0).steps().get(0).inputs().get(ResourceKey.from(bamboo)));
  }

  @Test
  void plannerMarksKnownOpaqueProcessesUnresolved() {
    var raw = item("fixture:raw", "Raw", 1);
    var opaque = item("fixture:opaque", "Opaque", 1);
    var process =
        new ProcessRecord(
            "fixture:opaque_process",
            "fixture:machine",
            "Opaque process",
            List.of(),
            List.of(new ProcessRecord.InputGroup("raw", List.of(raw))),
            List.of(),
            List.of(new ProcessRecord.Output(opaque, BigDecimal.ONE, true)),
            null,
            null,
            List.of(),
            List.of(),
            processSource(),
            false,
            List.of("Input roles are not safely visible."));
    var planner = new ProcessPlanner(snapshot(List.of(raw, opaque), List.of(process)));

    var planning =
        planner.plan(
            GENERATION, ResourceKey.from(opaque), BigDecimal.ONE, Map.of(), PlannerBudget.DEFAULT);

    assertEquals(ProcessPlanner.Status.PARTIAL, planning.status());
    assertTrue(planning.routes().get(0).materials().isEmpty());
    assertEquals(
        BigDecimal.ONE, planning.routes().get(0).unresolved().get(ResourceKey.from(opaque)));
    assertTrue(planning.routes().get(0).issues().contains(ProcessPlanner.Issue.PROCESS_UNUSABLE));
  }

  @Test
  void plannerReusesBatchSurplusCoproductsAndReturnedContainers() {
    var log = item("fixture:log", "Log", 1);
    var plank = item("fixture:plank", "Plank", 1);
    var frame = item("fixture:frame", "Frame", 1);
    var ore = item("fixture:ore", "Ore", 1);
    var ingot = item("fixture:ingot", "Ingot", 1);
    var slag = item("fixture:slag", "Slag", 1);
    var composite = item("fixture:composite", "Composite", 1);
    var herb = item("fixture:herb", "Herb", 1);
    var waterBucket = item("fixture:water_bucket", "Water Bucket", 1);
    var bucket = item("fixture:bucket", "Bucket", 1);
    var potion = item("fixture:potion", "Potion", 1);
    var kit = item("fixture:kit", "Kit", 1);

    var planks = process("fixture:planks", itemAmount(plank, 4), List.of(log));
    var frameProcess =
        processWithGroups(
            "fixture:frame",
            frame,
            List.of(
                new ProcessRecord.InputGroup("first", List.of(itemAmount(plank, 3))),
                new ProcessRecord.InputGroup("second", List.of(plank))));
    var smelt =
        new ProcessRecord(
            "fixture:smelt",
            "fixture:machine",
            "Smelt",
            List.of(),
            List.of(new ProcessRecord.InputGroup("ore", List.of(ore))),
            List.of(),
            List.of(
                new ProcessRecord.Output(ingot, BigDecimal.ONE, true),
                new ProcessRecord.Output(slag, BigDecimal.ONE, false)),
            null,
            null,
            List.of(),
            List.of(),
            processSource(),
            true,
            List.of());
    var compositeProcess = process("fixture:composite", composite, List.of(ingot, slag));
    var brew =
        new ProcessRecord(
            "fixture:brew",
            "fixture:machine",
            "Brew",
            List.of(),
            List.of(new ProcessRecord.InputGroup("herb", List.of(herb))),
            List.of(new ProcessRecord.Catalyst(waterBucket, true, bucket)),
            List.of(new ProcessRecord.Output(potion, BigDecimal.ONE, true)),
            null,
            null,
            List.of(),
            List.of(),
            processSource(),
            true,
            List.of());
    var kitProcess = process("fixture:kit", kit, List.of(potion, bucket));
    var planner =
        new ProcessPlanner(
            snapshot(
                List.of(
                    log,
                    plank,
                    frame,
                    ore,
                    ingot,
                    slag,
                    composite,
                    herb,
                    waterBucket,
                    bucket,
                    potion,
                    kit),
                List.of(planks, frameProcess, smelt, compositeProcess, brew, kitProcess)));

    var framePlan =
        planner.plan(
            GENERATION, ResourceKey.from(frame), BigDecimal.ONE, Map.of(), PlannerBudget.DEFAULT);
    assertEquals(BigDecimal.ONE, framePlan.routes().get(0).materials().get(ResourceKey.from(log)));

    var compositePlan =
        planner.plan(
            GENERATION,
            ResourceKey.from(composite),
            BigDecimal.ONE,
            Map.of(),
            PlannerBudget.DEFAULT);
    assertEquals(
        BigDecimal.ONE, compositePlan.routes().get(0).materials().get(ResourceKey.from(ore)));

    var kitPlan =
        planner.plan(
            GENERATION, ResourceKey.from(kit), BigDecimal.ONE, Map.of(), PlannerBudget.DEFAULT);
    assertEquals(BigDecimal.ONE, kitPlan.routes().get(0).materials().get(ResourceKey.from(herb)));
    assertEquals(
        BigDecimal.ONE, kitPlan.routes().get(0).materials().get(ResourceKey.from(waterBucket)));
    assertFalse(kitPlan.routes().get(0).materials().containsKey(ResourceKey.from(bucket)));
  }

  @Test
  void plannerReturnsPartialForCyclesAndBudgetsInsteadOfHanging() {
    var cycleA = item("fixture:cycle_a", "Cycle A", 1);
    var cycleB = item("fixture:cycle_b", "Cycle B", 1);
    var aFromB = process("fixture:a_from_b", cycleA, List.of(cycleB));
    var bFromA = process("fixture:b_from_a", cycleB, List.of(cycleA));
    var snapshot = snapshot(List.of(cycleA, cycleB), List.of(aFromB, bFromA));
    var planner = new ProcessPlanner(snapshot);

    var cycle =
        planner.plan(
            GENERATION, ResourceKey.from(cycleA), BigDecimal.ONE, Map.of(), PlannerBudget.DEFAULT);
    assertEquals(ProcessPlanner.Status.PARTIAL, cycle.status());
    assertTrue(cycle.routes().get(0).issues().contains(ProcessPlanner.Issue.CYCLE));

    var bounded =
        planner.plan(
            GENERATION,
            ResourceKey.from(cycleA),
            BigDecimal.ONE,
            Map.of(),
            new PlannerBudget(1, 12, 3, 500));
    assertEquals(ProcessPlanner.Status.PARTIAL, bounded.status());
    assertTrue(bounded.routes().get(0).issues().contains(ProcessPlanner.Issue.NODE_BUDGET));
  }

  @Test
  void publisherAtomicallyReplacesSnapshotsAndDropsLateGenerations() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    try (var publisher = new CatalogPublisher()) {
      var firstStarted = new CountDownLatch(1);
      var releaseFirst = new CountDownLatch(1);
      var first =
          publisher.rebuild(
              executor,
              (cancellation, progress) -> {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                return snapshotWithGeneration("generation-old");
              });
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

      var second =
          publisher.rebuild(
              executor,
              (cancellation, progress) -> {
                progress.update(1, 1);
                return snapshotWithGeneration("generation-new");
              });
      assertEquals("generation-new", second.future().get(5, TimeUnit.SECONDS).generationId());
      releaseFirst.countDown();
      first.future().get(5, TimeUnit.SECONDS);

      assertEquals("generation-new", publisher.current().orElseThrow().generationId());
      assertEquals(CatalogPublisher.Phase.READY, publisher.progress().phase());
      publisher.invalidate();
      assertTrue(publisher.current().isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  private static CatalogSnapshot snapshot(
      List<ResourceRef> resources, List<ProcessRecord> processes) {
    return new CatalogSnapshot(
        GENERATION, FINGERPRINT, Instant.parse("2026-07-17T00:00:00Z"), resources, processes);
  }

  private static CatalogSnapshot snapshotWithGeneration(String generation) {
    return new CatalogSnapshot(
        generation, FINGERPRINT, Instant.parse("2026-07-17T00:00:00Z"), List.of(), List.of());
  }

  private static ResourceRef item(String id, String name, int amount) {
    var modId = id.substring(0, id.indexOf(':'));
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id,
        null,
        name,
        "item." + id.replace(':', '.'),
        modId,
        modId,
        "1.0.0",
        BigDecimal.valueOf(amount),
        "item",
        registrySource(GENERATION));
  }

  private static ResourceRef itemAmount(ResourceRef source, int amount) {
    return new ResourceRef(
        source.kind(),
        source.id(),
        source.componentsFingerprint(),
        source.displayName(),
        source.translationKey(),
        source.modId(),
        source.modName(),
        source.modVersion(),
        BigDecimal.valueOf(amount),
        source.unit(),
        source.source());
  }

  private static ResourceRef.Source registrySource(String generation) {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_REGISTRY,
        "minecraft_registry",
        ResourceRef.Trust.L0B,
        ResourceRef.Completeness.COMPLETE,
        generation);
  }

  private static ResourceRef.Source processSource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_RECIPE,
        "vanilla_recipe_manager",
        ResourceRef.Trust.L1,
        ResourceRef.Completeness.COMPLETE,
        GENERATION);
  }

  private static ProcessRecord process(String id, ResourceRef output, List<ResourceRef> inputs) {
    var groups =
        java.util.stream.IntStream.range(0, inputs.size())
            .mapToObj(
                index -> new ProcessRecord.InputGroup("input_" + index, List.of(inputs.get(index))))
            .toList();
    return processWithGroups(id, output, groups);
  }

  private static ProcessRecord processWithStation(
      String id, ResourceRef output, ResourceRef station, List<ProcessRecord.InputGroup> inputs) {
    return processRecord(id, output, List.of(station), inputs);
  }

  private static ProcessRecord processWithGroups(
      String id, ResourceRef output, List<ProcessRecord.InputGroup> inputs) {
    return processRecord(id, output, List.of(), inputs);
  }

  private static ProcessRecord processRecord(
      String id,
      ResourceRef output,
      List<ResourceRef> stations,
      List<ProcessRecord.InputGroup> inputs) {
    return new ProcessRecord(
        id,
        "minecraft:crafting",
        id,
        stations,
        inputs,
        List.of(),
        List.of(new ProcessRecord.Output(output, BigDecimal.ONE, true)),
        null,
        null,
        List.of(),
        List.of(),
        processSource(),
        true,
        List.of());
  }
}
