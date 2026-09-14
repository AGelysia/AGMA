package dev.minecraftagent.standalone.ui.preview.litematica;

import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/** Deterministically converts one validated standalone preview to native Litematica NBT. */
public final class NativeLitematicaWriter {
  // Litematica up to 0.16.x reads schematic format versions 1-6; 0.26.x (MC 1.21.11) reads 7.
  static final int LITEMATICA_VERSION = 6;
  static final int LITEMATICA_SUB_VERSION = 1;

  private final int minecraftDataVersion;

  public NativeLitematicaWriter() {
    this(currentDataVersion());
  }

  NativeLitematicaWriter(int minecraftDataVersion) {
    if (minecraftDataVersion < 1) {
      throw new IllegalArgumentException("minecraftDataVersion must be positive");
    }
    this.minecraftDataVersion = minecraftDataVersion;
  }

  private static int currentDataVersion() {
    SharedConstants.tryDetectVersion();
    return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
  }

  public byte[] write(StandalonePreview preview) throws IOException {
    Objects.requireNonNull(preview, "preview");
    CompoundTag root = createRoot(preview);
    var output = new ByteArrayOutputStream();
    NbtIo.writeCompressed(root, output);
    return output.toByteArray();
  }

  CompoundTag createRoot(StandalonePreview preview) {
    int sizeX = preview.bounds().sizeX();
    int sizeY = preview.bounds().sizeY();
    int sizeZ = preview.bounds().sizeZ();
    int volume = preview.bounds().volume();

    int[] stateIds = new int[volume];
    var paletteIndex = paletteIndex(preview);
    for (var cell : preview.cells()) {
      int localX = Math.subtractExact(cell.x(), preview.bounds().min().x());
      int localY = Math.subtractExact(cell.y(), preview.bounds().min().y());
      int localZ = Math.subtractExact(cell.z(), preview.bounds().min().z());
      Integer state = paletteIndex.get(cell.state());
      if (localX < 0
          || localX >= sizeX
          || localY < 0
          || localY >= sizeY
          || localZ < 0
          || localZ >= sizeZ
          || state == null) {
        throw new IllegalArgumentException("preview cell is outside its validated geometry");
      }
      int index = localY * sizeX * sizeZ + localZ * sizeX + localX;
      if (stateIds[index] != 0) {
        throw new IllegalArgumentException("preview contains a duplicate cell position");
      }
      stateIds[index] = state + 1;
    }

    CompoundTag root = new CompoundTag();
    root.putInt("Version", LITEMATICA_VERSION);
    root.putInt("SubVersion", LITEMATICA_SUB_VERSION);
    root.putInt("MinecraftDataVersion", minecraftDataVersion);
    root.put("Metadata", metadata(preview, sizeX, sizeY, sizeZ, volume));

    CompoundTag regions = new CompoundTag();
    regions.put("main", region(preview, sizeX, sizeY, sizeZ, stateIds));
    root.put("Regions", regions);
    return root;
  }

  private static Map<String, Integer> paletteIndex(StandalonePreview preview) {
    var index = new HashMap<String, Integer>();
    for (var position = 0; position < preview.palette().size(); position++) {
      if (index.put(preview.palette().get(position), position) != null) {
        throw new IllegalArgumentException("preview palette contains a duplicate state");
      }
    }
    return index;
  }

  private static CompoundTag metadata(
      StandalonePreview preview, int sizeX, int sizeY, int sizeZ, int volume) {
    CompoundTag metadata = new CompoundTag();
    metadata.putString("Name", "Agent preview " + preview.previewId().toString().substring(0, 8));
    metadata.putString("Author", "AGMA");
    metadata.putString("Description", "Project " + preview.projectId());
    metadata.putInt("RegionCount", 1);
    metadata.putInt("TotalVolume", volume);
    metadata.putInt("TotalBlocks", preview.cells().size());
    metadata.putLong("TimeCreated", 0L);
    metadata.putLong("TimeModified", 0L);
    metadata.put("EnclosingSize", vector(sizeX, sizeY, sizeZ));
    return metadata;
  }

