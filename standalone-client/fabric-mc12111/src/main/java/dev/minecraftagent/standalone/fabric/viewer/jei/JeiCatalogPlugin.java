package dev.minecraftagent.standalone.fabric.viewer.jei;

import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.fabric.OptionalViewerRegistry;
import dev.minecraftagent.standalone.fabric.StackFingerprint;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Public-API-only JEI bridge; JEI loads this class only when its own runtime is present. */
@JeiPlugin
public final class JeiCatalogPlugin implements IModPlugin {
  private static final String ADAPTER_ID = "jei";

  @Override
  public Identifier getPluginUid() {
    return Identifier.fromNamespaceAndPath("agma_standalone", "jei_catalog");
  }

  @Override
  public void onRuntimeAvailable(IJeiRuntime runtime) {
    OptionalViewerRegistry.register(new JeiAdapter(runtime));
  }

  @Override
  public void onRuntimeUnavailable() {
    OptionalViewerRegistry.unregister(ADAPTER_ID);
  }

  private static final class JeiAdapter implements CatalogAdapter {
    private static final String REVIEWED_VERSION = "27.17.0.50";
    private static final int MAXIMUM_ALTERNATIVES = 64;
    private final IJeiRuntime runtime;

    private JeiAdapter(IJeiRuntime runtime) {
      this.runtime = runtime;
    }

    @Override
    public Descriptor descriptor() {
      return new Descriptor(ADAPTER_ID, Kind.JEI, 200);
    }

    @Override
    public Probe probe() {
      var installed =
          FabricLoader.getInstance()
              .getModContainer("jei")
              .map(container -> container.getMetadata().getVersion().getFriendlyString())
              .orElse(null);
      return REVIEWED_VERSION.equals(installed)
          ? new Probe(Status.READY, installed, true, "ADAPTER_READY")
          : new Probe(Status.INCOMPATIBLE, installed, false, "JEI_VERSION_UNREVIEWED");
    }

    @Override
    public Optional<String> hoveredItemId() {
      var minecraft = Minecraft.getInstance();
      if (minecraft.screen != null) {
        var window = minecraft.getWindow();
        var mouseX =
            minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        var mouseY =
            minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        var screenStack =
            runtime
                .getScreenHelper()
                .getClickableIngredientUnderMouse(minecraft.screen, mouseX, mouseY)
                .map(value -> value.getTypedIngredient().getItemStack())
                .flatMap(Optional::stream)
                .findFirst();
        if (screenStack.isPresent()) {
          return itemId(screenStack.get());
        }
      }
      var overlay = runtime.getIngredientListOverlay().getIngredientUnderMouse();
      if (overlay.isPresent()) {
        var stack = overlay.get().getItemStack();
        if (stack.isPresent()) {
          return itemId(stack.get());
        }
      }
      return runtime
          .getBookmarkOverlay()
          .getIngredientUnderMouse()
          .flatMap(value -> value.getItemStack())
          .flatMap(JeiAdapter::itemId);
    }

    private static Optional<String> itemId(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
        return Optional.empty();
      }
      var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
      return id == null ? Optional.empty() : Optional.of(id.toString());
    }

    @Override
    public Contribution capture(
        String generationId,
        CatalogPublisher.Cancellation cancellation,
        CatalogPublisher.ProgressListener progress)
        throws InterruptedException {
      var manager = runtime.getRecipeManager();
      var categories =
          manager
              .createRecipeCategoryLookup()
              .get()
              .sorted(
                  Comparator.comparing(category -> category.getRecipeType().getUid().toString()))
              .toList();
      var source =
          new ResourceRef.Source(
              ResourceRef.Layer.JEI,
              ADAPTER_ID,
              ResourceRef.Trust.L1,
              ResourceRef.Completeness.PARTIAL,
              generationId);
      var resources = new LinkedHashMap<ResourceKey, ResourceRef>();
      var processes = new LinkedHashMap<String, ProcessRecord>();
      var warnings = new ArrayList<String>();
      for (var category : categories) {
        cancellation.throwIfCancelled();
        captureCategory(
            manager,
            category,
            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup(),
            source,
            resources,
            processes,
            warnings,
            cancellation);
        if (processes.size() >= CatalogSnapshot.MAXIMUM_PROCESSES) {
          warnings.add("JEI_PROCESS_LIMIT_REACHED");
          break;
        }
      }
      return new Contribution(
          ADAPTER_ID,
          generationId,
          new ArrayList<>(resources.values()),
          new ArrayList<>(processes.values()),
          warnings.stream().distinct().limit(64).toList());
    }

