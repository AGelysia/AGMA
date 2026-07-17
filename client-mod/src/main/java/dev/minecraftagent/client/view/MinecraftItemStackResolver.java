package dev.minecraftagent.client.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

/** Maps the protocol's safe component subset to a real vanilla registry item. */
public final class MinecraftItemStackResolver {
  public ResolvedItemStack resolve(ItemStackView view) {
    Objects.requireNonNull(view, "view");
    Identifier identifier = Identifier.tryParse(view.itemId());
    if (identifier == null) {
      return ResolvedItemStack.missing(view, Resolution.MISSING_ITEM);
    }
    Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
    if (item == null) {
      return ResolvedItemStack.missing(view, Resolution.MISSING_ITEM);
    }

    try {
      ItemStack stack = new ItemStack(item, 1);
      applyComponents(stack, view.components());
      return new ResolvedItemStack(view, Resolution.AVAILABLE, stack);
    } catch (RuntimeException exception) {
      return ResolvedItemStack.missing(view, Resolution.INVALID_COMPONENTS);
    }
  }

  private static void applyComponents(ItemStack stack, ItemStackView.SafeComponents components) {
    components
        .customName()
        .ifPresent(name -> stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)));
    if (!components.lore().isEmpty()) {
      List<Component> lines = new ArrayList<>(components.lore().size());
      for (String line : components.lore()) {
        lines.add(Component.literal(line));
      }
      stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    if (components.maxDamage().isPresent()) {
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.MAX_DAMAGE, components.maxDamage().get());
    }
    if (components.damage().isPresent()) {
      int maximum = components.maxDamage().orElseGet(() -> Math.max(0, stack.getMaxDamage()));
      if (maximum == 0 || components.damage().get() > maximum) {
        throw new IllegalArgumentException("Damage is not valid for the resolved item");
      }
      stack.set(DataComponents.DAMAGE, components.damage().get());
    }
    components
        .customModelData()
        .ifPresent(
            value -> {
              if (value > 16_777_216) {
                throw new IllegalArgumentException(
                    "Custom model data is not exactly representable");
              }
              stack.set(
                  DataComponents.CUSTOM_MODEL_DATA,
                  new CustomModelData(
                      List.of(value.floatValue()), List.of(), List.of(), List.of()));
            });
    components
        .enchantmentGlint()
        .ifPresent(value -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, value));
  }

  public enum Resolution {
    AVAILABLE,
    MISSING_ITEM,
    INVALID_COMPONENTS
  }

  public record ResolvedItemStack(
      ItemStackView source, Resolution resolution, ItemStack minecraftStack) {
    public ResolvedItemStack {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(resolution, "resolution");
      Objects.requireNonNull(minecraftStack, "minecraftStack");
    }

    public static ResolvedItemStack missing(ItemStackView source, Resolution resolution) {
      if (resolution == Resolution.AVAILABLE) {
        throw new IllegalArgumentException("Missing stacks cannot be available");
      }
      return new ResolvedItemStack(source, resolution, ItemStack.EMPTY);
    }

    public boolean available() {
      return resolution == Resolution.AVAILABLE;
    }
  }
}
