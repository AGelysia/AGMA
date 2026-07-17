package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ItemStackView(String itemId, int count, SafeComponents components)
    implements ViewContent {

  public ItemStackView {
    Objects.requireNonNull(itemId, "itemId");
    Objects.requireNonNull(components, "components");
  }

  public record SafeComponents(
      Optional<String> customName,
      List<String> lore,
      Optional<Integer> damage,
      Optional<Integer> maxDamage,
      Optional<Integer> customModelData,
      Optional<Boolean> enchantmentGlint) {

    public SafeComponents {
      customName = Objects.requireNonNull(customName, "customName");
      lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
      damage = Objects.requireNonNull(damage, "damage");
      maxDamage = Objects.requireNonNull(maxDamage, "maxDamage");
      customModelData = Objects.requireNonNull(customModelData, "customModelData");
      enchantmentGlint = Objects.requireNonNull(enchantmentGlint, "enchantmentGlint");
    }
  }
}
