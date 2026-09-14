package dev.minecraftagent.standalone.ui.preview.litematica;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed combinations whose public class and method signatures were verified against released
 * JARs. Selection is by Minecraft version alone: loader, Litematica, and MaLiLib versions are
 * recorded for diagnostics, while compatibility itself is enforced by the reflective adapter's
 * link-time signature verification, which fails closed.
 */
public final class LitematicaSupportMatrix {
  public static final String LITEMATICA_SOURCE =
      "https://modrinth.com/mod/litematica/version/pF33JBAV";
  public static final String MALILIB_SOURCE = "https://modrinth.com/mod/malilib/version/V21ryIsV";
  public static final String LITEMATICA_API_SOURCE =
      "https://github.com/maruohon/litematica/tree/fabric_1.18.x";
  public static final String MALILIB_API_SOURCE =
      "https://github.com/maruohon/malilib/tree/fabric_1.18.x";

  private static final List<Entry> SUPPORTED =
      List.of(new Entry("1.18.2", "0.19.3", "0.11.7", "0.12.1", "litematica-reflection-file-1"));

  private LitematicaSupportMatrix() {}

  public static List<Entry> supported() {
    return SUPPORTED;
  }

  /**
   * Selects the adapter family for one Minecraft version. The entry documents the reviewed
   * Litematica/MaLiLib/loader combination for support reference only; loader and mod versions are
   * deliberately not matched here because the reflective link-time signature verification is the
   * real compatibility gate, and it fails closed on any signature drift.
   */
  public static Optional<Entry> findForMinecraft(String minecraftVersion) {
    return SUPPORTED.stream()
        .filter(entry -> entry.minecraftVersion().equals(minecraftVersion))
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
