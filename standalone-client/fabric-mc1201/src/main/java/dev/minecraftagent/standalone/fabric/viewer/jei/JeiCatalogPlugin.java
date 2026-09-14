package dev.minecraftagent.standalone.fabric.viewer.jei;

import dev.minecraftagent.standalone.common.OptionalViewerRegistry;
import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import dev.minecraftagent.standalone.fabric.StackFingerprint;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.fabric.constants.FabricTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Public-API-only JEI bridge; JEI loads this class only when its own runtime is present. */
@JeiPlugin
public final class JeiCatalogPlugin implements IModPlugin {
  private static final String ADAPTER_ID = "jei";

  @Override
  public ResourceLocation getPluginUid() {
    return new ResourceLocation("agma_standalone", "jei_catalog");
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
    private static final String REVIEWED_VERSION = "15.20.0.112";
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
      var ingredientManager = runtime.getIngredientManager();
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
            ingredientManager,
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
        IIngredientManager ingredientManager,
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
              .createRecipeCatalystLookup(category.getRecipeType())
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
                  ingredientManager,
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
        IIngredientManager ingredientManager,
        List<ItemStack> registeredStations,
        ResourceRef.Source source,
        LinkedHashMap<ResourceKey, ResourceRef> resources,
        List<String> contributionWarnings) {
      var inputs = new ArrayList<ProcessRecord.InputGroup>();
      var outputs = new ArrayList<List<ResourceRef>>();
      var stations = new LinkedHashMap<ResourceKey, ResourceRef>();
      var plannable = true;
      for (var slot : slots) {
        var slotResources = new LinkedHashMap<ResourceKey, ResourceRef>();
        itemResources(slot.getItemStacks().limit(65).toList(), source, resources)
            .forEach(value -> slotResources.putIfAbsent(ResourceKey.from(value), value));
        var unsupported = false;
        for (var typed : slot.getAllIngredients().toList()) {
          if (typed.getType() == VanillaTypes.ITEM_STACK) {
            continue;
          }
          if (typed.getType() == FabricTypes.FLUID_STACK
              && slot.getRole() != RecipeIngredientRole.CATALYST) {
            var fluid = fluidResource(typed, ingredientManager, source, resources);
            if (fluid == null) {
              unsupported = true;
            } else {
              slotResources.putIfAbsent(ResourceKey.from(fluid), fluid);
            }
          } else {
            unsupported = true;
          }
        }
        if (unsupported) {
          contributionWarnings.add("JEI_NON_ITEM_INGREDIENTS_OMITTED");
          plannable = false;
        }
        List<ResourceRef> alternatives = new ArrayList<>(slotResources.values());
        if (alternatives.size() > MAXIMUM_ALTERNATIVES) {
          alternatives = alternatives.subList(0, MAXIMUM_ALTERNATIVES);
          plannable = false;
        }
        if (slot.getRole() == RecipeIngredientRole.INPUT) {
          if (alternatives.isEmpty()) {
            plannable = false;
          } else if (inputs.size() < 128) {
            inputs.add(new ProcessRecord.InputGroup("input_" + inputs.size(), alternatives));
          } else {
            plannable = false;
          }
        } else if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
          if (!alternatives.isEmpty() && outputs.size() < 64) {
            outputs.add(alternatives);
          }
        } else if (slot.getRole() == RecipeIngredientRole.CATALYST) {
          alternatives.forEach(value -> stations.putIfAbsent(ResourceKey.from(value), value));
        } else if (!alternatives.isEmpty()) {
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
            new ProcessRecord.Output(alternatives.get(0), BigDecimal.ONE, outputIndex == 0));
      }
      if (inputs.isEmpty()) {
        plannable = false;
      }
      var recipeIdentifier = category.getRegistryName(recipe);
      var identity =
          category.getRecipeType().getUid()
              + "|"
              + (recipeIdentifier == null
                  ? recipeIndex
                      + "|"
                      + stableRecipeIdentity(inputs, normalizedOutputs, stations.values())
                  : recipeIdentifier.toString());
      var processId = "agma:jei_" + sha256(identity).substring(0, 32);
      var primary = normalizedOutputs.get(0).resource();
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

    private static <V> ResourceRef fluidResource(
        ITypedIngredient<V> typed,
        IIngredientManager ingredientManager,
        ResourceRef.Source source,
        LinkedHashMap<ResourceKey, ResourceRef> resources) {
      var helper = ingredientManager.getIngredientHelper(typed.getType());
      V ingredient = typed.getIngredient();
      if (ingredient == null) {
        return null;
      }
      ResourceLocation identifier;
      String displayName;
      long amount;
      try {
        identifier = helper.getResourceLocation(ingredient);
        displayName = helper.getDisplayName(ingredient);
        amount = helper.getAmount(ingredient);
      } catch (RuntimeException failure) {
        return null;
      }
      if (identifier == null || amount <= 0) {
        return null;
      }
      // JEI Fabric fluid amounts are droplets: 81000 droplets = 1000 millibuckets = one bucket.
      var millibuckets =
          BigDecimal.valueOf(amount)
              .divide(BigDecimal.valueOf(81), 6, RoundingMode.HALF_UP)
              .stripTrailingZeros();
      if (millibuckets.signum() <= 0) {
        return null;
      }
      var metadata = modMetadata(identifier.getNamespace());
      var resource =
          new ResourceRef(
              ResourceRef.Kind.FLUID,
              identifier.toString(),
              null,
              bounded(displayName, 512),
              null,
              metadata.id(),
              metadata.name(),
              metadata.version(),
              millibuckets,
              "millibucket",
              source);
      resources.putIfAbsent(ResourceKey.from(resource), resource);
      return resource;
    }

    private static ResourceRef resource(
        ItemStack stack, ResourceLocation id, int amount, ResourceRef.Source source) {
      Item item = stack.getItem();
      var metadata = modMetadata(id.getNamespace());
      return new ResourceRef(
          ResourceRef.Kind.ITEM,
          id.toString(),
          StackFingerprint.of(stack),
          bounded(item.getName(new ItemStack(item)).getString(), 512),
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
