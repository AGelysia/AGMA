package dev.minecraftagent.client.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decodes item stacks and item lists, including the safe component subset the client renders.
 *
 * <p>Stacks are budgeted per view: every materialized stack, wherever it appears in a view, draws
 * from the same {@link DecodeBudget} so a view cannot exceed {@link #MAX_ITEM_STACKS} stacks.
 */
final class ItemStackDecoder {
  static final int MAX_ITEM_STACKS = 2048;

  private ItemStackDecoder() {}

  static ItemListView decodeItemList(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object = JsonValues.closedObject(node, Set.of("items"), Set.of("items"));
    List<JsonNode> values = JsonValues.array(object.fields().get("items"), 1, 128);
    List<ItemStackView> items = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      items.add(decodeItemStack(value, budget));
    }
    return new ItemListView(items);
  }

  static ItemStackView decodeItemStack(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    budget.addItemStack();
    JsonObject object =
        JsonValues.closedObject(
            node, Set.of("itemId", "count", "components"), Set.of("itemId", "count", "components"));
    String itemId = JsonValues.namespacedId(JsonValues.string(object, "itemId", 3, 256, false));
    int count = JsonValues.integer(object.fields().get("count"), 1, 999999);
    return new ItemStackView(itemId, count, decodeComponents(object.fields().get("components")));
  }

  private static ItemStackView.SafeComponents decodeComponents(JsonNode node)
      throws ViewDecodeException {
    JsonObject object =
        JsonValues.closedObject(
            node,
            Set.of(
                "customName", "lore", "damage", "maxDamage", "customModelData", "enchantmentGlint"),
            Set.of());
    Optional<String> customName = JsonValues.optionalString(object, "customName", 0, 512, false);
    List<String> lore = new ArrayList<>();
    if (object.fields().containsKey("lore")) {
      for (JsonNode line : JsonValues.array(object.fields().get("lore"), 0, 32)) {
        lore.add(JsonValues.visibleString(line, 0, 512, false));
      }
    }
    Optional<Integer> damage = JsonValues.optionalInteger(object, "damage", 0, Integer.MAX_VALUE);
    Optional<Integer> maxDamage =
        JsonValues.optionalInteger(object, "maxDamage", 1, Integer.MAX_VALUE);
    if (damage.isPresent() && maxDamage.isPresent() && damage.get() > maxDamage.get()) {
      JsonValues.invalidValue();
    }
    Optional<Integer> customModelData =
        JsonValues.optionalInteger(object, "customModelData", 0, Integer.MAX_VALUE);
    Optional<Boolean> enchantmentGlint = JsonValues.optionalBoolean(object, "enchantmentGlint");
    return new ItemStackView.SafeComponents(
        customName, lore, damage, maxDamage, customModelData, enchantmentGlint);
  }

  /** Per-view budget shared by every content decoder that materializes item stacks. */
  static final class DecodeBudget {
    private int itemStacks;

    private void addItemStack() throws ViewDecodeException {
      if (++itemStacks > MAX_ITEM_STACKS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }
  }
}
