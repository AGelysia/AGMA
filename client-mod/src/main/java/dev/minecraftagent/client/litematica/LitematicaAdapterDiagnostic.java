package dev.minecraftagent.client.litematica;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded, path-free compatibility facts reported for operator diagnostics only. */
public record LitematicaAdapterDiagnostic(
    Status status,
    String minecraftVersion,
    String fabricLoaderVersion,
    Optional<String> litematicaVersion,
    Optional<String> malilibVersion,
    Optional<String> adapterId) {
  private static final Pattern ADAPTER_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

  public enum Status {
    READY,
    NOT_INSTALLED,
    MISSING_DEPENDENCY,
    UNSUPPORTED_VERSION,
    ADAPTER_LINKAGE_FAILED,
    PREVIEW_STORAGE_UNAVAILABLE
  }

  public LitematicaAdapterDiagnostic {
    Objects.requireNonNull(status, "status");
    requireVersion(minecraftVersion, "minecraftVersion");
    requireVersion(fabricLoaderVersion, "fabricLoaderVersion");
    litematicaVersion = Objects.requireNonNull(litematicaVersion, "litematicaVersion");
    malilibVersion = Objects.requireNonNull(malilibVersion, "malilibVersion");
    adapterId = Objects.requireNonNull(adapterId, "adapterId");
    litematicaVersion.ifPresent(value -> requireVersion(value, "litematicaVersion"));
    malilibVersion.ifPresent(value -> requireVersion(value, "malilibVersion"));
    adapterId.ifPresent(
        value -> {
          if (!ADAPTER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Litematica adapter ID");
          }
        });
    requireCoherentStatus(status, litematicaVersion, malilibVersion, adapterId);
  }

  public static LitematicaAdapterDiagnostic from(LitematicaCompatibility compatibility) {
    Objects.requireNonNull(compatibility, "compatibility");
    var versions = compatibility.detectedVersions();
    var adapterId =
        compatibility.supportedCombination().map(LitematicaSupportMatrix.Entry::adapterId);
    return new LitematicaAdapterDiagnostic(
        Status.valueOf(compatibility.status().name()),
        versions.minecraftVersion(),
        versions.fabricLoaderVersion(),
        versions.litematicaVersion(),
        versions.malilibVersion(),
        adapterId);
  }

  public static LitematicaAdapterDiagnostic previewStorageUnavailable(
      String minecraftVersion,
      String fabricLoaderVersion,
      Optional<String> litematicaVersion,
      Optional<String> malilibVersion) {
    return new LitematicaAdapterDiagnostic(
        Status.PREVIEW_STORAGE_UNAVAILABLE,
        minecraftVersion,
        fabricLoaderVersion,
        litematicaVersion,
        malilibVersion,
        Optional.empty());
  }

  private static void requireCoherentStatus(
      Status status,
      Optional<String> litematicaVersion,
      Optional<String> malilibVersion,
      Optional<String> adapterId) {
    boolean hasLitematica = litematicaVersion.isPresent();
    boolean hasMalilib = malilibVersion.isPresent();
    boolean hasAdapter = adapterId.isPresent();
    boolean valid =
        switch (status) {
          case READY, ADAPTER_LINKAGE_FAILED -> hasLitematica && hasMalilib && hasAdapter;
          case NOT_INSTALLED -> !hasLitematica && !hasAdapter;
          case MISSING_DEPENDENCY -> hasLitematica && !hasMalilib && !hasAdapter;
          case UNSUPPORTED_VERSION -> hasLitematica && hasMalilib && !hasAdapter;
          case PREVIEW_STORAGE_UNAVAILABLE -> !hasAdapter;
        };
    if (!valid) {
      throw new IllegalArgumentException("Litematica diagnostic status is inconsistent");
    }
  }

  private static void requireVersion(String value, String name) {
    if (value == null || !VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid " + name);
    }
  }
}
