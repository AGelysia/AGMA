package dev.minecraftagent.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RecipeViewV2DecoderTest {
  private final StructuredViewDecoder decoder = new StructuredViewDecoder();

  @Test
  void decodesEveryV2LayoutChoiceSummaryAndDynamicResult() throws Exception {
    String content =
        Files.readString(
            protocolDirectory().resolve("fixtures/valid/recipe-view-v2.json"),
            StandardCharsets.UTF_8);
    RecipeView view =
        assertInstanceOf(RecipeView.class, decoder.decode(envelope(content)).content());

    assertEquals("2.0", view.schemaVersion());
    assertEquals(2, view.selectedRecipe());
    assertEquals(9, view.totalMatches());
    assertTrue(view.truncated());
    assertEquals(6, view.recipes().size());

    RecipeView.GridLayout grid =
        assertInstanceOf(RecipeView.GridLayout.class, view.recipes().get(0).layout());
    assertEquals(
        RecipeView.ChoiceType.ITEM_TYPE, grid.ingredients().getFirst().ingredient().choiceType());
    assertInstanceOf(RecipeView.SingleInputLayout.class, view.recipes().get(1).layout());
    assertEquals(0, view.recipes().get(1).processing().orElseThrow().timeTicks());
    assertInstanceOf(RecipeView.SmithingLayout.class, view.recipes().get(3).layout());
    assertInstanceOf(RecipeView.TransmuteLayout.class, view.recipes().get(4).layout());
    assertInstanceOf(RecipeView.UnsupportedLayout.class, view.recipes().get(5).layout());
    assertFalse(view.recipes().get(5).result().isPresent());
  }

  @Test
  void preservesExplicitUnsupportedChoicesWithNoInventedAlternative() throws Exception {
    RecipeView view =
        assertInstanceOf(
            RecipeView.class, decoder.decode(envelope(unsupportedChoiceContent())).content());
    RecipeView.GridLayout layout =
        assertInstanceOf(RecipeView.GridLayout.class, view.recipes().getFirst().layout());
    RecipeView.IngredientChoice choice = layout.ingredients().getFirst().ingredient();

    assertEquals(RecipeView.ChoiceType.UNSUPPORTED, choice.choiceType());
    assertEquals(
        RecipeView.UnsupportedChoiceReason.UNSUPPORTED_INGREDIENT_CHOICE,
        choice.reason().orElseThrow());
    assertTrue(choice.alternatives().isEmpty());
  }

  @Test
  void rejectsV2SummaryLayoutSourceAndRemainingItemContradictions() {
    String content = unsupportedChoiceContent();
    assertInvalid(content.replace("\"totalMatches\":1", "\"totalMatches\":2"));
    assertInvalid(content.replace("\"kind\":\"grid\"", "\"kind\":\"single_input\""));
    assertInvalid(content.replace("\"providerId\":null", "\"providerId\":\"minecraft:vanilla\""));
    assertInvalid(
        content.replace(
            "\"remainingItems\":[]",
            "\"remainingItems\":[{\"slot\":8,\"item\":{\"itemId\":\"minecraft:bucket\",\"count\":1,\"components\":{}}}]"));
  }

  private void assertInvalid(String content) {
    ViewDecodeException exception =
        assertThrows(ViewDecodeException.class, () -> decoder.decode(envelope(content)));
    assertTrue(
        exception.code() == ViewDecodeException.Code.INVALID_VALUE
            || exception.code() == ViewDecodeException.Code.UNKNOWN_FIELD);
  }

  private static byte[] envelope(String content) {
    return ("{"
            + "\"viewSchemaVersion\":\"1.0\","
            + "\"viewId\":\"00000000-0000-4000-8000-000000000001\","
            + "\"requestId\":\"00000000-0000-4000-8000-000000000002\","
            + "\"viewType\":\"recipe\",\"revision\":1,\"title\":\"Recipes\","
            + "\"fallbackText\":\"Recipe fallback\",\"pinnable\":true,\"content\":"
            + content
            + "}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String unsupportedChoiceContent() {
    return """
        {
          "schemaVersion":"2.0",
          "query":{"mode":"lookup","itemId":"minecraft:barrier"},
          "selectedRecipe":0,
          "totalMatches":1,
          "truncated":false,
          "recipes":[{
            "recipeId":"example:unsupported_choice",
            "recipeType":"shaped",
            "source":{"kind":"server_registry","providerId":null},
            "result":{"itemId":"minecraft:barrier","count":1,"components":{}},
            "layout":{"kind":"grid","width":1,"height":1,"ingredients":[{
              "slot":0,"x":0,"y":0,
              "ingredient":{
                "choiceType":"unsupported",
                "reason":"UNSUPPORTED_INGREDIENT_CHOICE",
                "alternatives":[]
              }
            }]},
            "remainingItems":[]
          }]
        }
        """;
  }

  private static Path protocolDirectory() {
    return Path.of(System.getProperty("minecraftAgent.protocolDir"));
  }
}
