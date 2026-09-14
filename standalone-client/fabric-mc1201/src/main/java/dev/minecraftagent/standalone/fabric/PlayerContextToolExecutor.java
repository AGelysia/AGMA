package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.ClientToolCall;
import dev.minecraftagent.standalone.common.ClientToolError;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.ClientToolOutcome;
import dev.minecraftagent.standalone.common.ClientToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;

/**
 * Executes game.player.context.read against the live client: the local player's current dimension,
 * block position, and view rotation, so the model can place build previews next to the player.
 * Reads hop onto the client thread (the connector calls handlers on its own listener thread), and
 * an absent world completes as a non-retryable FAILED error the Runtime feeds back to the model.
 */
public final class PlayerContextToolExecutor implements ClientToolHandler, AutoCloseable {
  public static final String TOOL = "game.player.context.read";

  private static final long CLIENT_HOP_TIMEOUT_SECONDS = 5;

  private final ExecutorService executor;
  private final boolean ownsExecutor;
  private volatile boolean closed;

  public PlayerContextToolExecutor() {
    this(newContextExecutor(), true);
  }

  PlayerContextToolExecutor(ExecutorService executor, boolean ownsExecutor) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.ownsExecutor = ownsExecutor;
  }

  private static ExecutorService newContextExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          var thread = new Thread(runnable, "agma-standalone-player-context");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Override
  public CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call) {
    Objects.requireNonNull(call, "call");
    if (!TOOL.equals(call.tool())) {
      return CompletableFuture.completedFuture(
          failed(
              "TOOL_UNKNOWN",
              "The player context executor only handles game.player.context.read."));
    }
    if (closed) {
      return CompletableFuture.completedFuture(
          failed("CLIENT_TOOL_EXECUTOR_CLOSED", "The client tool executor is closed."));
    }
    return CompletableFuture.supplyAsync(this::readContext, executor);
  }

  private ClientToolOutcome readContext() {
    var minecraft = Minecraft.getInstance();
    var hop = new CompletableFuture<ClientToolOutcome>();
    minecraft.execute(
        () -> {
          try {
            hop.complete(readContextOnClient(minecraft));
          } catch (Throwable failure) {
            hop.completeExceptionally(failure);
          }
        });
    try {
      return hop.get(CLIENT_HOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return failed("PLAYER_CONTEXT_UNAVAILABLE", "The client world could not be read.");
    } catch (TimeoutException failure) {
      return failed("PLAYER_CONTEXT_UNAVAILABLE", "The client world could not be read in time.");
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof ContextFailure contextFailure) {
        return contextFailure.error;
      }
      return failed("PLAYER_CONTEXT_UNAVAILABLE", "The client world could not be read.");
    }
  }

  private static ClientToolOutcome readContextOnClient(Minecraft minecraft) {
    var level = minecraft.level;
    var player = minecraft.player;
    if (level == null || player == null) {
      throw new ContextFailure(
          failed("PLAYER_CONTEXT_UNAVAILABLE", "No client world and player are loaded."));
    }
    var position = player.blockPosition();
    var result = new LinkedHashMap<String, Object>();
    result.put("dimension", level.dimension().location().toString());
    result.put(
        "position", Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ()));
    result.put("yaw", player.getYRot());
    result.put("pitch", player.getXRot());
    return new ClientToolResult(Map.copyOf(result));
  }

  private static ClientToolError failed(String code, String message) {
    return new ClientToolError(ClientToolError.Status.FAILED, code, message, false);
  }

  @Override
  public void close() {
    closed = true;
    if (ownsExecutor) {
      executor.shutdownNow();
    }
  }

  private static final class ContextFailure extends RuntimeException {
    private final transient ClientToolError error;

    ContextFailure(ClientToolError error) {
      super(error.message());
      this.error = error;
    }
  }
}
