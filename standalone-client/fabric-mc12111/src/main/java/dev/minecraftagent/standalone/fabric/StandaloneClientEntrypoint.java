package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;

/** Minecraft 1.21.11 shell for the local standalone lifecycle. */
public final class StandaloneClientEntrypoint implements ClientModInitializer {
  public static final String MOD_ID = "agma_standalone";
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
  private static final StandaloneCatalogService CATALOG = new StandaloneCatalogService();
  private static final StandaloneUiState UI_STATE = new StandaloneUiState();
  private static ClientRuntimeController runtime;
  private static CatalogToolExecutor tools;

  @Override
  public void onInitializeClient() {
    if (!INITIALIZED.compareAndSet(false, true)) {
      return;
    }
    var version =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.3.0");
    var root =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir()
            .resolve(MOD_ID)
            .toAbsolutePath()
            .normalize();
    runtime = new ClientRuntimeController(root, version);
    tools = new CatalogToolExecutor(CATALOG);
    registerCatalogLifecycle();
    registerKey();
    OptionalViewerRegistry.setRefresh(
        () -> {
          var client = Minecraft.getInstance();
          client.execute(() -> CATALOG.refresh(client));
        });
    CATALOG.refresh(Minecraft.getInstance());
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          tools.revokeInventoryAuthorizations();
          tools.close();
          runtime.close();
          CATALOG.close();
        });
  }

  private static void registerCatalogLifecycle() {
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          invalidateGuideContext(true);
          tools.revokeInventoryAuthorizations();
          CATALOG.refresh(client);
        });
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          invalidateGuideContext(true);
          tools.revokeInventoryAuthorizations();
          CATALOG.invalidate();
        });
    ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
        .registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
              @Override
              public Identifier getFabricId() {
                return Identifier.fromNamespaceAndPath(MOD_ID, "catalog");
              }

              @Override
              public void onResourceManagerReload(ResourceManager manager) {
                var client = Minecraft.getInstance();
                client.execute(
                    () -> {
                      invalidateGuideContext(false);
                      tools.revokeInventoryAuthorizations();
                      CATALOG.refresh(client);
                    });
              }
            });
  }

  private static void registerKey() {
    var category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "standalone"));
    var open =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.agma_standalone.open", GLFW.GLFW_KEY_G, category));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          while (open.consumeClick()) {
            var context = CATALOG.context(client);
            var selected =
                context
                    .hovered()
                    .orElseGet(
                        () -> context.pointed().orElseGet(() -> context.held().orElse(null)));
            if (!java.util.Objects.equals(selected, UI_STATE.selected)) {
              invalidateGuideContext(false);
            }
            UI_STATE.selected = selected;
            client.setScreen(new StandaloneAssistantScreen(CATALOG, runtime, tools, UI_STATE));
          }
        });
  }

  static ClientRuntimeController runtimeController() {
    return runtime;
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
