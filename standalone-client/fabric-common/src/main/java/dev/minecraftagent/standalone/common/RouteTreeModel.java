package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.core.planning.ProcessPlanner;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Indented-tree presentation of one planned route. The model is game-free so the two
 * version-specific catalog screens only need to draw rows: target rows carry the request, step rows
 * carry process detail, resource rows carry icon and amount data, and info rows carry plain text.
 * Guides describe the ancestor connector lines of the classic tree-glyph rendering (vertical bar,
 * branch, corner).
 */
public final class RouteTreeModel {
  private RouteTreeModel() {}

  /** Hard bound on emitted rows so shared subtrees cannot explode the scroll view. */
  public static final int MAXIMUM_ROWS = 512;

  public enum ResourceState {
    PRODUCED,
    MATERIAL,
    INVENTORY,
    UNRESOLVED,
    LEAF
  }

  /**
   * Connector-line state: one flag per ancestor (true = that ancestor has a next sibling, so its
   * vertical line continues through this row) plus whether this row itself has a next sibling.
   */
  public record Guides(List<Boolean> ancestorContinues, boolean hasNextSibling) {
    public Guides {
      ancestorContinues = List.copyOf(ancestorContinues);
    }

    public int depth() {
      return ancestorContinues.size();
    }

    private Guides child() {
      var next = new ArrayList<>(ancestorContinues);
      next.add(hasNextSibling);
      return new Guides(next, false);
    }
  }

  public sealed interface Row {
    Guides guides();
  }

  public record TargetRow(Guides guides, ResourceRef resource, BigDecimal requested)
      implements Row {
    public TargetRow {
      Objects.requireNonNull(guides, "guides");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(requested, "requested");
    }
  }

  public record StepRow(
      Guides guides,
      String processId,
      String displayName,
      String categoryId,
      BigDecimal batches,
      boolean plannable)
      implements Row {
    public StepRow {
      Objects.requireNonNull(guides, "guides");
      Objects.requireNonNull(processId, "processId");
      Objects.requireNonNull(displayName, "displayName");
      Objects.requireNonNull(categoryId, "categoryId");
      Objects.requireNonNull(batches, "batches");
    }
  }

  public record ResourceRow(
      Guides guides, ResourceRef resource, BigDecimal amount, ResourceState state) implements Row {
    public ResourceRow {
      Objects.requireNonNull(guides, "guides");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(state, "state");
    }
  }

  public record InfoRow(Guides guides, String text) implements Row {
    public InfoRow {
      Objects.requireNonNull(guides, "guides");
      Objects.requireNonNull(text, "text");
    }
  }

