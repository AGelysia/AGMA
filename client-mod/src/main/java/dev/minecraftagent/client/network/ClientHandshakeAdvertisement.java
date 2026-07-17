package dev.minecraftagent.client.network;

import dev.minecraftagent.client.litematica.LitematicaAdapterDiagnostic;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Client presentation features. These values never convey server authority. */
public record ClientHandshakeAdvertisement(
    String modVersion,
    int overlay,
    int itemIcons,
    int recipeView,
    int litematicaPreview,
    int litematicaMaterialList,
    Optional<String> litematicaVersion,
    Optional<String> malilibVersion,
    LitematicaAdapterDiagnostic litematicaAdapterDiagnostic) {
  private static final Pattern VERSION =
      Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");
  private static final Pattern DEPENDENCY_VERSION =
      Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

  public ClientHandshakeAdvertisement {
    if (modVersion == null || modVersion.length() > 64 || !VERSION.matcher(modVersion).matches()) {
      throw new IllegalArgumentException("Invalid client Mod version");
    }
    requireFeatureVersion(overlay);
    requireFeatureVersion(itemIcons);
    requireFeatureVersion(recipeView, 2);
    requireFeatureVersion(litematicaPreview);
    requireFeatureVersion(litematicaMaterialList);
    litematicaVersion = Objects.requireNonNull(litematicaVersion);
    malilibVersion = Objects.requireNonNull(malilibVersion);
    Objects.requireNonNull(litematicaAdapterDiagnostic);
    litematicaVersion.ifPresent(ClientHandshakeAdvertisement::requireDependencyVersion);
    malilibVersion.ifPresent(ClientHandshakeAdvertisement::requireDependencyVersion);
    if ((litematicaPreview == 1 || litematicaMaterialList == 1)
        && (litematicaVersion.isEmpty() || malilibVersion.isEmpty())) {
      throw new IllegalArgumentException(
          "Litematica capabilities require both dependency versions");
    }
    if (litematicaMaterialList == 1 && litematicaPreview != 1) {
      throw new IllegalArgumentException("Material List requires preview support");
    }
    if (litematicaAdapterDiagnostic.status() != LitematicaAdapterDiagnostic.Status.READY
        && (litematicaPreview != 0 || litematicaMaterialList != 0)) {
      throw new IllegalArgumentException(
          "Unavailable Litematica adapters cannot advertise Litematica capabilities");
    }
    if (!litematicaVersion.equals(litematicaAdapterDiagnostic.litematicaVersion())
        || !malilibVersion.equals(litematicaAdapterDiagnostic.malilibVersion())) {
      throw new IllegalArgumentException("Dependency versions and diagnostics do not agree");
    }
  }

  private static void requireFeatureVersion(int version) {
    requireFeatureVersion(version, 1);
  }

  private static void requireFeatureVersion(int version, int maximum) {
    if (version < 0 || version > maximum) {
      throw new IllegalArgumentException("Unsupported client feature version");
    }
  }

  private static void requireDependencyVersion(String version) {
    if (!DEPENDENCY_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Invalid dependency version");
    }
  }
}
