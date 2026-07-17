package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientViewSelectorTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String FALLBACK = "Private text remains available.";

  @Test
  void usesFallbackWithoutModAndSelectsOnlyExactAdvertisedViews() {
    var connections = new ClientConnectionRegistry();
    var selector = new ClientViewSelector(connections, ClientViewSchemaRegistry.versionOne());
    var text = view(ClientViewType.TEXT);
    var recipe = view(ClientViewType.RECIPE);

    assertTrue(selector.select(PLAYER, FALLBACK, List.of(text, recipe)).usesFallbackOnly());

    connections.join(PLAYER);
    connections.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 0, 0));
    var overlayOnly = selector.select(PLAYER, FALLBACK, List.of(text, recipe));
    assertEquals(List.of(text), overlayOnly.structuredViews());
    assertFalse(overlayOnly.usesFallbackOnly());

    connections.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    assertEquals(
        List.of(text), selector.select(PLAYER, FALLBACK, List.of(text, recipe)).structuredViews());
    assertEquals(
        List.of(recipe),
        selector.select(PLAYER, FALLBACK, List.of(recipe, text)).structuredViews());
  }

  @Test
  void rejectsMismatchedFallbackAndDuplicateViewIds() {
    var connections = new ClientConnectionRegistry();
    var selector = new ClientViewSelector(connections, ClientViewSchemaRegistry.versionOne());
    var view = view(ClientViewType.TEXT);

    assertEquals(
        "CLIENT_VIEW_SET_INVALID",
        assertThrows(
                ClientProtocolException.class,
                () -> selector.select(PLAYER, "different", List.of(view)))
            .code());
    assertEquals(
        "CLIENT_VIEW_SET_INVALID",
        assertThrows(
                ClientProtocolException.class,
                () -> selector.select(PLAYER, FALLBACK, List.of(view, view)))
            .code());
  }

  @Test
  void serverRegistryPublishesOnlyTypesImplementedByThePhaseTenDecoder() {
    var connections = new ClientConnectionRegistry();
    connections.join(PLAYER);
    var connection = connections.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 1));
    var schemas = ClientViewSchemaRegistry.versionOne();

    assertTrue(schemas.canPublish(connection, ClientViewType.TEXT, "1.0"));
    assertTrue(schemas.canPublish(connection, ClientViewType.RECIPE, "1.0"));
    assertFalse(schemas.canPublish(connection, ClientViewType.BUILD_PREVIEW, "1.0"));
    assertFalse(schemas.canPublish(connection, ClientViewType.PROPOSAL, "1.0"));
    assertFalse(schemas.canPublish(connection, ClientViewType.SELECTION_LIST, "1.0"));
  }

  @Test
  void recipeV2RequiresTheExplicitVersionTwoCapability() throws Exception {
    var connections = new ClientConnectionRegistry();
    connections.join(PLAYER);
    var selector = new ClientViewSelector(connections, ClientViewSchemaRegistry.versionOne());
    var content =
        JsonParser.parseString(
                Files.readString(
                    Path.of(System.getProperty("minecraftAgent.protocolDir"))
                        .resolve("fixtures/valid/recipe-view-v2.json")))
            .getAsJsonObject();
    var view =
        new ClientStructuredView(
            "1.0",
            UUID.randomUUID(),
            REQUEST,
            ClientViewType.RECIPE,
            1,
            "Recipes",
            FALLBACK,
            true,
            content);

    connections.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 1, 0));
    assertTrue(selector.select(PLAYER, FALLBACK, List.of(view)).usesFallbackOnly());
    connections.replace(PLAYER, ClientConnectionRegistryTest.handshake(1, 2, 0));
    assertEquals(List.of(view), selector.select(PLAYER, FALLBACK, List.of(view)).structuredViews());
  }

  @Test
  void fallbackLimitCountsUnicodeCodePointsInsteadOfUtf16Units() {
    var selector =
        new ClientViewSelector(
            new ClientConnectionRegistry(), ClientViewSchemaRegistry.versionOne());
    var supplementaryText = "\ud83d\ude00".repeat(5000);

    assertTrue(selector.select(PLAYER, supplementaryText, List.of()).usesFallbackOnly());
  }

  @Test
  void fallbackRejectsControlsAndBidirectionalFormattingButAllowsLinesAndTabs() {
    var selector =
        new ClientViewSelector(
            new ClientConnectionRegistry(), ClientViewSchemaRegistry.versionOne());

    assertTrue(selector.select(PLAYER, "line one\n\tline two", List.of()).usesFallbackOnly());
    for (var unsafe : List.of("left\u0000right", "left\u0085right", "left\u202eright")) {
      assertEquals(
          "CLIENT_FALLBACK_INVALID",
          assertThrows(
                  ClientProtocolException.class, () -> selector.select(PLAYER, unsafe, List.of()))
              .code());
    }
  }

  static ClientStructuredView view(ClientViewType type) {
    var content = type == ClientViewType.RECIPE ? recipeContent() : textContent();
    return new ClientStructuredView(
        "1.0", UUID.randomUUID(), REQUEST, type, 1, "Result", FALLBACK, true, content);
  }

  private static JsonObject textContent() {
    var content = new JsonObject();
    content.addProperty("text", "Trusted fixed content");
    return content;
  }

  private static JsonObject recipeContent() {
    var components = new JsonObject();
    var stack = new JsonObject();
    stack.addProperty("itemId", "minecraft:stone");
    stack.addProperty("count", 1);
    stack.add("components", components);

    var choice = new JsonObject();
    choice.addProperty("choiceType", "material");
    var alternatives = new com.google.gson.JsonArray();
    alternatives.add(stack.deepCopy());
    choice.add("alternatives", alternatives);

    var slot = new JsonObject();
    slot.addProperty("slot", 0);
    slot.addProperty("x", 0);
    slot.addProperty("y", 0);
    slot.add("ingredient", choice);
    var ingredients = new com.google.gson.JsonArray();
    ingredients.add(slot);
    var layout = new JsonObject();
    layout.addProperty("width", 1);
    layout.addProperty("height", 1);
    layout.add("ingredients", ingredients);

    var source = new JsonObject();
    source.addProperty("kind", "server_registry");
    source.addProperty("providerId", "minecraft:stone");
    var recipe = new JsonObject();
    recipe.addProperty("recipeId", "minecraft:stone");
    recipe.addProperty("recipeType", "shapeless");
    recipe.add("source", source);
    recipe.add("result", stack);
    recipe.add("layout", layout);
    recipe.add("remainingItems", new com.google.gson.JsonArray());
    var recipes = new com.google.gson.JsonArray();
    recipes.add(recipe);

    var query = new JsonObject();
    query.addProperty("mode", "lookup");
    query.addProperty("itemId", "minecraft:stone");
    var content = new JsonObject();
    content.addProperty("schemaVersion", "1.0");
    content.add("query", query);
    content.addProperty("selectedRecipe", 0);
    content.add("recipes", recipes);
    return content;
  }
}
