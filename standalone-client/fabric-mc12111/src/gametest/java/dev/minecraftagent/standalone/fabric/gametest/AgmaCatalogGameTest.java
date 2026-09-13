package dev.minecraftagent.standalone.fabric.gametest;

import dev.minecraftagent.standalone.common.RouteTreeModel;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.fabric.StandaloneAssistantScreen;
import dev.minecraftagent.standalone.fabric.StandaloneCatalogScreen;
import dev.minecraftagent.standalone.fabric.StandaloneCatalogService;
import dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the standalone catalog surface end to end: integrated-server recipe capture, the G key,
 * the Catalog tab, an exact-id search, local planning, and the route tree rendering.
 */
public final class AgmaCatalogGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-gametest");

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
      var catalog = catalogService();
      var uiState = uiState();

      context.waitFor(ignored -> currentSnapshot(catalog) != null, 2400);
      var snapshot = currentSnapshot(catalog);
      LOGGER.info(
          "catalog ready: {} resources, {} processes, generation {}",
          snapshot.resources().size(),
          snapshot.processes().size(),
          snapshot.generationId());
      if (snapshot.processes().size() < 1000) {
        throw new AssertionError(
            "catalog has only "
                + snapshot.processes().size()
                + " processes; integrated-server capture is missing");
      }
      var ironSmelting =
          snapshot.processes().stream()
              .anyMatch(
                  process ->
                      process.categoryId().equals("minecraft:cooking")
                          && process.outputs().stream()
                              .anyMatch(
                                  output -> output.resource().id().equals("minecraft:iron_ingot")));
      if (!ironSmelting) {
        throw new AssertionError("no cooking process producing minecraft:iron_ingot");
      }
      var sourceLayer = snapshot.processes().getFirst().source().layer().name();
      LOGGER.info("process source layer: {}", sourceLayer);

      context.getInput().pressKey(GLFW.GLFW_KEY_G);
      context.waitForScreen(StandaloneAssistantScreen.class);
      clickGui(context, client, tabCenterX(client, 0), tabCenterY(client));
      context.waitForScreen(StandaloneCatalogScreen.class);
      context.waitTicks(5);
      context.takeScreenshot("01-catalog-open");

      clickGui(context, client, queryX(client) + 30, queryY(client));
      context.getInput().typeChars("minecraft:iron_ingot");
      context.waitTicks(2);
      clickGui(context, client, searchX(client), queryY(client));
      context.waitTicks(10);
      clickGui(context, client, candidateX(client), candidateY(client));
      context.waitFor(ignored -> uiState.selected != null, 400);
      context.waitFor(ignored -> uiState.localPlan != null, 2400);
      context.waitTicks(10);
      context.takeScreenshot("02-route-tree");

      var rows = RouteTreeModel.rows(uiState.localPlan, snapshot, 0);
      LOGGER.info("route tree rows: {}", rows.size());
      if (rows.size() < 3) {
        throw new AssertionError("route tree has only " + rows.size() + " rows");
      }

      var planArea = planAreaPoint(client);
      context.getInput().setCursorPos(planArea[0], planArea[1]);
      scrollLines(context, 4);
      context.takeScreenshot("03-route-tree-scrolled");
      scrollLines(context, 9);
      context.takeScreenshot("04-route-tree-steps");
      LOGGER.info("AgmaCatalogGameTest PASSED");
    } finally {
      world.close();
    }
  }

  private static StandaloneCatalogService catalogService() {
    return readStaticField("CATALOG");
  }

  private static StandaloneUiState uiState() {
    return readStaticField("UI_STATE");
  }

  @SuppressWarnings("unchecked")
  private static <T> T readStaticField(String name) {
    try {
      var field = StandaloneClientEntrypoint.class.getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(null);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("cannot read StandaloneClientEntrypoint." + name, failure);
    }
  }

  private static CatalogSnapshot currentSnapshot(StandaloneCatalogService catalog) {
    return catalog.current().orElse(null);
  }

  private static void scrollLines(ClientGameTestContext context, int lines) {
    for (var index = 0; index < lines; index++) {
      context.getInput().scroll(-1);
      context.waitTicks(1);
    }
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

  private static int panelLeft(Minecraft client) {
    var width = client.getWindow().getGuiScaledWidth();
    return (width - panelWidth(client)) / 2;
  }

  private static int panelWidth(Minecraft client) {
    var width = client.getWindow().getGuiScaledWidth();
    return Math.min(560, Math.max(280, width - 24));
  }

  private static int panelTop(Minecraft client) {
    var height = client.getWindow().getGuiScaledHeight();
    var panelHeight = Math.min(410, Math.max(180, height - 24));
    return Math.max(4, (height - panelHeight) / 2);
  }

  private static int tabCenterX(Minecraft client, int tab) {
    var tabWidth = (panelWidth(client) - 32) / 3;
    return panelLeft(client) + 16 + tabWidth * tab + tabWidth / 2;
  }

  private static int tabCenterY(Minecraft client) {
    return panelTop(client) + 18;
  }

  private static int queryX(Minecraft client) {
    return panelLeft(client) + 16;
  }

  private static int queryY(Minecraft client) {
    return panelTop(client) + 62;
  }

  private static int searchX(Minecraft client) {
    return panelLeft(client) + panelWidth(client) - 58;
  }

  private static int candidateX(Minecraft client) {
    return panelLeft(client) + 16 + (panelWidth(client) - 32) / 2;
  }

  private static int candidateY(Minecraft client) {
    return panelTop(client) + 124;
  }

  private static int[] planAreaPoint(Minecraft client) {
    var window = client.getWindow();
    var guiX = panelLeft(client) + panelWidth(client) / 2;
    var guiY = panelTop(client) + 200;
    return new int[] {
      guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
      guiY * window.getScreenHeight() / window.getGuiScaledHeight()
    };
  }
}
