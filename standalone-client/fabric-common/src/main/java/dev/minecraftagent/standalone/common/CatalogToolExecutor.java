package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.core.planning.PlannerBudget;
import dev.minecraftagent.standalone.core.planning.ProcessPlanner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the five bounded, client-visible catalog tools without accessing game APIs. */
public final class CatalogToolExecutor implements ClientToolHandler, AutoCloseable {
  private static final int MAXIMUM_ACTIVE_TOOLS = 8;
  private static final int MAXIMUM_PENDING_INVENTORY_AUTHORIZATIONS = 32;
  private static final Duration MAXIMUM_AUTHORIZATION_LIFETIME = Duration.ofMinutes(5);
  private static final long PLANNER_WALL_CLOCK_MILLISECONDS = 500;
  private static final BigDecimal MAXIMUM_WIRE_AMOUNT = BigDecimal.valueOf(1_000_000_000L);

  private final CatalogToolSource source;
  private final ExecutorService executor;
  private final Clock clock;
  private final boolean ownsExecutor;
  private final Map<UUID, RunningTask> running = new ConcurrentHashMap<>();
  private final Map<UUID, InventoryGrant> inventoryGrants = new ConcurrentHashMap<>();
  private final Map<UUID, RequestInventory> requestInventories = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  private volatile SearchCache searchCache;

  public CatalogToolExecutor(CatalogToolSource source) {
    this(source, newToolExecutor(), Clock.systemUTC(), true);
  }

  public CatalogToolExecutor(CatalogToolSource source, ExecutorService executor) {
    this(source, executor, Clock.systemUTC(), false);
  }

  CatalogToolExecutor(CatalogToolSource source, ExecutorService executor, Clock clock) {
    this(source, executor, clock, false);
  }

  private CatalogToolExecutor(
      CatalogToolSource source, ExecutorService executor, Clock clock, boolean ownsExecutor) {
    this.source = Objects.requireNonNull(source, "source");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ownsExecutor = ownsExecutor;
  }

