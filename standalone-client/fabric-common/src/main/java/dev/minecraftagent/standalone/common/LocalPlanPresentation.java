package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Language-neutral sections for the two version-specific local process screens. */
public final class LocalPlanPresentation {
  private LocalPlanPresentation() {}

  public static List<Section> sections(
      LocalPlanView view, CatalogSnapshot snapshot, int requestedRoute) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!view.generationId().equals(snapshot.generationId())) {
      throw new IllegalArgumentException("local plan snapshot is stale");
    }
    var routes = view.planning().routes();
    var routeIndex = Math.max(0, Math.min(requestedRoute, routes.size() - 1));
    var route = routes.get(routeIndex);
    var sections = new ArrayList<Section>();
    sections.add(
        new Section(
            "screen.agma_standalone.plan_target",
            List.of(
                amount(view.requestedAmount(), view.target().displayName(), view.target().id()),
                "status="
                    + view.planning().status().name().toLowerCase(java.util.Locale.ROOT)
                    + ", exploredNodes="
                    + view.planning().exploredNodes())));
    sections.add(
        new Section(
            "screen.agma_standalone.plan_ranking",
            List.of(
                "rank="
                    + (routeIndex + 1)
                    + ": "
                    + (route.complete() ? "complete route" : "partial route"),
                "then fewer unresolved resources, fewer process steps, stable process identity")));
    sections.add(
        new Section(
            "screen.agma_standalone.plan_materials",
            entries(route.materials(), "No base materials are required or visible.")));
    sections.add(
        new Section(
            "screen.agma_standalone.plan_inventory_used",
            entries(route.inventoryUsed(), "No inventory quantities were applied.")));
    sections.add(
        new Section(
            "screen.agma_standalone.plan_workstations",
            route.workstations().isEmpty()
                ? List.of("No workstation is visible.")
                : route.workstations().stream().map(key -> key.id()).toList()));

    var steps = new ArrayList<String>();
    for (var index = 0; index < route.steps().size(); index++) {
      var step = route.steps().get(index);
      steps.add(
          (index + 1) + ". " + step.processId() + " (" + decimal(step.batches()) + " batches)");
      appendAmounts(steps, "input", step.inputs());
      appendAmounts(steps, "output", step.outputs());
      snapshot
          .process(step.processId())
          .ifPresent(
              process -> {
                process
                    .catalysts()
                    .forEach(
                        catalyst ->
                            steps.add(
                                "   catalyst "
                                    + (catalyst.consumed() ? "consumed " : "reusable ")
                                    + amount(
                                        catalyst.resource().amount(),
                                        catalyst.resource().displayName(),
                                        catalyst.resource().id())));
                if (process.energy() != null) {
                  steps.add(
                      "   energy "
                          + amount(
                              process.energy().amount(),
                              process.energy().displayName(),
                              process.energy().id()));
                }
                process.conditions().forEach(condition -> steps.add("   condition " + condition));
                process.stages().forEach(stage -> steps.add("   stage " + stage.description()));
              });
    }
    sections.add(
        new Section(
            "screen.agma_standalone.plan_steps",
            steps.isEmpty() ? List.of("No plannable process step is visible.") : steps));
    sections.add(
        new Section(
            "screen.agma_standalone.plan_unresolved",
            entries(route.unresolved(), "No unresolved resource remains.")));
    if (!route.issues().isEmpty()) {
      sections.add(
          new Section(
              "screen.agma_standalone.plan_issues",
              route.issues().stream()
                  .map(issue -> issue.name().toLowerCase(java.util.Locale.ROOT))
                  .toList()));
    }
    return List.copyOf(sections);
  }

  private static List<String> entries(
      java.util.Map<dev.minecraftagent.standalone.core.catalog.ResourceKey, BigDecimal> values,
      String empty) {
    return values.isEmpty()
        ? List.of(empty)
        : new TreeMap<>(values)
            .entrySet().stream()
                .map(entry -> decimal(entry.getValue()) + " x " + entry.getKey().id())
                .toList();
  }

  private static void appendAmounts(
      List<String> lines,
      String label,
      Map<dev.minecraftagent.standalone.core.catalog.ResourceKey, BigDecimal> values) {
    new TreeMap<>(values)
        .forEach(
            (resource, quantity) ->
                lines.add("   " + label + " " + decimal(quantity) + " x " + resource.id()));
  }

  private static String amount(BigDecimal amount, String displayName, String id) {
    return decimal(amount) + " x " + displayName + " [" + id + "]";
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  public record Section(String titleKey, List<String> entries) {
    public Section {
      Objects.requireNonNull(titleKey, "titleKey");
      entries = List.copyOf(entries);
    }
  }
}
