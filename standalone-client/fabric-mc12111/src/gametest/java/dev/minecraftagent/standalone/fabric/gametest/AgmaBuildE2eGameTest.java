package dev.minecraftagent.standalone.fabric.gametest;

import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import dev.minecraftagent.standalone.fabric.StandaloneAssistantScreen;
import dev.minecraftagent.standalone.fabric.StandaloneCatalogService;
import dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint;
import dev.minecraftagent.standalone.fabric.preview.StandalonePreviewStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the standalone build flow end to end with the real managed Runtime and the configured
 * model provider: the model is asked to design a two-storey house next to the player, which needs
 * multiple tool rounds (player context, project create/read, build preview). The created preview is
 * asserted from the in-memory store, the HUD projection panel is captured, and the Litematica
 * hologram is toggled on for a world screenshot when Litematica is present.
 */
public final class AgmaBuildE2eGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-build-e2e");
  private static final String QUESTION =
      "保存项目：在我身旁建一座两层小楼并给我投影预览：石砖地板、橡木木板墙、留一扇门和至少两扇玻璃窗、"
          + "有屋顶。这是1.21.11平坦世界，地表y=-60，占地不超过12x12。"
          + "先用工具读取我的位置，再保存项目，最后创建投影。";

  @Override
  public void runTest(ClientGameTestContext context) {
    context.getInput().resizeWindow(1024, 768);
    context.computeOnClient(
        minecraft -> {
          minecraft.options.guiScale().set(2);
          return null;
        });
    context.waitTicks(5);
    var world = context.worldBuilder().create();
    try {
      Minecraft client = context.computeOnClient(minecraft -> minecraft);
      world.getClientWorld().waitForChunksDownload();
      var catalog = (StandaloneCatalogService) readField("CATALOG");
      var uiState = (StandaloneUiState) readField("UI_STATE");
      context.waitFor(ignored -> currentSnapshot(catalog) != null, 2400);

      var runtime = (ClientRuntimeController) readField("runtime");
      var toolRouter = (ClientToolHandler) readField("toolRouter");
      context.computeOnClient(
          minecraft -> {
            runtime.start(toolRouter);
            return null;
          });
      var ready =
          tryWaitFor(
              context,
              minecraft -> runtime.view().profile().state() == ClientLifecycleState.READY,
              4800);
      if (!ready) {
        throw new AssertionError(
            "managed Runtime did not become READY: " + runtime.view().profile().state());
      }
      LOGGER.info("managed Runtime READY; asking the model to build");

      context.getInput().pressKey(GLFW.GLFW_KEY_G);
      context.waitForScreen(StandaloneAssistantScreen.class);
      context.waitTicks(5);
      clickGui(context, client, questionX(client) + 40, questionY(client));
      // The box keeps the previous test's draft; clear it before typing (Ctrl+A, Backspace).
      context.getInput().holdControl();
      context.getInput().pressKey(GLFW.GLFW_KEY_A);
      context.getInput().releaseControl();
      context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
      context.waitTicks(2);
      context.getInput().typeChars(QUESTION);
      context.waitTicks(2);
      context.takeScreenshot("e2e-build-00-question");
      clickGui(context, client, sendX(client), questionY(client));

      var answered =
          tryWaitFor(
              context, minecraft -> !uiState.answer.isBlank() || requestSettled(uiState), 9600);
      if (!answered) {
        throw new AssertionError(
            "no answer arrived for the build request (status=" + uiState.status + ")");
      }
      LOGGER.info(
          "answer ({} chars, cost {} {}): {}",
          uiState.answer.length(),
          uiState.lastCostMicroUsd,
          uiState.lastCostKind,
          uiState.answer);

      var previews = (StandalonePreviewStore) readField("PREVIEWS");
      StandalonePreview preview = previews.latest().orElse(null);
      if (preview == null) {
        throw new AssertionError(
            "the model finished without creating a build preview: " + uiState.answer);
      }
      LOGGER.info(
          "preview {} project={} revision={} bounds={}..{} targets={} changes={} difference={} palette={}",
          preview.previewId(),
          preview.projectId(),
          preview.revision(),
          preview.bounds().min(),
          preview.bounds().max(),
          preview.targetBlockCount(),
          preview.changeCount(),
          preview.difference(),
          preview.palette());
      if (preview.targetBlockCount() < 20) {
        throw new AssertionError("preview is suspiciously small: " + preview.targetBlockCount());
      }

      // Close the screen so the in-game HUD projection panel and the world are visible.
      context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      context.waitTicks(15);
      context.takeScreenshot("e2e-build-01-hud-overlay");

      aimAtPreview(context, preview);
      var bridge = readField("hologramBridge");
      var litematicaPresent = bridge.getClass().getName().contains("Litematica");
      context.getInput().pressKey(GLFW.GLFW_KEY_O);
      context.waitTicks(40);
      if (litematicaPresent) {
        var hologramLoaded = (boolean) readField("hologramLoaded");
        if (!hologramLoaded) {
          throw new AssertionError("Litematica is present but the hologram did not load");
        }
        context.waitTicks(20);
        context.takeScreenshot("e2e-build-02-hologram");
        LOGGER.info("Litematica hologram loaded and captured");
      } else {
        LOGGER.info("Litematica not present; hologram stage skipped");
        context.takeScreenshot("e2e-build-02-no-hologram");
      }
      LOGGER.info("AgmaBuildE2eGameTest PASSED");
    } finally {
      world.close();
    }
  }

  private static void aimAtPreview(ClientGameTestContext context, StandalonePreview preview) {
    context.computeOnClient(
        minecraft -> {
          var player = minecraft.player;
          if (player == null) {
            return null;
          }
          var centerX = (preview.bounds().min().x() + preview.bounds().max().x()) / 2.0 + 0.5;
          var centerY = (preview.bounds().min().y() + preview.bounds().max().y()) / 2.0;
          var centerZ = (preview.bounds().min().z() + preview.bounds().max().z()) / 2.0 + 0.5;
          var eye = player.getEyePosition();
          var dx = centerX - eye.x;
          var dy = centerY - eye.y;
          var dz = centerZ - eye.z;
          var yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
          var pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
          player.setYRot(Mth.wrapDegrees(yaw));
          player.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
          player.setYHeadRot(player.getYRot());
          return null;
        });
    context.waitTicks(10);
  }

  private static boolean requestSettled(StandaloneUiState uiState) {
    return uiState.activeRequestId == null && !uiState.status.isBlank();
  }

  private static Object currentSnapshot(StandaloneCatalogService catalog) {
    return catalog.current().orElse(null);
  }

  @SuppressWarnings("unchecked")
  private static <T> T readField(String name) {
    try {
      var field = StandaloneClientEntrypoint.class.getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(null);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("cannot read StandaloneClientEntrypoint." + name, failure);
    }
  }

  private static boolean tryWaitFor(
      ClientGameTestContext context,
      java.util.function.Predicate<Minecraft> predicate,
      int timeoutTicks) {
    for (var tick = 0; tick < timeoutTicks; tick++) {
      if (context.computeOnClient(predicate::test)) {
        return true;
      }
      context.waitTick();
    }
    return false;
  }

  private static void clickGui(
      ClientGameTestContext context, Minecraft client, int guiX, int guiY) {
    var window = client.getWindow();
    var pixelX = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
    var pixelY = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
    context.getInput().setCursorPos(pixelX, pixelY);
    context.waitTicks(2);
    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    context.waitTicks(2);
  }

  private static int panelWidth(Minecraft client) {
    return Math.min(560, Math.max(300, client.getWindow().getGuiScaledWidth() - 24));
  }

  private static int panelLeft(Minecraft client) {
    return (client.getWindow().getGuiScaledWidth() - panelWidth(client)) / 2;
  }

  private static int panelTop(Minecraft client) {
    var height = client.getWindow().getGuiScaledHeight();
    var panelHeight = Math.min(350, Math.max(250, height - 24));
    return (height - panelHeight) / 2;
  }

  private static int questionX(Minecraft client) {
    return panelLeft(client) + 16;
  }

  private static int questionY(Minecraft client) {
    return panelTop(client) + 78;
  }

  private static int sendX(Minecraft client) {
    return panelLeft(client) + panelWidth(client) - 55;
  }
}
