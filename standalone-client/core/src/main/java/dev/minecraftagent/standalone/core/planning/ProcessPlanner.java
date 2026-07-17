package dev.minecraftagent.standalone.core.planning;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ProcessPlanner {
  private static final BigDecimal MAXIMUM_QUANTITY = new BigDecimal("1000000000000000");
  private final CatalogSnapshot snapshot;

  public ProcessPlanner(CatalogSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  public PlanningResult plan(
      String generationId,
      ResourceKey target,
      BigDecimal quantity,
      Map<ResourceKey, BigDecimal> inventory,
      PlannerBudget budget) {
    snapshot.requireCurrentGeneration(generationId);
    Objects.requireNonNull(target, "target");
    quantity = quantity(quantity, "quantity");
    if (quantity.signum() == 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(budget, "budget");
    if (inventory.size() > 10_000) {
      throw new IllegalArgumentException("inventory exceeds its bounded size");
    }
    var normalizedInventory = new TreeMap<ResourceKey, BigDecimal>();
    inventory.forEach(
        (key, value) ->
            normalizedInventory.put(
                Objects.requireNonNull(key, "inventory key"), quantity(value, "inventory value")));

    if (snapshot.resource(target).isEmpty()) {
      var route =
          new Route(
              false,
              Map.of(),
              Map.of(),
              Map.of(target, quantity),
              Set.of(),
              List.of(),
              Set.of(Issue.TARGET_UNKNOWN));
      return new PlanningResult(
          snapshot.generationId(), target, quantity, Status.UNRESOLVED, 0, List.of(route));
    }

    var context = new Context(budget);
    var initial = new State(normalizedInventory);
    var states = expand(target, quantity, 0, new HashSet<>(), initial, context);
    states = rankAndLimit(states, budget.topK());
    var routes = states.stream().map(State::toRoute).toList();
    var status =
        routes.stream().anyMatch(Route::complete)
            ? Status.COMPLETE
            : routes.isEmpty() ? Status.UNRESOLVED : Status.PARTIAL;
    return new PlanningResult(
        snapshot.generationId(), target, quantity, status, context.exploredNodes, routes);
  }

  private List<State> expand(
      ResourceKey key,
      BigDecimal required,
      int depth,
      Set<ResourceKey> path,
      State state,
      Context context) {
    if (!context.admitNode()) {
      var partial = state.copy();
      partial.incomplete(context.limitIssue(), key, required);
      return List.of(partial);
    }
    var remaining = state.consumeInventory(key, required);
    if (remaining.signum() == 0) {
      return List.of(state);
    }
    if (depth >= context.budget.depthMaximum()) {
      var partial = state.copy();
      partial.incomplete(Issue.DEPTH_BUDGET, key, remaining);
      return List.of(partial);
    }
    if (!path.add(key)) {
      var partial = state.copy();
      partial.incomplete(Issue.CYCLE, key, remaining);
      return List.of(partial);
    }

    var allProducers = snapshot.processesProducing(key);
    if (allProducers.isEmpty()) {
      state.addMaterial(key, remaining);
      path.remove(key);
      return List.of(state);
    }
    var producers = allProducers.stream().filter(ProcessRecord::plannable).toList();
    if (producers.isEmpty()) {
      var partial = state.copy();
      partial.incomplete(Issue.PROCESS_UNUSABLE, key, remaining);
      path.remove(key);
      return List.of(partial);
    }

    var results = new ArrayList<State>();
    for (var process : producers) {
      if (context.exhausted()) {
        break;
      }
      var primary =
          process.outputs().stream()
              .filter(ProcessRecord.Output::primary)
              .filter(output -> ResourceKey.from(output.resource()).equals(key))
              .findFirst();
      if (primary.isEmpty()) {
        continue;
      }
      var batches = batches(remaining, primary.get().resource().amount());
      var stepMarker = context.nextStepMarker();
      var branches = List.of(state.copy());

      for (var group : process.inputs()) {
        var alternatives = new ArrayList<State>();
        for (var branch : branches) {
          var ordered = new ArrayList<>(group.alternatives());
          ordered.sort(
              Comparator.<ResourceRef, BigDecimal>comparing(
                      alternative -> branch.inventoryAmount(ResourceKey.from(alternative)))
                  .reversed()
                  .thenComparing(ResourceKey::from));
          for (var alternative : ordered) {
            var selected = branch.copy();
            selected.recordStepInput(
                stepMarker, ResourceKey.from(alternative), alternative.amount().multiply(batches));
            alternatives.addAll(
                expand(
                    ResourceKey.from(alternative),
                    alternative.amount().multiply(batches),
                    depth + 1,
                    new HashSet<>(path),
                    selected,
                    context));
          }
        }
        branches = rankAndLimit(alternatives, context.budget.topK());
      }
      for (var catalyst : process.catalysts()) {
        var multiplier = catalyst.consumed() ? batches : BigDecimal.ONE;
        var requiredCatalyst = catalyst.resource().amount().multiply(multiplier);
        branches =
            catalyst.consumed()
                ? expandRecordedAcrossBranches(
                    branches,
                    stepMarker,
                    ResourceKey.from(catalyst.resource()),
                    requiredCatalyst,
                    depth + 1,
                    path,
                    context)
                : ensureReusableAcrossBranches(
                    branches,
                    stepMarker,
                    ResourceKey.from(catalyst.resource()),
                    requiredCatalyst,
                    depth + 1,
                    path,
                    context);
      }
      if (process.energy() != null) {
        branches =
            expandRecordedAcrossBranches(
                branches,
                stepMarker,
                ResourceKey.from(process.energy()),
                process.energy().amount().multiply(batches),
                depth + 1,
                path,
                context);
      }
      for (var branch : branches) {
        var outputs = deterministicOutputs(process, batches);
        process
            .workstations()
            .forEach(resource -> branch.workstations.add(ResourceKey.from(resource)));
        branch.addGeneratedOutputs(key, remaining, outputs);
        branch.steps.add(
            new PlanStep(process.processId(), batches, branch.takeStepInputs(stepMarker), outputs));
        results.add(branch);
      }
      results = new ArrayList<>(rankAndLimit(results, context.budget.topK()));
    }
    path.remove(key);
    if (results.isEmpty()) {
      var partial = state.copy();
      partial.incomplete(
          context.exhausted() ? context.limitIssue() : Issue.PROCESS_UNUSABLE, key, remaining);
      return List.of(partial);
    }
    return results;
  }

  private List<State> expandAcrossBranches(
      List<State> branches,
      ResourceKey key,
      BigDecimal quantity,
      int depth,
      Set<ResourceKey> path,
      Context context) {
    var expanded = new ArrayList<State>();
    for (var branch : branches) {
      expanded.addAll(expand(key, quantity, depth, new HashSet<>(path), branch, context));
    }
    return rankAndLimit(expanded, context.budget.topK());
  }

  private List<State> expandRecordedAcrossBranches(
      List<State> branches,
      long stepMarker,
      ResourceKey key,
      BigDecimal quantity,
      int depth,
      Set<ResourceKey> path,
      Context context) {
    var expanded = new ArrayList<State>();
    for (var branch : branches) {
      var selected = branch.copy();
      selected.recordStepInput(stepMarker, key, quantity);
      expanded.addAll(expand(key, quantity, depth, new HashSet<>(path), selected, context));
    }
    return rankAndLimit(expanded, context.budget.topK());
  }

  private List<State> ensureReusableAcrossBranches(
      List<State> branches,
      long stepMarker,
      ResourceKey key,
      BigDecimal quantity,
      int depth,
      Set<ResourceKey> path,
      Context context) {
    var expanded = new ArrayList<State>();
    for (var branch : branches) {
      if (branch.reusables.contains(key) || branch.inventoryAmount(key).compareTo(quantity) >= 0) {
        var selected = branch.copy();
        selected.reusables.add(key);
        selected.recordStepInput(stepMarker, key, quantity);
        expanded.add(selected);
        continue;
      }
      for (var selected :
          expand(key, quantity, depth, new HashSet<>(path), branch.copy(), context)) {
        selected.reusables.add(key);
        selected.recordStepInput(stepMarker, key, quantity);
        expanded.add(selected);
      }
    }
    return rankAndLimit(expanded, context.budget.topK());
  }

  private static Map<ResourceKey, BigDecimal> deterministicOutputs(
      ProcessRecord process, BigDecimal batches) {
    var outputs = new TreeMap<ResourceKey, BigDecimal>();
    process.outputs().stream()
        .filter(output -> output.probability().compareTo(BigDecimal.ONE) == 0)
        .forEach(
            output ->
                outputs.merge(
                    ResourceKey.from(output.resource()),
                    output.resource().amount().multiply(batches),
                    BigDecimal::add));
    process.catalysts().stream()
        .filter(ProcessRecord.Catalyst::consumed)
        .filter(catalyst -> catalyst.returnedResource() != null)
        .forEach(
            catalyst ->
                outputs.merge(
                    ResourceKey.from(catalyst.returnedResource()),
                    catalyst.returnedResource().amount().multiply(batches),
                    BigDecimal::add));
    return outputs;
  }

  private static List<State> rankAndLimit(Collection<State> states, int maximum) {
    return states.stream()
        .sorted(
            Comparator.comparing(State::complete)
                .reversed()
                .thenComparingInt(state -> state.unresolved.size())
                .thenComparingInt(state -> state.steps.size())
                .thenComparing(State::signature))
        .limit(maximum)
        .toList();
  }

  private static BigDecimal batches(BigDecimal required, BigDecimal outputAmount) {
    return required.divide(outputAmount, 0, RoundingMode.CEILING);
  }

  private static BigDecimal quantity(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0
        || value.compareTo(MAXIMUM_QUANTITY) > 0
        || value.precision() > 24
        || value.scale() > 9) {
      throw new IllegalArgumentException(field + " must be a bounded non-negative decimal");
    }
    return value.stripTrailingZeros();
  }

  public enum Status {
    COMPLETE,
    PARTIAL,
    UNRESOLVED
  }

  public enum Issue {
    TARGET_UNKNOWN,
    CYCLE,
    NODE_BUDGET,
    DEPTH_BUDGET,
    TIME_BUDGET,
    PROCESS_UNUSABLE
  }

  public record PlanStep(
      String processId,
      BigDecimal batches,
      Map<ResourceKey, BigDecimal> inputs,
      Map<ResourceKey, BigDecimal> outputs) {
    public PlanStep {
      Objects.requireNonNull(processId, "processId");
      batches = quantity(batches, "batches");
      inputs = immutableQuantities(inputs);
      outputs = immutableQuantities(outputs);
      if (outputs.isEmpty()) {
        throw new IllegalArgumentException("plan step requires a deterministic output");
      }
    }
  }

  public record Route(
      boolean complete,
      Map<ResourceKey, BigDecimal> materials,
      Map<ResourceKey, BigDecimal> inventoryUsed,
      Map<ResourceKey, BigDecimal> unresolved,
      Set<ResourceKey> workstations,
      List<PlanStep> steps,
      Set<Issue> issues) {
    public Route {
      materials = immutableQuantities(materials);
      inventoryUsed = immutableQuantities(inventoryUsed);
      unresolved = immutableQuantities(unresolved);
      workstations = Set.copyOf(workstations);
      steps = List.copyOf(steps);
      issues = Set.copyOf(issues);
      if (complete != (unresolved.isEmpty() && issues.isEmpty())) {
        throw new IllegalArgumentException("route completion state is inconsistent");
      }
    }
  }

  public record PlanningResult(
      String generationId,
      ResourceKey target,
      BigDecimal requestedQuantity,
      Status status,
      int exploredNodes,
      List<Route> routes) {
    public PlanningResult {
      Objects.requireNonNull(generationId, "generationId");
      Objects.requireNonNull(target, "target");
      requestedQuantity = quantity(requestedQuantity, "requestedQuantity");
      Objects.requireNonNull(status, "status");
      if (exploredNodes < 0) {
        throw new IllegalArgumentException("exploredNodes must not be negative");
      }
      routes = List.copyOf(routes);
      if (routes.isEmpty()) {
        throw new IllegalArgumentException("planning result requires at least one route");
      }
    }
  }

  private static Map<ResourceKey, BigDecimal> immutableQuantities(
      Map<ResourceKey, BigDecimal> source) {
    var result = new LinkedHashMap<ResourceKey, BigDecimal>();
    new TreeMap<>(source).forEach((key, value) -> result.put(key, quantity(value, "quantity")));
    return Map.copyOf(result);
  }

  private static final class Context {
    private final PlannerBudget budget;
    private final long deadlineNanos;
    private int exploredNodes;
    private long nextStepMarker;

    private Context(PlannerBudget budget) {
      this.budget = budget;
      this.deadlineNanos =
          System.nanoTime()
              + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget.wallClockMilliseconds());
    }

    private boolean admitNode() {
      if (exhausted()) {
        return false;
      }
      exploredNodes++;
      return true;
    }

    private boolean exhausted() {
      return exploredNodes >= budget.nodeMaximum() || System.nanoTime() - deadlineNanos >= 0;
    }

    private Issue limitIssue() {
      return exploredNodes >= budget.nodeMaximum() ? Issue.NODE_BUDGET : Issue.TIME_BUDGET;
    }

    private long nextStepMarker() {
      return nextStepMarker++;
    }
  }

  private static final class State {
    private final TreeMap<ResourceKey, BigDecimal> inventory;
    private final TreeMap<ResourceKey, BigDecimal> generatedInventory;
    private final TreeMap<ResourceKey, BigDecimal> inventoryUsed;
    private final TreeMap<ResourceKey, BigDecimal> materials;
    private final TreeMap<ResourceKey, BigDecimal> unresolved;
    private final TreeSet<ResourceKey> workstations;
    private final TreeSet<ResourceKey> reusables;
    private final ArrayList<PlanStep> steps;
    private final TreeSet<Issue> issues;
    private final TreeMap<Long, TreeMap<ResourceKey, BigDecimal>> stepInputs;

    private State(Map<ResourceKey, BigDecimal> inventory) {
      this(
          new TreeMap<>(inventory),
          new TreeMap<>(),
          new TreeMap<>(),
          new TreeMap<>(),
          new TreeMap<>(),
          new TreeSet<>(),
          new TreeSet<>(),
          new ArrayList<>(),
          new TreeSet<>(),
          new TreeMap<>());
    }

    private State(
        TreeMap<ResourceKey, BigDecimal> inventory,
        TreeMap<ResourceKey, BigDecimal> generatedInventory,
        TreeMap<ResourceKey, BigDecimal> inventoryUsed,
        TreeMap<ResourceKey, BigDecimal> materials,
        TreeMap<ResourceKey, BigDecimal> unresolved,
        TreeSet<ResourceKey> workstations,
        TreeSet<ResourceKey> reusables,
        ArrayList<PlanStep> steps,
        TreeSet<Issue> issues,
        TreeMap<Long, TreeMap<ResourceKey, BigDecimal>> stepInputs) {
      this.inventory = inventory;
      this.generatedInventory = generatedInventory;
      this.inventoryUsed = inventoryUsed;
      this.materials = materials;
      this.unresolved = unresolved;
      this.workstations = workstations;
      this.reusables = reusables;
      this.steps = steps;
      this.issues = issues;
      this.stepInputs = stepInputs;
    }

    private State copy() {
      return new State(
          new TreeMap<>(inventory),
          new TreeMap<>(generatedInventory),
          new TreeMap<>(inventoryUsed),
          new TreeMap<>(materials),
          new TreeMap<>(unresolved),
          new TreeSet<>(workstations),
          new TreeSet<>(reusables),
          new ArrayList<>(steps),
          new TreeSet<>(issues),
          copyStepInputs(stepInputs));
    }

    private BigDecimal consumeInventory(ResourceKey key, BigDecimal required) {
      var generated = generatedInventory.getOrDefault(key, BigDecimal.ZERO);
      var generatedUsed = generated.min(required);
      if (generatedUsed.signum() > 0) {
        generatedInventory.put(key, generated.subtract(generatedUsed));
      }
      required = required.subtract(generatedUsed);
      var available = inventory.getOrDefault(key, BigDecimal.ZERO);
      var used = available.min(required);
      if (used.signum() > 0) {
        inventory.put(key, available.subtract(used));
        inventoryUsed.merge(key, used, BigDecimal::add);
      }
      return required.subtract(used);
    }

    private BigDecimal inventoryAmount(ResourceKey key) {
      return inventory
          .getOrDefault(key, BigDecimal.ZERO)
          .add(generatedInventory.getOrDefault(key, BigDecimal.ZERO));
    }

    private void addGeneratedOutputs(
        ResourceKey satisfiedKey,
        BigDecimal satisfiedQuantity,
        Map<ResourceKey, BigDecimal> outputs) {
      outputs.forEach(
          (key, amount) -> {
            var available = key.equals(satisfiedKey) ? amount.subtract(satisfiedQuantity) : amount;
            if (available.signum() < 0) {
              throw new IllegalStateException("deterministic process output is insufficient");
            }
            if (available.signum() > 0) {
              generatedInventory.merge(key, available, BigDecimal::add);
            }
          });
    }

    private void addMaterial(ResourceKey key, BigDecimal quantity) {
      materials.merge(key, quantity, BigDecimal::add);
    }

    private void incomplete(Issue issue, ResourceKey key, BigDecimal quantity) {
      issues.add(issue);
      unresolved.merge(key, quantity, BigDecimal::add);
    }

    private void recordStepInput(long marker, ResourceKey key, BigDecimal quantity) {
      stepInputs
          .computeIfAbsent(marker, ignored -> new TreeMap<>())
          .merge(key, quantity, BigDecimal::add);
    }

    private Map<ResourceKey, BigDecimal> takeStepInputs(long marker) {
      var inputs = stepInputs.remove(marker);
      return inputs == null ? Map.of() : inputs;
    }

    private boolean complete() {
      return issues.isEmpty() && unresolved.isEmpty();
    }

    private String signature() {
      var builder = new StringBuilder();
      materials.forEach((key, value) -> builder.append(key).append('=').append(value).append(';'));
      steps.forEach(
          step ->
              builder
                  .append(step.processId())
                  .append('@')
                  .append(step.batches())
                  .append(step.inputs())
                  .append(';'));
      return builder.toString();
    }

    private Route toRoute() {
      return new Route(
          complete(), materials, inventoryUsed, unresolved, workstations, steps, issues);
    }

    private static TreeMap<Long, TreeMap<ResourceKey, BigDecimal>> copyStepInputs(
        TreeMap<Long, TreeMap<ResourceKey, BigDecimal>> source) {
      var copy = new TreeMap<Long, TreeMap<ResourceKey, BigDecimal>>();
      source.forEach((marker, values) -> copy.put(marker, new TreeMap<>(values)));
      return copy;
    }
  }
}
