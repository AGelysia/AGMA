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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Executes game.block.inspect against the live client: the block the local player is pointing at,
 * or an explicit absolute position within 32 blocks, plus a bounded sanitized summary of the block
 * entity state when one exists. Reads hop onto the client thread (the connector calls handlers on
 * its own listener thread); an absent world completes as a non-retryable FAILED error the Runtime
 * feeds back to the model, while a block without a block entity is a successful result with
 * hasBlockEntity=false, never an error.
 */
public final class BlockInspectToolExecutor implements ClientToolHandler, AutoCloseable {
  public static final String TOOL = "game.block.inspect";

  private static final double MAXIMUM_REACH_SQUARED = 32.0 * 32.0;
  private static final long CLIENT_HOP_TIMEOUT_SECONDS = 5;

  private final ExecutorService executor;
  private final boolean ownsExecutor;
  private volatile boolean closed;

  public BlockInspectToolExecutor() {
    this(newInspectExecutor(), true);
  }

  BlockInspectToolExecutor(ExecutorService executor, boolean ownsExecutor) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.ownsExecutor = ownsExecutor;
  }

  private static ExecutorService newInspectExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          var thread = new Thread(runnable, "agma-standalone-block-inspect");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Override
  public CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call) {
    Objects.requireNonNull(call, "call");
    if (!TOOL.equals(call.tool())) {
      return CompletableFuture.completedFuture(
          failed("TOOL_UNKNOWN", "The block inspect executor only handles game.block.inspect."));
    }
    if (closed) {
      return CompletableFuture.completedFuture(
          failed("CLIENT_TOOL_EXECUTOR_CLOSED", "The client tool executor is closed."));
    }
    final BlockPos requested;
    try {
      requested = requestedPosition(call.arguments());
    } catch (IllegalArgumentException exception) {
      return CompletableFuture.completedFuture(
          failed("TOOL_ARGUMENTS_INVALID", "The block inspect arguments are invalid."));
    }
    return CompletableFuture.supplyAsync(() -> inspect(requested), executor);
  }

  private static BlockPos requestedPosition(Map<String, Object> arguments) {
    var position = arguments.get("position");
    if (position == null) {
      return null;
    }
    if (!(position instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("position");
    }
    if (!(map.get("x") instanceof Number x)
        || !(map.get("y") instanceof Number y)
        || !(map.get("z") instanceof Number z)) {
      throw new IllegalArgumentException("position");
    }
    return new BlockPos(x.intValue(), y.intValue(), z.intValue());
  }

  private ClientToolOutcome inspect(BlockPos requested) {
    var minecraft = Minecraft.getInstance();
    var hop = new CompletableFuture<ClientToolOutcome>();
    minecraft.execute(
        () -> {
          try {
            hop.complete(inspectOnClient(minecraft, requested));
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
      if (failure.getCause() instanceof InspectFailure inspectFailure) {
        return inspectFailure.error;
      }
      return failed("PLAYER_CONTEXT_UNAVAILABLE", "The client world could not be read.");
    }
  }

  private static ClientToolOutcome inspectOnClient(Minecraft minecraft, BlockPos requested) {
    var level = minecraft.level;
    var player = minecraft.player;
    if (level == null || player == null) {
      throw new InspectFailure(
          failed("PLAYER_CONTEXT_UNAVAILABLE", "No client world and player are loaded."));
    }
    BlockPos target = requested;
    if (target != null) {
      if (player.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5)
          > MAXIMUM_REACH_SQUARED) {
        throw new InspectFailure(
            failed(
                "BLOCK_INSPECT_OUT_OF_REACH",
                "The requested block is more than 32 blocks from the player."));
      }
    } else if (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
      target = blockHit.getBlockPos();
    } else {
      target = player.blockPosition();
    }
    var state = level.getBlockState(target);
    var found = !state.isAir();
    var blockEntity = found ? level.getBlockEntity(target) : null;
    var result = new LinkedHashMap<String, Object>();
    result.put("found", found);
    result.put("blockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    result.put("position", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()));
    result.put("hasBlockEntity", blockEntity != null);
    if (blockEntity != null) {
      result.put(
          "blockEntity",
          Map.of(
              "data",
              BlockEntityDataSanitizer.sanitize(
                  BlockEntityNbtConverter.toJava(
                      blockEntity.saveWithFullMetadata(level.registryAccess())))));
    }
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

  private static final class InspectFailure extends RuntimeException {
    private final transient ClientToolError error;

    InspectFailure(ClientToolError error) {
      super(error.message());
      this.error = error;
    }
  }
}
