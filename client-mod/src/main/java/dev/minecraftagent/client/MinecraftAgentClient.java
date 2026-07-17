package dev.minecraftagent.client;

import dev.minecraftagent.client.litematica.FabricModInventory;
import dev.minecraftagent.client.litematica.LitematicaAdapterDiagnostic;
import dev.minecraftagent.client.litematica.LitematicaAdapterResolver;
import dev.minecraftagent.client.litematica.LitematicaClientController;
import dev.minecraftagent.client.litematica.LitematicaDisplayReport;
import dev.minecraftagent.client.network.AgentClientPayload;
import dev.minecraftagent.client.network.ClientHandshakeAdvertisement;
import dev.minecraftagent.client.network.ClientPayloadCodec;
import dev.minecraftagent.client.network.ClientPresentationSession;
import dev.minecraftagent.client.network.ClientServerMessage;
import dev.minecraftagent.client.transfer.ViewTransferAccumulator;
import dev.minecraftagent.client.ui.OverlayController;
import dev.minecraftagent.client.ui.OverlayInteractionScreen;
import dev.minecraftagent.client.ui.OverlayPreferenceStore;
import dev.minecraftagent.client.ui.OverlayRenderer;
import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.StructuredViewDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftAgentClient implements ClientModInitializer {
  public static final String MOD_ID = "minecraftagent";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
  private static final String KEY_EDIT = "key.minecraftagent.edit_overlay";
  private static final String KEY_PIN = "key.minecraftagent.toggle_pin";
  private static final String KEY_CLEAR = "key.minecraftagent.clear_views";
  private static final java.util.regex.Pattern DIAGNOSTIC_VERSION =
      java.util.regex.Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

  @Override
  public void onInitializeClient() {
    var minecraft = Minecraft.getInstance();
    var loader = FabricLoader.getInstance();
    var inventory = new FabricModInventory();
    var overlay = new OverlayController(new OverlayPreferenceStore(loader.getGameDir()));
    var renderer = new OverlayRenderer(minecraft, overlay);
    var tasks = ClientTaskQueue.create();
    var mainTasks = ClientMainThreadQueue.create(action -> executeOnClient(minecraft, action));
    var litematica = createLitematicaController(loader, inventory, minecraft);
    var advertisement = advertisement(loader, litematica);

    PayloadTypeRegistry.playC2S().register(AgentClientPayload.TYPE, AgentClientPayload.CODEC);
    PayloadTypeRegistry.playS2C().register(AgentClientPayload.TYPE, AgentClientPayload.CODEC);

    var session =
        new ClientPresentationSession(
            new ClientPayloadCodec(),
            new ViewTransferAccumulator(),
            new StructuredViewDecoder(),
            overlay,
            mainTasks::enqueue,
            bytes -> send(mainTasks, bytes),
            presentationActions(litematica.controller()));

    registerHud(renderer);
    registerKeys(renderer, overlay);
    registerNetworking(tasks, session, advertisement);
    registerLifecycle(tasks, mainTasks, session);
    LOGGER.info(
        "AGMA client initialized; Litematica presentation status={}",
        litematica.diagnostic().status());
  }

  private static LitematicaInitialization createLitematicaController(
      FabricLoader loader, FabricModInventory inventory, Minecraft minecraft) {
    Path previewRoot = loader.getConfigDir().resolve(MOD_ID).resolve("previews").normalize();
    try {
      previewRoot = preparePreviewRoot(loader.getGameDir());
      String minecraftVersion = safeVersion(loader.getRawGameVersion());
      String loaderVersion = safeVersion(inventory, "fabricloader").orElse("unknown");
      var compatibility =
          LitematicaAdapterResolver.resolve(
              minecraftVersion,
              loaderVersion,
              inventory,
              MinecraftAgentClient.class.getClassLoader(),
              previewRoot,
              minecraft::isSameThread);
      LOGGER.info("AGMA Litematica compatibility={}", compatibility.status());
      return new LitematicaInitialization(
          new LitematicaClientController(compatibility, previewRoot),
          LitematicaAdapterDiagnostic.from(compatibility));
    } catch (IOException | RuntimeException | LinkageError exception) {
      LOGGER.warn("AGMA managed preview directory is unavailable");
      try {
        return new LitematicaInitialization(
            new LitematicaClientController(Optional.empty(), previewRoot),
            LitematicaAdapterDiagnostic.previewStorageUnavailable(
                safeVersion(loader.getRawGameVersion()),
                safeVersion(inventory, "fabricloader").orElse("unknown"),
                safeVersion(inventory, "litematica"),
                safeVersion(inventory, "malilib")));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "Unable to create disabled Litematica controller", impossible);
      }
    }
  }

  private static Optional<String> safeVersion(FabricModInventory inventory, String modId) {
    try {
      return inventory.version(modId).filter(MinecraftAgentClient::validDiagnosticVersion);
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

  private static ClientHandshakeAdvertisement advertisement(
      FabricLoader loader, LitematicaInitialization litematica) {
    String modVersion =
        loader.getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
    var diagnostic = litematica.diagnostic();
    boolean litematicaAvailable =
        diagnostic.status() == LitematicaAdapterDiagnostic.Status.READY
            && litematica.controller().available();
    return new ClientHandshakeAdvertisement(
        modVersion,
        1,
        1,
        2,
        litematicaAvailable ? 1 : 0,
        litematicaAvailable ? 1 : 0,
        diagnostic.litematicaVersion(),
        diagnostic.malilibVersion(),
        diagnostic);
  }

  private static ClientPresentationSession.PresentationActionSink presentationActions(
      LitematicaClientController litematica) {
    return new ClientPresentationSession.PresentationActionSink() {
      @Override
      public ClientPresentationSession.PresentationAction prepare(
          ClientServerMessage.Action action, UUID viewId) {
        return switch (action) {
          case LITEMATICA_PREVIEW_LOAD -> {
            var prepared =
                litematica.prepareLoad(
                    viewId, "Agent preview " + viewId.toString().substring(0, 8));
            yield () -> logLitematicaReport(litematica.load(prepared));
          }
          case LITEMATICA_PREVIEW_REMOVE -> () -> logLitematicaReport(litematica.remove(viewId));
          case LITEMATICA_MATERIAL_LIST_OPEN ->
              () -> logLitematicaReport(litematica.openMaterialList(viewId));
          case PIN, UNPIN, CLEAR -> throw new IllegalArgumentException("Not a preview action");
        };
      }

      @Override
      public boolean stage(StructuredView view) {
        return !(view.content() instanceof BuildPreviewView preview)
            || litematica.stagePreview(preview);
      }

      @Override
      public boolean commit(StructuredView view, java.util.Set<UUID> displayedViewIds) {
        return view.content() instanceof BuildPreviewView preview
            ? litematica.commitPreview(preview, displayedViewIds)
            : litematica.reconcileDisplayedPreviews(displayedViewIds);
      }

      @Override
      public void discard(StructuredView view) {
        if (view.content() instanceof BuildPreviewView preview) {
          litematica.discardPreview(preview);
        }
      }

      @Override
      public void disconnect() {
        litematica.close();
      }

      @Override
      public void clear(UUID viewId) {
        litematica.remove(viewId);
      }
    };
  }

  private static void logLitematicaReport(LitematicaDisplayReport report) {
    if (report.state() == LitematicaDisplayReport.State.FAILED) {
      LOGGER.warn("AGMA Litematica action failed: {}", report.failure().orElseThrow().name());
    }
  }

  private static void registerHud(OverlayRenderer renderer) {
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath(MOD_ID, "overlay"),
        renderer::render);
  }

  private static void registerKeys(OverlayRenderer renderer, OverlayController overlay) {
    var category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "presentation"));
    var edit =
        KeyBindingHelper.registerKeyBinding(new KeyMapping(KEY_EDIT, GLFW.GLFW_KEY_O, category));
    var pin =
        KeyBindingHelper.registerKeyBinding(new KeyMapping(KEY_PIN, GLFW.GLFW_KEY_P, category));
    var clear =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(KEY_CLEAR, GLFW.GLFW_KEY_UNKNOWN, category));
    ClientTickEvents.END_CLIENT_TICK.register(
        minecraft -> {
          while (edit.consumeClick()) {
            OverlayInteractionScreen.open(minecraft, renderer);
          }
          while (pin.consumeClick()) {
            overlay.togglePin();
          }
          while (clear.consumeClick()) {
            overlay.clear();
          }
        });
  }

  private static void registerNetworking(
      ClientTaskQueue tasks,
      ClientPresentationSession session,
      ClientHandshakeAdvertisement advertisement) {
    ClientPlayNetworking.registerGlobalReceiver(
        AgentClientPayload.TYPE,
        (payload, context) -> tasks.enqueue(() -> session.receive(payload.bytes())));
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, minecraft) -> {
          if (ClientPlayNetworking.canSend(AgentClientPayload.TYPE)) {
            tasks.replacePending(() -> session.connect(advertisement));
          }
        });
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, minecraft) -> tasks.replacePending(session::disconnect));
    tasks.scheduleEverySecond(session::expireTransfers);
  }

  private static void registerLifecycle(
      ClientTaskQueue tasks, ClientMainThreadQueue mainTasks, ClientPresentationSession session) {
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        minecraft -> {
          tasks.stop(session::disconnect);
          mainTasks.stop();
        });
  }

  private static void executeOnClient(Minecraft minecraft, Runnable action) {
    if (minecraft.isSameThread()) {
      action.run();
    } else {
      minecraft.execute(action);
    }
  }

  private static void send(ClientMainThreadQueue mainTasks, byte[] bytes) {
    mainTasks.enqueue(
        () -> {
          try {
            if (ClientPlayNetworking.canSend(AgentClientPayload.TYPE)) {
              ClientPlayNetworking.send(new AgentClientPayload(bytes));
            }
          } catch (IllegalStateException | IllegalArgumentException exception) {
            LOGGER.warn("AGMA client payload could not be sent");
          }
        });
  }

  private record LitematicaInitialization(
      LitematicaClientController controller, LitematicaAdapterDiagnostic diagnostic) {}
}
