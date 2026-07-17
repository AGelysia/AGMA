package dev.minecraftagent.paper.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.landmark.LandmarkCatalog;
import dev.minecraftagent.paper.preview.PaperBuildPreviewService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/** Takes bounded immutable JSON snapshots of Bukkit state on the server thread. */
public final class BukkitReadToolExecutor implements ReadToolExecutor {
  @FunctionalInterface
  public interface MainThreadScheduler {
    void execute(Runnable task);
  }

  private static final int MAX_PLUGINS = 256;
  private static final int MAX_RECIPE_SCAN = 8192;
  private static final int MAX_RECIPE_RESULTS = 16;
  private static final int MAX_CHOICES = 32;
  private static final int MAX_RECIPES_PER_SLICE = 128;
  private static final long MAX_RECIPE_SLICE_NANOS = 2_000_000L;
  private static final PlainTextComponentSerializer PLAIN =
      PlainTextComponentSerializer.plainText();

  private final Server server;
  private final ReadToolRegistry registry;
  private final MainThreadScheduler mainThread;
  private final Supplier<LandmarkCatalog> landmarkCatalog;
  private final PaperBuildPreviewService buildPreviews;

  public BukkitReadToolExecutor(
      Server server, ReadToolRegistry registry, MainThreadScheduler mainThread) {
    this(server, registry, mainThread, LandmarkCatalog::empty, null);
  }

  public BukkitReadToolExecutor(
      Server server,
      ReadToolRegistry registry,
      MainThreadScheduler mainThread,
      Supplier<LandmarkCatalog> landmarkCatalog) {
    this(server, registry, mainThread, landmarkCatalog, null);
  }

  public BukkitReadToolExecutor(
      Server server,
      ReadToolRegistry registry,
      MainThreadScheduler mainThread,
      Supplier<LandmarkCatalog> landmarkCatalog,
      PaperBuildPreviewService buildPreviews) {
    this.server = Objects.requireNonNull(server);
    this.registry = Objects.requireNonNull(registry);
    this.mainThread = Objects.requireNonNull(mainThread);
    this.landmarkCatalog = Objects.requireNonNull(landmarkCatalog);
    this.buildPreviews = buildPreviews;
  }

  @Override
  public CompletionStage<ReadToolResult> execute(ReadToolCall call) {
    Objects.requireNonNull(call);
    var future = new CompletableFuture<ReadToolResult>();
    try {
      mainThread.execute(
          () -> {
            if (call.tool().equals("build.preview.create")) {
              beginBuildPreview(call, future);
              return;
            }
            if (call.tool().equals("server.recipe.lookup")
                || call.tool().equals("server.recipe.uses")) {
              beginRecipeScan(call, future);
              return;
            }
            try {
              future.complete(snapshot(call));
            } catch (ResultLimitException error) {
              future.complete(
                  ReadToolResult.failed(
                      source(call.tool()),
                      "TOOL_RESULT_LIMIT_EXCEEDED",
                      "The tool result exceeded a safe limit.",
                      false));
            } catch (RuntimeException error) {
              future.complete(
                  ReadToolResult.failed(
                      source(call.tool()),
                      "TOOL_EXECUTION_FAILED",
                      "The server could not read this data.",
                      true));
            }
          });
    } catch (RuntimeException error) {
      future.complete(
          ReadToolResult.failed(
              source(call.tool()),
              "TOOL_EXECUTION_FAILED",
              "The server could not schedule this read.",
              true));
    }
    return future;
  }

  private void beginBuildPreview(ReadToolCall call, CompletableFuture<ReadToolResult> future) {
    if (buildPreviews != null) {
      buildPreviews.discard(call.requestId());
    }
    var validation = registry.validate(call.tool(), call.module(), call.arguments());
    if (!validation.accepted()) {
      future.complete(validation.rejection());
      return;
    }
    if (buildPreviews == null) {
      future.complete(
          ReadToolResult.failed(
              ReadToolResult.Source.PAPER_API,
              "BUILD_PREVIEW_UNAVAILABLE",
              "Build preview generation is not available.",
              false));
      return;
    }
    try {
      buildPreviews
          .execute(call)
          .whenComplete(
              (result, error) -> {
                if (error == null && result != null) {
                  future.complete(result);
                } else {
                  future.complete(
                      ReadToolResult.failed(
                          ReadToolResult.Source.PAPER_API,
                          "BUILD_PREVIEW_FAILED",
                          "The server could not create this preview.",
                          false));
                }
              });
    } catch (RuntimeException error) {
      future.complete(
          ReadToolResult.failed(
              ReadToolResult.Source.PAPER_API,
              "BUILD_PREVIEW_FAILED",
              "The server could not create this preview.",
              false));
    }
  }

