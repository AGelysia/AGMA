package dev.minecraftagent.standalone.fabric.viewer.jei;

import dev.minecraftagent.standalone.fabric.FabricModMetadataSource;
import dev.minecraftagent.standalone.ui.viewer.jei.StandaloneJeiPlugin;
import mezz.jei.api.JeiPlugin;

/** Public-API-only JEI bridge; JEI loads this class only when its own runtime is present. */
@JeiPlugin
public final class JeiCatalogPlugin extends StandaloneJeiPlugin {
  public JeiCatalogPlugin() {
    super(new FabricModMetadataSource());
  }
}
