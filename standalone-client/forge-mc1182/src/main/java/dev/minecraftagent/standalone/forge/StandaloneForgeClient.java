package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.OptionalViewerRegistry;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.ui.BlockInspectToolExecutor;
import dev.minecraftagent.standalone.ui.PlayerContextToolExecutor;
import dev.minecraftagent.standalone.ui.StandaloneCatalogService;
import dev.minecraftagent.standalone.ui.StandaloneScreenNavigation;
import dev.minecraftagent.standalone.ui.preview.BuildPreviewToolExecutor;
import dev.minecraftagent.standalone.ui.preview.StandalonePreviewOverlay;
import dev.minecraftagent.standalone.ui.preview.StandalonePreviewStore;
import dev.minecraftagent.standalone.ui.preview.StandaloneToolRouter;
import dev.minecraftagent.standalone.ui.preview.hologram.PreviewHologramBridge;
import dev.minecraftagent.standalone.ui.preview.litematica.LitematicaAdapterDiagnostic;
import dev.minecraftagent.standalone.ui.preview.litematica.LitematicaAdapterResolver;
import dev.minecraftagent.standalone.ui.preview.litematica.StandaloneLitematicaController;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

/** Minecraft Forge 1.18.2 client implementation, loaded only on the client distribution. */
final class StandaloneForgeClient {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(StandaloneForgeMod.MOD_ID);
  private static final java.util.regex.Pattern DIAGNOSTIC_VERSION =
      java.util.regex.Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
  private static final AtomicBoolean CLOSED = new AtomicBoolean();
  private static final StandaloneCatalogService CATALOG =
      new StandaloneCatalogService(new ForgeModMetadataSource());
  private static final StandaloneUiState UI_STATE = new StandaloneUiState();
  private static final StandalonePreviewStore PREVIEWS = new StandalonePreviewStore();
  private static final KeyMapping OPEN =
      new KeyMapping("key.agma_standalone.open", GLFW.GLFW_KEY_G, "key.categories.agma_standalone");
  private static final KeyMapping PREVIEW_TOGGLE =
      new KeyMapping(
          "key.agma_standalone.preview_toggle", GLFW.GLFW_KEY_P, "key.categories.agma_standalone");
  private static final KeyMapping HOLOGRAM_TOGGLE =
      new KeyMapping(
          "key.agma_standalone.hologram_toggle", GLFW.GLFW_KEY_O, "key.categories.agma_standalone");

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

  private StandaloneForgeClient() {}

  static void bootstrap() {
    var client = new StandaloneForgeClient();
    StandaloneScreenNavigation.assistant(
        (catalog, runtime, tools, toolRouter, state) ->
            new StandaloneAssistantScreen(catalog, runtime, tools, toolRouter, state));
    var modBus = FMLJavaModLoadingContext.get().getModEventBus();
    modBus.addListener(client::clientSetup);
    modBus.addListener(client::registerReloadListeners);
    MinecraftForge.EVENT_BUS.addListener(client::clientTick);
    MinecraftForge.EVENT_BUS.addListener(client::loggedIn);
    MinecraftForge.EVENT_BUS.addListener(client::loggedOut);
    MinecraftForge.EVENT_BUS.addListener(client::renderOverlay);
  }

  private void clientSetup(FMLClientSetupEvent event) {
    ClientRegistry.registerKeyBinding(OPEN);
    ClientRegistry.registerKeyBinding(PREVIEW_TOGGLE);
    ClientRegistry.registerKeyBinding(HOLOGRAM_TOGGLE);
    event.enqueueWork(StandaloneForgeClient::initializeRuntime);
  }

  private static void initializeRuntime() {
    if (!INITIALIZED.compareAndSet(false, true)) {
      return;
    }
    try {
      var version =
          ModList.get()
              .getModContainerById(StandaloneForgeMod.MOD_ID)
              .map(container -> container.getModInfo().getVersion().toString())
              .orElse("0.3.2");
      var root =
          FMLPaths.CONFIGDIR.get().resolve(StandaloneForgeMod.MOD_ID).toAbsolutePath().normalize();
      runtime =
          new ClientRuntimeController(root, version, StandaloneForgeClient.class.getClassLoader());
      CATALOG.knowledgeDirectory(root.resolve("knowledge").resolve("local-docs"));
      tools = new CatalogToolExecutor(CATALOG);
      previewOverlay = new StandalonePreviewOverlay(PREVIEWS);
      previewOverlay.toggleKey(PREVIEW_TOGGLE);
      previewOverlay.hologramKey(HOLOGRAM_TOGGLE);
      previewTools = new BuildPreviewToolExecutor(PREVIEWS, previewOverlay::onPreviewCreated);
      playerContextTools = new PlayerContextToolExecutor();
      blockInspectTools = new BlockInspectToolExecutor();
      toolRouter =
          new StandaloneToolRouter(previewTools, playerContextTools, blockInspectTools, tools);
      installHologramBridge();
      OptionalViewerRegistry.setRefresh(StandaloneForgeClient::refreshCatalog);
      CATALOG.refresh(Minecraft.getInstance());
      Runtime.getRuntime()
          .addShutdownHook(new Thread(StandaloneForgeClient::close, "agma-standalone-forge-close"));
    } catch (RuntimeException | Error failure) {
      INITIALIZED.set(false);
      throw failure;
    }
  }

