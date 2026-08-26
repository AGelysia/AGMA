package dev.minecraftagent.standalone.forge.viewer.jei;

import dev.minecraftagent.standalone.forge.ForgeModMetadataSource;
import dev.minecraftagent.standalone.ui.viewer.jei.StandaloneJeiPlugin;
import mezz.jei.api.JeiPlugin;

/** Public-API-only JEI bridge; JEI loads this class only when its own runtime is present. */
@JeiPlugin
public final class JeiCatalogPlugin extends StandaloneJeiPlugin {
  public JeiCatalogPlugin() {
    super(new ForgeModMetadataSource());
  }
}
