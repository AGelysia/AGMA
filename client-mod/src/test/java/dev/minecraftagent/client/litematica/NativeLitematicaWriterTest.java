package dev.minecraftagent.client.litematica;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.BuildPreviewView.Bounds;
import dev.minecraftagent.client.view.BuildPreviewView.Difference;
import dev.minecraftagent.client.view.BuildPreviewView.Mirror;
import dev.minecraftagent.client.view.BuildPreviewView.Operation;
import dev.minecraftagent.client.view.BuildPreviewView.PaletteEntry;
import dev.minecraftagent.client.view.BuildPreviewView.PlacedBlock;
import dev.minecraftagent.client.view.BuildPreviewView.Position;
import dev.minecraftagent.client.view.BuildPreviewView.Transform;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

final class NativeLitematicaWriterTest {
  @Test
  void writesDeterministicNativeV7NbtWithAbsoluteCoordinatesBaked() throws Exception {
    var writer = new NativeLitematicaWriter(4321);
    BuildPreviewView preview = preview();

    byte[] first = writer.write(preview);
    byte[] second = writer.write(preview);
    assertArrayEquals(first, second);

    var root = NbtIo.readCompressed(new ByteArrayInputStream(first), NbtAccounter.unlimitedHeap());
    assertEquals(7, root.getIntOr("Version", -1));
    assertEquals(1, root.getIntOr("SubVersion", -1));
    assertEquals(4321, root.getIntOr("MinecraftDataVersion", -1));

    var metadata = root.getCompoundOrEmpty("Metadata");
    assertEquals(1, metadata.getIntOr("RegionCount", -1));
    assertEquals(12, metadata.getIntOr("TotalVolume", -1));
    assertEquals(3, metadata.getIntOr("TotalBlocks", -1));
    assertEquals(0L, metadata.getLongOr("TimeCreated", -1));
    assertVector(metadata.getCompoundOrEmpty("EnclosingSize"), 3, 2, 2);

    var region = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("main");
    assertVector(region.getCompoundOrEmpty("Position"), -1, 0, 0);
    assertVector(region.getCompoundOrEmpty("Size"), 3, 2, 2);
    var palette = region.getListOrEmpty("BlockStatePalette");
    assertEquals(3, palette.size());
    assertEquals("minecraft:air", palette.getCompoundOrEmpty(0).getStringOr("Name", ""));
    assertEquals("minecraft:dirt", palette.getCompoundOrEmpty(1).getStringOr("Name", ""));
    assertEquals("minecraft:oak_log", palette.getCompoundOrEmpty(2).getStringOr("Name", ""));
    assertEquals(
        "y",
        palette.getCompoundOrEmpty(2).getCompoundOrEmpty("Properties").getStringOr("axis", ""));
    assertEquals(0, region.getListOrEmpty("TileEntities").size());
    assertEquals(0, region.getListOrEmpty("Entities").size());

    long[] packed = region.getLongArray("BlockStates").orElseThrow();
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
    BuildPreviewView source = preview();
    BuildPreviewView removal =
        new BuildPreviewView(
            source.schemaVersion(),
            source.previewId(),
            source.projectId(),
            source.revision(),
            source.operation(),
            source.dimension(),
            source.bounds(),
            source.origin(),
            source.transform(),
            source.baseRegionHash(),
            source.changeSetHash(),
            source.contentHash(),
            "3".repeat(64),
            new Difference(0, 0, 3),
            List.of(),
            List.of());

    var root =
        NbtIo.readCompressed(
            new ByteArrayInputStream(new NativeLitematicaWriter(4321).write(removal)),
            NbtAccounter.unlimitedHeap());
    var region = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("main");
    assertEquals(1, region.getListOrEmpty("BlockStatePalette").size());
    assertArrayEquals(
        new int[removal.bounds().volume()],
        unpack(region.getLongArray("BlockStates").orElseThrow(), removal.bounds().volume(), 1));
  }

  static BuildPreviewView preview() {
    Position min = new Position(10, 64, 20);
    Position max = new Position(12, 65, 21);
    return new BuildPreviewView(
        "1.0",
        UUID.fromString("30000000-0000-4000-8000-000000000001"),
        UUID.fromString("30000000-0000-4000-8000-000000000002"),
        1,
        Operation.CREATE,
        "minecraft:overworld",
        new Bounds(min, max),
        new Position(11, 64, 20),
        new Transform(90, Mirror.FRONT_BACK),
        "a".repeat(64),
        "b".repeat(64),
        "1".repeat(64),
        "2".repeat(64),
        new Difference(3, 0, 0),
        List.of(
            new PaletteEntry(0, "minecraft:dirt", Map.of()),
            new PaletteEntry(1, "minecraft:oak_log", Map.of("axis", "y"))),
        List.of(
            new PlacedBlock(0, min),
            new PlacedBlock(1, new Position(12, 64, 21)),
            new PlacedBlock(0, new Position(10, 65, 20))));
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
    assertEquals(x, vector.getIntOr("x", Integer.MIN_VALUE));
    assertEquals(y, vector.getIntOr("y", Integer.MIN_VALUE));
    assertEquals(z, vector.getIntOr("z", Integer.MIN_VALUE));
  }
}