  private ReadToolResult snapshot(ReadToolCall call) {
    var validation = registry.validate(call.tool(), call.module(), call.arguments());
    if (!validation.accepted()) {
      return validation.rejection();
    }
    var player = server.getPlayer(call.playerUuid());
    if (player == null || !player.isOnline() || !player.getUniqueId().equals(call.playerUuid())) {
      return rejected("PLAYER_UNAVAILABLE", "The requesting player is no longer online.");
    }
    if (!player.hasPermission("minecraftagent.use")) {
      return rejected("PERMISSION_DENIED", "The player is not allowed to use this tool.");
    }
    var result =
        switch (call.tool()) {
          case "player.context.read" -> playerContextSnapshot(player);
          case "player.held_item.read" -> heldItemsSnapshot(player);
          case "server.info.read" -> serverInfoSnapshot(server);
          case "server.plugins.list" -> pluginsSnapshot(server);
          case "landmark.search" ->
              landmarkSnapshot(player, call.arguments(), landmarkCatalog.get());
          default -> throw new IllegalStateException("Validated read tool has no executor");
        };
    return ReadToolResult.succeeded(validation.descriptor().source(), result);
  }

  static JsonObject landmarkSnapshot(
      Player player, JsonObject arguments, LandmarkCatalog landmarkCatalog) {
    var location = player.getLocation();
    return landmarkCatalog.search(
        arguments.get("query").getAsString(),
        player.getWorld().getKey().toString(),
        location.getX(),
        location.getY(),
        location.getZ(),
        player::hasPermission);
  }

  private ReadToolResult rejected(String code, String message) {
    return ReadToolResult.rejected(ReadToolResult.Source.PAPER_POLICY, code, message);
  }

  private ReadToolResult.Source source(String tool) {
    return registry
        .find(tool)
        .map(ReadToolRegistry.Descriptor::source)
        .orElse(ReadToolResult.Source.PAPER_POLICY);
  }

