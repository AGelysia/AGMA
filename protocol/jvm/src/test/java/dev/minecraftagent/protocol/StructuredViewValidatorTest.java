package dev.minecraftagent.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the shared structured-view validation, driven by the protocol fixtures the
 * schemas are validated against, plus the shared-path assertions carried over from the Fabric
 * client mod's decoder test.
 */
final class StructuredViewValidatorTest {
  private static final String INVALID = "VIEW_CONTENT_INVALID";
  private static final String SCHEMA_UNSUPPORTED = "VIEW_SCHEMA_UNSUPPORTED";
  private static final String TYPE_UNKNOWN = "VIEW_TYPE_UNKNOWN";

  private final StructuredViewValidator validator =
      new StructuredViewValidator(INVALID, SCHEMA_UNSUPPORTED, TYPE_UNKNOWN);
  private final Path fixtures =
      Path.of(System.getProperty("minecraftAgent.protocolDir"))
          .toAbsolutePath()
          .normalize()
          .resolve("fixtures");

  @Test
  void acceptsEveryValidStructuredViewFixture() throws IOException {
    var expectations = List.of("text", "item_stack", "recipe");
    var names =
        List.of(
            "valid/structured-text-view.json",
            "valid/structured-item-stack-view.json",
            "valid/structured-recipe-v2.json");
    for (var index = 0; index < names.size(); index++) {
      var view = validator.validate(readFixture(names.get(index)));
      assertEquals("1.0", view.viewSchemaVersion());
      assertEquals(expectations.get(index), view.viewType());
      assertEquals(1, view.revision());
      assertEquals(true, view.pinnable());
      assertEquals(false, view.title().isEmpty());
      assertEquals(false, view.fallbackText().isEmpty());
    }
  }

  @Test
  void rejectsEveryInvalidStructuredViewFixture() throws IOException {
    for (var name :
        List.of(
            "invalid/structured-item-stack-content-invalid.json",
            "invalid/structured-item-list-content-invalid.json",
            "invalid/structured-recipe-content-invalid.json",
            "invalid/structured-build-preview-content-invalid.json",
            "invalid/structured-proposal-content-invalid.json")) {
      assertEquals(
          INVALID,
          assertThrows(
                  ProtocolViolationException.class,
                  () -> validator.validate(readFixture(name)),
                  () -> "expected rejection of " + name)
              .code());
    }
  }

  @Test
  void acceptsEveryValidContentFixtureInsideAnEnvelope() throws IOException {
    for (var name :
        List.of(
            "valid/recipe-view.json",
            "valid/recipe-view-v2.json",
            "valid/build-preview.json",
            "valid/build-preview-out-of-order-chunks.json",
            "valid/build-preview-empty-target.json",
            "valid/proposal.json")) {
      var type =
          name.contains("build-preview")
              ? "build_preview"
              : name.contains("recipe") ? "recipe" : "proposal";
      var view = validator.validate(view(type, readFixture(name)));
      assertEquals(type, view.viewType());
    }
  }

  @Test
  void reportsSchemaAndTypePolicyWithTheCallerSuppliedCodes() throws IOException {
    var unsupported = view("recipe", readFixture("valid/recipe-view.json"));
    unsupported.putString("viewSchemaVersion", "9.9");
    assertEquals(
        SCHEMA_UNSUPPORTED,
        assertThrows(ProtocolViolationException.class, () -> validator.validate(unsupported))
            .code());

    var unknown = view("recipe", readFixture("valid/recipe-view.json"));
    unknown.putString("viewType", "hologram");
    assertEquals(
        TYPE_UNKNOWN,
        assertThrows(ProtocolViolationException.class, () -> validator.validate(unknown)).code());
  }

  @Test
  void acceptsEnvelopeFieldsInAnyOrder() {
    var reordered =
        (WireJson.ObjectNode)
            WireJsonReader.parse(
                "{\"content\":{\"text\":\"ok\"},\"pinnable\":false,"
                    + "\"fallbackText\":\"fallback\",\"title\":\"Title\",\"revision\":2,"
                    + "\"viewType\":\"text\","
                    + "\"requestId\":\"00000000-0000-0000-0000-000000000002\","
                    + "\"viewId\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"viewSchemaVersion\":\"1.0\"}",
                ClientPayloadFrames.BUDGET);
    assertEquals(2, validator.validate(reordered).revision());
  }

  @Test
  void acceptsLineFormattingOnlyWhereTheSchemaAllowsIt() {
    assertEquals(
        "line 1\n\tline 2",
        ((WireJson.TextNode)
                validator.validate(view("text", text("line 1\n\tline 2"))).content().get("text"))
            .value());
    assertInvalid(view("text", text("left‮right")));
    assertInvalid(view("text", text("bad\ud800")));
  }

  @Test
  void rejectsUnknownWidgetFieldsAndUnsafeComponents() {
    var arbitrary = text("ok");
    arbitrary.put("style", new WireJson.ObjectNode());
    assertInvalid(view("text", arbitrary));

    var stack =
        new WireJson.ObjectNode()
            .putString("itemId", "minecraft:stone")
            .putNumber("count", "1")
            .put("components", new WireJson.ObjectNode().putString("nbt", "{op:1}"));
    assertInvalid(view("item_stack", stack));
  }

  @Test
  void rejectsItemListsBeyondTheSchemaBound() {
    var item =
        new WireJson.ObjectNode()
            .putString("itemId", "minecraft:stone")
            .putNumber("count", "1")
            .put("components", new WireJson.ObjectNode());
    var items = new WireJson.ArrayNode();
    for (var index = 0; index < StructuredViewContract.ITEM_LIST_MAX_ITEMS; index++) {
      items.add(item.deepCopy());
    }
    var list = new WireJson.ObjectNode().put("items", items);
    assertEquals("item_list", validator.validate(view("item_list", list)).viewType());
    items.add(item.deepCopy());
    assertInvalid(view("item_list", list));
  }

