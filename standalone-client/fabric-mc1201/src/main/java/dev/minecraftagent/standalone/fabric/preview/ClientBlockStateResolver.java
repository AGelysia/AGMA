package dev.minecraftagent.standalone.fabric.preview;

import java.util.HashSet;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Registry-backed parser for complete canonical block states. Any block registered in the client
 * registry is accepted, including modded blocks; the supplied text must be the complete canonical
 * state (every property present, sorted names) because it round-trips through the registry and must
 * re-serialize to itself. Ported from the server line's client-mod MinecraftBlockStateResolver.
 */
final class ClientBlockStateResolver {
  private ClientBlockStateResolver() {}

  record Resolved(String canonical, BlockState state) {}

  static Resolved parse(String supplied) {
    var bracket = supplied.indexOf('[');
    var blockId = bracket < 0 ? supplied : supplied.substring(0, bracket);
    var properties = new TreeMap<String, String>();
    if (bracket >= 0) {
      if (!supplied.endsWith("]")) {
        throw unknown("The block state properties are malformed.");
      }
      var body = supplied.substring(bracket + 1, supplied.length() - 1);
      if (body.isEmpty()) {
        throw unknown("The block state properties are malformed.");
      }
      for (var pair : body.split(",", -1)) {
        var equals = pair.indexOf('=');
        if (equals < 1
            || equals == pair.length() - 1
            || properties.put(pair.substring(0, equals), pair.substring(equals + 1)) != null) {
          throw unknown("The block state properties are malformed.");
        }
      }
    }
    var identifier = ResourceLocation.tryParse(blockId);
    if (identifier == null) {
      throw unknown("The block identifier is malformed.");
    }
    var block =
        BuiltInRegistries.BLOCK
            .getOptional(identifier)
            .orElseThrow(() -> unknown("The block is not registered in the client registry."));
    var definition = block.getStateDefinition();
    var expectedProperties = new HashSet<String>();
    for (var property : definition.getProperties()) {
      expectedProperties.add(property.getName());
    }
    if (!expectedProperties.equals(properties.keySet())) {
      throw unknown("The block state must be the complete canonical state with every property.");
    }
    BlockState state = block.defaultBlockState();
    for (Property<?> property : definition.getProperties()) {
      state = setValue(state, property, properties.get(property.getName()));
    }
    if (state.isAir()) {
      throw unknown("Air is not a preview target; use a clear shape instead.");
    }
    if (state.hasBlockEntity()) {
      throw new BlockStateFailure(
          "PREVIEW_BLOCK_ENTITY_UNSUPPORTED", "Previews cannot contain block entities.");
    }
    var canonical = serialize(state);
    if (!canonical.equals(supplied)) {
      throw unknown("The block state must be the complete canonical state " + canonical + ".");
    }
    return new Resolved(canonical, state);
  }

  /** Serializes one observed world state to the same canonical text form the parser accepts. */
  static String serialize(BlockState state) {
    var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    var values = new TreeMap<String, String>();
    for (var entry : state.getValues().entrySet()) {
      values.put(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
    }
    if (values.isEmpty()) {
      return blockId;
    }
    var result = new StringBuilder(blockId).append('[');
    for (var entry : values.entrySet()) {
      if (result.charAt(result.length() - 1) != '[') {
        result.append(',');
      }
      result.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return result.append(']').toString();
  }

  private static <T extends Comparable<T>> BlockState setValue(
      BlockState state, Property<T> property, String wireValue) {
    var value =
        property
            .getValue(wireValue)
            .orElseThrow(() -> unknown("The block state property value is invalid."));
    return state.setValue(property, value);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static String valueName(Property property, Comparable value) {
    return property.getName(value);
  }

  private static BlockStateFailure unknown(String message) {
    return new BlockStateFailure("PREVIEW_BLOCK_UNKNOWN", message);
  }

  /** A domain parse failure; the executor maps the code to a non-retryable FAILED tool error. */
  @SuppressWarnings("serial")
  static final class BlockStateFailure extends RuntimeException {
    private final String code;

    private BlockStateFailure(String code, String message) {
      super(message);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
