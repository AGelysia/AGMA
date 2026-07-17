package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CatalogToolSource;
import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.adapter.CatalogAssembler;
import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/** Owns the immutable client-visible catalog for one 1.21.11 connection generation. */
public final class StandaloneCatalogService implements AutoCloseable, CatalogToolSource {
  private static final int MAXIMUM_ALTERNATIVES = 64;
  private static final String PROVIDER_ID = "vanilla_client";

  private final AtomicLong generationSequence = new AtomicLong();
  private final CatalogPublisher publisher = new CatalogPublisher();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          task -> {
            var thread = new Thread(task, "agma-standalone-catalog-1.21.11");
            thread.setDaemon(true);
            return thread;
          });

  private volatile ResourceSearchIndex searchIndex;

  public void refresh(Minecraft minecraft) {
    var generationId = "mc12111-" + generationSequence.incrementAndGet();
    var entries = new LinkedHashMap<Integer, RecipeDisplayEntry>();
    if (minecraft.player != null) {
      for (var collection : minecraft.player.getRecipeBook().getCollections()) {
        for (var entry : collection.getRecipes()) {
          entries.putIfAbsent(entry.id().index(), entry);
        }
      }
    }
    ContextMap displayContext =
        minecraft.level == null ? null : SlotDisplayContext.fromLevel(minecraft.level);
    var handle =
        publisher.rebuild(
            executor,
            (cancellation, progress) ->
                build(
                    generationId,
                    List.copyOf(entries.values()),
                    displayContext,
                    cancellation,
                    progress));
    handle
        .future()
        .whenComplete(
            (snapshot, failure) -> {
              if (failure == null
                  && snapshot != null
                  && publisher
                      .current()
                      .map(current -> current.generationId().equals(snapshot.generationId()))
                      .orElse(false)) {
                searchIndex = new ResourceSearchIndex(snapshot);
              }
            });
  }

  public void invalidate() {
    searchIndex = null;
    generationSequence.incrementAndGet();
    publisher.invalidate();
  }

  public CatalogPublisher.Progress progress() {
    return publisher.progress();
  }

  public Optional<CatalogSnapshot> current() {
    return publisher.current();
  }

  public ResourceSearchIndex.SearchResult search(String query, int maximumResults) {
    var snapshot =
        publisher
            .current()
            .orElseThrow(() -> new IllegalStateException("Client catalog is not ready"));
    var index = searchIndex;
    if (index == null) {
      throw new IllegalStateException("Client catalog search index is not ready");
    }
    return index.search(snapshot.generationId(), query, maximumResults);
  }

  @Override
  public CatalogView catalogView() {
    var minecraft = Minecraft.getInstance();
    var visibility = visibility(minecraft);
    var snapshot = publisher.current().orElse(null);
    if (snapshot != null) {
      var warnings =
          OptionalViewerRegistry.selected().isPresent()
              ? List.of("CLIENT_VISIBLE_DATA_ONLY", "RECIPE_VIEWER_PARTIAL")
              : List.of("CLIENT_VISIBLE_DATA_ONLY", "NO_RECIPE_VIEWER");
      return CatalogView.ready(visibility, Completeness.PARTIAL, snapshot, warnings);
    }
    var state =
        publisher.progress().phase() == CatalogPublisher.Phase.BUILDING
            ? State.RELOADING
            : State.UNAVAILABLE;
    return CatalogView.unavailable(
        "mc12111-" + generationSequence.get(),
        state,
        visibility,
        List.of(state == State.RELOADING ? "CATALOG_RELOADING" : "CATALOG_UNAVAILABLE"));
  }

  @Override
  public InventorySnapshot inventorySnapshot(InventoryRequest request) throws Exception {
    var minecraft = Minecraft.getInstance();
    if (minecraft.isSameThread()) {
      return inventoryOnClient(minecraft, request);
    }
    var result = new CompletableFuture<InventorySnapshot>();
    minecraft.execute(
        () -> {
          try {
            result.complete(inventoryOnClient(minecraft, request));
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result.get(2, TimeUnit.SECONDS);
  }

  public ContextSelection context(Minecraft minecraft) {
    var hovered = OptionalViewerRegistry.hoveredItemId().flatMap(this::resolve);
    var held =
        minecraft.player == null
            ? Optional.<ResourceRef>empty()
            : resolve(minecraft.player.getInventory().getSelectedItem());
    Optional<ResourceRef> pointed = Optional.empty();
    if (minecraft.level != null && minecraft.hitResult instanceof BlockHitResult blockHit) {
      pointed =
          resolve(new ItemStack(minecraft.level.getBlockState(blockHit.getBlockPos()).getBlock()));
    } else if (minecraft.hitResult instanceof EntityHitResult entityHit) {
      pointed = resolve(entityHit.getEntity().getPickResult());
    }
    return new ContextSelection(hovered, held, pointed);
  }

  private Optional<ResourceRef> resolve(String resourceId) {
    var snapshot = publisher.current().orElse(null);
    return snapshot == null
        ? Optional.empty()
        : snapshot.resource(new ResourceKey(ResourceRef.Kind.ITEM, resourceId, null));
  }

  private InventorySnapshot inventoryOnClient(Minecraft minecraft, InventoryRequest request)
      throws InterruptedException {
    request.cancellation().throwIfCancelled();
    var snapshot = publisher.current().orElse(null);
    if (snapshot == null || !snapshot.generationId().equals(request.generationId())) {
      throw new IllegalStateException("Inventory request uses a stale catalog generation");
    }
    if (minecraft.player == null) {
      return new InventorySnapshot(List.of(), false, List.of("NO_PLAYER_INVENTORY"));
    }
    var requested = Set.copyOf(request.resourceIds());
    var counts = new java.util.TreeMap<InventoryKey, Long>();
    var slots = new java.util.TreeMap<InventoryKey, List<Integer>>();
    var inventory = minecraft.player.getInventory();
    for (var slot = 0; slot < inventory.getContainerSize(); slot++) {
      request.cancellation().throwIfCancelled();
      var stack = inventory.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      var id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      if (requested.contains(id)) {
        var key = new InventoryKey(id, StackFingerprint.of(stack));
        counts.merge(key, (long) stack.getCount(), Long::sum);
        slots.computeIfAbsent(key, ignored -> new ArrayList<>()).add(slot);
      }
    }
    var entries = new ArrayList<InventoryEntry>();
    for (var entry : counts.entrySet()) {
      entries.add(
          new InventoryEntry(
              entry.getKey().resourceId,
              entry.getKey().componentsFingerprint,
              BigDecimal.valueOf(entry.getValue()),
              slots.get(entry.getKey())));
    }
    return new InventorySnapshot(entries, false, List.of());
  }

  private static Visibility visibility(Minecraft minecraft) {
    if (minecraft.level == null) {
      return minecraft.getConnection() == null ? Visibility.MAIN_MENU : Visibility.NO_WORLD;
    }
    return minecraft.hasSingleplayerServer() ? Visibility.SINGLEPLAYER : Visibility.MULTIPLAYER;
  }

  @Override
  public void close() {
    searchIndex = null;
    publisher.close();
    executor.shutdownNow();
  }

  private CatalogSnapshot build(
      String generationId,
      List<RecipeDisplayEntry> entries,
      ContextMap displayContext,
      CatalogPublisher.Cancellation cancellation,
      CatalogPublisher.ProgressListener progress)
      throws InterruptedException {
    var itemIds = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
    itemIds.sort(Comparator.comparing(Identifier::toString));
    if (itemIds.size() > CatalogSnapshot.MAXIMUM_RESOURCES) {
      itemIds.subList(CatalogSnapshot.MAXIMUM_RESOURCES, itemIds.size()).clear();
    }
    var resources = new ArrayList<ResourceRef>(itemIds.size());
    var registrySource =
        new ResourceRef.Source(
            ResourceRef.Layer.CLIENT_REGISTRY,
            PROVIDER_ID,
            ResourceRef.Trust.L0B,
            ResourceRef.Completeness.COMPLETE,
            generationId);
    var total = (long) itemIds.size() + Math.min(entries.size(), CatalogSnapshot.MAXIMUM_PROCESSES);
    for (var index = 0; index < itemIds.size(); index++) {
      cancellation.throwIfCancelled();
      var id = itemIds.get(index);
      var item = BuiltInRegistries.ITEM.getValue(id);
      if (item != null) {
        resources.add(resource(item, id, BigDecimal.ONE, registrySource));
      }
      if ((index & 255) == 0) {
        progress.update(index, total);
      }
    }

    var knownIds =
        itemIds.stream().map(Identifier::toString).collect(java.util.stream.Collectors.toSet());
    var processSource =
        new ResourceRef.Source(
            ResourceRef.Layer.CLIENT_RECIPE,
            PROVIDER_ID,
            ResourceRef.Trust.L1,
            ResourceRef.Completeness.PARTIAL,
            generationId);
    var processes = new ArrayList<ProcessRecord>();
    if (displayContext != null) {
      entries.stream()
          .sorted(Comparator.comparingInt(entry -> entry.id().index()))
          .limit(CatalogSnapshot.MAXIMUM_PROCESSES)
          .forEach(
              entry -> {
                if (!cancellation.cancelled()) {
                  toProcess(entry, displayContext, processSource, knownIds)
                      .ifPresent(processes::add);
                }
              });
    }
    cancellation.throwIfCancelled();
    progress.update(total, total);
    var selected =
        new CatalogAdapter.Contribution(PROVIDER_ID, generationId, List.of(), processes, List.of());
    var viewer = OptionalViewerRegistry.selected().orElse(null);
    if (viewer != null) {
      try {
        selected = viewer.capture(generationId, cancellation, progress);
      } catch (Exception | LinkageError failure) {
        selected =
            new CatalogAdapter.Contribution(
                PROVIDER_ID, generationId, List.of(), processes, List.of("VIEWER_CAPTURE_FAILED"));
      }
    }
    return new CatalogAssembler()
        .assemble(
            generationId,
            packFingerprint(),
            Instant.now(),
            new CatalogAdapter.Contribution(
                "minecraft_registry", generationId, resources, List.of(), List.of()),
            selected);
  }

  private Optional<ProcessRecord> toProcess(
      RecipeDisplayEntry entry,
      ContextMap context,
      ResourceRef.Source source,
      java.util.Set<String> knownIds) {
    var display = entry.display();
    var results = stacks(display.result(), context, source, knownIds);
    if (results.isEmpty()) {
      return Optional.empty();
    }
    var result = results.getFirst();
    var inputDisplays = inputDisplays(display);
    var inputs = new ArrayList<ProcessRecord.InputGroup>();
    var plannable = results.size() == 1 && !inputDisplays.isEmpty();
    for (var index = 0; index < inputDisplays.size(); index++) {
      var alternatives = stacks(inputDisplays.get(index), context, source, knownIds);
      if (alternatives.isEmpty()) {
        plannable = false;
      } else {
        if (alternatives.size() > MAXIMUM_ALTERNATIVES) {
          alternatives = new ArrayList<>(alternatives.subList(0, MAXIMUM_ALTERNATIVES));
          plannable = false;
        }
        inputs.add(new ProcessRecord.InputGroup("input_" + index, alternatives));
      }
    }
    var workstations = stacks(display.craftingStation(), context, source, knownIds);
    var duration =
        display instanceof FurnaceRecipeDisplay furnace ? (long) furnace.duration() : null;
    var warnings =
        plannable
            ? List.of("Client recipe display is partial and may omit server-only conditions")
            : List.of("Recipe display is ambiguous, opaque, or exceeds bounded alternatives");
    return Optional.of(
        new ProcessRecord(
            "agma:client_display_" + entry.id().index(),
            categoryId(display),
            bounded(result.displayName(), 220) + " recipe",
            workstations.isEmpty() ? List.of() : List.of(workstations.getFirst()),
            inputs,
            List.of(),
            List.of(new ProcessRecord.Output(result, BigDecimal.ONE, true)),
            duration,
            null,
            List.of(),
            List.of(),
            source,
            plannable,
            warnings));
  }

  private static List<SlotDisplay> inputDisplays(RecipeDisplay display) {
    if (display instanceof ShapedCraftingRecipeDisplay shaped) {
      return shaped.ingredients();
    }
    if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
      return shapeless.ingredients();
    }
    if (display instanceof FurnaceRecipeDisplay furnace) {
      return List.of(furnace.ingredient());
    }
    if (display instanceof StonecutterRecipeDisplay stonecutter) {
      return List.of(stonecutter.input());
    }
    if (display instanceof SmithingRecipeDisplay smithing) {
      return List.of(smithing.template(), smithing.base(), smithing.addition());
    }
    return List.of();
  }

  private static String categoryId(RecipeDisplay display) {
    if (display instanceof ShapedCraftingRecipeDisplay
        || display instanceof ShapelessCraftingRecipeDisplay) {
      return "minecraft:crafting";
    }
    if (display instanceof FurnaceRecipeDisplay) {
      return "minecraft:cooking";
    }
    if (display instanceof StonecutterRecipeDisplay) {
      return "minecraft:stonecutting";
    }
    if (display instanceof SmithingRecipeDisplay) {
      return "minecraft:smithing";
    }
    return "agma:unknown";
  }

  private static ArrayList<ResourceRef> stacks(
      SlotDisplay display,
      ContextMap context,
      ResourceRef.Source source,
      java.util.Set<String> knownIds) {
    var resources = new LinkedHashMap<String, ResourceRef>();
    for (var stack : display.resolveForStacks(context)) {
      if (stack.isEmpty()) {
        continue;
      }
      var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
      if (id != null && knownIds.contains(id.toString())) {
        resources.putIfAbsent(
            id.toString(),
            resource(
                stack.getItem(), id, BigDecimal.valueOf(Math.max(1, stack.getCount())), source));
      }
    }
    return new ArrayList<>(resources.values());
  }

  private Optional<ResourceRef> resolve(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return Optional.empty();
    }
    var snapshot = publisher.current().orElse(null);
    if (snapshot == null) {
      return Optional.empty();
    }
    var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    if (id == null) {
      return Optional.empty();
    }
    var fingerprint = StackFingerprint.of(stack);
    var exact =
        snapshot.resource(new ResourceKey(ResourceRef.Kind.ITEM, id.toString(), fingerprint));
    if (exact.isPresent() || fingerprint == null) {
      return exact;
    }
    return snapshot
        .resource(new ResourceKey(ResourceRef.Kind.ITEM, id.toString(), null))
        .map(
            base ->
                new ResourceRef(
                    base.kind(),
                    base.id(),
                    fingerprint,
                    bounded(stack.getHoverName().getString(), 512),
                    base.translationKey(),
                    base.modId(),
                    base.modName(),
                    base.modVersion(),
                    BigDecimal.valueOf(Math.max(1, stack.getCount())),
                    base.unit(),
                    base.source()));
  }

  private static ResourceRef resource(
      Item item, Identifier id, BigDecimal amount, ResourceRef.Source source) {
    var metadata = modMetadata(id.getNamespace());
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id.toString(),
        null,
        bounded(item.getName().getString(), 512),
        bounded(item.getDescriptionId(), 256),
        metadata.id(),
        metadata.name(),
        metadata.version(),
        amount,
        "item",
        source);
  }

  private static ModMetadata modMetadata(String namespace) {
    return FabricLoader.getInstance()
        .getModContainer(namespace)
        .map(StandaloneCatalogService::metadata)
        .orElseGet(
            () -> {
              var safeId = namespace.matches("^[a-z][a-z0-9_.-]{0,127}$") ? namespace : "unknown";
              return new ModMetadata(safeId, bounded(namespace, 128), "unknown");
            });
  }

  private static ModMetadata metadata(ModContainer container) {
    var metadata = container.getMetadata();
    return new ModMetadata(
        metadata.getId(),
        bounded(metadata.getName(), 128),
        bounded(metadata.getVersion().getFriendlyString(), 128));
  }

  private static String packFingerprint() {
    var entries =
        FabricLoader.getInstance().getAllMods().stream()
            .map(
                container ->
                    container.getMetadata().getId()
                        + "="
                        + container.getMetadata().getVersion().getFriendlyString())
            .sorted()
            .toList();
    return sha256(String.join("\n", entries));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM does not provide SHA-256", error);
    }
  }

  private static String bounded(String value, int maximum) {
    var normalized = value == null || value.isBlank() ? "unknown" : value.strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }

  public record ContextSelection(
      Optional<ResourceRef> hovered, Optional<ResourceRef> held, Optional<ResourceRef> pointed) {
    public ContextSelection {
      hovered = Optional.ofNullable(hovered).orElseGet(Optional::empty);
      held = Optional.ofNullable(held).orElseGet(Optional::empty);
      pointed = Optional.ofNullable(pointed).orElseGet(Optional::empty);
    }
  }

  private record ModMetadata(String id, String name, String version) {}

  private record InventoryKey(String resourceId, String componentsFingerprint)
      implements Comparable<InventoryKey> {
    @Override
    public int compareTo(InventoryKey other) {
      var idOrder = resourceId.compareTo(other.resourceId);
      if (idOrder != 0) {
        return idOrder;
      }
      if (componentsFingerprint == null) {
        return other.componentsFingerprint == null ? 0 : -1;
      }
      return other.componentsFingerprint == null
          ? 1
          : componentsFingerprint.compareTo(other.componentsFingerprint);
    }
  }
}