  public static List<Row> rows(LocalPlanView view, CatalogSnapshot snapshot, int requestedRoute) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!view.generationId().equals(snapshot.generationId())) {
      throw new IllegalArgumentException("local plan snapshot is stale");
    }
    var routes = view.planning().routes();
    var routeIndex = Math.max(0, Math.min(requestedRoute, routes.size() - 1));
    var route = routes.get(routeIndex);

    var producerByKey = new LinkedHashMap<ResourceKey, ProcessPlanner.PlanStep>();
    for (var step : route.steps()) {
      for (var key : step.outputs().keySet()) {
        producerByKey.putIfAbsent(key, step);
      }
    }

    var builder = new Builder(snapshot, route, producerByKey);
    var targetKey = view.planning().target();
    var rootGuides = new Guides(List.of(), false);
    builder.emit(new TargetRow(rootGuides, view.target(), view.requestedAmount()));
    var rootStep = producerByKey.get(targetKey);
    if (rootStep != null) {
      builder.emitStep(rootStep, rootGuides.child(), new ArrayDeque<>());
    }
    // Any route fact that never appeared under a step (for example an unresolved target)
    // is still shown directly under the target so nothing is silently dropped.
    builder.appendOrphans(rootGuides.child(), targetKey);
    return List.copyOf(builder.rows);
  }

  private static ResourceState resourceState(
      ProcessPlanner.Route route, ResourceKey key, ProcessPlanner.PlanStep producer) {
    if (route.unresolved().containsKey(key)) {
      return ResourceState.UNRESOLVED;
    }
    if (route.inventoryUsed().containsKey(key)) {
      return ResourceState.INVENTORY;
    }
    if (route.materials().containsKey(key)) {
      return ResourceState.MATERIAL;
    }
    return producer != null ? ResourceState.PRODUCED : ResourceState.LEAF;
  }

  private static final class Builder {
    private final CatalogSnapshot snapshot;
    private final ProcessPlanner.Route route;
    private final Map<ResourceKey, ProcessPlanner.PlanStep> producerByKey;
    private final List<Row> rows = new ArrayList<>();
    private final java.util.Set<ResourceKey> seenResources = new java.util.HashSet<>();
    private boolean truncated = false;

    private Builder(
        CatalogSnapshot snapshot,
        ProcessPlanner.Route route,
        Map<ResourceKey, ProcessPlanner.PlanStep> producerByKey) {
      this.snapshot = snapshot;
      this.route = route;
      this.producerByKey = producerByKey;
    }

    private void emit(Row row) {
      rows.add(row);
    }

    private ResourceRef resolve(ResourceKey key) {
      return snapshot
          .resource(key)
          .orElseThrow(() -> new IllegalStateException("route references an unknown resource"));
    }

    private void emitStep(ProcessPlanner.PlanStep step, Guides guides, Deque<String> path) {
      if (!budget(guides)) {
        return;
      }
      var process = snapshot.process(step.processId());
      emit(
          new StepRow(
              guides,
              step.processId(),
              process.map(ProcessRecord::displayName).orElse(step.processId()),
              process.map(ProcessRecord::categoryId).orElse("agma:unknown"),
              step.batches(),
              process.map(ProcessRecord::plannable).orElse(true)));

      var childGuides = guides.child();
      var infoTexts = new ArrayList<String>();
      process.ifPresent(record -> appendProcessDetails(infoTexts, record));
      var inputEntries = new ArrayList<>(new TreeMap<>(step.inputs()).entrySet());
      var childCount = infoTexts.size() + inputEntries.size();
      for (var index = 0; index < infoTexts.size(); index++) {
        emit(
            new InfoRow(
                new Guides(childGuides.ancestorContinues(), index < childCount - 1),
                infoTexts.get(index)));
      }
      for (var index = 0; index < inputEntries.size(); index++) {
        var entry = inputEntries.get(index);
        emitResource(
            entry.getKey(),
            entry.getValue(),
            new Guides(childGuides.ancestorContinues(), infoTexts.size() + index < childCount - 1),
            path);
      }
    }

    private void emitResource(
        ResourceKey key, BigDecimal amount, Guides guides, Deque<String> path) {
      if (!budget(guides)) {
        return;
      }
      var producer = producerByKey.get(key);
      emit(new ResourceRow(guides, resolve(key), amount, resourceState(route, key, producer)));
      seenResources.add(key);
      if (producer != null && !path.contains(producer.processId())) {
        path.addLast(producer.processId());
        emitStep(producer, guides.child(), path);
        path.removeLast();
      }
    }

    private boolean budget(Guides guides) {
      if (truncated) {
        return false;
      }
      if (rows.size() >= MAXIMUM_ROWS) {
        truncated = true;
        rows.add(new InfoRow(guides, "… route truncated …"));
        return false;
      }
      return true;
    }

    private void appendOrphans(Guides childGuides, ResourceKey targetKey) {
      var orphans = new ArrayList<Map.Entry<ResourceKey, BigDecimal>>();
      new TreeMap<>(route.unresolved())
          .forEach(
              (key, amount) -> {
                if (!seenResources.contains(key) && !key.equals(targetKey)) {
                  orphans.add(Map.entry(key, amount));
                }
              });
      new TreeMap<>(route.materials())
          .forEach(
              (key, amount) -> {
                if (!seenResources.contains(key) && !key.equals(targetKey)) {
                  orphans.add(Map.entry(key, amount));
                }
              });
      new TreeMap<>(route.inventoryUsed())
          .forEach(
              (key, amount) -> {
                if (!seenResources.contains(key) && !key.equals(targetKey)) {
                  orphans.add(Map.entry(key, amount));
                }
              });
      for (var index = 0; index < orphans.size(); index++) {
        var orphan = orphans.get(index);
        if (!budget(childGuides)) {
          return;
        }
        emit(
            new ResourceRow(
                new Guides(childGuides.ancestorContinues(), index < orphans.size() - 1),
                resolve(orphan.getKey()),
                orphan.getValue(),
                resourceState(route, orphan.getKey(), producerByKey.get(orphan.getKey()))));
      }
    }

    private static void appendProcessDetails(List<String> infoTexts, ProcessRecord record) {
      record
          .workstations()
          .forEach(
              station ->
                  infoTexts.add(
                      "workstation " + station.displayName() + " [" + station.id() + "]"));
      record
          .catalysts()
          .forEach(
              catalyst ->
                  infoTexts.add(
                      "catalyst "
                          + (catalyst.consumed() ? "consumed " : "reusable ")
                          + catalyst.resource().displayName()
                          + " ["
                          + catalyst.resource().id()
                          + "]"));
      if (record.energy() != null) {
        infoTexts.add(
            "energy "
                + record.energy().amount().stripTrailingZeros().toPlainString()
                + " "
                + record.energy().unit()
                + " "
                + record.energy().displayName()
                + " ["
                + record.energy().id()
                + "]");
      }
      if (record.durationTicks() != null) {
        infoTexts.add("duration " + record.durationTicks() + " ticks");
      }
      record.conditions().forEach(condition -> infoTexts.add("condition " + condition));
      record.stages().forEach(stage -> infoTexts.add("stage " + stage.description()));
    }
  }
}
