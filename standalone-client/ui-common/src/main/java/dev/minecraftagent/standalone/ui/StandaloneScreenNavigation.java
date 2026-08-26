package dev.minecraftagent.standalone.ui;

import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import java.util.Objects;
import net.minecraft.client.gui.screens.Screen;

/** Installs the loader-specific screen hooks the shared tabs navigate to. */
public final class StandaloneScreenNavigation {
  private static volatile StandaloneAssistantScreenSource assistantSource;

  private StandaloneScreenNavigation() {}

  public static void assistant(StandaloneAssistantScreenSource source) {
    assistantSource = Objects.requireNonNull(source, "source");
  }

  public static Screen assistantScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState state) {
    var source = assistantSource;
    if (source == null) {
      throw new IllegalStateException("Assistant screen source is not installed");
    }
    return source.create(catalog, runtime, tools, state);
  }
}
