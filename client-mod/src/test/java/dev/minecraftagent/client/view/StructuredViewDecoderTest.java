package dev.minecraftagent.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class StructuredViewDecoderTest {
  private final StructuredViewDecoder decoder = new StructuredViewDecoder();

  @Test
  void decodesAllFourFixedViewTypes() throws Exception {
    StructuredView text = decoder.decode(envelope("text", "{\"text\":\"line 1\\n\\tline 2\"}"));
    assertInstanceOf(TextView.class, text.content());

    StructuredView item =
        decoder.decode(
            envelope(
                "item_stack",
                "{\"itemId\":\"minecraft:diamond\",\"count\":2,\"components\":{\"customName\":\"Gem\",\"lore\":[\"Safe\"],\"enchantmentGlint\":true}}"));
    assertEquals(2, assertInstanceOf(ItemStackView.class, item.content()).count());

    StructuredView list =
        decoder.decode(
            envelope(
                "item_list",
                "{\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":64,\"components\":{}}]}"));
    assertEquals(1, assertInstanceOf(ItemListView.class, list.content()).items().size());

    StructuredView recipe = decoder.decode(envelope("recipe", recipeContent()));
    RecipeView decodedRecipe = assertInstanceOf(RecipeView.class, recipe.content());
    assertEquals(1, decodedRecipe.recipes().size());
    RecipeView.GridLayout grid =
        assertInstanceOf(RecipeView.GridLayout.class, decodedRecipe.recipes().getFirst().layout());
    assertEquals(
        RecipeView.ChoiceType.TAG, grid.ingredients().getFirst().ingredient().choiceType());
  }

  @Test
  void acceptsEnvelopeFieldsInAnyOrder() throws Exception {
    byte[] payload =
        ("{\"content\":{\"text\":\"ok\"},"
                + "\"pinnable\":false,\"fallbackText\":\"fallback\",\"title\":\"Title\","
                + "\"revision\":2,\"viewType\":\"text\","
                + "\"requestId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"viewId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"viewSchemaVersion\":\"1.0\"}")
            .getBytes(StandardCharsets.UTF_8);
    assertEquals(2, decoder.decode(payload).revision());
  }

  @Test
  void bindsDecodedIdentityToTransferDescriptor() throws Exception {
    byte[] payload = envelope("text", "{\"text\":\"ok\"}");
    assertEquals(
        1,
        decoder
            .decode(
                payload,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1)
            .revision());
    ViewDecodeException mismatch =
        assertThrows(
            ViewDecodeException.class,
            () ->
                decoder.decode(
                    payload,
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    1));
    assertEquals(ViewDecodeException.Code.METADATA_MISMATCH, mismatch.code());
  }

  @Test
  void rejectsDuplicateUnknownAndUnsupportedFields() {
    assertCode(
        ViewDecodeException.Code.DUPLICATE_FIELD,
        "{\"viewSchemaVersion\":\"1.0\",\"viewSchemaVersion\":\"1.0\"}");
    assertCode(
        ViewDecodeException.Code.UNKNOWN_FIELD,
        new String(envelope("text", "{\"text\":\"ok\",\"style\":{}}"), StandardCharsets.UTF_8));
    assertCode(
        ViewDecodeException.Code.UNSUPPORTED_VIEW_TYPE,
        new String(envelope("proposal", "{}"), StandardCharsets.UTF_8));
  }

  @Test
  void rejectsMalformedEncodingTrailingJsonAndOversizedPayload() {
    ViewDecodeException invalidUtf8 =
        assertThrows(
            ViewDecodeException.class, () -> decoder.decode(new byte[] {(byte) 0xc3, 0x28}));
    assertEquals(ViewDecodeException.Code.INVALID_UTF8, invalidUtf8.code());

    assertCode(
        ViewDecodeException.Code.INVALID_JSON,
        new String(envelope("text", "{\"text\":\"ok\"}"), StandardCharsets.UTF_8) + " true");

    byte[] oversized = new byte[StructuredViewDecoder.MAX_PAYLOAD_BYTES + 1];
    ViewDecodeException tooLarge =
        assertThrows(ViewDecodeException.class, () -> decoder.decode(oversized));
    assertEquals(ViewDecodeException.Code.PAYLOAD_TOO_LARGE, tooLarge.code());
  }

  @Test
  void acceptsLowComplexityPayloadAboveFormerTransportLimit() throws Exception {
    String json =
        new String(envelope("text", "{\"text\":\"ok\"}"), StandardCharsets.UTF_8)
            + " ".repeat(300 * 1024);
    assertInstanceOf(
        TextView.class, decoder.decode(json.getBytes(StandardCharsets.UTF_8)).content());
  }

  @Test
  void rejectsUnsafeTextAndInvalidRecipeSemantics() {
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        new String(envelope("text", "{\"text\":\"left\\u202eright\"}"), StandardCharsets.UTF_8));
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        new String(envelope("text", "{\"text\":\"bad\\ud800\"}"), StandardCharsets.UTF_8));

    String invalidSelection =
        recipeContent().replace("\"selectedRecipe\":0", "\"selectedRecipe\":1");
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        new String(envelope("recipe", invalidSelection), StandardCharsets.UTF_8));

    String nonTagWithTag =
        recipeContent().replace("\"choiceType\":\"tag\"", "\"choiceType\":\"exact\"");
    assertCode(
        ViewDecodeException.Code.INVALID_VALUE,
        new String(envelope("recipe", nonTagWithTag), StandardCharsets.UTF_8));
  }

  @Test
  void rejectsUnboundedNumericTokensBeforeConversion() {
    String content =
        recipeContent()
            .replace(
                "\"remainingItems\":[]",
                "\"remainingItems\":[],\"processing\":{\"timeTicks\":1,\"experience\":"
                    + "1".repeat(65)
                    + "}")
            .replace("\"recipeType\":\"shaped\"", "\"recipeType\":\"smelting\"");
    assertCode(
        ViewDecodeException.Code.JSON_LIMIT_EXCEEDED,
        new String(envelope("recipe", content), StandardCharsets.UTF_8));
  }

  private void assertCode(ViewDecodeException.Code code, String json) {
    ViewDecodeException exception =
        assertThrows(
            ViewDecodeException.class, () -> decoder.decode(json.getBytes(StandardCharsets.UTF_8)));
    assertEquals(code, exception.code());
  }

  private static byte[] envelope(String type, String content) {
    return ("{"
            + "\"viewSchemaVersion\":\"1.0\","
            + "\"viewId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"requestId\":\"00000000-0000-0000-0000-000000000002\","
            + "\"viewType\":\""
            + type
            + "\",\"revision\":1,\"title\":\"Title\","
            + "\"fallbackText\":\"fallback\",\"pinnable\":true,\"content\":"
            + content
            + "}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String recipeContent() {
    return """
    {
      "schemaVersion":"2.0",
      "query":{"mode":"lookup","itemId":"minecraft:torch"},
      "selectedRecipe":0,
      "totalMatches":1,
      "truncated":false,
      "recipes":[{
        "recipeId":"minecraft:torch",
        "recipeType":"shaped",
        "source":{"kind":"server_registry","providerId":null},
        "result":{"itemId":"minecraft:torch","count":4,"components":{}},
        "layout":{"kind":"grid","width":1,"height":1,"ingredients":[{
          "slot":0,"x":0,"y":0,
          "ingredient":{"choiceType":"tag","tagId":"minecraft:coals","alternatives":[
            {"itemId":"minecraft:coal","count":1,"components":{}}
          ]}
        }]},
        "remainingItems":[]
      }]
    }
    """;
  }
}
