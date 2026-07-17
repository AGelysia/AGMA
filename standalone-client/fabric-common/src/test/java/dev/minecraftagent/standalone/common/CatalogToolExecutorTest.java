package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CatalogToolExecutorTest {
  private static final String GENERATION = "generation-1";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SUBJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);

  public static void main(String[] arguments) throws Exception {
    var test = new CatalogToolExecutorTest();
    test.serializesSearchLookupUsesAndPlanDeterministically();
    test.plansTheRequestedLocalAmountAndPresentsStepFlows();
    test.canonicalizesObservedTargetsAgainstTheCurrentGeneration();
    test.reportsUnavailableAndStaleCatalogStatesWithoutReadingData();
    test.inventoryRequiresAnExactSingleUseAuthorization();
    test.cancellationInterruptsAnAuthorizedInventoryRead();
  }

  @Test
  void plansTheRequestedLocalAmountAndPresentsStepFlows() throws Exception {
    var view = readyView();
    var source = new FakeSource(view);
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var target =
          view.snapshot().resources().stream()
              .filter(resource -> resource.id().equals("minecraft:iron_pickaxe"))
              .findFirst()
              .orElseThrow();
      var localPlan =
          executor
              .planLocal(target, new BigDecimal("3"))
              .toCompletableFuture()
              .get(3, TimeUnit.SECONDS);
      assertEquals(new BigDecimal("3"), localPlan.requestedAmount());

      var sections = LocalPlanPresentation.sections(localPlan, view.snapshot(), 0);
      var ranking =
          sections.stream()
              .filter(section -> section.titleKey().equals("screen.agma_standalone.plan_ranking"))
              .findFirst()
              .orElseThrow();
      assertEquals("rank=1: complete route", ranking.entries().get(0));
      var steps =
          sections.stream()
              .filter(section -> section.titleKey().equals("screen.agma_standalone.plan_steps"))
              .findFirst()
              .orElseThrow()
              .entries();
      assertTrue(steps.contains("   input 9 x minecraft:iron_ingot"));
      assertTrue(steps.contains("   input 6 x minecraft:stick"));
      assertTrue(steps.contains("   output 3 x minecraft:iron_pickaxe"));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void canonicalizesObservedTargetsAgainstTheCurrentGeneration() {
    var view = readyView();
    var source = new FakeSource(view);
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var catalogTarget =
          view.snapshot().resources().stream()
              .filter(resource -> resource.id().equals("minecraft:iron_pickaxe"))
              .findFirst()
              .orElseThrow();
      var observed =
          new ResourceRef(
              catalogTarget.kind(),
              catalogTarget.id(),
              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "Private Custom Name",
              catalogTarget.translationKey(),
              catalogTarget.modId(),
              catalogTarget.modName(),
              catalogTarget.modVersion(),
              catalogTarget.amount(),
              catalogTarget.unit(),
              catalogTarget.source());
      assertEquals(catalogTarget, executor.canonicalPlanningTarget(observed).orElseThrow());

      var sourceMetadata = catalogTarget.source();
      var staleSource =
          new ResourceRef.Source(
              sourceMetadata.layer(),
              sourceMetadata.providerId(),
              sourceMetadata.trust(),
              sourceMetadata.completeness(),
              "generation-2");
      var stale =
          new ResourceRef(
              catalogTarget.kind(),
              catalogTarget.id(),
              null,
              catalogTarget.displayName(),
              catalogTarget.translationKey(),
              catalogTarget.modId(),
              catalogTarget.modName(),
              catalogTarget.modVersion(),
              catalogTarget.amount(),
              catalogTarget.unit(),
              staleSource);
      assertTrue(executor.canonicalPlanningTarget(stale).isEmpty());
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void serializesSearchLookupUsesAndPlanDeterministically() throws Exception {
    var source = new FakeSource(readyView());
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var search =
          result(
              executor,
              call(
                  10,
                  "game.resource.search",
                  Map.of("query", "minecraft:iron_pickaxe", "limit", 5)));
      assertEquals(
          Set.of(
              "generationId",
              "visibility",
              "completeness",
              "candidates",
              "ambiguous",
              "truncated",
              "warnings"),
          search.keySet());
      assertEquals("multiplayer", search.get("visibility"));
      assertEquals("partial", search.get("completeness"));
      assertFalse((boolean) search.get("ambiguous"));
      var candidates = list(search.get("candidates"));
      assertEquals(1, candidates.size());
      var candidate = object(candidates.get(0));
      assertEquals(Set.of("rank", "score", "matchedBy", "resource"), candidate.keySet());
      assertEquals(1, candidate.get("rank"));
      assertEquals(new BigDecimal("1.0000"), candidate.get("score"));
      assertEquals("exact_id", candidate.get("matchedBy"));
      assertEquals("minecraft:iron_pickaxe", object(candidate.get("resource")).get("id"));

      var lookup =
          result(
              executor,
              call(
                  11,
                  "game.process.lookup",
                  Map.of(
                      "resourceId",
                      "minecraft:iron_pickaxe",
                      "generationId",
                      GENERATION,
                      "limit",
                      16)));
      assertEquals(
          Set.of("generationId", "status", "processes", "truncated", "warnings"), lookup.keySet());
      assertEquals("ready", lookup.get("status"));
      var process = object(list(lookup.get("processes")).get(0));
      assertEquals(
          Set.of(
              "processId",
              "categoryId",
              "displayName",
              "inputRelation",
              "workstations",
              "inputs",
              "catalysts",
              "outputs",
              "durationTicks",
              "energy",
              "conditions",
              "stages",
              "source",
              "plannable",
              "warnings"),
          process.keySet());
      assertEquals("and", process.get("inputRelation"));
      assertEquals("or", object(list(process.get("inputs")).get(0)).get("relation"));

      var uses =
          result(
              executor,
              call(
                  12,
                  "game.process.uses",
                  Map.of(
                      "resourceId",
                      "minecraft:iron_ingot",
                      "generationId",
                      GENERATION,
                      "limit",
                      16)));
      assertEquals(
          "agma:iron_pickaxe", object(list(uses.get("processes")).get(0)).get("processId"));

      var planCall =
          call(
              13,
              "game.process.plan",
              ConnectorEnvelopeCodec.map(
                  "resourceId",
                  "minecraft:iron_pickaxe",
                  "amount",
                  2,
                  "generationId",
                  GENERATION,
                  "maxDepth",
                  12,
                  "maxNodes",
                  2000,
                  "topK",
                  3));
      var first = result(executor, planCall);
      var second = result(executor, call(14, planCall.tool(), planCall.arguments()));
      assertEquals(first, second);
      assertEquals(
          Set.of(
              "generationId",
              "status",
              "target",
              "routes",
              "unresolved",
              "cycles",
              "exploredNodes",
              "inventoryApplied",
              "warnings"),
          first.keySet());
      assertEquals(Set.of("resourceId", "amount"), object(first.get("target")).keySet());
      assertEquals("complete", first.get("status"));
      assertEquals(false, first.get("inventoryApplied"));
      var route = object(list(first.get("routes")).get(0));
      assertEquals(
          Set.of(
              "rank",
              "complete",
              "rankingReasons",
              "steps",
              "materials",
              "inventoryUsed",
              "unresolved",
              "workstations",
              "issues"),
          route.keySet());
      assertEquals(List.of("minecraft:crafting_table"), route.get("workstations"));
      var step = object(list(route.get("steps")).get(0));
      assertEquals(Set.of("index", "processId", "batches", "inputs", "outputs"), step.keySet());
      assertEquals(2, step.get("batches"));
      assertEquals(
          List.of(
              Map.of("resourceId", "minecraft:iron_ingot", "amount", new BigDecimal("6")),
              Map.of("resourceId", "minecraft:stick", "amount", new BigDecimal("4"))),
          step.get("inputs"));

      var boundedArguments = new java.util.LinkedHashMap<>(planCall.arguments());
      boundedArguments.put("maxNodes", 1);
      var bounded = result(executor, call(15, planCall.tool(), boundedArguments));
      assertEquals("partial", bounded.get("status"));
      assertEquals(1, bounded.get("exploredNodes"));
      assertEquals(
          List.of("node_budget"), object(list(bounded.get("routes")).get(0)).get("issues"));
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void reportsUnavailableAndStaleCatalogStatesWithoutReadingData() throws Exception {
    var source =
        new FakeSource(
            CatalogToolSource.CatalogView.unavailable(
                "generation-2",
                CatalogToolSource.State.RELOADING,
                CatalogToolSource.Visibility.NO_WORLD,
                List.of("Catalog reload is in progress.")));
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var search =
          result(executor, call(20, "game.resource.search", Map.of("query", "iron", "limit", 5)));
      assertEquals("unavailable", search.get("completeness"));
      assertEquals(List.of(), search.get("candidates"));

      var stale =
          result(
              executor,
              call(
                  21,
                  "game.process.lookup",
                  Map.of(
                      "resourceId",
                      "minecraft:iron_pickaxe",
                      "generationId",
                      GENERATION,
                      "limit",
                      5)));
      assertEquals("generation-2", stale.get("generationId"));
      assertEquals("stale_generation", stale.get("status"));
      assertEquals(List.of(), stale.get("processes"));
      assertEquals(0, source.inventoryReads);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void inventoryRequiresAnExactSingleUseAuthorization() throws Exception {
    var source = new FakeSource(readyView());
    source.inventory =
        new CatalogToolSource.InventorySnapshot(
            List.of(
                new CatalogToolSource.InventoryEntry(
                    "minecraft:iron_ingot", new BigDecimal("7"), List.of(5, 1))),
            false,
            List.of());
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var unauthorized =
          outcome(
              executor, inventoryCall(30, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")));
      assertEquals(
          "INVENTORY_AUTHORIZATION_REQUIRED",
          assertInstanceOf(ClientToolError.class, unauthorized).code());
      assertEquals(0, source.inventoryReads);

      var authorization =
          executor.authorizeInventoryOnce(
              REQUEST_ID, SUBJECT_ID, List.of("minecraft:iron_ingot"), Duration.ofSeconds(30));
      var authorized = result(executor, inventoryCall(31, authorization.authorizationId()));
      assertEquals(
          Set.of("generationId", "authorizationId", "entries", "truncated", "warnings"),
          authorized.keySet());
      assertEquals(authorization.authorizationId().toString(), authorized.get("authorizationId"));
      assertEquals(
          Set.of("resourceId", "componentsFingerprint", "count"),
          object(list(authorized.get("entries")).get(0)).keySet());
      assertEquals(
          List.of(
              ConnectorEnvelopeCodec.map(
                  "resourceId",
                  "minecraft:iron_ingot",
                  "componentsFingerprint",
                  null,
                  "count",
                  new BigDecimal("7"))),
          authorized.get("entries"));
      assertEquals(1, source.inventoryReads);

      var plan =
          result(
              executor,
              call(
                  33,
                  "game.process.plan",
                  Map.of(
                      "resourceId",
                      "minecraft:iron_pickaxe",
                      "amount",
                      2,
                      "generationId",
                      GENERATION,
                      "maxDepth",
                      12,
                      "maxNodes",
                      2000,
                      "topK",
                      3)));
      assertEquals(true, plan.get("inventoryApplied"));
      var route = object(list(plan.get("routes")).get(0));
      assertEquals(
          List.of(Map.of("resourceId", "minecraft:stick", "amount", new BigDecimal("4"))),
          route.get("materials"));

      var replay = outcome(executor, inventoryCall(32, authorization.authorizationId()));
      assertEquals(
          "INVENTORY_AUTHORIZATION_REQUIRED",
          assertInstanceOf(ClientToolError.class, replay).code());
      assertEquals(1, source.inventoryReads);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void cancellationInterruptsAnAuthorizedInventoryRead() throws Exception {
    var source = new FakeSource(readyView());
    source.blockInventory = true;
    var worker = Executors.newSingleThreadExecutor();
    try (var executor = new CatalogToolExecutor(source, worker, CLOCK)) {
      var authorization =
          executor.authorizeInventoryOnce(
              REQUEST_ID, SUBJECT_ID, List.of("minecraft:iron_ingot"), Duration.ofSeconds(30));
      var call = inventoryCall(40, authorization.authorizationId());
      var future = executor.execute(call).toCompletableFuture();
      assertTrue(source.inventoryEntered.await(3, TimeUnit.SECONDS));
      executor.cancel(
          new ClientToolCancellation(
              call.requestId(),
              call.toolCallId(),
              call.subjectId(),
              call.tool(),
              call.sequence(),
              ClientToolCancellation.Reason.REQUEST_CANCELLED));
      assertThrows(CancellationException.class, future::join);
      assertTrue(source.inventoryCancelled.await(3, TimeUnit.SECONDS));
      assertTrue(source.sawCancellation.get());
    } finally {
      worker.shutdownNow();
    }
  }

  private static CatalogToolSource.CatalogView readyView() {
    return CatalogToolSource.CatalogView.ready(
        CatalogToolSource.Visibility.MULTIPLAYER,
        CatalogToolSource.Completeness.PARTIAL,
        catalog(),
        List.of("Client-visible recipes may omit server-only rules."));
  }

  private static CatalogSnapshot catalog() {
    var registry =
        new ResourceRef.Source(
            ResourceRef.Layer.CLIENT_REGISTRY,
            "minecraft_registry",
            ResourceRef.Trust.L0B,
            ResourceRef.Completeness.COMPLETE,
            GENERATION);
    var recipe =
        new ResourceRef.Source(
            ResourceRef.Layer.CLIENT_RECIPE,
            "vanilla_client",
            ResourceRef.Trust.L1,
            ResourceRef.Completeness.PARTIAL,
            GENERATION);
    var resources =
        List.of(
            resource("minecraft:crafting_table", "Crafting Table", BigDecimal.ONE, registry),
            resource("minecraft:iron_ingot", "Iron Ingot", BigDecimal.ONE, registry),
            resource("minecraft:iron_pickaxe", "Iron Pickaxe", BigDecimal.ONE, registry),
            resource("minecraft:stick", "Stick", BigDecimal.ONE, registry));
    var process =
        new ProcessRecord(
            "agma:iron_pickaxe",
            "minecraft:crafting",
            "Iron Pickaxe",
            List.of(resource("minecraft:crafting_table", "Crafting Table", BigDecimal.ONE, recipe)),
            List.of(
                new ProcessRecord.InputGroup(
                    "iron",
                    List.of(
                        resource(
                            "minecraft:iron_ingot", "Iron Ingot", new BigDecimal("3"), recipe))),
                new ProcessRecord.InputGroup(
                    "stick",
                    List.of(resource("minecraft:stick", "Stick", new BigDecimal("2"), recipe)))),
            List.of(),
            List.of(
                new ProcessRecord.Output(
                    resource("minecraft:iron_pickaxe", "Iron Pickaxe", BigDecimal.ONE, recipe),
                    BigDecimal.ONE,
                    true)),
            null,
            null,
            List.of(),
            List.of(),
            recipe,
            true,
            List.of("Client-visible recipe."));
    return new CatalogSnapshot(
        GENERATION,
        "0000000000000000000000000000000000000000000000000000000000000000",
        Instant.parse("2026-07-17T00:00:00Z"),
        resources,
        List.of(process));
  }

  private static ResourceRef resource(
      String id, String name, BigDecimal amount, ResourceRef.Source source) {
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id,
        null,
        name,
        "item." + id.replace(':', '.'),
        "minecraft",
        "Minecraft",
        "test",
        amount,
        "item",
        source);
  }

  private static ClientToolCall inventoryCall(int seed, UUID authorizationId) {
    return call(
        seed,
        "game.inventory.snapshot",
        Map.of(
            "authorizationId",
            authorizationId.toString(),
            "generationId",
            GENERATION,
            "resourceIds",
            List.of("minecraft:iron_ingot")));
  }

  private static ClientToolCall call(int seed, String tool, Map<String, Object> arguments) {
    return new ClientToolCall(
        REQUEST_ID, new UUID(0x4000L + seed, 0x8000L + seed), SUBJECT_ID, tool, 0, arguments);
  }

  private static ClientToolOutcome outcome(CatalogToolExecutor executor, ClientToolCall call)
      throws Exception {
    return executor.execute(call).toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  private static Map<String, Object> result(CatalogToolExecutor executor, ClientToolCall call)
      throws Exception {
    var result = assertInstanceOf(ClientToolResult.class, outcome(executor, call)).result();
    ClientToolPayloads.validateResult(call.tool(), result);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private static final class FakeSource implements CatalogToolSource {
    private volatile CatalogView view;
    private volatile InventorySnapshot inventory =
        new InventorySnapshot(List.of(), false, List.of());
    private volatile int inventoryReads;
    private volatile boolean blockInventory;
    private final CountDownLatch inventoryEntered = new CountDownLatch(1);
    private final CountDownLatch inventoryCancelled = new CountDownLatch(1);
    private final AtomicBoolean sawCancellation = new AtomicBoolean();

    private FakeSource(CatalogView view) {
      this.view = view;
    }

    @Override
    public CatalogView catalogView() {
      return view;
    }

    @Override
    public InventorySnapshot inventorySnapshot(InventoryRequest request) throws Exception {
      inventoryReads++;
      if (!blockInventory) {
        return inventory;
      }
      inventoryEntered.countDown();
      try {
        while (true) {
          if (request.cancellation().cancelled()) {
            sawCancellation.set(true);
            request.cancellation().throwIfCancelled();
          }
          Thread.sleep(5);
        }
      } finally {
        sawCancellation.set(sawCancellation.get() || request.cancellation().cancelled());
        inventoryCancelled.countDown();
      }
    }
  }
}