  private static CompoundTag region(
      StandalonePreview preview, int sizeX, int sizeY, int sizeZ, int[] stateIds) {
    CompoundTag region = new CompoundTag();
    ListTag palette = new ListTag();
    palette.add(blockState("minecraft:air", Map.of()));
    for (var entry : preview.palette()) {
      palette.add(blockState(entry));
    }
    region.put("BlockStatePalette", palette);
    region.putLongArray("BlockStates", packBlockStates(stateIds, palette.size()));
    region.put("TileEntities", new ListTag());
    region.put("Entities", new ListTag());
    region.put("PendingBlockTicks", new ListTag());
    region.put("PendingFluidTicks", new ListTag());
    region.put(
        "Position",
        vector(
            Math.subtractExact(preview.bounds().min().x(), preview.origin().x()),
            Math.subtractExact(preview.bounds().min().y(), preview.origin().y()),
            Math.subtractExact(preview.bounds().min().z(), preview.origin().z())));
    region.put("Size", vector(sizeX, sizeY, sizeZ));
    return region;
  }

  private static CompoundTag blockState(String canonicalState) {
    int bracket = canonicalState.indexOf('[');
    if (bracket < 0) {
      return blockState(canonicalState, Map.of());
    }
    if (bracket == 0 || !canonicalState.endsWith("]")) {
      throw new IllegalArgumentException("preview palette state is not canonical");
    }
    var properties = new TreeMap<String, String>();
    var body = canonicalState.substring(bracket + 1, canonicalState.length() - 1);
    for (var pair : body.split(",", -1)) {
      int separator = pair.indexOf('=');
      if (separator < 1 || separator != pair.lastIndexOf('=')) {
        throw new IllegalArgumentException("preview palette state is not canonical");
      }
      if (properties.put(pair.substring(0, separator), pair.substring(separator + 1)) != null) {
        throw new IllegalArgumentException("preview palette state repeats a property");
      }
    }
    return blockState(canonicalState.substring(0, bracket), properties);
  }

  private static CompoundTag blockState(String blockId, Map<String, String> properties) {
    CompoundTag state = new CompoundTag();
    state.putString("Name", blockId);
    if (!properties.isEmpty()) {
      CompoundTag propertyTag = new CompoundTag();
      properties.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> propertyTag.putString(entry.getKey(), entry.getValue()));
      state.put("Properties", propertyTag);
    }
    return state;
  }

  private static CompoundTag vector(int x, int y, int z) {
    CompoundTag vector = new CompoundTag();
    vector.putInt("x", x);
    vector.putInt("y", y);
    vector.putInt("z", z);
    return vector;
  }

  static int bitsForPaletteSize(int paletteSize) {
    if (paletteSize < 1) {
      throw new IllegalArgumentException("paletteSize must be positive");
    }
    return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
  }

  static long[] packBlockStates(int[] stateIds, int paletteSize) {
    Objects.requireNonNull(stateIds, "stateIds");
    int bits = bitsForPaletteSize(paletteSize);
    long totalBits = Math.multiplyExact((long) stateIds.length, bits);
    long[] packed = new long[Math.toIntExact((totalBits + Long.SIZE - 1) / Long.SIZE)];
    for (int index = 0; index < stateIds.length; index++) {
      int value = stateIds[index];
      if (value < 0 || value >= paletteSize) {
        throw new IllegalArgumentException("state ID is outside the native palette");
      }
      long startBit = (long) index * bits;
      int word = Math.toIntExact(startBit >>> 6);
      int offset = (int) (startBit & 63L);
      packed[word] |= (long) value << offset;
      int spill = offset + bits - Long.SIZE;
      if (spill > 0) {
        packed[word + 1] |= (long) value >>> (bits - spill);
      }
    }
    return packed;
  }
}
