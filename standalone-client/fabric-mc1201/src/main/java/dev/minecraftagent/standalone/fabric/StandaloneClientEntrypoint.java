package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.OptionalViewerRegistry;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.fabric.preview.BuildPreviewToolExecutor;
import dev.minecraftagent.standalone.fabric.preview.StandalonePreviewOverlay;
import dev.minecraftagent.standalone.fabric.preview.StandalonePreviewStore;
import dev.minecraftagent.standalone.fabric.preview.StandaloneToolRouter;
import dev.minecraftagent.standalone.fabric.preview.hologram.PreviewHologramBridge;
import dev.minecraftagent.standalone.fabric.preview.litematica.FabricModInventory;
import dev.minecraftagent.standalone.fabric.preview.litematica.LitematicaAdapterDiagnostic;
import dev.minecraftagent.standalone.fabric.preview.litematica.LitematicaAdapterResolver;
import dev.minecraftagent.standalone.fabric.preview.litematica.StandaloneLitematicaController;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;

/** Minecraft 1.20.1 shell for the local standalone lifecycle. */
public final class StandaloneClientEntrypoint implements ClientModInitializer {
  public static final String MOD_ID = "agma_standalone";
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);
  private static final java.util.regex.Pattern DIAGNOSTIC_VERSION =
      java.util.regex.Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
  private static final StandaloneCatalogService CATALOG =
      new StandaloneCatalogService(new FabricModMetadataSource());
  private static final StandaloneUiState UI_STATE = new StandaloneUiState();
  private static final StandalonePreviewStore PREVIEWS = new StandalonePreviewStore();
  private static ClientRuntimeController runtime;
  private static CatalogToolExecutor tools;
  private static BuildPreviewToolExecutor previewTools;
  private static PlayerContextToolExecutor playerContextTools;
  private static BlockInspectToolExecutor blockInspectTools;
  private static StandaloneToolRouter toolRouter;
  private static StandalonePreviewOverlay previewOverlay;
  private static volatile PreviewHologramBridge hologramBridge =
      PreviewHologramBridge.unsupported();
  private static boolean hologramLoaded;

  @Override
  public void onInitializeClient() {
    if (!INITIALIZED.compareAndSet(false, true)) {
      return;
    }
    var version =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.3.2");
    var root =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir()
            .resolve(MOD_ID)
            .toAbsolutePath()
            .normalize();
    runtime =
        new ClientRuntimeController(
            root, version, StandaloneClientEntrypoint.class.getClassLoader());
    CATALOG.knowledgeDirectory(root.resolve("knowledge").resolve("local-docs"));
    tools = new CatalogToolExecutor(CATALOG);
    previewOverlay = new StandalonePreviewOverlay(PREVIEWS);
    previewTools = new BuildPreviewToolExecutor(PREVIEWS, previewOverlay::onPreviewCreated);
    playerContextTools = new PlayerContextToolExecutor();
    blockInspectTools = new BlockInspectToolExecutor();
    toolRouter =
        new StandaloneToolRouter(previewTools, playerContextTools, blockInspectTools, tools);
    installHologramBridge();
    registerCatalogLifecycle();
    registerKey();
    registerPreviewHud();
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
          previewTools.close();
          playerContextTools.close();
          blockInspectTools.close();
          hologramBridge.close();
          runtime.close();
          CATALOG.close();
        });
  }

  /** Installs the hologram presentation bridge used by the hologram keybind; default is a no-op. */
  public static void setHologramBridge(PreviewHologramBridge bridge) {
    hologramBridge = Objects.requireNonNull(bridge, "bridge");
  }

  /**
   * Resolves the exact Litematica/MaLiLib combination and installs the hologram bridge only when
   * the reflective adapter links; every other outcome keeps the no-op bridge. Availability is
   * logged through the bounded, path-free diagnostic like the server-line client mod does.
   */
  private static void installHologramBridge() {
    var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
    var minecraft = Minecraft.getInstance();
    var inventory = new FabricModInventory();
    LitematicaAdapterDiagnostic diagnostic;
    StandaloneLitematicaController controller = null;
    try {
      var previewRoot = preparePreviewRoot(loader.getGameDir());
      var compatibility =
          LitematicaAdapterResolver.resolve(
              safeVersion(loader.getRawGameVersion()),
              safeVersion(inventory, "fabricloader").orElse("unknown"),
              inventory,
              StandaloneClientEntrypoint.class.getClassLoader(),
              previewRoot,
              minecraft::isSameThread);
      diagnostic = LitematicaAdapterDiagnostic.from(compatibility);
      if (compatibility.adapter().isPresent()) {
        controller =
            new StandaloneLitematicaController(
                compatibility, previewRoot, minecraft::isSameThread, minecraft::execute);
      }
    } catch (IOException | RuntimeException | LinkageError exception) {
      diagnostic =
          LitematicaAdapterDiagnostic.previewStorageUnavailable(
              safeVersion(loader.getRawGameVersion()),
              safeVersion(inventory, "fabricloader").orElse("unknown"),
              safeVersion(inventory, "litematica"),
              safeVersion(inventory, "malilib"));
    }
    if (controller != null) {
      setHologramBridge(controller);
    }
    LOGGER.info("AGMA standalone Litematica hologram availability={}", diagnostic.status());
  }

  private static Path preparePreviewRoot(Path gameDirectory) throws IOException {
    Path gameRoot = gameDirectory.toRealPath();
    Path config = ensureDirectory(gameRoot.resolve("config"), false);
    Path agentConfig = ensureDirectory(config.resolve(MOD_ID), true);
    Path previewRoot = ensureDirectory(agentConfig.resolve("previews"), true).toRealPath();
    if (!previewRoot.startsWith(gameRoot)) {
      throw new IOException("Managed preview root escaped the game directory");
    }
    return previewRoot;
  }

  private static Path ensureDirectory(Path directory, boolean ownerOnly) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(directory);
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Managed path is not a directory");
    }
    if (ownerOnly && directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    return directory;
  }

  private static Optional<String> safeVersion(FabricModInventory inventory, String modId) {
    try {
      return inventory.version(modId).filter(StandaloneClientEntrypoint::validDiagnosticVersion);
    } catch (RuntimeException | LinkageError exception) {
      return Optional.empty();
    }
  }

  private static String safeVersion(String version) {
    return validDiagnosticVersion(version) ? version : "unknown";
  }

  private static boolean validDiagnosticVersion(String version) {
    return version != null && DIAGNOSTIC_VERSION.matcher(version).matches();
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
          PREVIEWS.clear();
          previewOverlay.reset();
          hologramBridge.removeCurrent();
          hologramLoaded = false;
        });
    ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
        .registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
              @Override
              public ResourceLocation getFabricId() {
                return new ResourceLocation(MOD_ID, "catalog");
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

  private static void registerPreviewHud() {
    HudRenderCallback.EVENT.register(previewOverlay::render);
  }

  private static void registerKey() {
    var open =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.agma_standalone.open", GLFW.GLFW_KEY_G, "key.categories.agma_standalone"));
    var previewToggle =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.agma_standalone.preview_toggle",
                GLFW.GLFW_KEY_P,
                "key.categories.agma_standalone"));
    previewOverlay.toggleKey(previewToggle);
    var hologramToggle =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.agma_standalone.hologram_toggle",
                GLFW.GLFW_KEY_O,
                "key.categories.agma_standalone"));
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
            client.setScreen(
                new StandaloneAssistantScreen(CATALOG, runtime, tools, toolRouter, UI_STATE));
          }
          while (previewToggle.consumeClick()) {
            previewOverlay.toggle();
          }
          while (hologramToggle.consumeClick()) {
            if (hologramLoaded) {
              hologramBridge.removeCurrent();
              hologramLoaded = false;
            } else {
              var latest = PREVIEWS.latest();
              if (latest.isPresent()) {
                hologramBridge.load(latest.get());
                hologramLoaded = true;
              }
            }
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
