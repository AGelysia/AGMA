package dev.minecraftagent.client.view;

import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Registry-backed resolver for complete, non-air block states without block entity payloads. */
final class MinecraftBlockStateResolver implements BuildPreviewBlockStateResolver {
  @Override
  public void validate(String blockId, Map<String, String> properties) {
    Identifier identifier = Identifier.tryParse(blockId);
    if (identifier == null) {
      throw new IllegalArgumentException("invalid block identifier");
    }
    var block =
        BuiltInRegistries.BLOCK
            .getOptional(identifier)
            .orElseThrow(() -> new IllegalArgumentException("unknown block"));
    var definition = block.getStateDefinition();
    var expectedProperties = new HashSet<String>();
    for (Property<?> property : definition.getProperties()) {
      expectedProperties.add(property.getName());
    }
    if (!expectedProperties.equals(properties.keySet())) {
      throw new IllegalArgumentException("block state properties are incomplete");
    }

    BlockState state = block.defaultBlockState();
    for (Property<?> property : definition.getProperties()) {
      state = setValue(state, property, properties.get(property.getName()));
    }
    if (state.isAir() || state.hasBlockEntity()) {
      throw new IllegalArgumentException(
          "air and block entity states are not preview palette states");
    }
  }

  private static <T extends Comparable<T>> BlockState setValue(
      BlockState state, Property<T> property, String wireValue) {
    T value =
        property
            .getValue(wireValue)
            .orElseThrow(() -> new IllegalArgumentException("invalid block state property value"));
    return state.setValue(property, value);
  }
}
