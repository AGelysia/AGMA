package dev.minecraftagent.standalone.ui;

import java.util.List;
import java.util.Optional;

/**
 * Loader-specific mod metadata lookup behind the shared Minecraft 1.18.2 surfaces.
 *
 * <p>Implementations live in each loader module and preserve that loader's exact lookup semantics
 * so the shared catalog never links against a mod-loader API.
 */
public interface ModMetadataSource {
  /**
   * Resolves the mod that owns a resource namespace, using the loader's own matching rules. Callers
   * provide the masked fallback when no mod matches.
   */
  Optional<ModMetadata> find(String namespace);

  /** Returns the installed version of the mod with the exact mod id, if present. */
  Optional<String> installedVersion(String modId);

  /** Returns every installed mod with its normalized id and version. */
  List<ModMetadata> all();
}
