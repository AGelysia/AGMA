package dev.minecraftagent.standalone.fabric.preview.litematica;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact combinations whose public class and method signatures were verified against released JARs.
 */
public final class LitematicaSupportMatrix {
  public static final String LITEMATICA_SOURCE =
      "https://modrinth.com/mod/litematica/version/1ZTpW1lr";
  public static final String MALILIB_SOURCE = "https://modrinth.com/mod/malilib/version/V7yLDtJV";
  public static final String LITEMATICA_API_SOURCE =
      "https://github.com/sakura-ryoko/litematica/tree/1.20.1";
  public static final String MALILIB_API_SOURCE =
      "https://github.com/sakura-ryoko/malilib/tree/1.20.1";

  private static final List<Entry> SUPPORTED =
      List.of(new Entry("1.20.1", "0.19.3", "0.15.4", "0.16.3", "litematica-reflection-file-1"));

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
