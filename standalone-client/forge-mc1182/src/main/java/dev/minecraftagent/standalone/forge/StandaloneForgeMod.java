package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

/** Minecraft Forge 1.18.2 shell for the local standalone lifecycle. */
@Mod(StandaloneForgeMod.MOD_ID)
public final class StandaloneForgeMod {
  public static final String MOD_ID = "agma_standalone";

  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
  private static final AtomicBoolean CLOSED = new AtomicBoolean();
  private static final StandaloneCatalogService CATALOG = new StandaloneCatalogService();
  private static final StandaloneUiState UI_STATE = new StandaloneUiState();
  private static final KeyMapping OPEN =
      new KeyMapping("key.agma_standalone.open", GLFW.GLFW_KEY_G, "key.categories.agma_standalone");

  private static ClientRuntimeController runtime;
  private static CatalogToolExecutor tools;

  public StandaloneForgeMod() {
    var modBus = FMLJavaModLoadingContext.get().getModEventBus();
    modBus.addListener(this::clientSetup);
    modBus.addListener(this::registerReloadListeners);
    MinecraftForge.EVENT_BUS.addListener(this::clientTick);
    MinecraftForge.EVENT_BUS.addListener(this::loggedIn);
    MinecraftForge.EVENT_BUS.addListener(this::loggedOut);
  }

  private void clientSetup(FMLClientSetupEvent event) {
    ClientRegistry.registerKeyBinding(OPEN);
    event.enqueueWork(StandaloneForgeMod::initialize);
  }

  private static void initialize() {
    if (!INITIALIZED.compareAndSet(false, true)) {
      return;
    }
    try {
      var version =
          ModList.get()
              .getModContainerById(MOD_ID)
              .map(container -> container.getModInfo().getVersion().toString())
              .orElse("0.3.0");
      var root = FMLPaths.CONFIGDIR.get().resolve(MOD_ID).toAbsolutePath().normalize();
      runtime = new ClientRuntimeController(root, version);
      tools = new CatalogToolExecutor(CATALOG);
      OptionalViewerRegistry.setRefresh(StandaloneForgeMod::refreshCatalog);
      CATALOG.refresh(Minecraft.getInstance());
      Runtime.getRuntime()
          .addShutdownHook(new Thread(StandaloneForgeMod::close, "agma-standalone-forge-close"));
    } catch (RuntimeException | Error failure) {
      INITIALIZED.set(false);
      throw failure;
    }
  }

  private void registerReloadListeners(RegisterClientReloadListenersEvent event) {
    event.registerReloadListener(
        (ResourceManagerReloadListener)
            manager ->
                runOnClient(
                    () -> {
                      invalidateGuideContext(false);
                      tools.revokeInventoryAuthorizations();
                      refreshCatalog();
                    }));
  }

  private void loggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
    runOnClient(
        () -> {
          invalidateGuideContext(true);
          tools.revokeInventoryAuthorizations();
          refreshCatalog();
        });
  }

  private void loggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
    runOnClient(
        () -> {
          invalidateGuideContext(true);
          tools.revokeInventoryAuthorizations();
          CATALOG.invalidate();
        });
  }

  private void clientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END || !INITIALIZED.get() || CLOSED.get()) {
      return;
    }
    var client = Minecraft.getInstance();
    while (OPEN.consumeClick()) {
      var context = CATALOG.context(client);
      var selected =
          context
              .hovered()
              .orElseGet(() -> context.pointed().orElseGet(() -> context.held().orElse(null)));
      if (!Objects.equals(selected, UI_STATE.selected)) {
        invalidateGuideContext(false);
      }
      UI_STATE.selected = selected;
      client.setScreen(new StandaloneAssistantScreen(CATALOG, runtime, tools, UI_STATE));
    }
  }

  static ClientRuntimeController runtimeController() {
    return runtime;
  }

  private static void runOnClient(Runnable action) {
    if (!INITIALIZED.get() || CLOSED.get()) {
      return;
    }
    Minecraft.getInstance()
        .execute(
            () -> {
              if (INITIALIZED.get() && !CLOSED.get()) {
                action.run();
              }
            });
  }

  private static void refreshCatalog() {
    if (INITIALIZED.get() && !CLOSED.get()) {
      var client = Minecraft.getInstance();
      client.execute(() -> CATALOG.refresh(client));
    }
  }

  private static void close() {
    if (!CLOSED.compareAndSet(false, true)) {
      return;
    }
    OptionalViewerRegistry.setRefresh(null);
    OptionalViewerRegistry.clear();
    if (tools != null) {
      tools.revokeInventoryAuthorizations();
      closeQuietly(tools);
    }
    closeQuietly(runtime);
    closeQuietly(CATALOG);
  }

  private static void closeQuietly(AutoCloseable value) {
    if (value == null) {
      return;
    }
    try {
      value.close();
    } catch (Exception ignored) {
      // Shutdown must continue so the managed Runtime cannot be left behind.
    }
  }

  private static void invalidateGuideContext(boolean clearSession) {
    if (UI_STATE.activeRequestId != null) {
      runtime.cancel(UI_STATE.activeRequestId, CancelReason.CONTEXT_CHANGED);
    }
    UI_STATE.activeRequestId = null;
    if (clearSession) {
      UI_STATE.sessionId = null;
    }
    UI_STATE.answer = "";
    UI_STATE.status = "";
    UI_STATE.lastCostMicroUsd = 0;
    UI_STATE.lastCostKind = null;
    UI_STATE.sources = java.util.List.of();
    UI_STATE.selected = null;
    UI_STATE.localPlan = null;
    UI_STATE.localPlanAmount = 1;
    UI_STATE.selectedRoute = 0;
    UI_STATE.localPlanScroll = 0;
    UI_STATE.answerScroll = 0;
    UI_STATE.webOnce = false;
    UI_STATE.inventoryOnce = false;
  }
}