  static JsonObject playerContextSnapshot(Player player) {
    var location = player.getLocation();
    var world = player.getWorld();
    var position = new JsonObject();
    position.addProperty("x", location.getX());
    position.addProperty("y", location.getY());
    position.addProperty("z", location.getZ());
    position.addProperty("yaw", org.bukkit.Location.normalizeYaw(location.getYaw()));
    position.addProperty("pitch", location.getPitch());

    var result = new JsonObject();
    result.addProperty("online", true);
    result.addProperty("playerName", player.getName());
    result.addProperty("worldId", world.getKey().toString());
    result.add("position", position);
    result.addProperty("gameMode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
    result.addProperty("health", player.getHealth());
    result.addProperty("maxHealth", player.getMaxHealth());
    result.addProperty("foodLevel", player.getFoodLevel());
    result.addProperty("saturation", player.getSaturation());
    result.addProperty("experienceLevel", player.getLevel());
    return result;
  }

  static JsonObject heldItemsSnapshot(Player player) {
    var result = new JsonObject();
    result.addProperty("selectedSlot", player.getInventory().getHeldItemSlot());
    result.add("mainHand", nullableItem(player.getInventory().getItemInMainHand()));
    result.add("offHand", nullableItem(player.getInventory().getItemInOffHand()));
    return result;
  }

  static JsonObject serverInfoSnapshot(Server server) {
    var result = new JsonObject();
    result.addProperty("serverName", bounded(server.getName(), 128));
    result.addProperty("minecraftVersion", bounded(server.getMinecraftVersion(), 64));
    result.addProperty("serverVersion", bounded(server.getVersion(), 256));
    result.addProperty("onlinePlayers", server.getOnlinePlayers().size());
    result.addProperty("maxPlayers", server.getMaxPlayers());
    result.addProperty("viewDistance", server.getViewDistance());
    result.addProperty("simulationDistance", server.getSimulationDistance());
    return result;
  }

  static JsonObject pluginsSnapshot(Server server) {
    var installed = server.getPluginManager().getPlugins();
    var rows = new ArrayList<JsonObject>(Math.min(installed.length, MAX_PLUGINS));
    for (var plugin : installed) {
      var meta = plugin.getPluginMeta();
      var row = new JsonObject();
      row.addProperty("name", bounded(meta.getName(), 128));
      row.addProperty("version", bounded(meta.getVersion(), 128));
      row.addProperty("enabled", plugin.isEnabled());
      rows.add(row);
    }
    rows.sort(Comparator.comparing(row -> row.get("name").getAsString()));
    var truncated = rows.size() > MAX_PLUGINS;
    if (truncated) {
      rows.subList(MAX_PLUGINS, rows.size()).clear();
    }
    var entries = new JsonArray();
    rows.forEach(entries::add);
    var result = new JsonObject();
    result.add("plugins", entries);
    result.addProperty("truncated", truncated);
    return result;
  }

  private void beginRecipeScan(ReadToolCall call, CompletableFuture<ReadToolResult> future) {
    try {
      var validation = registry.validate(call.tool(), call.module(), call.arguments());
      if (!validation.accepted()) {
        future.complete(validation.rejection());
        return;
      }
      var player = server.getPlayer(call.playerUuid());
      if (player == null || !player.isOnline() || !player.getUniqueId().equals(call.playerUuid())) {
        future.complete(
            ReadToolResult.rejected(
                ReadToolResult.Source.PAPER_POLICY,
                "PLAYER_UNAVAILABLE",
                "The requesting player is no longer online."));
        return;
      }
      if (!player.hasPermission("minecraftagent.use")) {
        future.complete(
            ReadToolResult.rejected(
                ReadToolResult.Source.PAPER_POLICY,
                "PERMISSION_DENIED",
                "The player is not allowed to use this tool."));
        return;
      }

      var itemId = call.arguments().get("itemId").getAsString();
      var key = NamespacedKey.fromString(itemId);
      var material = key == null ? null : Registry.MATERIAL.get(key);
      if (key == null
          || !key.toString().equals(itemId)
          || material == null
          || !material.isItem()
          || material.isAir()) {
        future.complete(
            ReadToolResult.rejected(
                ReadToolResult.Source.PAPER_POLICY,
                "TOOL_ARGUMENTS_INVALID",
                "The item ID is not present in the server registry."));
        return;
      }
      scanRecipeSlice(
          new RecipeScan(
              call.tool().equals("server.recipe.uses"),
              itemId,
              material,
              server.recipeIterator(),
              validation.descriptor().source(),
              future));
    } catch (RuntimeException error) {
      future.complete(
          ReadToolResult.failed(
              ReadToolResult.Source.SERVER_REGISTRY,
              "TOOL_EXECUTION_FAILED",
              "The server could not read the recipe registry.",
              true));
    }
  }

  private void scanRecipeSlice(RecipeScan scan) {
    if (scan.future.isDone()) {
      return;
    }
    try {
      var deadline = System.nanoTime() + MAX_RECIPE_SLICE_NANOS;
      var sliceCount = 0;
      while (scan.iterator.hasNext()
          && sliceCount < MAX_RECIPES_PER_SLICE
          && System.nanoTime() < deadline) {
        sliceCount++;
        if (++scan.scanned > MAX_RECIPE_SCAN) {
          throw new ResultLimitException();
        }
        accumulateRecipe(scan, scan.iterator.next());
      }
      if (scan.iterator.hasNext()) {
        mainThread.execute(() -> scanRecipeSlice(scan));
        return;
      }
      scan.matches.sort(Comparator.comparing(RecipeRow::id));
      var result = finishRecipeResult(scan);
      scan.future.complete(ReadToolResult.succeeded(scan.source, result));
    } catch (ResultLimitException error) {
      scan.future.complete(
          ReadToolResult.failed(
              ReadToolResult.Source.SERVER_REGISTRY,
              "TOOL_RESULT_LIMIT_EXCEEDED",
              "The recipe registry exceeded a safe scan limit.",
              false));
    } catch (RuntimeException error) {
      scan.future.complete(
          ReadToolResult.failed(
              ReadToolResult.Source.SERVER_REGISTRY,
              "TOOL_EXECUTION_FAILED",
              "The server could not read the recipe registry.",
              true));
    }
  }

  private static void accumulateRecipe(RecipeScan scan, Recipe recipe) {
    try {
      if (!scan.uses) {
        var output = recipe.getResult();
        if (output == null || output.getType() != scan.material) {
          return;
        }
        scan.totalMatches++;
      }
      var row = mapRecipe(recipe);
      if (row == null) {
        scan.skipped = true;
        return;
      }
      if (scan.uses) {
        if (!row.inputsKnown()) {
          scan.skipped = true;
          return;
        }
        if (!row.uses(scan.material)) {
          return;
        }
      }
      if (scan.uses) {
        scan.totalMatches++;
      }
      if (scan.matches.size() < MAX_RECIPE_RESULTS) {
        scan.matches.add(row);
      }
    } catch (RuntimeException malformedRecipe) {
      scan.skipped = true;
    }
  }

  private static JsonObject finishRecipeResult(RecipeScan scan) {
    scan.matches.sort(Comparator.comparing(RecipeRow::id));
    return recipeResultSnapshot(
        scan.itemId,
        scan.uses,
        scan.matches.stream().map(RecipeRow::json).toList(),
        scan.totalMatches,
        scan.skipped);
  }

  static JsonObject recipeQuerySnapshot(
      String itemId, boolean uses, Material material, List<Recipe> recipes) {
    var scan =
        new RecipeScan(
            uses,
            itemId,
            material,
            List.copyOf(recipes).iterator(),
            ReadToolResult.Source.SERVER_REGISTRY,
            new CompletableFuture<>());
    recipes.forEach(recipe -> accumulateRecipe(scan, recipe));
    return finishRecipeResult(scan);
  }

  static JsonObject recipeResultSnapshot(
      String itemId, boolean uses, List<JsonObject> recipes, int totalMatches, boolean skipped) {
    Objects.requireNonNull(itemId);
    recipes = List.copyOf(recipes);
    if (recipes.size() > MAX_RECIPE_RESULTS
        || totalMatches < recipes.size()
        || totalMatches > 1_000_000) {
      throw new IllegalArgumentException("Invalid recipe result bounds");
    }
    var query = new JsonObject();
    query.addProperty("mode", uses ? "uses" : "lookup");
    query.addProperty("itemId", itemId);
    var serialized = new JsonArray();
    recipes.forEach(recipe -> serialized.add(recipe.deepCopy()));
    var result = new JsonObject();
    result.add("query", query);
    result.add("recipes", serialized);
    result.addProperty("totalMatches", totalMatches);
    result.addProperty("truncated", skipped || totalMatches > recipes.size());
    return result;
  }

  private static final class RecipeScan {
    private final boolean uses;
    private final String itemId;
    private final Material material;
    private final java.util.Iterator<Recipe> iterator;
    private final ReadToolResult.Source source;
    private final CompletableFuture<ReadToolResult> future;
    private final ArrayList<RecipeRow> matches = new ArrayList<>();
    private int scanned;
    private int totalMatches;
    private boolean skipped;

    private RecipeScan(
        boolean uses,
        String itemId,
        Material material,
        java.util.Iterator<Recipe> iterator,
        ReadToolResult.Source source,
        CompletableFuture<ReadToolResult> future) {
      this.uses = uses;
      this.itemId = itemId;
      this.material = material;
      this.iterator = iterator;
      this.source = source;
      this.future = future;
    }
  }

  private static RecipeRow mapRecipe(Recipe recipe) {
    if (!(recipe instanceof org.bukkit.Keyed keyed)) {
      return null;
    }
    var ingredients = new ArrayList<Ingredient>();
    String type;
    JsonObject layout;
    JsonObject processing = null;

    if (recipe instanceof ShapedRecipe shaped) {
      type = "shaped";
      var shape = shaped.getShape();
      var height = shape.length;
      var width = java.util.Arrays.stream(shape).mapToInt(String::length).max().orElse(1);
      var choices = shaped.getChoiceMap();
      for (var y = 0; y < shape.length; y++) {
        for (var x = 0; x < shape[y].length(); x++) {
          var choice = choices.get(shape[y].charAt(x));
          if (choice != null) {
            ingredients.add(new Ingredient(y * width + x, x, y, choice(choice)));
          }
        }
      }
      layout = gridLayout(width, height, ingredients);
    } else if (recipe instanceof ShapelessRecipe shapeless) {
      type = "shapeless";
      var choices = shapeless.getChoiceList();
      var width = Math.min(3, Math.max(1, choices.size()));
      var height = Math.max(1, (choices.size() + width - 1) / width);
      for (var index = 0; index < choices.size(); index++) {
        ingredients.add(
            new Ingredient(index, index % width, index / width, choice(choices.get(index))));
      }
      layout = gridLayout(width, height, ingredients);
    } else if (recipe instanceof CookingRecipe<?> cooking) {
      type = cookingType(cooking);
      var input = choice(cooking.getInputChoice());
      ingredients.add(new Ingredient(0, 0, 0, input));
      layout = singleInputLayout(input);
      var cookingTime = cooking.getCookingTime();
      var experience = cooking.getExperience();
      if (cookingTime < 0
          || cookingTime > 120_000
          || !Float.isFinite(experience)
          || experience < 0
          || experience > 1_000_000) {
        throw new ResultLimitException();
      }
      processing = new JsonObject();
      processing.addProperty("timeTicks", cookingTime);
      processing.addProperty("experience", experience);
    } else if (recipe instanceof StonecuttingRecipe stonecutting) {
      type = "stonecutting";
      var input = choice(stonecutting.getInputChoice());
      ingredients.add(new Ingredient(0, 0, 0, input));
      layout = singleInputLayout(input);
    } else if (recipe instanceof SmithingTransformRecipe smithing) {
      type = "smithing_transform";
      var template = choice(smithing.getTemplate());
      var base = choice(smithing.getBase());
      var addition = choice(smithing.getAddition());
      ingredients.add(new Ingredient(0, 0, 0, template));
      ingredients.add(new Ingredient(1, 1, 0, base));
      ingredients.add(new Ingredient(2, 2, 0, addition));
      layout = smithingLayout(template, base, addition);
    } else if (recipe instanceof SmithingTrimRecipe smithing) {
      type = "smithing_trim";
      var template = choice(smithing.getTemplate());
      var base = choice(smithing.getBase());
      var addition = choice(smithing.getAddition());
      ingredients.add(new Ingredient(0, 0, 0, template));
      ingredients.add(new Ingredient(1, 1, 0, base));
      ingredients.add(new Ingredient(2, 2, 0, addition));
      layout = smithingLayout(template, base, addition);
    } else if (recipe instanceof TransmuteRecipe transmute) {
      type = "transmute";
      var input = choice(transmute.getInput());
      var material = choice(transmute.getMaterial());
      ingredients.add(new Ingredient(0, 0, 0, input));
      ingredients.add(new Ingredient(1, 1, 0, material));
      layout = new JsonObject();
      layout.addProperty("kind", "transmute");
      layout.add("input", input.deepCopy());
      layout.add("material", material.deepCopy());
    } else {
      type = recipe instanceof org.bukkit.inventory.ComplexRecipe ? "complex" : "custom";
      layout = new JsonObject();
      layout.addProperty("kind", "unsupported");
      layout.addProperty("reason", "UNSUPPORTED_RECIPE_LAYOUT");
    }
    if (ingredients.size() > 9) {
      return null;
    }

    var source = new JsonObject();
    source.addProperty("kind", "server_registry");
    source.add("providerId", JsonNull.INSTANCE);
    var recipeResult = recipe.getResult();
    var json = new JsonObject();
    json.addProperty("recipeId", keyed.getKey().toString());
    json.addProperty("recipeType", type);
    json.add("source", source);
    json.add("result", nullableItem(recipeResult));
    json.add("layout", layout);
    json.add("remainingItems", remainingItems(ingredients));
    if (processing != null) {
      json.add("processing", processing);
    }
    var resultMaterial =
        recipeResult == null || isAir(recipeResult.getType()) ? null : recipeResult.getType();
    var inputsKnown =
        !"unsupported".equals(layout.get("kind").getAsString())
            && ingredients.stream().allMatch(Ingredient::choiceKnown);
    return new RecipeRow(keyed.getKey().toString(), resultMaterial, ingredients, inputsKnown, json);
  }

  static JsonObject recipeSnapshot(Recipe recipe) {
    var row = mapRecipe(Objects.requireNonNull(recipe));
    return row == null ? null : row.json().deepCopy();
  }

  private static JsonObject gridLayout(int width, int height, List<Ingredient> ingredients) {
    if (ingredients.isEmpty() || width < 1 || width > 3 || height < 1 || height > 3) {
      throw new IllegalArgumentException("Invalid crafting grid");
    }
    var layout = new JsonObject();
    layout.addProperty("kind", "grid");
    layout.addProperty("width", width);
    layout.addProperty("height", height);
    var values = new JsonArray();
    ingredients.forEach(ingredient -> values.add(ingredient.json()));
    layout.add("ingredients", values);
    return layout;
  }

  private static JsonObject singleInputLayout(JsonObject ingredient) {
    var layout = new JsonObject();
    layout.addProperty("kind", "single_input");
    layout.add("ingredient", ingredient.deepCopy());
    return layout;
  }

  private static JsonObject smithingLayout(
      JsonObject template, JsonObject base, JsonObject addition) {
    var layout = new JsonObject();
    layout.addProperty("kind", "smithing");
    layout.add("template", template.deepCopy());
    layout.add("base", base.deepCopy());
    layout.add("addition", addition.deepCopy());
    return layout;
  }

  private static String cookingType(CookingRecipe<?> recipe) {
    if (recipe instanceof FurnaceRecipe) {
      return "smelting";
    }
    if (recipe instanceof BlastingRecipe) {
      return "blasting";
    }
    if (recipe instanceof SmokingRecipe) {
      return "smoking";
    }
    if (recipe instanceof CampfireRecipe) {
      return "campfire_cooking";
    }
    throw new IllegalArgumentException("Unknown cooking recipe");
  }

  private static JsonObject choice(RecipeChoice source) {
    var alternatives = new ArrayList<JsonObject>();
    var type = "exact";
    if (source instanceof RecipeChoice.MaterialChoice materialChoice) {
      type = "material";
      materialChoice.getChoices().forEach(material -> alternatives.add(item(material)));
    } else if (source instanceof RecipeChoice.ExactChoice exactChoice) {
      exactChoice.getChoices().forEach(item -> alternatives.add(item(item)));
    } else if (source instanceof RecipeChoice.ItemTypeChoice itemTypeChoice) {
      type = "item_type";
      itemTypeChoice
          .itemTypes()
          .resolve(Registry.ITEM)
          .forEach(itemType -> alternatives.add(item(itemType.createItemStack())));
    } else {
      var unsupported = new JsonObject();
      unsupported.addProperty("choiceType", "unsupported");
      unsupported.addProperty("reason", "UNSUPPORTED_INGREDIENT_CHOICE");
      unsupported.add("alternatives", new JsonArray());
      return unsupported;
    }
    alternatives.sort(Comparator.comparing(item -> item.get("itemId").getAsString()));
    if (alternatives.isEmpty() || alternatives.size() > MAX_CHOICES) {
      throw new ResultLimitException();
    }
    var values = new JsonArray();
    alternatives.forEach(values::add);
    var result = new JsonObject();
    result.addProperty("choiceType", type);
    result.add("alternatives", values);
    return result;
  }

  private static JsonArray remainingItems(List<Ingredient> ingredients) {
    var result = new JsonArray();
    for (var ingredient : ingredients) {
      var alternatives = ingredient.choice().getAsJsonArray("alternatives");
      Material remainder = null;
      var initialized = false;
      var consistent = true;
      for (var element : alternatives) {
        var material =
            Material.matchMaterial(element.getAsJsonObject().get("itemId").getAsString(), false);
        var current = craftingRemainder(material);
        if (!initialized) {
          remainder = current;
          initialized = true;
        } else if (current != remainder) {
          consistent = false;
        }
      }
      if (consistent && remainder != null && !isAir(remainder)) {
        var entry = new JsonObject();
        entry.addProperty("slot", ingredient.slot());
        entry.add("item", item(new ItemStack(remainder)));
        result.add(entry);
      }
    }
    return result;
  }

  private static JsonElement nullableItem(ItemStack value) {
    return value == null || isAir(value.getType()) || value.getAmount() <= 0
        ? JsonNull.INSTANCE
        : item(value);
  }

  private static JsonObject item(ItemStack value) {
    if (value == null || isAir(value.getType()) || value.getAmount() <= 0) {
      throw new IllegalArgumentException("Recipe contained an empty item");
    }
    var result = new JsonObject();
    result.addProperty("itemId", value.getType().getKey().toString());
    result.addProperty("count", value.getAmount());
    result.add("components", safeComponents(value));
    return result;
  }

  private static JsonObject item(Material material) {
    if (material == null || isAir(material)) {
      throw new IllegalArgumentException("Recipe contained an empty material");
    }
    var result = new JsonObject();
    result.addProperty("itemId", material.getKey().toString());
    result.addProperty("count", 1);
    result.add("components", new JsonObject());
    return result;
  }

  private static Material craftingRemainder(Material material) {
    if (material == null) {
      return null;
    }
    try {
      return material.getCraftingRemainingItem();
    } catch (RuntimeException | LinkageError unavailableRegistry) {
      return null;
    }
  }

  private static boolean isAir(Material material) {
    return material == Material.AIR
        || material == Material.CAVE_AIR
        || material == Material.VOID_AIR;
  }

  private static JsonObject safeComponents(ItemStack item) {
    var result = new JsonObject();
    if (!item.hasItemMeta()) {
      return result;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta.hasCustomName() && meta.customName() != null) {
      result.addProperty("customName", safeVisibleText(PLAIN.serialize(meta.customName()), 512));
    }
    if (meta.hasLore() && meta.lore() != null) {
      var lore = new JsonArray();
      meta.lore().stream()
          .limit(32)
          .map(PLAIN::serialize)
          .map(line -> safeVisibleText(line, 512))
          .forEach(lore::add);
      result.add("lore", lore);
    }
    if (meta instanceof Damageable damageable && damageable.hasDamage()) {
      result.addProperty("damage", damageable.getDamage());
      result.addProperty("maxDamage", damageable.getMaxDamage());
    }
    if (meta.hasCustomModelData() && meta.getCustomModelData() >= 0) {
      result.addProperty("customModelData", meta.getCustomModelData());
    }
    if (meta.hasEnchantmentGlintOverride()) {
      result.addProperty(
          "enchantmentGlint", Boolean.TRUE.equals(meta.getEnchantmentGlintOverride()));
    } else if (meta.hasEnchants()) {
      result.addProperty("enchantmentGlint", true);
    }
    return result;
  }

  static String safeVisibleText(String value, int limit) {
    Objects.requireNonNull(value);
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    var result = new StringBuilder(Math.min(value.length(), limit));
    var index = 0;
    var accepted = 0;
    while (index < value.length() && accepted < limit) {
      var character = value.charAt(index++);
      final int codePoint;
      if (Character.isHighSurrogate(character)) {
        if (index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          continue;
        }
        codePoint = Character.toCodePoint(character, value.charAt(index++));
      } else if (Character.isLowSurrogate(character)) {
        continue;
      } else {
        codePoint = character;
      }
      if (unsafeVisibleCodePoint(codePoint)) {
        continue;
      }
      result.appendCodePoint(codePoint);
      accepted++;
    }
    return result.toString();
  }

  private static String bounded(String value, int limit) {
    Objects.requireNonNull(value);
    return value.codePointCount(0, value.length()) <= limit
        ? value
        : value.substring(0, value.offsetByCodePoints(0, limit));
  }

  private static boolean unsafeVisibleCodePoint(int value) {
    return value <= 0x1f
        || value >= 0x7f && value <= 0x9f
        || value == 0x061c
        || value == 0x200e
        || value == 0x200f
        || value >= 0x202a && value <= 0x202e
        || value >= 0x2066 && value <= 0x2069;
  }

  private record Ingredient(int slot, int x, int y, JsonObject choice) {
    JsonObject json() {
      var result = new JsonObject();
      result.addProperty("slot", slot);
      result.addProperty("x", x);
      result.addProperty("y", y);
      result.add("ingredient", choice.deepCopy());
      return result;
    }

    boolean contains(Material material) {
      for (var alternative : choice.getAsJsonArray("alternatives")) {
        if (alternative
            .getAsJsonObject()
            .get("itemId")
            .getAsString()
            .equals(material.getKey().toString())) {
          return true;
        }
      }
      return false;
    }

    boolean choiceKnown() {
      return !"unsupported".equals(choice.get("choiceType").getAsString());
    }
  }

  private record RecipeRow(
      String id,
      Material result,
      List<Ingredient> ingredients,
      boolean inputsKnown,
      JsonObject json) {
    boolean produces(Material material) {
      return result == material;
    }

    boolean uses(Material material) {
      return ingredients.stream().anyMatch(ingredient -> ingredient.contains(material));
    }
  }

  private static final class ResultLimitException extends RuntimeException {}
}
