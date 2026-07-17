package dev.minecraftagent.client.litematica;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact combinations whose public class and method signatures were verified against released JARs.
 */
public final class LitematicaSupportMatrix {
  public static final String LITEMATICA_SOURCE =
      "https://modrinth.com/mod/litematica/version/b3dJnV8d";
  public static final String MALILIB_SOURCE = "https://modrinth.com/mod/malilib/version/oaU4Ys3J";
  public static final String LITEMATICA_API_SOURCE =
      "https://github.com/sakura-ryoko/litematica/tree/a1fad824536c8d9d8e93c54fe7222999d4fe4a7a";
  public static final String MALILIB_API_SOURCE =
      "https://github.com/sakura-ryoko/malilib/tree/53babf779aa6ce1478de17e0ac6c249bd764f803";

  private static final List<Entry> SUPPORTED =
      List.of(new Entry("1.21.11", "0.19.3", "0.26.12", "0.27.16", "litematica-reflection-1"));

  private LitematicaSupportMatrix() {}

  public static List<Entry> supported() {
    return SUPPORTED;
  }

  public static Optional<Entry> findExact(
      String minecraftVersion,
      String fabricLoaderVersion,
      String litematicaVersion,
      String malilibVersion) {
    return SUPPORTED.stream()
        .filter(
            entry ->
                entry.minecraftVersion().equals(minecraftVersion)
                    && entry.fabricLoaderVersion().equals(fabricLoaderVersion)
                    && entry.litematicaVersion().equals(litematicaVersion)
                    && entry.malilibVersion().equals(malilibVersion))
        .findFirst();
  }

  public record Entry(
      String minecraftVersion,
      String fabricLoaderVersion,
      String litematicaVersion,
      String malilibVersion,
      String adapterId) {
    public Entry {
      Objects.requireNonNull(minecraftVersion, "minecraftVersion");
      Objects.requireNonNull(fabricLoaderVersion, "fabricLoaderVersion");
      Objects.requireNonNull(litematicaVersion, "litematicaVersion");
      Objects.requireNonNull(malilibVersion, "malilibVersion");
      Objects.requireNonNull(adapterId, "adapterId");
    }
  }
}
