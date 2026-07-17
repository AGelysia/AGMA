package dev.minecraftagent.paper.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.AllowSchemaLoader;
import com.networknt.schema.resource.UriSchemaLoader;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewType;
import dev.minecraftagent.paper.landmark.Landmark;
import dev.minecraftagent.paper.landmark.LandmarkCatalog;
import dev.minecraftagent.paper.preview.PaperBuildPreviewService;
import dev.minecraftagent.paper.request.AgentModule;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class BukkitReadToolExecutorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void neverTouchesBukkitBeforeTheMainThreadTaskRuns() {
    var tasks = new ArrayDeque<Runnable>();
    var server =
        (Server)
            Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, arguments) -> {
                  throw new AssertionError("Bukkit accessed for an invalid tool");
                });
    var executor = new BukkitReadToolExecutor(server, new ReadToolRegistry(), tasks::add);
    var call =
        new ReadToolCall(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentModule.GENERAL,
            "server.command.execute",
            new JsonObject(),
            0);

    var result = executor.execute(call).toCompletableFuture();
    assertFalse(result.isDone());
    tasks.remove().run();
    assertEquals(ReadToolResult.Status.REJECTED, result.join().status());
    assertEquals(ReadToolResult.Source.PAPER_POLICY, result.join().source());
  }

  @Test
  void buildAttemptDiscardsAnEarlierArtifactBeforeArgumentRejection() {
    var tasks = new ArrayDeque<Runnable>();
    var previews = mock(PaperBuildPreviewService.class);
    var executor =
        new BukkitReadToolExecutor(
            mock(Server.class),
            new ReadToolRegistry(),
            tasks::add,
            LandmarkCatalog::empty,
            previews);
    var requestId = UUID.randomUUID();
    var call =
        new ReadToolCall(
            UUID.randomUUID(),
            requestId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentModule.BUILD,
            "build.preview.create",
            new JsonObject(),
            1);

    var result = executor.execute(call).toCompletableFuture();
    tasks.remove().run();

    assertEquals(ReadToolResult.Status.REJECTED, result.join().status());
    verify(previews).discard(requestId);
  }

  @Test
  void landmarkSnapshotUsesLivePermissionAndSatisfiesSharedSchema() throws Exception {
    var world = mock(World.class);
    when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
    var player = mock(Player.class);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
    when(player.hasPermission("minecraftagent.landmark.private")).thenReturn(false);
    var catalog =
        new LandmarkCatalog(
            List.of(
                new Landmark(
                    "public-market",
                    "Public Market",
                    List.of("shops"),
                    List.of("trade"),
                    "minecraft:overworld",
                    3,
                    64,
                    4,
                    null),
                new Landmark(
                    "private-market",
                    "Private Market",
                    List.of(),
                    List.of("trade"),
                    "minecraft:overworld",
                    1,
                    64,
                    1,
                    "minecraftagent.landmark.private")));
    var arguments = new JsonObject();
    arguments.addProperty("query", "market");

    var result = BukkitReadToolExecutor.landmarkSnapshot(player, arguments, catalog);

    assertEquals(1, result.get("totalMatches").getAsInt());
    assertEquals(
        5.0,
        result.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("distance").getAsDouble());
    assertFalse(result.toString().contains("private"));
    assertSchema("landmark-search-result.schema.json", result);
  }

  @Test
  void allFourBasicBukkitSnapshotsSatisfyTheirSharedSchemas() throws Exception {
    var playerId = UUID.randomUUID();
    var world = mock(World.class);
    when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
    var inventory = mock(PlayerInventory.class);
    when(inventory.getHeldItemSlot()).thenReturn(4);
    var mainHand = item(Material.STONE, 3);
    var offHand = item(Material.AIR, 0);
    when(inventory.getItemInMainHand()).thenReturn(mainHand);
    when(inventory.getItemInOffHand()).thenReturn(offHand);
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.getName()).thenReturn("SchemaPlayer");
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(new Location(world, 12.5, 64, -8.25, 725, 20));
    when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(player.getHealth()).thenReturn(19.0);
    when(player.getMaxHealth()).thenReturn(20.0);
    when(player.getFoodLevel()).thenReturn(18);
    when(player.getSaturation()).thenReturn(4.5f);
    when(player.getLevel()).thenReturn(12);
    when(player.getInventory()).thenReturn(inventory);

    assertSchema(
        "player-context-read-result.schema.json",
        BukkitReadToolExecutor.playerContextSnapshot(player));
    assertSchema(
        "player-held-item-read-result.schema.json",
        BukkitReadToolExecutor.heldItemsSnapshot(player));

    var pluginMeta = mock(PluginMeta.class);
    when(pluginMeta.getName()).thenReturn("ExamplePlugin");
    when(pluginMeta.getVersion()).thenReturn("1.2.3");
    var plugin = mock(Plugin.class);
    when(plugin.getPluginMeta()).thenReturn(pluginMeta);
    when(plugin.isEnabled()).thenReturn(true);
    var plugins = mock(PluginManager.class);
    when(plugins.getPlugins()).thenReturn(new Plugin[] {plugin});
    var server = mock(Server.class);
    when(server.getName()).thenReturn("Paper");
    when(server.getMinecraftVersion()).thenReturn("1.21.11");
    when(server.getVersion()).thenReturn("1.21.11-132");
    doReturn(List.of(player)).when(server).getOnlinePlayers();
    when(server.getMaxPlayers()).thenReturn(20);
    when(server.getViewDistance()).thenReturn(10);
    when(server.getSimulationDistance()).thenReturn(8);
    when(server.getPluginManager()).thenReturn(plugins);

    assertSchema(
        "server-info-read-result.schema.json", BukkitReadToolExecutor.serverInfoSnapshot(server));
    assertSchema(
        "server-plugins-list-result.schema.json", BukkitReadToolExecutor.pluginsSnapshot(server));
  }

  @Test
  void everySupportedRecipeFamilyPreservesTypedLayoutChoiceAndItemStack() throws Exception {
    var materialChoice = materialChoice(Material.OAK_PLANKS, Material.BIRCH_PLANKS);

    var shaped = recipe(ShapedRecipe.class, "shaped", Material.CRAFTING_TABLE);
    when(shaped.getShape()).thenReturn(new String[] {"AA", "AA"});
    when(shaped.getChoiceMap()).thenReturn(Map.of('A', materialChoice));
    assertRecipe(shaped, "shaped", "grid");

    var shapeless = recipe(ShapelessRecipe.class, "shapeless", Material.CRAFTING_TABLE);
    when(shapeless.getChoiceList()).thenReturn(List.of(materialChoice, materialChoice));
    assertRecipe(shapeless, "shapeless", "grid");

    assertCooking(recipe(FurnaceRecipe.class, "smelting", Material.IRON_INGOT), "smelting", 0);
    assertCooking(recipe(BlastingRecipe.class, "blasting", Material.IRON_INGOT), "blasting");
    assertCooking(recipe(SmokingRecipe.class, "smoking", Material.COOKED_BEEF), "smoking");
    assertCooking(
        recipe(CampfireRecipe.class, "campfire", Material.COOKED_BEEF), "campfire_cooking");

    var stonecutting = recipe(StonecuttingRecipe.class, "stonecutting", Material.STONE_SLAB);
    var stoneInput = materialChoice(Material.STONE);
    when(stonecutting.getInputChoice()).thenReturn(stoneInput);
    assertRecipe(stonecutting, "stonecutting", "single_input");

    var transform = recipe(SmithingTransformRecipe.class, "transform", Material.NETHERITE_SWORD);
    stubSmithing(transform);
    assertRecipe(transform, "smithing_transform", "smithing");

    var trim = recipe(SmithingTrimRecipe.class, "trim", null);
    stubSmithing(trim);
    assertRecipe(trim, "smithing_trim", "smithing");

    var transmute = recipe(TransmuteRecipe.class, "transmute", Material.GOLD_INGOT);
    var transmuteInput = materialChoice(Material.IRON_INGOT);
    var transmuteMaterial = materialChoice(Material.GOLD_NUGGET);
    when(transmute.getInput()).thenReturn(transmuteInput);
    when(transmute.getMaterial()).thenReturn(transmuteMaterial);
    assertRecipe(transmute, "transmute", "transmute");
  }

  @Test
  void emptyRecipeResultsAreSchemaValidAndMalformedUsesEntriesAreIsolated() throws Exception {
    var empty =
        BukkitReadToolExecutor.recipeResultSnapshot(
            "minecraft:barrier", false, List.of(), 0, false);
    assertSchema("server-recipe-lookup-result.schema.json", empty);

    var tooBroad = recipe(ShapelessRecipe.class, "bad", Material.CRAFTING_TABLE);
    var tooBroadChoice = mock(RecipeChoice.MaterialChoice.class);
    when(tooBroadChoice.getChoices()).thenReturn(Collections.nCopies(33, Material.STONE));
    when(tooBroad.getChoiceList()).thenReturn(List.of(tooBroadChoice));
    var good = recipe(ShapelessRecipe.class, "good", Material.CRAFTING_TABLE);
    var goodChoices = List.<RecipeChoice>of(materialChoice(Material.STONE));
    when(good.getChoiceList()).thenReturn(goodChoices);

    var uses =
        BukkitReadToolExecutor.recipeQuerySnapshot(
            "minecraft:stone", true, Material.STONE, List.of(tooBroad, good));
    assertSchema("server-recipe-uses-result.schema.json", uses);
    assertEquals(1, uses.get("totalMatches").getAsInt());
    assertEquals(1, uses.getAsJsonArray("recipes").size());
    assertTrue(uses.get("truncated").getAsBoolean());
  }

  @Test
  void unknownRecipeChoicesMakeUsesResultsExplicitlyIncomplete() throws Exception {
    var uncertain = recipe(ShapelessRecipe.class, "uncertain", Material.CRAFTING_TABLE);
    when(uncertain.getChoiceList()).thenReturn(List.of(mock(RecipeChoice.class)));

    var uses =
        BukkitReadToolExecutor.recipeQuerySnapshot(
            "minecraft:stone", true, Material.STONE, List.of(uncertain));

    assertSchema("server-recipe-uses-result.schema.json", uses);
    assertEquals(0, uses.get("totalMatches").getAsInt());
    assertEquals(0, uses.getAsJsonArray("recipes").size());
    assertTrue(uses.get("truncated").getAsBoolean());
  }

  @Test
  void sanitizesRecipeMetadataBeforeAuthoritativePresentationAndClientValidation()
      throws Exception {
    var recipe = recipe(ShapelessRecipe.class, "safe-metadata", Material.CRAFTING_TABLE);
    var stone = materialChoice(Material.STONE);
    when(recipe.getChoiceList()).thenReturn(List.of(stone));
    var item = item(Material.CRAFTING_TABLE, 1);
    var meta = mock(ItemMeta.class);
    when(meta.hasCustomName()).thenReturn(true);
    when(meta.customName()).thenReturn(Component.text("A\u0000\u0085\u202eB"));
    when(meta.hasLore()).thenReturn(true);
    when(meta.lore()).thenReturn(List.of(Component.text("C\t\u2066D")));
    when(item.hasItemMeta()).thenReturn(true);
    when(item.getItemMeta()).thenReturn(meta);
    when(recipe.getResult()).thenReturn(item);

    var snapshot = BukkitReadToolExecutor.recipeSnapshot(recipe);
    var components = snapshot.getAsJsonObject("result").getAsJsonObject("components");
    assertEquals("AB", components.get("customName").getAsString());
    assertEquals("CD", components.getAsJsonArray("lore").get(0).getAsString());

    var toolResult =
        BukkitReadToolExecutor.recipeResultSnapshot(
            "minecraft:crafting_table", false, List.of(snapshot), 1, false);
    assertSchema("server-recipe-lookup-result.schema.json", toolResult);
    var viewContent = toolResult.deepCopy();
    viewContent.addProperty("schemaVersion", "2.0");
    viewContent.addProperty("selectedRecipe", 0);

    var view =
        new ClientStructuredView(
            "1.0",
            UUID.randomUUID(),
            UUID.randomUUID(),
            ClientViewType.RECIPE,
            1,
            "Recipe",
            "fallback",
            true,
            viewContent);

    assertEquals(
        "AB",
        view.content()
            .getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("result")
            .getAsJsonObject("components")
            .get("customName")
            .getAsString());
  }

  @Test
  void safeVisibleTextDropsUnpairedSurrogatesAndBoundsUnicodeCodePoints() {
    assertEquals(
        "A\ud83d\ude00B",
        BukkitReadToolExecutor.safeVisibleText("A\ud800\ud83d\ude00\udc00B-extra", 3));
  }

  private static void assertCooking(org.bukkit.inventory.CookingRecipe<?> recipe, String type)
      throws Exception {
    assertCooking(recipe, type, 200);
  }

  private static void assertCooking(
      org.bukkit.inventory.CookingRecipe<?> recipe, String type, int cookingTime) throws Exception {
    var input = materialChoice(Material.RAW_IRON);
    when(recipe.getInputChoice()).thenReturn(input);
    when(recipe.getCookingTime()).thenReturn(cookingTime);
    when(recipe.getExperience()).thenReturn(0.7f);
    assertRecipe(recipe, type, "single_input");
  }

  private static void stubSmithing(org.bukkit.inventory.SmithingRecipe recipe) {
    var base = materialChoice(Material.DIAMOND_SWORD);
    var addition = materialChoice(Material.NETHERITE_INGOT);
    when(recipe.getBase()).thenReturn(base);
    when(recipe.getAddition()).thenReturn(addition);
    if (recipe instanceof SmithingTransformRecipe transform) {
      var template = materialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
      when(transform.getTemplate()).thenReturn(template);
    } else if (recipe instanceof SmithingTrimRecipe trim) {
      var template = materialChoice(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
      when(trim.getTemplate()).thenReturn(template);
    }
  }

  private static void assertRecipe(Recipe recipe, String type, String layout) throws Exception {
    var snapshot = BukkitReadToolExecutor.recipeSnapshot(recipe);
    assertEquals(type, snapshot.get("recipeType").getAsString());
    assertEquals(layout, snapshot.getAsJsonObject("layout").get("kind").getAsString());
    var wrapper =
        BukkitReadToolExecutor.recipeResultSnapshot(
            "minecraft:crafting_table", false, List.of(snapshot), 1, false);
    assertSchema("server-recipe-lookup-result.schema.json", wrapper);
  }

  private static RecipeChoice.MaterialChoice materialChoice(Material... materials) {
    var choice = mock(RecipeChoice.MaterialChoice.class);
    when(choice.getChoices()).thenReturn(List.of(materials));
    return choice;
  }

  private static <T extends Recipe & Keyed> T recipe(Class<T> type, String key, Material result) {
    var recipe = mock(type);
    when(recipe.getKey()).thenReturn(new NamespacedKey("minecraftagent", key));
    var resultItem = result == null ? null : item(result, 1);
    when(recipe.getResult()).thenReturn(resultItem);
    return recipe;
  }

  private static ItemStack item(Material material, int amount) {
    var item = mock(ItemStack.class);
    when(item.getType()).thenReturn(material);
    when(item.getAmount()).thenReturn(amount);
    when(item.hasItemMeta()).thenReturn(false);
    return item;
  }

  private static void assertSchema(String name, JsonObject value) throws Exception {
    var path = protocolRoot().resolve("schemas/tools/" + name);
    var localSchemaPrefix = protocolRoot().resolve("schemas").toUri().toString();
    var schema =
        JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> {
                  builder.schemaMappers(
                      mappers ->
                          mappers.mapPrefix(
                              "https://minecraft-agent.dev/schemas/1.0/", localSchemaPrefix));
                  builder.schemaLoaders(
                      loaders ->
                          loaders.values(
                              values -> {
                                var uriLoaderIndex = 0;
                                while (uriLoaderIndex < values.size()
                                    && !(values.get(uriLoaderIndex) instanceof UriSchemaLoader)) {
                                  uriLoaderIndex++;
                                }
                                values.add(
                                    uriLoaderIndex,
                                    new AllowSchemaLoader(
                                        iri -> iri.toString().startsWith("file:")));
                              }));
                })
            .getSchema(
                JSON.readTree(path.toFile()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    var errors = schema.validate(JSON.readTree(value.toString()));
    assertTrue(errors.isEmpty(), () -> name + " errors: " + errors + " value=" + value);
  }

  private static Path protocolRoot() {
    return Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
  }
}
