package dev.minecraftagent.standalone.fabric.gametest;

import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.fabric.StandaloneAssistantScreen;
import dev.minecraftagent.standalone.fabric.StandaloneCatalogService;
import dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the standalone Ask flow end to end with the real managed Runtime and the configured model
 * provider: start the embedded Runtime, ask a recipe question in Chinese, and capture the answered
 * screen. The multi-round tool usage then happens through the real connector against the live
 * catalog.
 */
public final class AgmaAskE2eGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-ask-e2e");
  private static final String QUESTION = "这个物品怎么获得？用中文详细说明它的合成配方和材料路线";

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
      var catalog = readStaticField("CATALOG");
      var uiState = (StandaloneUiState) readField("UI_STATE");
      context.waitFor(ignored -> currentSnapshot(catalog) != null, 2400);
      LOGGER.info("catalog processes: {}", currentSnapshot(catalog).processes().size());

      // Select iron_ingot in the Catalog tab so the Ask flow gets a pinned target context and
      // the model can drive multiple process tool rounds against a known catalog generation.
      context.getInput().pressKey(GLFW.GLFW_KEY_G);
      context.waitForScreen(StandaloneAssistantScreen.class);
      clickGui(context, client, catalogTabCenterX(client, 0), tabCenterY(client));
      context.waitForScreen(dev.minecraftagent.standalone.fabric.StandaloneCatalogScreen.class);
      clickGui(context, client, catalogQueryX(client) + 40, catalogQueryY(client));
      context.getInput().typeChars("minecraft:iron_ingot");
      context.waitTicks(2);
      clickGui(context, client, searchX(client), catalogQueryY(client));
      context.waitTicks(10);
      clickGui(context, client, candidateX(client), candidateY(client));
      context.waitFor(ignored -> uiState.selected != null, 400);
      LOGGER.info("selected target: {}", uiState.selected.id());

      var runtime = (ClientRuntimeController) readField("runtime");
      var tools = (ClientToolHandler) readField("toolRouter");
      context.computeOnClient(
          minecraft -> {
            runtime.start(tools);
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
      LOGGER.info("managed Runtime READY; asking the model");

      clickGui(context, client, catalogTabCenterX(client, 1), tabCenterY(client));
      context.waitForScreen(StandaloneAssistantScreen.class);
      context.waitTicks(5);
      clickGui(context, client, questionX(client) + 40, questionY(client));
      // Clear any leftover draft before typing (Ctrl+A, Backspace).
      context.getInput().holdControl();
      context.getInput().pressKey(GLFW.GLFW_KEY_A);
      context.getInput().releaseControl();
      context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
      context.waitTicks(2);
      context.getInput().typeChars(QUESTION);
      context.waitTicks(2);
      context.takeScreenshot("standalone-ask-question");
      clickGui(context, client, sendX(client), questionY(client));

      var answered =
          tryWaitFor(
              context, minecraft -> !uiState.answer.isBlank() || requestSettled(uiState), 7200);
      if (!answered) {
        throw new AssertionError(
            "no answer arrived for: " + QUESTION + " (status=" + uiState.status + ")");
      }
      context.waitTicks(10);
      context.takeScreenshot("standalone-ask-answer");
      LOGGER.info(
          "answer ({} chars, cost {} {}, status={}): {}",
          uiState.answer.length(),
          uiState.lastCostMicroUsd,
          uiState.lastCostKind,
          uiState.status,
          uiState.answer.substring(0, Math.min(300, uiState.answer.length())));
      if (uiState.answer.isBlank()) {
        throw new AssertionError("request settled without an answer, status=" + uiState.status);
      }
      LOGGER.info("AgmaAskE2eGameTest PASSED");
    } finally {
      world.close();
    }
  }

  private static boolean requestSettled(StandaloneUiState uiState) {
    return uiState.activeRequestId == null && !uiState.status.isBlank();
  }

  private static CatalogSnapshot currentSnapshot(StandaloneCatalogService catalog) {
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

  @SuppressWarnings("unchecked")
  private static StandaloneCatalogService readStaticField(String name) {
    return (StandaloneCatalogService) readField(name);
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

  private static int catalogPanelWidth(Minecraft client) {
    return Math.min(560, Math.max(280, client.getWindow().getGuiScaledWidth() - 24));
  }

  private static int catalogPanelLeft(Minecraft client) {
    return (client.getWindow().getGuiScaledWidth() - catalogPanelWidth(client)) / 2;
  }

  private static int catalogPanelTop(Minecraft client) {
    var height = client.getWindow().getGuiScaledHeight();
    var panelHeight = Math.min(410, Math.max(180, height - 24));
    return Math.max(4, (height - panelHeight) / 2);
  }

  private static int catalogTabCenterX(Minecraft client, int tab) {
    var tabWidth = (catalogPanelWidth(client) - 32) / 3;
    return catalogPanelLeft(client) + 16 + tabWidth * tab + tabWidth / 2;
  }

  private static int tabCenterY(Minecraft client) {
    return catalogPanelTop(client) + 18;
  }

  private static int catalogQueryX(Minecraft client) {
    return catalogPanelLeft(client) + 16;
  }

  private static int catalogQueryY(Minecraft client) {
    return catalogPanelTop(client) + 62;
  }

  private static int searchX(Minecraft client) {
    return catalogPanelLeft(client) + catalogPanelWidth(client) - 58;
  }

  private static int candidateX(Minecraft client) {
    return catalogPanelLeft(client) + 16 + (catalogPanelWidth(client) - 32) / 2;
  }

  private static int candidateY(Minecraft client) {
    return catalogPanelTop(client) + 124;
  }
}
