package dev.minecraftagent.client;

import dev.minecraftagent.client.litematica.LitematicaDisplayReport;
import dev.minecraftagent.client.view.ViewType;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full server-line end-to-end drive: connect to the local Paper E2E server, ask the agent (with the
 * real Runtime and model provider) to design and preview structures over multiple tool rounds, then
 * load each preview through the real Litematica presentation path and capture screenshots. Skips
 * cleanly when the E2E server is not running.
 */
public final class AgmaServerE2eGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-server-e2e");
  private static final String SERVER_ADDRESS =
      System.getProperty("agma.e2e.server", "127.0.0.1:25599");

  private static final String[][] REQUESTS = {
    {
      "/agent module project 保存项目「石塔」：摘要 solid 石头塔，底座 5x5，高 8；目标 [用 solid 石头建塔, 底座 5x5, 高 8]；约束 [只用 solid 石头, 底座固定 5x5, 高度固定 8]。字段已齐全，现在直接保存，不要询问",
      "/agent module build 为我的「石塔」项目生成建造投影：pattern=solid，bounds 以我当前位置为最小角、尺寸 5x5x8，方块 minecraft:stone。这是 1.21.11 平坦世界，y=-60 就是地表，直接执行不要再询问",
    },
    {
      "/agent module project 保存项目「空心木屋」：摘要 橡木木板空心小屋，7x7，高 5；目标 [建空心小屋, 7x7, 高 5]；约束 [只用橡木木板, 尺寸固定 7x7x5]。字段已齐全，现在直接保存，不要询问",
      "/agent module build 为我的「空心木屋」项目生成建造投影：pattern=walls，bounds 以我当前位置为最小角、尺寸 7x5x7，方块 minecraft:oak_planks。这是 1.21.11 平坦世界，y=-60 就是地表，直接执行不要再询问",
    },
    {
      "/agent module project 保存项目「石板地板」：摘要 石砖地板 9x9 一层；目标 [铺石砖地板, 9x9]；约束 [只用石砖, 尺寸固定 9x1x9]。字段已齐全，现在直接保存，不要询问",
      "/agent module build 为我的「石板地板」项目生成建造投影：pattern=floor，bounds 以我当前位置为最小角、尺寸 9x1x9，方块 minecraft:stone_bricks。这是 1.21.11 平坦世界，y=-60 就是地表，直接执行不要再询问",
    },
  };

  @Override
  public void runTest(ClientGameTestContext context) {
    context.getInput().resizeWindow(1024, 768);
    context.computeOnClient(
        minecraft -> {
          minecraft.options.guiScale().set(2);
          return null;
        });
    context.waitTicks(5);

    context.computeOnClient(
        minecraft -> {
          var address = ServerAddress.parseString(SERVER_ADDRESS);
          ConnectScreen.startConnecting(
              new TitleScreen(),
              minecraft,
              address,
              new ServerData("agma-e2e", SERVER_ADDRESS, ServerData.Type.OTHER),
              false,
              null);
          return null;
        });
    var joined =
        tryWaitFor(context, minecraft -> minecraft.level != null && minecraft.player != null, 1200);
    if (!joined) {
      LOGGER.info("E2E server {} is unreachable; skipping the server-line drive", SERVER_ADDRESS);
      return;
    }
    LOGGER.info("joined E2E server; waiting for the agent handshake to settle");
    context.waitTicks(60);
    context.computeOnClient(
        minecraft -> {
          MinecraftAgentClient.overlayController().clear();
          return null;
        });
    context.waitTicks(5);

    for (var index = 0; index < REQUESTS.length; index++) {
      driveStructure(context, index + 1, REQUESTS[index]);
    }
    LOGGER.info("AgmaServerE2eGameTest PASSED");
  }

  private static void driveStructure(
      ClientGameTestContext context, int index, String[] requestMessages) {
    sendChat(context, requestMessages[0]);
    if (!waitForAnyView(context, 2400)) {
      throw new AssertionError("project save did not complete for: " + requestMessages[0]);
    }
    sendChat(context, requestMessages[1]);
    var viewId = waitForPreview(context, 3600);
    if (viewId == null) {
      throw new AssertionError("no build preview view arrived for: " + requestMessages[0]);
    }
    context.waitTicks(10);
    context.takeScreenshot(String.format("e2e-server-%02d-overlay", index));

    sendChat(context, "/agent ui preview " + viewId);
    context.waitTicks(30);
    context.takeScreenshot(String.format("e2e-server-%02d-hologram", index));

    var controller = MinecraftAgentClient.litematicaController();
    var removed = context.computeOnClient(minecraft -> controller.remove(viewId));
    if (removed.state() != LitematicaDisplayReport.State.REMOVED) {
      throw new AssertionError(
          "preview " + viewId + " was not loaded through the command path: " + removed.failure());
    }
    LOGGER.info("structure {} preview loaded and removed via /agent ui preview", index);
    context.computeOnClient(
        minecraft -> {
          MinecraftAgentClient.overlayController().clear();
          return null;
        });
    context.waitTicks(5);
  }

  private static boolean waitForAnyView(ClientGameTestContext context, int timeoutTicks) {
    var overlay = MinecraftAgentClient.overlayController();
    for (var tick = 0; tick < timeoutTicks; tick += 5) {
      var present = context.computeOnClient(minecraft -> overlay.snapshot().isPresent());
      if (present) {
        return true;
      }
      context.waitTicks(5);
    }
    return false;
  }

  private static UUID waitForPreview(ClientGameTestContext context, int timeoutTicks) {
    var overlay = MinecraftAgentClient.overlayController();
    for (var tick = 0; tick < timeoutTicks; tick += 5) {
      var viewId =
          context.computeOnClient(
              minecraft ->
                  overlay
                      .snapshot()
                      .filter(snapshot -> snapshot.view().viewType() == ViewType.BUILD_PREVIEW)
                      .map(snapshot -> snapshot.view().viewId())
                      .orElse(null));
      if (viewId != null) {
        return viewId;
      }
      context.waitTicks(5);
    }
    return null;
  }

  private static boolean tryWaitFor(
      ClientGameTestContext context,
      java.util.function.Predicate<Minecraft> predicate,
      int timeoutTicks) {
    for (var tick = 0; tick < timeoutTicks; tick++) {
      if (context.computeOnClient(minecraft -> predicate.test(minecraft))) {
        return true;
      }
      context.waitTick();
    }
    return false;
  }

  private static void sendChat(ClientGameTestContext context, String command) {
    context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);
    context.waitTicks(3);
    context.getInput().typeChars(command.substring(1));
    context.waitTicks(3);
    context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitTicks(3);
  }
}
