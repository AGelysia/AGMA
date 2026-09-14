package dev.minecraftagent.standalone.ui.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.common.ClientToolCall;
import dev.minecraftagent.standalone.common.ClientToolError;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.ClientToolResult;
import dev.minecraftagent.standalone.ui.BlockInspectToolExecutor;
import dev.minecraftagent.standalone.ui.PlayerContextToolExecutor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class StandaloneToolRouterTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TOOL_CALL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SUBJECT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void routesBlockInspectToItsOwnExecutor() {
    var router = router();
    var outcome = router.execute(call("game.block.inspect", Map.of())).toCompletableFuture().join();
    var error = assertInstanceOf(ClientToolError.class, outcome);
    assertEquals("CLIENT_TOOL_EXECUTOR_CLOSED", error.code());
  }

  @Test
  void routesBlockInspectWithAnExplicitPosition() {
    var router = router();
    var outcome =
        router
            .execute(
                call("game.block.inspect", Map.of("position", Map.of("x", 1, "y", 64, "z", 2))))
            .toCompletableFuture()
            .join();
    var error = assertInstanceOf(ClientToolError.class, outcome);
    assertEquals("CLIENT_TOOL_EXECUTOR_CLOSED", error.code());
  }

  @Test
  void keepsPlayerContextAndCatalogRouting() {
    var router = router();
    var context =
        router.execute(call("game.player.context.read", Map.of())).toCompletableFuture().join();
    assertEquals(
        "CLIENT_TOOL_EXECUTOR_CLOSED", assertInstanceOf(ClientToolError.class, context).code());

    var catalog =
        router
            .execute(call("game.resource.search", Map.of("query", "iron", "limit", 1)))
            .toCompletableFuture()
            .join();
    var result = assertInstanceOf(ClientToolResult.class, catalog);
    assertEquals(Map.of("catalog", "game.resource.search"), result.result());
  }

  @Test
  void blockInspectExecutorRejectsForeignToolsWithoutTouchingTheGame() {
    try (var executor = new BlockInspectToolExecutor()) {
      var outcome =
          executor.execute(call("game.player.context.read", Map.of())).toCompletableFuture().join();
      var error = assertInstanceOf(ClientToolError.class, outcome);
      assertEquals("TOOL_UNKNOWN", error.code());
      assertTrue(error.message().contains("game.block.inspect"));
    }
  }

  private static StandaloneToolRouter router() {
    var preview = new BuildPreviewToolExecutor(new StandalonePreviewStore(), created -> {});
    preview.close();
    var playerContext = new PlayerContextToolExecutor();
    playerContext.close();
    var blockInspect = new BlockInspectToolExecutor();
    blockInspect.close();
    ClientToolHandler catalog =
        call ->
            CompletableFuture.completedFuture(new ClientToolResult(Map.of("catalog", call.tool())));
    return new StandaloneToolRouter(preview, playerContext, blockInspect, catalog);
  }

  private static ClientToolCall call(String tool, Map<String, Object> arguments) {
    return new ClientToolCall(REQUEST_ID, TOOL_CALL_ID, SUBJECT_ID, tool, 0, arguments);
  }
}
