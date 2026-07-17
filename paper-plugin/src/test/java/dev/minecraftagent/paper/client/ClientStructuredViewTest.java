package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientStructuredViewTest {
  @Test
  void strictlyDecodesClosedTextViewAndDefensivelyCopiesContent() {
    var json = viewJson("text", text("hello"));
    var view = ClientStructuredView.fromJson(json);

    json.getAsJsonObject("content").addProperty("command", "/op me");
    assertEquals("hello", view.content().get("text").getAsString());
    assertEquals(view.toJson(), ClientStructuredView.fromJson(view.toJson()).toJson());
  }

  @Test
  void acceptsLineFormattingInTextAndFallbackButNotInTitles() {
    var json = viewJson("text", text("line 1\n\tline 2"));
    json.addProperty("fallbackText", "line 1\n\tline 2");
    assertEquals("line 1\n\tline 2", ClientStructuredView.fromJson(json).fallbackText());

    json.addProperty("title", "bad\ntitle");
    assertEquals(
        "CLIENT_VIEW_TITLE_INVALID",
        assertThrows(ClientProtocolException.class, () -> ClientStructuredView.fromJson(json))
            .code());
  }

  @Test
  void rejectsArbitraryWidgetFieldsAndUnsafeItemComponents() {
    var arbitrary = text("hello");
    arbitrary.addProperty("clickCommand", "/op me");
    assertInvalid(viewJson("text", arbitrary));

    var components = new JsonObject();
    components.addProperty("nbt", "{op:1}");
    var stack = new JsonObject();
    stack.addProperty("itemId", "minecraft:stone");
    stack.addProperty("count", 1);
    stack.add("components", components);
    assertInvalid(viewJson("item_stack", stack));
  }

  @Test
  void acceptsClosedItemStackAndItemListComponents() {
    var components = new JsonObject();
    components.addProperty("customName", "Stone");
    components.addProperty("enchantmentGlint", false);
    var stack = new JsonObject();
    stack.addProperty("itemId", "minecraft:stone");
    stack.addProperty("count", 64);
    stack.add("components", components);

    assertEquals(
        "minecraft:stone",
        ClientStructuredView.fromJson(viewJson("item_stack", stack.deepCopy()))
            .content()
            .get("itemId")
            .getAsString());
    var items = new JsonArray();
    items.add(stack);
    var list = new JsonObject();
    list.add("items", items);
    assertEquals(
        1,
        ClientStructuredView.fromJson(viewJson("item_list", list))
            .content()
            .getAsJsonArray("items")
            .size());
  }

  @Test
  void rejectsUndeclaredRecipeFieldsAndOversizeItemLists() {
    var validRecipe = ClientViewSelectorTest.view(ClientViewType.RECIPE).content();
    validRecipe.getAsJsonArray("recipes").get(0).getAsJsonObject().addProperty("widget", "button");
    assertInvalid(viewJson("recipe", validRecipe));

    var items = new JsonArray();
    var item = new JsonObject();
    item.addProperty("itemId", "minecraft:stone");
    item.addProperty("count", 1);
    item.add("components", new JsonObject());
    for (var index = 0; index < 129; index++) {
      items.add(item.deepCopy());
    }
    var list = new JsonObject();
    list.add("items", items);
    assertInvalid(viewJson("item_list", list));
  }

  @Test
  void rejectsInvalidDamageDuplicateRemainingSlotsAndBidiText() {
    var components = new JsonObject();
    components.addProperty("damage", 3);
    components.addProperty("maxDamage", 2);
    var stack = new JsonObject();
    stack.addProperty("itemId", "minecraft:stone");
    stack.addProperty("count", 1);
    stack.add("components", components);
    assertInvalid(viewJson("item_stack", stack));

    var recipe = ClientViewSelectorTest.view(ClientViewType.RECIPE).content();
    var remaining =
        recipe.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonArray("remainingItems");
    var result =
        recipe.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("result");
    for (var index = 0; index < 2; index++) {
      var entry = new JsonObject();
      entry.addProperty("slot", 0);
      entry.add("item", result.deepCopy());
      remaining.add(entry);
    }
    assertInvalid(viewJson("recipe", recipe));
    assertInvalid(viewJson("text", text("safe\u202etext")));
  }

  @Test
  void acceptsTheSharedRecipeV2LayoutsAndDynamicResult() throws Exception {
    var content =
        JsonParser.parseString(
                Files.readString(
                    Path.of(System.getProperty("minecraftAgent.protocolDir"))
                        .resolve("fixtures/valid/recipe-view-v2.json")))
            .getAsJsonObject();

    var decoded = ClientStructuredView.fromJson(viewJson("recipe", content));

    assertEquals("2.0", decoded.content().get("schemaVersion").getAsString());
    assertEquals(6, decoded.content().getAsJsonArray("recipes").size());
  }

  private static JsonObject viewJson(String type, JsonObject content) {
    var view = new JsonObject();
    view.addProperty("viewSchemaVersion", "1.0");
    view.addProperty("viewId", UUID.randomUUID().toString());
    view.addProperty("requestId", UUID.randomUUID().toString());
    view.addProperty("viewType", type);
    view.addProperty("revision", 1);
    view.addProperty("title", "Result");
    view.addProperty("fallbackText", "fallback");
    view.addProperty("pinnable", true);
    view.add("content", content);
    return view;
  }

  private static JsonObject text(String value) {
    var content = new JsonObject();
    content.addProperty("text", value);
    return content;
  }

  private static void assertInvalid(JsonObject view) {
    assertEquals(
        "CLIENT_VIEW_CONTENT_INVALID",
        assertThrows(ClientProtocolException.class, () -> ClientStructuredView.fromJson(view))
            .code());
  }
}