  /**
   * Resolves the exact Litematica/MaLiLib combination and installs the hologram bridge only when
   * the reflective adapter links; every other outcome keeps the no-op bridge. Litematica is not
   * published for Forge, so the resolver is expected to report NOT_INSTALLED here.
   */
  private static void installHologramBridge() {
    var minecraft = Minecraft.getInstance();
    var inventory = new ForgeModInventory();
    LitematicaAdapterDiagnostic diagnostic;
    StandaloneLitematicaController controller = null;
    try {
      var previewRoot = preparePreviewRoot(FMLPaths.GAMEDIR.get());
      var compatibility =
          LitematicaAdapterResolver.resolve(
              safeVersion(minecraft.getLaunchedVersion()),
              safeVersion(inventory, "forge").orElse("unknown"),
              inventory,
              StandaloneForgeClient.class.getClassLoader(),
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
              safeVersion(minecraft.getLaunchedVersion()),
              safeVersion(inventory, "forge").orElse("unknown"),
              safeVersion(inventory, "litematica"),
              safeVersion(inventory, "malilib"));
    }
    if (controller != null) {
      hologramBridge = controller;
      controller.loadListener(StandaloneForgeClient::onHologramLoad);
    }
    LOGGER.info("AGMA standalone Litematica hologram availability={}", diagnostic.status());
  }

  private static Path preparePreviewRoot(Path gameDirectory) throws IOException {
    Path gameRoot = gameDirectory.toRealPath();
    Path config = ensureDirectory(gameRoot.resolve("config"), false);
    Path agentConfig = ensureDirectory(config.resolve(StandaloneForgeMod.MOD_ID), true);
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

  private static Optional<String> safeVersion(ForgeModInventory inventory, String modId) {
    try {
      return inventory.version(modId).filter(StandaloneForgeClient::validDiagnosticVersion);
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
          PREVIEWS.clear();
          previewOverlay.reset();
          hologramBridge.removeCurrent();
          hologramLoaded = false;
        });
  }

  private void renderOverlay(RenderGameOverlayEvent.Post event) {
    if (!INITIALIZED.get() || CLOSED.get() || previewOverlay == null) {
      return;
    }
    if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
      previewOverlay.render(event.getMatrixStack(), event.getPartialTicks());
    }
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
      client.setScreen(
          new StandaloneAssistantScreen(CATALOG, runtime, tools, toolRouter, UI_STATE));
    }
    while (PREVIEW_TOGGLE.consumeClick()) {
      previewOverlay.toggle();
    }
    while (HOLOGRAM_TOGGLE.consumeClick()) {
      if (hologramLoaded) {
        hologramBridge.removeCurrent();
        hologramLoaded = false;
      } else if (!hologramBridge.available()) {
        clientMessage(
            client, new TranslatableComponent("chat.agma_standalone.hologram_unavailable"));
      } else {
        var latest = PREVIEWS.latest();
        if (latest.isPresent()) {
          hologramBridge.load(latest.get());
        } else {
          clientMessage(
              client, new TranslatableComponent("chat.agma_standalone.hologram_no_preview"));
        }
      }
    }
  }

  static ClientRuntimeController runtimeController() {
    return runtime;
  }

  private static void onHologramLoad(boolean loaded, String reason) {
    if (loaded) {
      hologramLoaded = true;
      return;
    }
    hologramLoaded = false;
    var key =
        reason == null
            ? "chat.agma_standalone.hologram_load_failed"
            : switch (reason) {
              case "STAGE_FAILED" -> "chat.agma_standalone.hologram_stage_failed";
              case "ADAPTER_UNAVAILABLE" -> "chat.agma_standalone.hologram_unavailable";
              case "MANAGED_FILE_UNAVAILABLE", "MANAGED_FILE_HASH_MISMATCH" ->
                  "chat.agma_standalone.hologram_file_unavailable";
              default -> "chat.agma_standalone.hologram_load_failed";
            };
    clientMessage(Minecraft.getInstance(), new TranslatableComponent(key));
  }

  private static void clientMessage(Minecraft client, TranslatableComponent message) {
    client.execute(
        () -> {
          if (client.player != null) {
            client.player.displayClientMessage(message, false);
          }
        });
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
    closeQuietly(previewTools);
    closeQuietly(playerContextTools);
    closeQuietly(blockInspectTools);
    closeQuietly(hologramBridge);
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
