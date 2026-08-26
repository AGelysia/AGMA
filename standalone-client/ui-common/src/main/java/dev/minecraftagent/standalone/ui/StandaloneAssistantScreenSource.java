package dev.minecraftagent.standalone.ui;

import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import net.minecraft.client.gui.screens.Screen;

/** Supplies the loader-specific assistant screen for the shared navigation tabs. */
public interface StandaloneAssistantScreenSource {
  Screen create(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState state);
}
