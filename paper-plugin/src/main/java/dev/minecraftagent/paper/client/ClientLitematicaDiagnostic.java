package dev.minecraftagent.paper.client;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Untrusted, path-free client compatibility declaration used only for diagnostics. */
public record ClientLitematicaDiagnostic(
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
    PREVIEW_STORAGE_UNAVAILABLE,
    LEGACY_UNREPORTED;

    public static Status fromWireName(String wireName) {
      return switch (Objects.requireNonNullElse(wireName, "")) {
        case "READY" -> READY;
        case "NOT_INSTALLED" -> NOT_INSTALLED;
        case "MISSING_DEPENDENCY" -> MISSING_DEPENDENCY;
        case "UNSUPPORTED_VERSION" -> UNSUPPORTED_VERSION;
        case "ADAPTER_LINKAGE_FAILED" -> ADAPTER_LINKAGE_FAILED;
        case "PREVIEW_STORAGE_UNAVAILABLE" -> PREVIEW_STORAGE_UNAVAILABLE;
        default -> throw new ClientProtocolException("CLIENT_ADAPTER_STATUS_INVALID");
      };
    }
  }

  public static ClientLitematicaDiagnostic legacy(
      Optional<String> litematicaVersion, Optional<String> malilibVersion) {
    return new ClientLitematicaDiagnostic(
        Status.LEGACY_UNREPORTED,
        "unknown",
        "unknown",
        Objects.requireNonNull(litematicaVersion),
        Objects.requireNonNull(malilibVersion),
        Optional.empty());
  }

  public ClientLitematicaDiagnostic {
    if (status == null) {
      throw new ClientProtocolException("CLIENT_ADAPTER_STATUS_INVALID");
    }
    requireVersion(minecraftVersion);
    requireVersion(fabricLoaderVersion);
    if (litematicaVersion == null || malilibVersion == null || adapterId == null) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
    litematicaVersion.ifPresent(ClientLitematicaDiagnostic::requireVersion);
    malilibVersion.ifPresent(ClientLitematicaDiagnostic::requireVersion);
    adapterId.ifPresent(
        value -> {
          if (!ADAPTER_ID.matcher(value).matches()) {
            throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
          }
        });
    requireCoherentStatus(status, litematicaVersion, malilibVersion, adapterId);
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
          case LEGACY_UNREPORTED -> !hasAdapter;
        };
    if (!valid) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
  }

  private static void requireVersion(String value) {
    if (value == null || !VERSION.matcher(value).matches()) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
  }
}