  /**
   * Grants one request access to exactly the listed resource ids in the current generation. The
   * grant is consumed even when a mismatched or failed call presents it.
   */
  public InventoryAuthorization authorizeInventoryOnce(
      UUID requestId, UUID subjectId, Collection<String> resourceIds, Duration lifetime) {
    requireOpen();
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(MAXIMUM_AUTHORIZATION_LIFETIME) > 0) {
      throw new IllegalArgumentException("inventory authorization lifetime is invalid");
    }
    var view = Objects.requireNonNull(source.catalogView(), "catalogView");
    if (view.state() != CatalogToolSource.State.READY) {
      throw new IllegalStateException("the catalog must be ready before inventory authorization");
    }
    var ids = sortedResourceIds(resourceIds);
    var known =
        view.snapshot().resources().stream()
            .map(ResourceRef::id)
            .collect(java.util.stream.Collectors.toSet());
    if (!known.containsAll(ids)) {
      throw new IllegalArgumentException("inventory authorization contains an unknown resource");
    }
    pruneExpiredGrants();
    if (inventoryGrants.size() >= MAXIMUM_PENDING_INVENTORY_AUTHORIZATIONS) {
      throw new IllegalStateException("too many pending inventory authorizations");
    }
    UUID authorizationId;
    var expiration = clock.instant().plus(lifetime);
    var grant = new InventoryGrant(requestId, subjectId, view.generationId(), ids, expiration);
    do {
      authorizationId = UUID.randomUUID();
    } while (inventoryGrants.putIfAbsent(authorizationId, grant) != null);
    if (inventoryGrants.size() > MAXIMUM_PENDING_INVENTORY_AUTHORIZATIONS
        && inventoryGrants.remove(authorizationId, grant)) {
      throw new IllegalStateException("too many pending inventory authorizations");
    }
    return new InventoryAuthorization(
        authorizationId, requestId, subjectId, view.generationId(), ids, expiration);
  }

  /** Revokes every unconsumed inventory grant, for example on disconnect or world change. */
  public void revokeInventoryAuthorizations() {
    inventoryGrants.clear();
    requestInventories.clear();
  }

  /** Returns a bounded deterministic dependency set for one explicitly authorized route query. */
  public List<String> inventoryResourceIdsForPlan(String targetId) {
    requireOpen();
    if (targetId == null || !targetId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new IllegalArgumentException("inventory planning target is invalid");
    }
    var view = view();
    if (view.state() != CatalogToolSource.State.READY) {
      throw new IllegalStateException("the catalog must be ready before inventory authorization");
    }
    var pending = new ArrayDeque<Dependency>();
    pending.add(new Dependency(new ResourceKey(ResourceRef.Kind.ITEM, targetId, null), 0));
    var result = new java.util.TreeSet<String>();
    var visited = new HashSet<ResourceKey>();
    while (!pending.isEmpty() && result.size() < 64) {
      var dependency = pending.removeFirst();
      if (!visited.add(dependency.key)) {
        continue;
      }
      result.add(dependency.key.id());
      if (dependency.depth >= 12) {
        continue;
      }
      for (var process : view.snapshot().processesProducing(dependency.key)) {
        if (!process.plannable()) {
          continue;
        }
        for (var group : process.inputs()) {
          group.alternatives().stream()
              .map(ResourceKey::from)
              .sorted()
              .forEach(key -> pending.addLast(new Dependency(key, dependency.depth + 1)));
        }
        process.catalysts().stream()
            .map(catalyst -> ResourceKey.from(catalyst.resource()))
            .sorted()
            .forEach(key -> pending.addLast(new Dependency(key, dependency.depth + 1)));
        if (process.energy() != null) {
          pending.addLast(new Dependency(ResourceKey.from(process.energy()), dependency.depth + 1));
        }
      }
    }
    return result.stream().limit(64).toList();
  }

  /**
   * Resolves an observed stack to catalog-owned metadata without exposing observed display data.
   */
  public Optional<ResourceRef> canonicalPlanningTarget(ResourceRef observed) {
    requireOpen();
    Objects.requireNonNull(observed, "observed");
    var view = view();
    if (view.state() != CatalogToolSource.State.READY
        || !view.generationId().equals(observed.source().generationId())) {
      return Optional.empty();
    }
    return Optional.ofNullable(resolvePlanningTarget(view.snapshot(), observed));
  }

  /** Builds a bounded local-only plan for the catalog screen without using the Runtime or model. */
  public CompletionStage<LocalPlanView> planLocal(ResourceRef target, BigDecimal amount) {
    requireOpen();
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(amount, "amount");
    var view = view();
    var planningTarget =
        view.state() == CatalogToolSource.State.READY
            ? resolvePlanningTarget(view.snapshot(), target)
            : null;
    if (view.state() != CatalogToolSource.State.READY
        || !view.generationId().equals(target.source().generationId())
        || planningTarget == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("the selected local resource is stale or unavailable"));
    }
    var future = new CompletableFuture<LocalPlanView>();
    try {
      executor.execute(
          () -> {
            try {
              var planning =
                  new ProcessPlanner(view.snapshot())
                      .plan(
                          view.generationId(),
                          ResourceKey.from(planningTarget),
                          amount,
                          Map.of(),
                          PlannerBudget.DEFAULT);
              future.complete(new LocalPlanView(view.generationId(), target, amount, planning));
            } catch (RuntimeException failure) {
              future.completeExceptionally(failure);
            }
          });
    } catch (RejectedExecutionException failure) {
      future.completeExceptionally(failure);
    }
    return future;
  }

  private static ResourceRef resolvePlanningTarget(CatalogSnapshot snapshot, ResourceRef observed) {
    var exact = snapshot.resource(ResourceKey.from(observed));
    if (exact.isPresent()) {
      return exact.get();
    }
    var candidates =
        snapshot.resources().stream()
            .filter(
                resource ->
                    resource.kind() == observed.kind() && resource.id().equals(observed.id()))
            .sorted(Comparator.comparing(ResourceKey::from))
            .toList();
    var bases =
        candidates.stream().filter(resource -> resource.componentsFingerprint() == null).toList();
    if (bases.size() == 1) {
      return bases.get(0);
    }
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  @Override
  public CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call) {
    Objects.requireNonNull(call, "call");
    if (closed.get()) {
      return CompletableFuture.completedFuture(
          error("CLIENT_TOOL_EXECUTOR_CLOSED", "The client tool executor is closed.", false));
    }
    var task = new RunningTask(call);
    if (running.putIfAbsent(call.toolCallId(), task) != null) {
      return CompletableFuture.completedFuture(
          rejected("DUPLICATE_TOOL_CALL", "The client tool call is already active.", false));
    }
    if (running.size() > MAXIMUM_ACTIVE_TOOLS && running.remove(call.toolCallId(), task)) {
      return CompletableFuture.completedFuture(
          rejected("CLIENT_TOOL_CAPACITY", "The client tool capacity is exhausted.", true));
    }
    try {
      task.attach(
          executor.submit(
              () -> {
                try {
                  task.checkCancelled();
                  task.result.complete(dispatch(task));
                } catch (CancellationException exception) {
                  task.result.cancel(false);
                } catch (ToolFailure failure) {
                  task.result.complete(failure.error);
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  task.result.cancel(false);
                } catch (RuntimeException exception) {
                  task.result.complete(
                      error(
                          "CLIENT_TOOL_EXECUTION_FAILED",
                          "The client tool could not be executed.",
                          true));
                } finally {
                  running.remove(call.toolCallId(), task);
                }
              }));
    } catch (RejectedExecutionException exception) {
      running.remove(call.toolCallId(), task);
      task.result.complete(
          rejected("CLIENT_TOOL_CAPACITY", "The client tool capacity is exhausted.", true));
    }
    return task.result;
  }

  @Override
  public void cancel(ClientToolCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    var task = running.get(cancellation.toolCallId());
    if (task != null
        && task.matches(cancellation)
        && running.remove(cancellation.toolCallId(), task)) {
      task.cancel();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    revokeInventoryAuthorizations();
    running.values().forEach(RunningTask::cancel);
    running.clear();
    requestInventories.clear();
    searchCache = null;
    if (ownsExecutor) {
      executor.shutdownNow();
    }
  }

  private ClientToolOutcome dispatch(RunningTask task) throws InterruptedException {
    return switch (task.call.tool()) {
      case "game.resource.search" -> search(task);
      case "game.process.lookup" -> processes(task, false);
      case "game.process.uses" -> processes(task, true);
      case "game.process.plan" -> plan(task);
      case "game.inventory.snapshot" -> inventory(task);
      default ->
          throw failure(
              rejected("CLIENT_TOOL_UNSUPPORTED", "The client tool is not supported.", false));
    };
  }

  private ClientToolResult search(RunningTask task) throws InterruptedException {
    var arguments = task.call.arguments();
    var query = string(arguments, "query");
    var limit = integer(arguments, "limit");
    var view = view();
    if (view.state() != CatalogToolSource.State.READY) {
      return result(
          map(
              "generationId", view.generationId(),
              "visibility", wire(view.visibility()),
              "completeness", "unavailable",
              "candidates", List.of(),
              "ambiguous", false,
              "truncated", false,
              "warnings", limitedWarnings(view.warnings(), 32)));
    }
    task.checkCancelled();
    var index = searchIndex(view);
    var indexed = index.search(view.generationId(), query, Math.min(50, limit + 1));
    var truncated = indexed.candidates().size() > limit;
    var candidates = new ArrayList<Map<String, Object>>();
    for (var indexValue = 0;
        indexValue < Math.min(limit, indexed.candidates().size());
        indexValue++) {
      task.checkCancelled();
      var candidate = indexed.candidates().get(indexValue);
      candidates.add(
          map(
              "rank", indexValue + 1,
              "score", BigDecimal.valueOf(candidate.score()).movePointLeft(4),
              "matchedBy", matchedBy(candidate.reasons()),
              "resource", wire(candidate.resource())));
    }
    return result(
        map(
            "generationId", view.generationId(),
            "visibility", wire(view.visibility()),
            "completeness", wire(view.completeness()),
            "candidates", List.copyOf(candidates),
            "ambiguous", indexed.requiresPlayerSelection(),
            "truncated", truncated,
            "warnings", limitedWarnings(view.warnings(), 32)));
  }

  private ClientToolResult processes(RunningTask task, boolean uses) throws InterruptedException {
    var arguments = task.call.arguments();
    var generationId = string(arguments, "generationId");
    var resourceId = string(arguments, "resourceId");
    var limit = integer(arguments, "limit");
    var view = view();
    var status = processStatus(view, generationId);
    if (!"ready".equals(status)) {
      return processResult(view, status, List.of(), false);
    }

    var matches = new ArrayList<ProcessRecord>();
    for (var process : view.snapshot().processes()) {
      task.checkCancelled();
      if (uses ? uses(process, resourceId) : produces(process, resourceId)) {
        matches.add(process);
      }
    }
    matches.sort(Comparator.comparing(ProcessRecord::processId));
    var truncated = matches.size() > limit;
    if (truncated) {
      matches.subList(limit, matches.size()).clear();
    }
    var serialized = new ArrayList<Map<String, Object>>(matches.size());
    for (var process : matches) {
      task.checkCancelled();
      serialized.add(wire(process));
    }
    return processResult(view, "ready", List.copyOf(serialized), truncated);
  }

  private ClientToolResult plan(RunningTask task) throws InterruptedException {
    var arguments = task.call.arguments();
    var requestedGeneration = string(arguments, "generationId");
    var resourceId = string(arguments, "resourceId");
    var amount = decimal(arguments, "amount");
    var view = view();
    if (!requestedGeneration.equals(view.generationId())) {
      return emptyPlan(view, "stale_generation", resourceId, amount, List.of());
    }
    if (view.state() != CatalogToolSource.State.READY) {
      return emptyPlan(
          view,
          "unresolved",
          resourceId,
          amount,
          appendWarning(view.warnings(), "The client-visible catalog is not ready.", 64));
    }

    var matchingResources =
        view.snapshot().resources().stream()
            .filter(resource -> resource.id().equals(resourceId))
            .sorted(Comparator.comparing(ResourceKey::from))
            .toList();
    final ResourceKey target;
    var baseResources =
        matchingResources.stream()
            .filter(resource -> resource.componentsFingerprint() == null)
            .toList();
    if (baseResources.size() > 1 || (baseResources.isEmpty() && matchingResources.size() > 1)) {
      throw failure(
          rejected(
              "RESOURCE_AMBIGUOUS",
              "The resource id identifies more than one catalog resource.",
              false));
    }
    target =
        !baseResources.isEmpty()
            ? ResourceKey.from(baseResources.get(0))
            : matchingResources.isEmpty()
                ? new ResourceKey(ResourceRef.Kind.ITEM, resourceId, null)
                : ResourceKey.from(matchingResources.get(0));

    task.checkCancelled();
    var authorizedInventory = requestInventory(task.call.requestId(), view.generationId());
    var planning =
        new ProcessPlanner(view.snapshot())
            .plan(
                view.generationId(),
                target,
                amount,
                authorizedInventory,
                new PlannerBudget(
                    integer(arguments, "maxNodes"),
                    integer(arguments, "maxDepth"),
                    integer(arguments, "topK"),
                    PLANNER_WALL_CLOCK_MILLISECONDS));
    task.checkCancelled();
    return result(
        wirePlan(view, planning, resourceId, amount, !authorizedInventory.isEmpty(), task));
  }

  private ClientToolResult inventory(RunningTask task) throws InterruptedException {
    var arguments = task.call.arguments();
    var authorizationId = UUID.fromString(string(arguments, "authorizationId"));
    var requestedGeneration = string(arguments, "generationId");
    var resourceIds = sortedResourceIds(stringList(arguments, "resourceIds"));
    pruneExpiredGrants();
    var grant = inventoryGrants.remove(authorizationId);
    if (grant == null
        || !grant.requestId.equals(task.call.requestId())
        || !grant.subjectId.equals(task.call.subjectId())
        || !grant.generationId.equals(requestedGeneration)
        || !grant.resourceIds.equals(resourceIds)
        || !clock.instant().isBefore(grant.expiresAt)) {
      throw failure(
          rejected(
              "INVENTORY_AUTHORIZATION_REQUIRED",
              "A matching single-use inventory authorization is required.",
              false));
    }
    var view = view();
    if (view.state() != CatalogToolSource.State.READY
        || !view.generationId().equals(requestedGeneration)) {
      throw failure(
          rejected(
              "INVENTORY_AUTHORIZATION_STALE",
              "The inventory authorization is no longer valid for this world.",
              false));
    }

    task.checkCancelled();
    final CatalogToolSource.InventorySnapshot snapshot;
    try {
      snapshot =
          Objects.requireNonNull(
              source.inventorySnapshot(
                  new CatalogToolSource.InventoryRequest(
                      requestedGeneration, resourceIds, task::cancelled)),
              "inventorySnapshot");
    } catch (InterruptedException exception) {
      throw exception;
    } catch (Exception exception) {
      throw failure(
          error(
              "INVENTORY_SNAPSHOT_FAILED",
              "The authorized inventory snapshot could not be read.",
              true));
    }
    task.checkCancelled();
    var requested = Set.copyOf(resourceIds);
    var entries = new ArrayList<>(snapshot.entries());
    if (entries.stream().anyMatch(entry -> !requested.contains(entry.resourceId()))) {
      throw failure(
          error(
              "INVENTORY_SOURCE_INVALID",
              "The inventory source returned data outside the authorization.",
              false));
    }
    entries.sort(Comparator.comparing(CatalogToolSource.InventoryEntry::resourceId));
    var inventory = new TreeMap<ResourceKey, BigDecimal>();
    for (var entry : entries) {
      if (entry.count().signum() > 0) {
        inventory.merge(
            new ResourceKey(
                ResourceRef.Kind.ITEM, entry.resourceId(), entry.componentsFingerprint()),
            entry.count(),
            BigDecimal::add);
      }
    }
    requestInventories.put(
        task.call.requestId(),
        new RequestInventory(
            view.generationId(), Collections.unmodifiableMap(inventory), grant.expiresAt));
    var serialized =
        entries.stream()
            .map(
                entry ->
                    map(
                        "resourceId", entry.resourceId(),
                        "componentsFingerprint", entry.componentsFingerprint(),
                        "count", entry.count()))
            .toList();
    return result(
        map(
            "generationId", view.generationId(),
            "authorizationId", authorizationId.toString(),
            "entries", serialized,
            "truncated", snapshot.truncated(),
            "warnings", mergeWarnings(view.warnings(), snapshot.warnings(), 32)));
  }

  private Map<String, Object> wirePlan(
      CatalogToolSource.CatalogView view,
      ProcessPlanner.PlanningResult planning,
      String resourceId,
      BigDecimal amount,
      boolean inventoryApplied,
      RunningTask task)
      throws InterruptedException {
    var routes = new ArrayList<Map<String, Object>>();
    var warnings = new ArrayList<>(view.warnings());
    for (var index = 0; index < planning.routes().size(); index++) {
      task.checkCancelled();
      try {
        routes.add(wire(planning.routes().get(index), index + 1, task));
      } catch (BoundedResultException exception) {
        warnings.add("A route exceeded the bounded client result and was omitted.");
      }
    }
    var best = planning.routes().get(0);
    var unresolved =
        wireAmounts(best.unresolved(), 64, warnings, "Unresolved resources were truncated.");
    var hasCycle =
        planning.routes().stream()
            .anyMatch(route -> route.issues().contains(ProcessPlanner.Issue.CYCLE));
    if (hasCycle) {
      warnings.add(
          "A dependency cycle was detected; the bounded planner does not expose an inferred cycle"
              + " path.");
    }
    var status =
        planning.status() == ProcessPlanner.Status.COMPLETE
                && routes.size() == planning.routes().size()
            ? "complete"
            : hasCycle
                ? "cycle"
                : planning.status() == ProcessPlanner.Status.UNRESOLVED ? "unresolved" : "partial";
    return map(
        "generationId", view.generationId(),
        "status", status,
        "target", amount(resourceId, amount),
        "routes", List.copyOf(routes),
        "unresolved", unresolved,
        "cycles", List.of(),
        "exploredNodes", planning.exploredNodes(),
        "inventoryApplied", inventoryApplied,
        "warnings", limitedWarnings(warnings, 64));
  }

  private Map<String, Object> wire(ProcessPlanner.Route route, int rank, RunningTask task)
      throws InterruptedException {
    if (route.steps().size() > 128
        || route.materials().size() > 64
        || route.workstations().size() > 32
        || route.issues().size() > 16) {
      throw new BoundedResultException();
    }
    var steps = new ArrayList<Map<String, Object>>(route.steps().size());
    for (var index = 0; index < route.steps().size(); index++) {
      task.checkCancelled();
      var step = route.steps().get(index);
      if (step.inputs().size() > 64 || step.outputs().size() > 64) {
        throw new BoundedResultException();
      }
      steps.add(
          map(
              "index", index,
              "processId", step.processId(),
              "batches", wireInteger(step.batches()),
              "inputs", wireAmounts(step.inputs(), 64, null, null),
              "outputs", wireAmounts(step.outputs(), 64, null, null)));
    }
    var workstations =
        route.workstations().stream().map(ResourceKey::id).distinct().sorted().toList();
    if (workstations.size() > 32) {
      throw new BoundedResultException();
    }
    return map(
        "rank", rank,
        "complete", route.complete(),
        "rankingReasons",
            List.of(
                route.complete() ? "complete_route" : "partial_route",
                "fewer_unresolved_resources",
                "fewer_process_steps",
                "stable_process_identity_tiebreak"),
        "steps", List.copyOf(steps),
        "materials", wireAmounts(route.materials(), 64, null, null),
        "inventoryUsed", wireAmounts(route.inventoryUsed(), 64, null, null),
        "unresolved", wireAmounts(route.unresolved(), 64, null, null),
        "workstations", workstations,
        "issues", route.issues().stream().map(CatalogToolExecutor::wire).sorted().toList());
  }

  private ClientToolResult emptyPlan(
      CatalogToolSource.CatalogView view,
      String status,
      String resourceId,
      BigDecimal amount,
      List<String> warnings) {
    return result(
        map(
            "generationId", view.generationId(),
            "status", status,
            "target", amount(resourceId, amount),
            "routes", List.of(),
            "unresolved", List.of(amount(resourceId, amount)),
            "cycles", List.of(),
            "exploredNodes", 0,
            "inventoryApplied", false,
            "warnings", limitedWarnings(warnings, 64)));
  }

  private ClientToolResult processResult(
      CatalogToolSource.CatalogView view,
      String status,
      List<Map<String, Object>> processes,
      boolean truncated) {
    return result(
        map(
            "generationId", view.generationId(),
            "status", status,
            "processes", processes,
            "truncated", truncated,
            "warnings", limitedWarnings(view.warnings(), 32)));
  }

  private ResourceSearchIndex searchIndex(CatalogToolSource.CatalogView view) {
    var cached = searchCache;
    if (cached != null && cached.snapshot == view.snapshot()) {
      return cached.index;
    }
    synchronized (this) {
      cached = searchCache;
      if (cached == null || cached.snapshot != view.snapshot()) {
        cached =
            new SearchCache(
                view.snapshot(),
                Objects.requireNonNull(source.searchIndex(view.snapshot()), "searchIndex"));
        searchCache = cached;
      }
      return cached.index;
    }
  }

  private CatalogToolSource.CatalogView view() {
    return Objects.requireNonNull(source.catalogView(), "catalogView");
  }

  private void pruneExpiredGrants() {
    var now = clock.instant();
    inventoryGrants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    requestInventories.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
  }

  private Map<ResourceKey, BigDecimal> requestInventory(UUID requestId, String generationId) {
    pruneExpiredGrants();
    var inventory = requestInventories.get(requestId);
    if (inventory == null) {
      return Map.of();
    }
    if (!inventory.generationId.equals(generationId)) {
      requestInventories.remove(requestId, inventory);
      return Map.of();
    }
    return inventory.amounts;
  }

  private void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("catalog tool executor is closed");
    }
  }

  private static List<String> sortedResourceIds(Collection<String> source) {
    Objects.requireNonNull(source, "resourceIds");
    if (source.isEmpty() || source.size() > 64) {
      throw new IllegalArgumentException("inventory resource ids are invalid");
    }
    var result = source.stream().map(Objects::requireNonNull).sorted().toList();
    if (new HashSet<>(result).size() != result.size()) {
      throw new IllegalArgumentException("inventory resource ids must be unique");
    }
    result.forEach(
        id -> {
          if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("inventory resource id is invalid");
          }
        });
    return result;
  }

  private static List<String> stringList(Map<String, Object> arguments, String field) {
    var value = arguments.get(field);
    if (!(value instanceof List<?> source)) {
      throw new IllegalArgumentException("client tool argument is invalid");
    }
    var result = new ArrayList<String>();
    for (var entry : source) {
      if (!(entry instanceof String text)) {
        throw new IllegalArgumentException("client tool argument is invalid");
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static String string(Map<String, Object> arguments, String field) {
    var value = arguments.get(field);
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("client tool argument is invalid");
    }
    return text;
  }

  private static int integer(Map<String, Object> arguments, String field) {
    try {
      var number = decimal(arguments, field);
      return number.intValueExact();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("client tool argument is invalid", exception);
    }
  }

  private static BigDecimal decimal(Map<String, Object> arguments, String field) {
    var value = arguments.get(field);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("client tool argument is invalid");
    }
    try {
      return value instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("client tool argument is invalid", exception);
    }
  }

  private static boolean produces(ProcessRecord process, String resourceId) {
    return process.outputs().stream()
        .anyMatch(output -> output.primary() && output.resource().id().equals(resourceId));
  }

  private static boolean uses(ProcessRecord process, String resourceId) {
    return process.inputs().stream()
            .flatMap(group -> group.alternatives().stream())
            .anyMatch(resource -> resource.id().equals(resourceId))
        || process.catalysts().stream()
            .anyMatch(catalyst -> catalyst.resource().id().equals(resourceId))
        || (process.energy() != null && process.energy().id().equals(resourceId))
        || process.workstations().stream().anyMatch(resource -> resource.id().equals(resourceId));
  }

  private static String processStatus(
      CatalogToolSource.CatalogView view, String requestedGeneration) {
    if (!requestedGeneration.equals(view.generationId())) {
      return "stale_generation";
    }
    return switch (view.state()) {
      case READY -> "ready";
      case RELOADING -> "reloading";
      case UNAVAILABLE -> "unavailable";
    };
  }

  private static String matchedBy(Set<ResourceSearchIndex.MatchReason> reasons) {
    if (reasons.contains(ResourceSearchIndex.MatchReason.EXACT_ID)) {
      return "exact_id";
    }
    if (reasons.contains(ResourceSearchIndex.MatchReason.EXACT_NAME)) {
      return "exact_name";
    }
    if (reasons.contains(ResourceSearchIndex.MatchReason.NAME_PREFIX)) {
      return "prefix";
    }
    return "fuzzy";
  }

  private static Map<String, Object> wire(ResourceRef resource) {
    var result = new LinkedHashMap<String, Object>();
    result.put("kind", wire(resource.kind()));
    result.put("id", resource.id());
    if (resource.componentsFingerprint() != null) {
      result.put("componentsFingerprint", resource.componentsFingerprint());
    }
    result.put("displayName", resource.displayName());
    if (resource.translationKey() != null) {
      result.put("translationKey", resource.translationKey());
    }
    result.put("modId", resource.modId());
    result.put("modName", resource.modName());
    result.put("modVersion", resource.modVersion());
    result.put("amount", resource.amount());
    result.put("unit", resource.unit());
    result.put("source", wire(resource.source()));
    return Collections.unmodifiableMap(result);
  }

  private static Map<String, Object> wire(ResourceRef.Source source) {
    return map(
        "layer", wire(source.layer()),
        "providerId", source.providerId(),
        "trust", source.trust().name(),
        "completeness", wire(source.completeness()),
        "generationId", source.generationId());
  }

  private static Map<String, Object> wire(ProcessRecord process) {
    return map(
        "processId", process.processId(),
        "categoryId", process.categoryId(),
        "displayName", process.displayName(),
        "inputRelation", "and",
        "workstations", process.workstations().stream().map(CatalogToolExecutor::wire).toList(),
        "inputs",
            process.inputs().stream()
                .map(
                    group ->
                        map(
                            "groupId", group.groupId(),
                            "relation", "or",
                            "alternatives",
                                group.alternatives().stream()
                                    .map(CatalogToolExecutor::wire)
                                    .toList()))
                .toList(),
        "catalysts",
            process.catalysts().stream()
                .map(
                    catalyst ->
                        map(
                            "resource", wire(catalyst.resource()),
                            "consumed", catalyst.consumed(),
                            "returnedResource",
                                catalyst.returnedResource() == null
                                    ? null
                                    : wire(catalyst.returnedResource())))
                .toList(),
        "outputs",
            process.outputs().stream()
                .map(
                    output ->
                        map(
                            "resource", wire(output.resource()),
                            "probability", output.probability(),
                            "primary", output.primary()))
                .toList(),
        "durationTicks", process.durationTicks(),
        "energy", process.energy() == null ? null : wire(process.energy()),
        "conditions", process.conditions(),
        "stages",
            process.stages().stream()
                .map(
                    stage ->
                        map(
                            "index", stage.index(),
                            "description", stage.description(),
                            "inputGroupIds", stage.inputGroupIds()))
                .toList(),
        "source", wire(process.source()),
        "plannable", process.plannable(),
        "warnings", process.warnings());
  }

  private static List<Map<String, Object>> wireAmounts(
      Map<ResourceKey, BigDecimal> amounts,
      int maximum,
      List<String> warnings,
      String truncationWarning) {
    var ordered = new TreeMap<>(amounts);
    if (ordered.size() > maximum && warnings == null) {
      throw new BoundedResultException();
    }
    if (ordered.size() > maximum) {
      warnings.add(truncationWarning);
    }
    var result = new ArrayList<Map<String, Object>>();
    for (var entry : ordered.entrySet()) {
      if (result.size() == maximum) {
        break;
      }
      result.add(amount(entry.getKey().id(), wireAmount(entry.getValue())));
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> amount(String resourceId, BigDecimal amount) {
    return map("resourceId", resourceId, "amount", wireAmount(amount));
  }

  private static BigDecimal wireAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0 || amount.compareTo(MAXIMUM_WIRE_AMOUNT) > 0) {
      throw new BoundedResultException();
    }
    return amount.stripTrailingZeros();
  }

  private static int wireInteger(BigDecimal amount) {
    try {
      var integer = amount.setScale(0, RoundingMode.UNNECESSARY).intValueExact();
      if (integer < 1 || integer > 1_000_000_000) {
        throw new BoundedResultException();
      }
      return integer;
    } catch (ArithmeticException exception) {
      throw new BoundedResultException();
    }
  }

  private static String wire(ProcessPlanner.Issue issue) {
    return issue.name().toLowerCase(Locale.ROOT);
  }

  private static String wire(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static List<String> mergeWarnings(List<String> first, List<String> second, int maximum) {
    var result = new ArrayList<String>();
    result.addAll(first);
    result.addAll(second);
    return limitedWarnings(result, maximum);
  }

  private static List<String> appendWarning(List<String> source, String warning, int maximum) {
    var result = new ArrayList<>(source);
    result.add(warning);
    return limitedWarnings(result, maximum);
  }

  private static List<String> limitedWarnings(List<String> source, int maximum) {
    return source.stream().distinct().limit(maximum).toList();
  }

  private static ClientToolResult result(Map<String, Object> value) {
    return new ClientToolResult(value);
  }

  private static ClientToolError rejected(String code, String message, boolean retryable) {
    return new ClientToolError(ClientToolError.Status.REJECTED, code, message, retryable);
  }

  private static ClientToolError error(String code, String message, boolean retryable) {
    return new ClientToolError(ClientToolError.Status.FAILED, code, message, retryable);
  }

  private static ToolFailure failure(ClientToolError error) {
    return new ToolFailure(error);
  }

  private static Map<String, Object> map(Object... entries) {
    if ((entries.length & 1) != 0) {
      throw new IllegalArgumentException("map entries must be key-value pairs");
    }
    var result = new LinkedHashMap<String, Object>();
    for (var index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return Collections.unmodifiableMap(result);
  }

  private static ExecutorService newToolExecutor() {
    return Executors.newSingleThreadExecutor(
        task -> {
          var thread = new Thread(task, "agma-standalone-client-tools");
          thread.setDaemon(true);
          return thread;
        });
  }

  public record InventoryAuthorization(
      UUID authorizationId,
      UUID requestId,
      UUID subjectId,
      String generationId,
      List<String> resourceIds,
      Instant expiresAt) {
    public InventoryAuthorization {
      Objects.requireNonNull(authorizationId, "authorizationId");
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(subjectId, "subjectId");
      Objects.requireNonNull(generationId, "generationId");
      resourceIds = List.copyOf(resourceIds);
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  private record InventoryGrant(
      UUID requestId,
      UUID subjectId,
      String generationId,
      List<String> resourceIds,
      Instant expiresAt) {}

  private record RequestInventory(
      String generationId, Map<ResourceKey, BigDecimal> amounts, Instant expiresAt) {}

  private record Dependency(ResourceKey key, int depth) {}

  private record SearchCache(CatalogSnapshot snapshot, ResourceSearchIndex index) {}

  private static final class RunningTask {
    private final ClientToolCall call;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<ClientToolOutcome> result = new CompletableFuture<>();
    private volatile Future<?> future;

    private RunningTask(ClientToolCall call) {
      this.call = call;
    }

    private void attach(Future<?> supplied) {
      future = supplied;
      if (cancelled.get()) {
        supplied.cancel(true);
      }
    }

    private void cancel() {
      cancelled.set(true);
      var submitted = future;
      if (submitted != null) {
        submitted.cancel(true);
      }
      result.cancel(false);
    }

    private boolean cancelled() {
      return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    private void checkCancelled() {
      if (cancelled()) {
        throw new CancellationException("client tool execution was cancelled");
      }
    }

    private boolean matches(ClientToolCancellation cancellation) {
      return call.requestId().equals(cancellation.requestId())
          && call.toolCallId().equals(cancellation.toolCallId())
          && call.subjectId().equals(cancellation.subjectId())
          && call.tool().equals(cancellation.tool())
          && call.sequence() == cancellation.sequence();
    }
  }

  @SuppressWarnings("serial")
  private static final class ToolFailure extends RuntimeException {
    private final ClientToolError error;

    private ToolFailure(ClientToolError error) {
      super(null, null, false, false);
      this.error = error;
    }
  }

  @SuppressWarnings("serial")
  private static final class BoundedResultException extends RuntimeException {
    private BoundedResultException() {
      super(null, null, false, false);
    }
  }
}
