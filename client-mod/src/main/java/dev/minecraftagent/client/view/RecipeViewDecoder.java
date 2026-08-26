package dev.minecraftagent.client.view;

import dev.minecraftagent.client.view.ItemStackDecoder.DecodeBudget;
import dev.minecraftagent.client.view.RecipeView.ChoiceType;
import dev.minecraftagent.client.view.RecipeView.GridLayout;
import dev.minecraftagent.client.view.RecipeView.IngredientChoice;
import dev.minecraftagent.client.view.RecipeView.IngredientSlot;
import dev.minecraftagent.client.view.RecipeView.Layout;
import dev.minecraftagent.client.view.RecipeView.Processing;
import dev.minecraftagent.client.view.RecipeView.Query;
import dev.minecraftagent.client.view.RecipeView.QueryMode;
import dev.minecraftagent.client.view.RecipeView.Recipe;
import dev.minecraftagent.client.view.RecipeView.RecipeType;
import dev.minecraftagent.client.view.RecipeView.RemainingItem;
import dev.minecraftagent.client.view.RecipeView.SingleInputLayout;
import dev.minecraftagent.client.view.RecipeView.SmithingLayout;
import dev.minecraftagent.client.view.RecipeView.Source;
import dev.minecraftagent.client.view.RecipeView.SourceKind;
import dev.minecraftagent.client.view.RecipeView.TransmuteLayout;
import dev.minecraftagent.client.view.RecipeView.UnsupportedChoiceReason;
import dev.minecraftagent.client.view.RecipeView.UnsupportedLayout;
import dev.minecraftagent.client.view.RecipeView.UnsupportedLayoutReason;
import dev.minecraftagent.protocol.StructuredViewContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Decodes recipe views (schema 2.0), including every layout family and ingredient choice. */
final class RecipeViewDecoder {
  private RecipeViewDecoder() {}

