package dev.minecraftagent.standalone.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Converts block entity NBT into plain Java maps, lists, strings, and numbers so the shared-logic
 * sanitizer stays free of Minecraft types. Numeric arrays become lists of numbers; unrecognized
 * tags become null and are dropped by the sanitizer's bounds.
 */
final class BlockEntityNbtConverter {
  private BlockEntityNbtConverter() {}

  static Map<String, Object> toJava(CompoundTag compound) {
    var result = new LinkedHashMap<String, Object>();
    for (var key : compound.keySet()) {
      result.put(key, value(Objects.requireNonNull(compound.get(key), key)));
    }
    return result;
  }

  private static Object value(Tag tag) {
    if (tag instanceof CompoundTag compound) {
      return toJava(compound);
    }
    if (tag instanceof ListTag list) {
      var result = new ArrayList<>(list.size());
      for (var entry : list) {
        result.add(value(entry));
      }
      return result;
    }
    if (tag instanceof StringTag string) {
      return string.value();
    }
    if (tag instanceof NumericTag number) {
      return number.box();
    }
    if (tag instanceof ByteArrayTag bytes) {
      var raw = bytes.getAsByteArray();
      var result = new ArrayList<>(raw.length);
      for (var entry : raw) {
        result.add(Integer.valueOf(entry));
      }
      return result;
    }
    if (tag instanceof IntArrayTag integers) {
      var raw = integers.getAsIntArray();
      var result = new ArrayList<>(raw.length);
      for (var entry : raw) {
        result.add(Integer.valueOf(entry));
      }
      return result;
    }
    if (tag instanceof LongArrayTag longs) {
      var raw = longs.getAsLongArray();
      var result = new ArrayList<>(raw.length);
      for (var entry : raw) {
        result.add(Long.valueOf(entry));
      }
      return result;
    }
    return null;
  }
}
