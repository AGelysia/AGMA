package dev.minecraftagent.standalone.fabric.gametest;

import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.fabric.StandaloneAssistantScreen;
import dev.minecraftagent.standalone.fabric.StandaloneCatalogService;
import dev.minecraftagent.standalone.fabric.StandaloneClientEntrypoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the two phase-2 tools end to end with the real managed Runtime and the configured model
 * provider: the model must call local.knowledge.search for a planted guide document and
 * game.block.inspect for a chest placed in the world, then answer from both sources. Sentinel terms
 * exist only in the planted document, so the answer proves the knowledge retrieval path.
 */
public final class AgmaKnowledgeE2eGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-knowledge-e2e");
  private static final String GUIDE =
      """
      # 星辉工厂手册（测试文档）

      ## 星辉熔炉怎么用
      星辉熔炉是星辉工厂的核心机器。用法：把星尘矿石放入燃料槽，再用星尘粉点燃炉心，\
      等待三十秒即可获得星辉锭。

      ## 星辉熔炉的配方
      星辉熔炉由四个星辉砖块和一个熔炉合成。
      """;

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

      // Plant the sentinel guide document next to the managed knowledge docs (non-managed name so
      // the knowledge writer's stale cleanup never touches it).
      context.computeOnClient(
          minecraft -> {
            try {
              var dir =
                  minecraft
                      .gameDirectory
                      .toPath()
                      .resolve("config")
                      .resolve("agma_standalone")
                      .resolve("knowledge")
                      .resolve("local-docs");
              Files.createDirectories(dir);
              var guide = dir.resolve("custom-starlight-guide.md");
              Files.writeString(guide, GUIDE, StandardCharsets.UTF_8);
              if (guide.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(
                    guide, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
              }
            } catch (java.io.IOException failure) {
              throw new IllegalStateException("cannot plant the guide document", failure);
            }
            return null;
          });

      // Place a chest with seven apples east of the player through the integrated server.
      var chestPos = new java.util.concurrent.atomic.AtomicReference<net.minecraft.core.BlockPos>();
      context.computeOnClient(
          minecraft -> {
            var server = minecraft.getSingleplayerServer();
            var playerPos = minecraft.player.blockPosition().east(3);
            chestPos.set(playerPos);
            server.execute(
                () -> {
                  var level = server.overworld();
                  level.setBlockAndUpdate(playerPos, Blocks.CHEST.defaultBlockState());
                  if (level.getBlockEntity(playerPos) instanceof ChestBlockEntity chest) {
                    chest.setItem(0, new ItemStack(Items.APPLE, 7));
                    chest.setChanged();
                    // Container content changes do not broadcast on their own (that path only
                    // exists for open container menus), so push a block update with the current
                    // block entity data to tracking clients.
                    level.sendBlockUpdated(
                        playerPos,
                        Blocks.CHEST.defaultBlockState(),
                        Blocks.CHEST.defaultBlockState(),
                        3);
                    LOGGER.info(
                        "server-side chest NBT: {}",
                        chest.saveWithFullMetadata(level.registryAccess()));
                  } else {
                    LOGGER.warn("server-side chest block entity missing at {}", playerPos);
                  }
                });
            return null;
          });
      context.waitTicks(10);
      LOGGER.info("chest placed at {}", chestPos.get());
      context.computeOnClient(
          minecraft -> {
            // Container content changes on the server do not broadcast to clients outside an open
            // container menu, so seed the client-visible chest contents directly: the tool under
            // test reads client-visible block entity state by contract.
            var level = minecraft.level;
            if (level != null
                && level.getBlockEntity(chestPos.get()) instanceof ChestBlockEntity clientChest) {
              clientChest.setItem(0, new ItemStack(Items.APPLE, 7));
              LOGGER.info(
                  "client-side chest NBT after seeding: {}",
                  clientChest.saveWithFullMetadata(level.registryAccess()));
            } else {
              LOGGER.warn("client-side chest block entity missing at {}", chestPos.get());
            }
            return null;
          });

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

      var pos = chestPos.get();
      var knowledgeAnswer = "";
      for (var attempt = 1; attempt <= 3 && !knowledgeAnswer.contains("星尘"); attempt++) {
        knowledgeAnswer =
            askAndSettle(
                context,
                client,
                uiState,
                "只调用一次 local.knowledge.search（查询词“星辉熔炉”）；"
                    + "不要调用任何其他工具，不要创建任何投影。"
                    + "然后根据工具结果用中文回答：星辉熔炉怎么用？");
        LOGGER.info("knowledge answer (attempt {}): {}", attempt, knowledgeAnswer);
      }
      if (!knowledgeAnswer.contains("星尘")) {
        throw new AssertionError(
            "answer does not cite the planted guide document (missing 星尘): " + knowledgeAnswer);
      }
      var inspectAnswer = "";
      for (var attempt = 1;
          attempt <= 3
              && !(inspectAnswer.contains("苹果")
                  || inspectAnswer.contains("apple")
                  || inspectAnswer.contains("Apple"));
          attempt++) {
        inspectAnswer =
            askAndSettle(
                context,
                client,
                uiState,
                String.format(
                    "只调用一次 game.block.inspect（position 参数为坐标 (%d, %d, %d)）；"
                        + "不要调用任何其他工具，不要创建任何投影。"
                        + "然后根据工具结果用中文回答：那个方块实体里有什么物品，有多少个？",
                    pos.getX(), pos.getY(), pos.getZ()));
        LOGGER.info("block inspect answer (attempt {}): {}", attempt, inspectAnswer);
      }
      if (!inspectAnswer.contains("苹果")
          && !inspectAnswer.contains("apple")
          && !inspectAnswer.contains("Apple")) {
        throw new AssertionError(
            "answer does not report the chest contents from game.block.inspect: " + inspectAnswer);
      }
      context.takeScreenshot("standalone-knowledge-answer");
      LOGGER.info("AgmaKnowledgeE2eGameTest PASSED");
    } finally {
      world.close();
    }
  }

  /** Types one question into the assistant screen, sends it, and waits for the settled answer. */
  private static String askAndSettle(
      ClientGameTestContext context, Minecraft client, StandaloneUiState uiState, String question) {
    context.getInput().pressKey(GLFW.GLFW_KEY_G);
    context.waitForScreen(StandaloneAssistantScreen.class);
    context.waitTicks(5);
    clickGui(context, client, questionX(client) + 40, questionY(client));
    context.getInput().holdControl();
    context.getInput().pressKey(GLFW.GLFW_KEY_A);
    context.getInput().releaseControl();
    context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
    context.waitTicks(2);
    context.getInput().typeChars(question);
    context.waitTicks(2);
    context.takeScreenshot("standalone-knowledge-question");
    clickGui(context, client, sendX(client), questionY(client));
    var started = tryWaitFor(context, minecraft -> uiState.activeRequestId != null, 400);
    if (!started) {
      throw new AssertionError("request never started for: " + question);
    }
    var answered =
        tryWaitFor(
            context,
            minecraft -> uiState.activeRequestId == null && !uiState.status.isBlank(),
            7200);
    if (!answered) {
      throw new AssertionError(
          "no settled answer for: " + question + " (status=" + uiState.status + ")");
    }
    context.waitTicks(5);
    context.waitTicks(40);
    LOGGER.info(
        "answer ({} chars, cost {} {}, status={})",
        uiState.answer.length(),
        uiState.lastCostMicroUsd,
        uiState.lastCostKind,
        uiState.status);
    return uiState.answer;
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
}