  static RecipeView decodeRecipeView(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node,
            Set.of(
                "schemaVersion", "query", "selectedRecipe", "totalMatches", "truncated", "recipes"),
            Set.of(
                "schemaVersion",
                "query",
                "selectedRecipe",
                "totalMatches",
                "truncated",
                "recipes"));
    String schemaVersion = JsonValues.string(object, "schemaVersion", 3, 3, false);
    if (!StructuredViewContract.RECIPE_SCHEMA_VERSION_V2.equals(schemaVersion)) {
      JsonValues.invalidValue();
    }
    Query query = decodeQuery(object.fields().get("query"));
    int selectedRecipe = JsonValues.integer(object.fields().get("selectedRecipe"), 0, 15);
    int totalMatches = JsonValues.integer(object.fields().get("totalMatches"), 1, 1_000_000);
    boolean truncated = JsonValues.bool(object.fields().get("truncated"));
    List<JsonNode> values = JsonValues.array(object.fields().get("recipes"), 1, 16);
    if (selectedRecipe >= values.size()) {
      JsonValues.invalidValue();
    }
    List<Recipe> recipes = new ArrayList<>(values.size());
    Set<String> recipeIds = new HashSet<>();
    for (JsonNode value : values) {
      Recipe recipe = decodeRecipe(value, budget);
      if (!recipeIds.add(recipe.recipeId())) {
        JsonValues.invalidValue();
      }
      recipes.add(recipe);
    }
    if (totalMatches < recipes.size() || (!truncated && totalMatches != recipes.size())) {
      JsonValues.invalidValue();
    }
    return new RecipeView(schemaVersion, query, selectedRecipe, totalMatches, truncated, recipes);
  }

  private static Query decodeQuery(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(node, Set.of("mode", "itemId"), Set.of("mode", "itemId"));
    QueryMode mode =
        JsonValues.enumValue(JsonValues.string(object, "mode", 1, 16, false), QueryMode.class);
    return new Query(
        mode, JsonValues.namespacedId(JsonValues.string(object, "itemId", 3, 256, false)));
  }

  private static Recipe decodeRecipe(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node,
            Set.of(
                "recipeId",
                "recipeType",
                "source",
                "result",
                "layout",
                "remainingItems",
                "processing"),
            Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems"));
    String recipeId = JsonValues.namespacedId(JsonValues.string(object, "recipeId", 3, 256, false));
    RecipeType recipeType =
        JsonValues.enumValue(
            JsonValues.string(object, "recipeType", 1, 32, false), RecipeType.class);
    Source source = decodeSource(object.fields().get("source"));
    Optional<ItemStackView> result = Optional.empty();
    if (object.fields().get("result") != JsonNull.INSTANCE) {
      result = Optional.of(ItemStackDecoder.decodeItemStack(object.fields().get("result"), budget));
    }
    Set<Integer> logicalSlots = new HashSet<>();
    Layout layout = decodeLayout(object.fields().get("layout"), budget, recipeType, logicalSlots);
    List<RemainingItem> remainingItems =
        decodeRemainingItems(object.fields().get("remainingItems"), budget, logicalSlots);
    Optional<Processing> processing = Optional.empty();
    if (object.fields().containsKey("processing")) {
      processing = Optional.of(decodeProcessing(object.fields().get("processing")));
    }
    if (isCooking(recipeType) && processing.isEmpty()) {
      throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
    }
    if (!isCooking(recipeType) && processing.isPresent()) {
      JsonValues.invalidValue();
    }
    return new Recipe(recipeId, recipeType, source, result, layout, remainingItems, processing);
  }

  private static Source decodeSource(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(node, Set.of("kind", "providerId"), Set.of("kind", "providerId"));
    SourceKind kind =
        JsonValues.enumValue(JsonValues.string(object, "kind", 1, 32, false), SourceKind.class);
    JsonNode provider = object.fields().get("providerId");
    Optional<String> providerId;
    if (provider == JsonNull.INSTANCE) {
      providerId = Optional.empty();
    } else {
      providerId =
          Optional.of(JsonValues.namespacedId(JsonValues.visibleString(provider, 3, 256, false)));
    }
    if ((kind == SourceKind.SERVER_REGISTRY) == providerId.isPresent()) {
      JsonValues.invalidValue();
    }
    return new Source(kind, providerId);
  }

  private static Layout decodeLayout(
      JsonNode node, DecodeBudget budget, RecipeType recipeType, Set<Integer> logicalSlots)
      throws ViewDecodeException {
    if (!(node instanceof JsonObject object)) {
      JsonValues.invalidValue();
      throw new AssertionError();
    }
    String kind = JsonValues.string(object, "kind", 1, 16, false);
    if (!expectedLayoutKind(recipeType).equals(kind)) {
      JsonValues.invalidValue();
    }
    return switch (kind) {
      case "grid" -> decodeGridLayout(object, budget, logicalSlots);
      case "single_input" -> {
        JsonObject exact =
            JsonValues.closedObject(
                object, Set.of("kind", "ingredient"), Set.of("kind", "ingredient"));
        logicalSlots.add(0);
        yield new SingleInputLayout(
            decodeIngredientChoice(exact.fields().get("ingredient"), budget));
      }
      case "smithing" -> {
        JsonObject exact =
            JsonValues.closedObject(
                object,
                Set.of("kind", "template", "base", "addition"),
                Set.of("kind", "template", "base", "addition"));
        logicalSlots.addAll(Set.of(0, 1, 2));
        yield new SmithingLayout(
            decodeIngredientChoice(exact.fields().get("template"), budget),
            decodeIngredientChoice(exact.fields().get("base"), budget),
            decodeIngredientChoice(exact.fields().get("addition"), budget));
      }
      case "transmute" -> {
        JsonObject exact =
            JsonValues.closedObject(
                object, Set.of("kind", "input", "material"), Set.of("kind", "input", "material"));
        logicalSlots.addAll(Set.of(0, 1));
        yield new TransmuteLayout(
            decodeIngredientChoice(exact.fields().get("input"), budget),
            decodeIngredientChoice(exact.fields().get("material"), budget));
      }
      case "unsupported" -> {
        JsonObject exact =
            JsonValues.closedObject(object, Set.of("kind", "reason"), Set.of("kind", "reason"));
        yield new UnsupportedLayout(
            JsonValues.enumValue(
                JsonValues.string(exact, "reason", 1, 64, false), UnsupportedLayoutReason.class));
      }
      default -> throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE);
    };
  }

  private static GridLayout decodeGridLayout(
      JsonObject input, DecodeBudget budget, Set<Integer> logicalSlots) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            input,
            Set.of("kind", "width", "height", "ingredients"),
            Set.of("kind", "width", "height", "ingredients"));
    int width = JsonValues.integer(object.fields().get("width"), 1, 3);
    int height = JsonValues.integer(object.fields().get("height"), 1, 3);
    List<JsonNode> values = JsonValues.array(object.fields().get("ingredients"), 1, 9);
    List<IngredientSlot> ingredients = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    Set<Integer> positions = new HashSet<>();
    for (JsonNode value : values) {
      IngredientSlot ingredient = decodeIngredientSlot(value, budget);
      if (ingredient.x() >= width
          || ingredient.y() >= height
          || ingredient.slot() != ingredient.y() * width + ingredient.x()) {
        JsonValues.invalidValue();
      }
      if (!slots.add(ingredient.slot()) || !positions.add(ingredient.y() * 3 + ingredient.x())) {
        JsonValues.invalidValue();
      }
      logicalSlots.add(ingredient.slot());
      ingredients.add(ingredient);
    }
    return new GridLayout(width, height, ingredients);
  }

  private static IngredientSlot decodeIngredientSlot(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node, Set.of("slot", "x", "y", "ingredient"), Set.of("slot", "x", "y", "ingredient"));
    return new IngredientSlot(
        JsonValues.integer(object.fields().get("slot"), 0, 8),
        JsonValues.integer(object.fields().get("x"), 0, 2),
        JsonValues.integer(object.fields().get("y"), 0, 2),
        decodeIngredientChoice(object.fields().get("ingredient"), budget));
  }

  private static IngredientChoice decodeIngredientChoice(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node,
            Set.of("choiceType", "tagId", "reason", "alternatives"),
            Set.of("choiceType", "alternatives"));
    ChoiceType choiceType =
        JsonValues.enumValue(
            JsonValues.string(object, "choiceType", 1, 16, false), ChoiceType.class);
    Optional<String> tagId = Optional.empty();
    if (object.fields().containsKey("tagId")) {
      tagId =
          Optional.of(JsonValues.namespacedId(JsonValues.string(object, "tagId", 3, 256, false)));
    }
    Optional<UnsupportedChoiceReason> reason = Optional.empty();
    if (object.fields().containsKey("reason")) {
      reason =
          Optional.of(
              JsonValues.enumValue(
                  JsonValues.string(object, "reason", 1, 64, false),
                  UnsupportedChoiceReason.class));
    }
    boolean unsupported = choiceType == ChoiceType.UNSUPPORTED;
    if ((choiceType == ChoiceType.TAG) != tagId.isPresent()
        || unsupported != reason.isPresent()
        || unsupported && tagId.isPresent()) {
      JsonValues.invalidValue();
    }
    List<JsonNode> values =
        JsonValues.array(
            object.fields().get("alternatives"), unsupported ? 0 : 1, unsupported ? 0 : 64);
    List<ItemStackView> alternatives = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      alternatives.add(ItemStackDecoder.decodeItemStack(value, budget));
    }
    return new IngredientChoice(choiceType, tagId, reason, alternatives);
  }

  private static List<RemainingItem> decodeRemainingItems(
      JsonNode node, DecodeBudget budget, Set<Integer> logicalSlots) throws ViewDecodeException {
    List<JsonNode> values = JsonValues.array(node, 0, 9);
    List<RemainingItem> result = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    for (JsonNode value : values) {
      JsonObject object =
          JsonValues.closedObject(value, Set.of("slot", "item"), Set.of("slot", "item"));
      int slot = JsonValues.integer(object.fields().get("slot"), 0, 8);
      if (!logicalSlots.contains(slot) || !slots.add(slot)) {
        JsonValues.invalidValue();
      }
      result.add(
          new RemainingItem(
              slot, ItemStackDecoder.decodeItemStack(object.fields().get("item"), budget)));
    }
    return List.copyOf(result);
  }

  private static Processing decodeProcessing(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node, Set.of("timeTicks", "experience"), Set.of("timeTicks", "experience"));
    return new Processing(
        JsonValues.integer(object.fields().get("timeTicks"), 0, 120000),
        JsonValues.decimal(
            object.fields().get("experience"), BigDecimal.ZERO, new BigDecimal("1000000")));
  }

  private static String expectedLayoutKind(RecipeType type) {
    return switch (type) {
      case SHAPED, SHAPELESS -> "grid";
      case SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING, STONECUTTING -> "single_input";
      case SMITHING_TRANSFORM, SMITHING_TRIM -> "smithing";
      case TRANSMUTE -> "transmute";
      case COMPLEX, CUSTOM -> "unsupported";
    };
  }

  private static boolean isCooking(RecipeType type) {
    return type == RecipeType.SMELTING
        || type == RecipeType.BLASTING
        || type == RecipeType.SMOKING
        || type == RecipeType.CAMPFIRE_COOKING;
  }
}