    private static <T> void captureCategory(
        IRecipeManager manager,
        IRecipeCategory<T> category,
        IFocusGroup focuses,
        ResourceRef.Source source,
        LinkedHashMap<ResourceKey, ResourceRef> resources,
        LinkedHashMap<String, ProcessRecord> processes,
        List<String> warnings,
        CatalogPublisher.Cancellation cancellation)
        throws InterruptedException {
      var recipes = manager.createRecipeLookup(category.getRecipeType()).get().toList();
      var stations =
          manager
              .createCraftingStationLookup(category.getRecipeType())
              .getItemStack()
              .limit(32)
              .toList();
      for (var index = 0; index < recipes.size(); index++) {
        if (processes.size() >= CatalogSnapshot.MAXIMUM_PROCESSES) {
          return;
        }
        cancellation.throwIfCancelled();
        var recipe = recipes.get(index);
        try {
          IRecipeLayoutDrawable<T> layout =
              manager.createRecipeLayoutDrawable(category, recipe, focuses).orElse(null);
          if (layout == null) {
            warnings.add("JEI_LAYOUT_UNAVAILABLE");
            continue;
          }
          var process =
              toProcess(
                  category,
                  recipe,
                  index,
                  layout.getRecipeSlotsView().getSlotViews(),
                  stations,
                  source,
                  resources,
                  warnings);
          if (process != null) {
            processes.putIfAbsent(process.processId(), process);
          }
        } catch (RuntimeException | LinkageError failure) {
          warnings.add("JEI_RECIPE_CAPTURE_FAILED");
        }
      }
    }

