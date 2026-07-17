package dev.minecraftagent.paper.client;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validated client hello. It contains no player identity or server authority. */
public record ClientHandshake(
    String clientProtocolVersion,
    String modVersion,
    ClientCapabilities capabilities,
    Map<String, String> dependencies,
    ClientLitematicaDiagnostic litematicaAdapterDiagnostic) {
  public static final String LEGACY_PROTOCOL_VERSION = "1.0";
  public static final String CURRENT_PROTOCOL_VERSION = "1.1";

  private static final Pattern MOD_VERSION =
      Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");
  private static final Pattern DEPENDENCY_VERSION =
      Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

  public ClientHandshake {
    if (!isSupportedProtocolVersion(clientProtocolVersion)) {
      throw new ClientProtocolException("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    if (modVersion == null
        || modVersion.length() > 64
        || !MOD_VERSION.matcher(modVersion).matches()) {
      throw new ClientProtocolException("CLIENT_MOD_VERSION_INVALID");
    }
    Objects.requireNonNull(capabilities);
    dependencies = validateDependencies(dependencies);
    Objects.requireNonNull(litematicaAdapterDiagnostic);
    boolean legacy = LEGACY_PROTOCOL_VERSION.equals(clientProtocolVersion);
    if (legacy
        != (litematicaAdapterDiagnostic.status()
            == ClientLitematicaDiagnostic.Status.LEGACY_UNREPORTED)) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
    if ((capabilities.version(ClientFeature.LITEMATICA_PREVIEW) == 1
            || capabilities.version(ClientFeature.LITEMATICA_MATERIAL_LIST) == 1)
        && (!dependencies.containsKey("litematica") || !dependencies.containsKey("malilib"))) {
      throw new ClientProtocolException("CLIENT_DEPENDENCY_CLAIM_INVALID");
    }
    if (capabilities.version(ClientFeature.LITEMATICA_MATERIAL_LIST) == 1
        && capabilities.version(ClientFeature.LITEMATICA_PREVIEW) != 1) {
      throw new ClientProtocolException("CLIENT_DEPENDENCY_CLAIM_INVALID");
    }
    if (!legacy
        && litematicaAdapterDiagnostic.status() != ClientLitematicaDiagnostic.Status.READY
        && (capabilities.version(ClientFeature.LITEMATICA_PREVIEW) != 0
            || capabilities.version(ClientFeature.LITEMATICA_MATERIAL_LIST) != 0)) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
    if (!litematicaAdapterDiagnostic
            .litematicaVersion()
            .equals(Optional.ofNullable(dependencies.get("litematica")))
        || !litematicaAdapterDiagnostic
            .malilibVersion()
            .equals(Optional.ofNullable(dependencies.get("malilib")))) {
      throw new ClientProtocolException("CLIENT_ADAPTER_DIAGNOSTIC_INVALID");
    }
  }

  public Optional<String> dependencyVersion(String dependency) {
    return Optional.ofNullable(dependencies.get(dependency));
  }

  public static boolean isSupportedProtocolVersion(String version) {
    return LEGACY_PROTOCOL_VERSION.equals(version) || CURRENT_PROTOCOL_VERSION.equals(version);
  }

  private static Map<String, String> validateDependencies(Map<String, String> dependencies) {
    Objects.requireNonNull(dependencies);
    if (!dependencies.keySet().equals(java.util.Set.of("litematica", "malilib"))) {
      throw new ClientProtocolException("CLIENT_DEPENDENCIES_INVALID");
    }
    var result = new java.util.LinkedHashMap<String, String>();
    for (var name : java.util.List.of("litematica", "malilib")) {
      var value = dependencies.get(name);
      if (value != null) {
        if (!DEPENDENCY_VERSION.matcher(value).matches()) {
          throw new ClientProtocolException("CLIENT_DEPENDENCY_VERSION_INVALID");
        }
        result.put(name, value);
      }
    }
    return Map.copyOf(result);
  }
}
