package dev.minecraftagent.standalone.fabric;

import java.util.List;
import java.util.Optional;

/**
 * Loader-specific mod metadata lookup behind the Minecraft 1.20.1 catalog surfaces.
 *
 * <p>The implementation lives in this loader module and preserves the loader's exact lookup
 * semantics so the catalog never links against a mod-loader API directly.
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