  @Test
  void rejectsRecipeSemanticsThatCrossFields() throws IOException {
    var selectedOutOfRange = recipeContent().putNumber("selectedRecipe", "6");
    assertInvalid(view("recipe", selectedOutOfRange));

    var tagWithoutTagId = recipeContent();
    var recipe = (WireJson.ObjectNode) ((WireJson.ArrayNode) tagWithoutTagId.get("recipes")).get(0);
    var layout = (WireJson.ObjectNode) recipe.get("layout");
    var ingredient = (WireJson.ObjectNode) ((WireJson.ArrayNode) layout.get("ingredients")).get(0);
    ((WireJson.ObjectNode) ingredient.get("ingredient")).putString("choiceType", "tag");
    assertInvalid(view("recipe", tagWithoutTagId));

    // The inverse crossing, carried over from the Fabric decoder test: a non-tag choice that
    // still carries a tagId.
    var nonTagWithTagId = recipeContent();
    var otherRecipe =
        (WireJson.ObjectNode) ((WireJson.ArrayNode) nonTagWithTagId.get("recipes")).get(0);
    var otherLayout = (WireJson.ObjectNode) otherRecipe.get("layout");
    var otherIngredient =
        (WireJson.ObjectNode) ((WireJson.ArrayNode) otherLayout.get("ingredients")).get(0);
    ((WireJson.ObjectNode) otherIngredient.get("ingredient"))
        .putString("choiceType", "exact")
        .putString("tagId", "minecraft:coals");
    assertInvalid(view("recipe", nonTagWithTagId));

    // An untruncated result set must report exactly the recipes it carries.
    var mismatchedTotal = recipeContent().putNumber("totalMatches", "2");
    assertInvalid(view("recipe", mismatchedTotal));

    assertEquals(
        "2.0",
        ((WireJson.TextNode)
                validator.validate(view("recipe", recipeContent())).content().get("schemaVersion"))
            .value());
  }

  @Test
  void rejectsDamageAboveMaxDamageAndDuplicateRemainingSlots() throws IOException {
    var stack =
        new WireJson.ObjectNode()
            .putString("itemId", "minecraft:stone")
            .putNumber("count", "1")
            .put(
                "components",
                new WireJson.ObjectNode().putNumber("damage", "3").putNumber("maxDamage", "2"));
    assertInvalid(view("item_stack", stack));

    var content = recipeContent();
    var recipe = (WireJson.ObjectNode) ((WireJson.ArrayNode) content.get("recipes")).get(0);
    var remaining = (WireJson.ArrayNode) recipe.get("remainingItems");
    var result = (WireJson.ObjectNode) recipe.get("result");
    for (var index = 0; index < 2; index++) {
      remaining.add(
          new WireJson.ObjectNode().putNumber("slot", "0").put("item", result.deepCopy()));
    }
    assertInvalid(view("recipe", content));
  }

  @Test
  void validatesPreviewPalettesChunksAndHashesTogether() throws IOException {
    assertEquals(
        "build_preview",
        validator
            .validate(view("build_preview", readFixture("valid/build-preview.json")))
            .viewType());

    var missingChunkIndex = readFixture("valid/build-preview.json");
    ((WireJson.ObjectNode) ((WireJson.ArrayNode) missingChunkIndex.get("chunks")).get(0))
        .putNumber("index", "1");
    assertInvalid(view("build_preview", missingChunkIndex));

    var badHash = readFixture("valid/build-preview.json");
    ((WireJson.ObjectNode) ((WireJson.ArrayNode) badHash.get("chunks")).get(0))
        .putString("sha256", "0".repeat(64));
    assertInvalid(view("build_preview", badHash));

    var wrongChunkCount = readFixture("valid/build-preview.json");
    wrongChunkCount.putNumber("chunkCount", "2");
    assertInvalid(view("build_preview", wrongChunkCount));

    var paletteShifted = readFixture("valid/build-preview.json");
    ((WireJson.ObjectNode) ((WireJson.ArrayNode) paletteShifted.get("palette")).get(0))
        .putNumber("id", "1");
    assertInvalid(view("build_preview", paletteShifted));
  }

  private void assertInvalid(WireJson.ObjectNode source) {
    assertEquals(
        INVALID,
        assertThrows(ProtocolViolationException.class, () -> validator.validate(source)).code());
  }

  private WireJson.ObjectNode view(String viewType, WireJson.ObjectNode content) {
    return new WireJson.ObjectNode()
        .putString("viewSchemaVersion", "1.0")
        .putString("viewId", "00000000-0000-0000-0000-000000000001")
        .putString("requestId", "00000000-0000-0000-0000-000000000002")
        .putString("viewType", viewType)
        .putNumber("revision", "1")
        .putString("title", "Title")
        .putString("fallbackText", "fallback")
        .putBoolean("pinnable", true)
        .put("content", content);
  }

  private static WireJson.ObjectNode text(String value) {
    return new WireJson.ObjectNode().putString("text", value);
  }

  private WireJson.ObjectNode recipeContent() throws IOException {
    return readFixture("valid/recipe-view-v2.json");
  }

  private WireJson.ObjectNode readFixture(String name) throws IOException {
    var path = fixtures.resolve(name).normalize();
    assertEquals(true, path.startsWith(fixtures));
    return (WireJson.ObjectNode)
        WireJsonReader.parse(
            Files.readString(path, StandardCharsets.UTF_8), ClientPayloadFrames.BUDGET);
  }
}
