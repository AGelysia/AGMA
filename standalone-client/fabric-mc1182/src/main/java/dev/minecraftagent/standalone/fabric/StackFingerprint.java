package dev.minecraftagent.standalone.fabric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.world.item.ItemStack;

/** Bounded one-way identity for item NBT; the original NBT never leaves the client shell. */
public final class StackFingerprint {
  private static final int MAXIMUM_NBT_CHARACTERS = 65_536;

  private StackFingerprint() {}

  public static String of(ItemStack stack) {
    if (stack == null || stack.isEmpty() || !stack.hasTag() || stack.getTag() == null) {
      return null;
    }
    var serialized = stack.getTag().toString();
    var bounded =
        serialized.length() <= MAXIMUM_NBT_CHARACTERS
            ? serialized
            : serialized.substring(0, MAXIMUM_NBT_CHARACTERS) + "#truncated=" + serialized.length();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(bounded.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("JVM does not provide SHA-256", failure);
    }
  }
}
