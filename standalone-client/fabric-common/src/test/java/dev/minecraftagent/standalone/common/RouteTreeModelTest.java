package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.common.RouteTreeModel.Guides;
import dev.minecraftagent.standalone.common.RouteTreeModel.InfoRow;
import dev.minecraftagent.standalone.common.RouteTreeModel.ResourceRow;
import dev.minecraftagent.standalone.common.RouteTreeModel.ResourceState;
import dev.minecraftagent.standalone.common.RouteTreeModel.StepRow;
import dev.minecraftagent.standalone.common.RouteTreeModel.TargetRow;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.core.planning.PlannerBudget;
import dev.minecraftagent.standalone.core.planning.ProcessPlanner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTreeModelTest {
  private static final String GENERATION = "gen-1";
  private static final String FINGERPRINT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void buildsIndentedTreeWithStatesAndGuides() {
    var dust = item("fixture:dust", "Dust", 1);
    var alloy = item("fixture:alloy", "Alloy", 1);
    var bucket = item("minecraft:bucket", "Bucket", 1);
    var water = fluid("minecraft:water", "Water", 1000);
    var basin = item("fixture:basin", "Basin", 1);

    var waterProcess = process("fixture:water_collection", water, List.of(itemAmount(bucket, 1)));
    var alloyProcess =
        processWithStation(
            "fixture:alloy_smelting",
            itemAmount(alloy, 1),
            basin,
            List.of(
                new ProcessRecord.InputGroup("dust", List.of(itemAmount(dust, 2))),
                new ProcessRecord.InputGroup("water", List.of(itemAmount(water, 1000)))));
    var snapshot =
        snapshot(List.of(dust, alloy, bucket, water, basin), List.of(waterProcess, alloyProcess));
    var planning =
        new ProcessPlanner(snapshot)
            .plan(
                GENERATION,
                ResourceKey.from(alloy),
                BigDecimal.ONE,
                Map.of(ResourceKey.from(dust), new BigDecimal("2")),
                PlannerBudget.DEFAULT);
    var view = new LocalPlanView(GENERATION, alloy, BigDecimal.ONE, planning);

    var rows = RouteTreeModel.rows(view, snapshot, 0);

    // target -> alloy step -> [water (produced) -> water step -> bucket] -> dust (inventory)
    assertEquals(7, rows.size());

    var target = assertInstanceOf(TargetRow.class, rows.get(0));
    assertEquals(new Guides(List.of(), false), target.guides());
    assertEquals("fixture:alloy", target.resource().id());
    assertEquals(0, BigDecimal.ONE.compareTo(target.requested()));

    var alloyStep = assertInstanceOf(StepRow.class, rows.get(1));
    assertEquals(new Guides(List.of(false), false), alloyStep.guides());
    assertEquals("fixture:alloy_smelting", alloyStep.processId());
    assertEquals("Alloy", alloyStep.displayName());

    var workstation = assertInstanceOf(InfoRow.class, rows.get(2));
    assertTrue(workstation.text().startsWith("workstation Basin [fixture:basin]"));
    assertTrue(workstation.guides().hasNextSibling());

    // FLUID kinds sort before ITEM kinds, so water is the first input row.
    var waterRow = assertInstanceOf(ResourceRow.class, rows.get(3));
    assertEquals(ResourceState.PRODUCED, waterRow.state());
    assertEquals("minecraft:water", waterRow.resource().id());
    assertEquals(0, new BigDecimal("1000").compareTo(waterRow.amount()));
    assertTrue(waterRow.guides().hasNextSibling());
    assertEquals(List.of(false, false), waterRow.guides().ancestorContinues());

    var waterStep = assertInstanceOf(StepRow.class, rows.get(4));
    assertEquals("fixture:water_collection", waterStep.processId());
    // The alloy-step line continues through the water subtree because dust follows it.
    assertEquals(List.of(false, false, true), waterStep.guides().ancestorContinues());

    var bucketRow = assertInstanceOf(ResourceRow.class, rows.get(5));
    assertEquals(ResourceState.MATERIAL, bucketRow.state());
    assertFalse(bucketRow.guides().hasNextSibling());
    assertEquals(List.of(false, false, true, false), bucketRow.guides().ancestorContinues());

    var dustRow = assertInstanceOf(ResourceRow.class, rows.get(6));
    assertEquals(ResourceState.INVENTORY, dustRow.state());
    assertEquals(0, new BigDecimal("2").compareTo(dustRow.amount()));
    assertFalse(dustRow.guides().hasNextSibling());
  }

  @Test
  void keepsUnresolvedTargetsVisibleAsOrphans() {
    var raw = item("fixture:raw", "Raw", 1);
    var opaque = item("fixture:opaque", "Opaque", 1);
    var opaqueProcess =
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
    var snapshot = snapshot(List.of(raw, opaque), List.of(opaqueProcess));
    var planning =
        new ProcessPlanner(snapshot)
            .plan(
                GENERATION,
                ResourceKey.from(opaque),
                BigDecimal.ONE,
                Map.of(),
                PlannerBudget.DEFAULT);
    var view = new LocalPlanView(GENERATION, opaque, BigDecimal.ONE, planning);

    var rows = RouteTreeModel.rows(view, snapshot, 0);

    assertEquals(1, rows.size());
    assertInstanceOf(TargetRow.class, rows.get(0));
  }

  @Test
  void truncatesVeryLongChainsDeterministically() {
    var resources = new ArrayList<ResourceRef>();
    var processes = new ArrayList<ProcessRecord>();
    var chain = 300;
    for (var index = 0; index <= chain; index++) {
      resources.add(item("fixture:node_" + index, "Node " + index, 1));
    }
    for (var index = 0; index < chain; index++) {
      processes.add(
          process(
              "fixture:step_" + index, resources.get(index), List.of(resources.get(index + 1))));
    }
    var snapshot = snapshot(resources, processes);
    var target = resources.get(0);
    var steps = new ArrayList<ProcessPlanner.PlanStep>();
    for (var index = 0; index < chain; index++) {
      steps.add(
          new ProcessPlanner.PlanStep(
              "fixture:step_" + index,
              BigDecimal.ONE,
              Map.of(ResourceKey.from(resources.get(index + 1)), BigDecimal.ONE),
              Map.of(ResourceKey.from(resources.get(index)), BigDecimal.ONE)));
    }
    var route =
        new ProcessPlanner.Route(
            true,
            Map.of(ResourceKey.from(resources.get(chain)), BigDecimal.ONE),
            Map.of(),
            Map.of(),
            java.util.Set.of(),
            steps,
            java.util.Set.of());
    var planning =
        new ProcessPlanner.PlanningResult(
            GENERATION,
            ResourceKey.from(target),
            BigDecimal.ONE,
            ProcessPlanner.Status.COMPLETE,
            chain,
            List.of(route));
    var view = new LocalPlanView(GENERATION, target, BigDecimal.ONE, planning);

    var rows = RouteTreeModel.rows(view, snapshot, 0);

    assertEquals(RouteTreeModel.MAXIMUM_ROWS + 1, rows.size());
    var last = assertInstanceOf(InfoRow.class, rows.get(rows.size() - 1));
    assertTrue(last.text().contains("truncated"));
  }

  @Test
  void rejectsStaleSnapshots() {
    var target = item("fixture:target", "Target", 1);
    var snapshot = snapshot(List.of(target), List.of());
    var planning =
        new ProcessPlanner.PlanningResult(
            GENERATION,
            ResourceKey.from(target),
            BigDecimal.ONE,
            ProcessPlanner.Status.UNRESOLVED,
            0,
            List.of(
                new ProcessPlanner.Route(
                    false,
                    Map.of(),
                    Map.of(),
                    Map.of(ResourceKey.from(target), BigDecimal.ONE),
                    java.util.Set.of(),
                    List.of(),
                    java.util.Set.of(ProcessPlanner.Issue.TARGET_UNKNOWN))));
    var view = new LocalPlanView(GENERATION, target, BigDecimal.ONE, planning);
    var stale = snapshotWithGeneration("gen-stale");

    assertThrows(IllegalArgumentException.class, () -> RouteTreeModel.rows(view, stale, 0));

    // An unresolved resource without any route step still appears as an orphan row.
    var other = item("fixture:other", "Other", 1);
    var snapshotWithOther = snapshot(List.of(target, other), List.of());
    var orphanPlanning =
        new ProcessPlanner.PlanningResult(
            GENERATION,
            ResourceKey.from(target),
            BigDecimal.ONE,
            ProcessPlanner.Status.PARTIAL,
            0,
            List.of(
                new ProcessPlanner.Route(
                    false,
                    Map.of(),
                    Map.of(),
                    Map.of(ResourceKey.from(other), BigDecimal.ONE),
                    java.util.Set.of(),
                    List.of(),
                    java.util.Set.of(ProcessPlanner.Issue.PROCESS_UNUSABLE))));
    var orphanView = new LocalPlanView(GENERATION, target, BigDecimal.ONE, orphanPlanning);
    var rows = RouteTreeModel.rows(orphanView, snapshotWithOther, 0);
    assertEquals(2, rows.size());
    assertInstanceOf(TargetRow.class, rows.get(0));
    var orphan = assertInstanceOf(ResourceRow.class, rows.get(1));
    assertEquals(ResourceState.UNRESOLVED, orphan.state());
    assertEquals("fixture:other", orphan.resource().id());
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
        registrySource());
  }

  private static ResourceRef fluid(String id, String name, int amount) {
    var modId = id.substring(0, id.indexOf(':'));
    return new ResourceRef(
        ResourceRef.Kind.FLUID,
        id,
        null,
        name,
        "fluid." + id.replace(':', '.'),
        modId,
        modId,
        "1.0.0",
        BigDecimal.valueOf(amount),
        "millibucket",
        registrySource());
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

  private static ResourceRef.Source registrySource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_REGISTRY,
        "minecraft_registry",
        ResourceRef.Trust.L0B,
        ResourceRef.Completeness.COMPLETE,
        GENERATION);
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
    return processRecord(id, output, List.of(), groups);
  }

  private static ProcessRecord processWithStation(
      String id, ResourceRef output, ResourceRef station, List<ProcessRecord.InputGroup> inputs) {
    return processRecord(id, output, List.of(station), inputs);
  }

  private static ProcessRecord processRecord(
      String id,
      ResourceRef output,
      List<ResourceRef> stations,
      List<ProcessRecord.InputGroup> inputs) {
    return new ProcessRecord(
        id,
        "minecraft:crafting",
        output.displayName(),
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

  private static CatalogSnapshot snapshot(
      List<ResourceRef> resources, List<ProcessRecord> processes) {
    return new CatalogSnapshot(
        GENERATION, FINGERPRINT, Instant.parse("2026-07-17T00:00:00Z"), resources, processes);
  }
}
