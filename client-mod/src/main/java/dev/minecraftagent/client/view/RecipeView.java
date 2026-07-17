package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed client model for authoritative recipe-view-v2 content. */
public record RecipeView(
    String schemaVersion,
    Query query,
    int selectedRecipe,
    int totalMatches,
    boolean truncated,
    List<Recipe> recipes)
    implements ViewContent {

  public RecipeView {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(query, "query");
    recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
  }

  public record Query(QueryMode mode, String itemId) {
    public Query {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(itemId, "itemId");
    }
  }

  public enum QueryMode {
    LOOKUP,
    USES
  }

  public record Recipe(
      String recipeId,
      RecipeType recipeType,
      Source source,
      Optional<ItemStackView> result,
      Layout layout,
      List<RemainingItem> remainingItems,
      Optional<Processing> processing) {

    public Recipe {
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(recipeType, "recipeType");
      Objects.requireNonNull(source, "source");
      result = Objects.requireNonNull(result, "result");
      Objects.requireNonNull(layout, "layout");
      remainingItems = List.copyOf(Objects.requireNonNull(remainingItems, "remainingItems"));
      processing = Objects.requireNonNull(processing, "processing");
    }
  }

  public enum RecipeType {
    SHAPED,
    SHAPELESS,
    SMELTING,
    BLASTING,
    SMOKING,
    CAMPFIRE_COOKING,
    STONECUTTING,
    SMITHING_TRANSFORM,
    SMITHING_TRIM,
    TRANSMUTE,
    COMPLEX,
    CUSTOM
  }

  public record Source(SourceKind kind, Optional<String> providerId) {
    public Source {
      Objects.requireNonNull(kind, "kind");
      providerId = Objects.requireNonNull(providerId, "providerId");
    }
  }

  public enum SourceKind {
    SERVER_REGISTRY,
    PLUGIN_PROVIDER
  }

  public sealed interface Layout
      permits GridLayout, SingleInputLayout, SmithingLayout, TransmuteLayout, UnsupportedLayout {}

  public record GridLayout(int width, int height, List<IngredientSlot> ingredients)
      implements Layout {
    public GridLayout {
      ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
    }
  }

  public record SingleInputLayout(IngredientChoice ingredient) implements Layout {
    public SingleInputLayout {
      Objects.requireNonNull(ingredient, "ingredient");
    }
  }

  public record SmithingLayout(
      IngredientChoice template, IngredientChoice base, IngredientChoice addition)
      implements Layout {
    public SmithingLayout {
      Objects.requireNonNull(template, "template");
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(addition, "addition");
    }
  }

  public record TransmuteLayout(IngredientChoice input, IngredientChoice material)
      implements Layout {
    public TransmuteLayout {
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(material, "material");
    }
  }

  public record UnsupportedLayout(UnsupportedLayoutReason reason) implements Layout {
    public UnsupportedLayout {
      Objects.requireNonNull(reason, "reason");
    }
  }

  public enum UnsupportedLayoutReason {
    UNSUPPORTED_RECIPE_LAYOUT
  }

  public record IngredientSlot(int slot, int x, int y, IngredientChoice ingredient) {
    public IngredientSlot {
      Objects.requireNonNull(ingredient, "ingredient");
    }
  }

  public record IngredientChoice(
      ChoiceType choiceType,
      Optional<String> tagId,
      Optional<UnsupportedChoiceReason> reason,
      List<ItemStackView> alternatives) {
    public IngredientChoice {
      Objects.requireNonNull(choiceType, "choiceType");
      tagId = Objects.requireNonNull(tagId, "tagId");
      reason = Objects.requireNonNull(reason, "reason");
      alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
    }
  }

  public enum ChoiceType {
    MATERIAL,
    EXACT,
    ITEM_TYPE,
    TAG,
    UNSUPPORTED
  }

  public enum UnsupportedChoiceReason {
    UNSUPPORTED_INGREDIENT_CHOICE
  }

  public record RemainingItem(int slot, ItemStackView item) {
    public RemainingItem {
      Objects.requireNonNull(item, "item");
    }
  }

  public record Processing(int timeTicks, double experience) {}
}
