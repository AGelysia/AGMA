package dev.minecraftagent.standalone.fabric.preview.litematica;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minecraftagent.standalone.common.preview.PreviewBounds;
import dev.minecraftagent.standalone.common.preview.PreviewCell;
import dev.minecraftagent.standalone.common.preview.PreviewDifference;
import dev.minecraftagent.standalone.common.preview.PreviewMirror;
import dev.minecraftagent.standalone.common.preview.PreviewOperation;
import dev.minecraftagent.standalone.common.preview.PreviewPosition;
import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

final class NativeLitematicaWriterTest {
  @Test
  void writesDeterministicNativeV7NbtWithAbsoluteCoordinatesBaked() throws Exception {
    var writer = new NativeLitematicaWriter(4321);
    StandalonePreview preview = preview();

    byte[] first = writer.write(preview);
    byte[] second = writer.write(preview);
    assertArrayEquals(first, second);

    var root = NbtIo.readCompressed(new ByteArrayInputStream(first));
    assertEquals(6, root.getInt("Version"));
    assertEquals(1, root.getInt("SubVersion"));
    assertEquals(4321, root.getInt("MinecraftDataVersion"));

    var metadata = root.getCompound("Metadata");
    assertEquals(1, metadata.getInt("RegionCount"));
    assertEquals(12, metadata.getInt("TotalVolume"));
    assertEquals(3, metadata.getInt("TotalBlocks"));
    assertEquals(0L, metadata.getLong("TimeCreated"));
    assertVector(metadata.getCompound("EnclosingSize"), 3, 2, 2);

    var region = root.getCompound("Regions").getCompound("main");
    assertVector(region.getCompound("Position"), -1, 0, 0);
    assertVector(region.getCompound("Size"), 3, 2, 2);
    var palette = region.getList("BlockStatePalette", 10);
    assertEquals(3, palette.size());
    assertEquals("minecraft:air", palette.getCompound(0).getString("Name"));
    assertEquals("minecraft:dirt", palette.getCompound(1).getString("Name"));
    assertEquals("minecraft:oak_log", palette.getCompound(2).getString("Name"));
    assertEquals("y", palette.getCompound(2).getCompound("Properties").getString("axis"));
    assertEquals(0, region.getList("TileEntities", 10).size());
    assertEquals(0, region.getList("Entities", 10).size());

    long[] packed = region.getLongArray("BlockStates");
    int[] states = unpack(packed, 12, 3);
    assertEquals(1, states[0]);
    assertEquals(2, states[5]);
    assertEquals(1, states[6]);
    for (int index : new int[] {1, 2, 3, 4, 7, 8, 9, 10, 11}) {
      assertEquals(0, states[index]);
    }
  }

  @Test
  void packsTwoFourAndFiveBitPalettesAcrossWordBoundaries() {
    assertEquals(2, NativeLitematicaWriter.bitsForPaletteSize(3));
    assertEquals(4, NativeLitematicaWriter.bitsForPaletteSize(9));
    assertEquals(5, NativeLitematicaWriter.bitsForPaletteSize(17));

    for (int paletteSize : new int[] {3, 9, 17}) {
      int[] states = new int[97];
      for (int index = 0; index < states.length; index++) {
        states[index] = index % paletteSize;
      }
      assertArrayEquals(
          states,
          unpack(
              NativeLitematicaWriter.packBlockStates(states, paletteSize),
              states.length,
              paletteSize));
    }
  }

  @Test
  void writesRemovalOnlyTargetsAsAnAirOnlyNativePalette() throws Exception {
    StandalonePreview source = preview();
    StandalonePreview removal =
        new StandalonePreview(
            source.previewId(),
            source.projectId(),
            source.revision(),
            source.operation(),
            source.dimension(),
            source.bounds(),
            source.origin(),
            source.rotation(),
            source.mirror(),
            source.baseRegionHash(),
            source.changeSetHash(),
            0,
            3,
            new PreviewDifference(0, 0, 3),
            List.of(),
            List.of());

    var root =
        NbtIo.readCompressed(
            new ByteArrayInputStream(new NativeLitematicaWriter(4321).write(removal)));
    var region = root.getCompound("Regions").getCompound("main");
    assertEquals(1, region.getList("BlockStatePalette", 10).size());
    assertArrayEquals(
        new int[removal.bounds().volume()],
        unpack(region.getLongArray("BlockStates"), removal.bounds().volume(), 1));
  }

  static StandalonePreview preview() {
    var min = new PreviewPosition(10, 64, 20);
    var max = new PreviewPosition(12, 65, 21);
    return new StandalonePreview(
        UUID.fromString("30000000-0000-4000-8000-000000000001"),
        UUID.fromString("30000000-0000-4000-8000-000000000002"),
        1,
        PreviewOperation.CREATE,
        "minecraft:overworld",
        new PreviewBounds(min, max),
        new PreviewPosition(11, 64, 20),
        90,
        PreviewMirror.FRONT_BACK,
        "a".repeat(64),
        "b".repeat(64),
        3,
        3,
        new PreviewDifference(3, 0, 0),
        List.of(
            new PreviewCell(10, 64, 20, "minecraft:dirt"),
            new PreviewCell(10, 65, 20, "minecraft:dirt"),
            new PreviewCell(12, 64, 21, "minecraft:oak_log[axis=y]")),
        List.of("minecraft:dirt", "minecraft:oak_log[axis=y]"));
  }

  private static int[] unpack(long[] packed, int size, int paletteSize) {
    int bits = NativeLitematicaWriter.bitsForPaletteSize(paletteSize);
    long mask = (1L << bits) - 1L;
    int[] result = new int[size];
    for (int index = 0; index < size; index++) {
      long startBit = (long) index * bits;
      int word = (int) (startBit >>> 6);
      int offset = (int) (startBit & 63L);
      long value = packed[word] >>> offset;
      if (offset + bits > Long.SIZE) {
        value |= packed[word + 1] << (Long.SIZE - offset);
      }
      result[index] = (int) (value & mask);
    }
    return result;
  }

  private static void assertVector(net.minecraft.nbt.CompoundTag vector, int x, int y, int z) {
    assertEquals(x, vector.getInt("x"));
    assertEquals(y, vector.getInt("y"));
    assertEquals(z, vector.getInt("z"));
  }
}