    private static <T> ProcessRecord toProcess(
        IRecipeCategory<T> category,
        T recipe,
        int recipeIndex,
        List<IRecipeSlotView> slots,
        List<ItemStack> registeredStations,
        ResourceRef.Source source,
        LinkedHashMap<ResourceKey, ResourceRef> resources,
        List<String> contributionWarnings) {
      var inputs = new ArrayList<ProcessRecord.InputGroup>();
      var outputs = new ArrayList<List<ResourceRef>>();
      var stations = new LinkedHashMap<ResourceKey, ResourceRef>();
      var plannable = true;
      for (var slot : slots) {
        var slotResources =
            itemResources(slot.getItemStacks().limit(65).toList(), source, resources);
        if (slot.getAllIngredients().count() > slotResources.size()) {
          contributionWarnings.add("JEI_NON_ITEM_INGREDIENTS_OMITTED");
          plannable = false;
        }
        if (slotResources.size() > MAXIMUM_ALTERNATIVES) {
          slotResources = slotResources.subList(0, MAXIMUM_ALTERNATIVES);
          plannable = false;
        }
        if (slot.getRole() == RecipeIngredientRole.INPUT) {
          if (slotResources.isEmpty()) {
            plannable = false;
          } else if (inputs.size() < 128) {
            inputs.add(new ProcessRecord.InputGroup("input_" + inputs.size(), slotResources));
          } else {
            plannable = false;
          }
        } else if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
          if (!slotResources.isEmpty() && outputs.size() < 64) {
            outputs.add(slotResources);
          }
        } else if (slot.getRole() == RecipeIngredientRole.CRAFTING_STATION) {
          slotResources.forEach(value -> stations.putIfAbsent(ResourceKey.from(value), value));
        } else if (!slotResources.isEmpty()) {
          contributionWarnings.add("JEI_RENDER_ONLY_INGREDIENTS_OMITTED");
          plannable = false;
        }
      }
      itemResources(registeredStations, source, resources)
          .forEach(value -> stations.putIfAbsent(ResourceKey.from(value), value));
      if (outputs.isEmpty()) {
        return null;
      }
      var normalizedOutputs = new ArrayList<ProcessRecord.Output>();
      for (var outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
        var alternatives = outputs.get(outputIndex);
        if (alternatives.size() != 1) {
          plannable = false;
        }
        normalizedOutputs.add(
            new ProcessRecord.Output(alternatives.getFirst(), BigDecimal.ONE, outputIndex == 0));
      }
      if (inputs.isEmpty()) {
        plannable = false;
      }
      var recipeIdentifier = category.getIdentifier(recipe);
      var identity =
          category.getRecipeType().getUid()
              + "|"
              + (recipeIdentifier == null
                  ? recipeIndex
                      + "|"
                      + stableRecipeIdentity(inputs, normalizedOutputs, stations.values())
                  : recipeIdentifier.toString());
      var processId = "agma:jei_" + sha256(identity).substring(0, 32);
      var primary = normalizedOutputs.getFirst().resource();
      return new ProcessRecord(
          processId,
          category.getRecipeType().getUid().toString(),
          bounded(primary.displayName(), 220)
              + " - "
              + bounded(category.getTitle().getString(), 32),
          stations.values().stream().limit(32).toList(),
          inputs,
          List.of(),
          normalizedOutputs,
          null,
          null,
          List.of(),
          List.of(),
          source,
          plannable,
          plannable
              ? List.of("JEI client catalog; server-only conditions may be unavailable")
              : List.of("JEI recipe is partial or contains ambiguous/custom ingredients"));
    }

    private static String stableRecipeIdentity(
        List<ProcessRecord.InputGroup> inputs,
        List<ProcessRecord.Output> outputs,
        java.util.Collection<ResourceRef> stations) {
      var identity = new StringBuilder();
      inputs.forEach(
          group -> {
            identity.append("in:");
            group.alternatives().stream()
                .sorted(java.util.Comparator.comparing(ResourceKey::from))
                .forEach(resource -> appendIdentity(identity, resource));
          });
      outputs.forEach(output -> appendIdentity(identity.append("out:"), output.resource()));
      stations.stream()
          .sorted(java.util.Comparator.comparing(ResourceKey::from))
          .forEach(resource -> appendIdentity(identity.append("station:"), resource));
      return identity.toString();
    }

    private static void appendIdentity(StringBuilder identity, ResourceRef resource) {
      identity
          .append(resource.kind())
          .append('|')
          .append(resource.id())
          .append('|')
          .append(resource.componentsFingerprint())
          .append('|')
          .append(resource.amount().toPlainString())
          .append(';');
    }

    private static List<ResourceRef> itemResources(
        List<ItemStack> stacks,
        ResourceRef.Source source,
        LinkedHashMap<ResourceKey, ResourceRef> resources) {
      var result = new LinkedHashMap<ResourceKey, ResourceRef>();
      for (var stack : stacks) {
        if (stack == null || stack.isEmpty()) {
          continue;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
          continue;
        }
        var resource = resource(stack, id, Math.max(1, stack.getCount()), source);
        var key = ResourceKey.from(resource);
        resources.putIfAbsent(key, resource);
        result.putIfAbsent(key, resource);
      }
      return new ArrayList<>(result.values());
    }

    private static ResourceRef resource(
        ItemStack stack, Identifier id, int amount, ResourceRef.Source source) {
      Item item = stack.getItem();
      var metadata = modMetadata(id.getNamespace());
      return new ResourceRef(
          ResourceRef.Kind.ITEM,
          id.toString(),
          StackFingerprint.of(stack),
          bounded(item.getName().getString(), 512),
          bounded(item.getDescriptionId(), 256),
          metadata.id(),
          metadata.name(),
          metadata.version(),
          BigDecimal.valueOf(amount),
          "item",
          source);
    }

    private static ModMetadata modMetadata(String namespace) {
      return FabricLoader.getInstance()
          .getModContainer(namespace)
          .map(JeiAdapter::metadata)
          .orElseGet(
              () -> new ModMetadata(safeModId(namespace), bounded(namespace, 128), "unknown"));
    }

    private static ModMetadata metadata(ModContainer container) {
      var metadata = container.getMetadata();
      return new ModMetadata(
          metadata.getId(),
          bounded(metadata.getName(), 128),
          bounded(metadata.getVersion().getFriendlyString(), 128));
    }

    private static String safeModId(String value) {
      return value.matches("^[a-z][a-z0-9_.-]{0,127}$") ? value : "unknown";
    }

    private static String bounded(String value, int maximum) {
      var normalized = value == null || value.isBlank() ? "unknown" : value.strip();
      return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String sha256(String value) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException error) {
        throw new IllegalStateException("JVM does not provide SHA-256", error);
      }
    }

    private record ModMetadata(String id, String name, String version) {}
  }
}
