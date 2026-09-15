package dev.minecraftagent.standalone.ui.preview;

import dev.minecraftagent.standalone.common.ClientToolCall;
import dev.minecraftagent.standalone.common.ClientToolCancellation;
import dev.minecraftagent.standalone.common.ClientToolError;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.ClientToolOutcome;
import dev.minecraftagent.standalone.common.ClientToolResult;
import dev.minecraftagent.standalone.common.preview.BlockLookup;
import dev.minecraftagent.standalone.common.preview.PreviewArguments;
import dev.minecraftagent.standalone.common.preview.PreviewCell;
import dev.minecraftagent.standalone.common.preview.PreviewChunkUnavailableException;
import dev.minecraftagent.standalone.common.preview.PreviewEngine;
import dev.minecraftagent.standalone.common.preview.PreviewLimitException;
import dev.minecraftagent.standalone.common.preview.PreviewPattern;
import dev.minecraftagent.standalone.common.preview.PreviewPosition;
import dev.minecraftagent.standalone.common.preview.PreviewRequest;
import dev.minecraftagent.standalone.common.preview.PreviewTargets;
import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Executes build.preview.create against the live client world. The engine itself is pure; every
 * world read (dimension, distance, chunks, registry states, block entity refusals, and the region
 * snapshot) happens on the client thread, mirrored from StandaloneCatalogService's marshal pattern.
 * Domain failures complete as non-retryable FAILED tool errors so the Runtime feeds them back to
 * the model for correction instead of aborting the request.
 */
public final class BuildPreviewToolExecutor implements ClientToolHandler, AutoCloseable {
  public static final String TOOL = "build.preview.create";

  private static final int MAXIMUM_ACTIVE_TOOLS = 4;
  private static final double MAXIMUM_DISTANCE = 128.0;
  private static final long CLIENT_HOP_TIMEOUT_SECONDS = 5;

  private final StandalonePreviewStore store;
  private final Consumer<StandalonePreview> created;
  private final ExecutorService executor;
  private final boolean ownsExecutor;
  private final Map<UUID, RunningTask> running = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public BuildPreviewToolExecutor(
      StandalonePreviewStore store, Consumer<StandalonePreview> created) {
    this(store, created, newToolExecutor(), true);
  }

  BuildPreviewToolExecutor(
      StandalonePreviewStore store,
      Consumer<StandalonePreview> created,
      ExecutorService executor,
      boolean ownsExecutor) {
    this.store = Objects.requireNonNull(store, "store");
    this.created = Objects.requireNonNull(created, "created");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.ownsExecutor = ownsExecutor;
  }

  @Override
  public CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call) {
    Objects.requireNonNull(call, "call");
    if (!TOOL.equals(call.tool())) {
      return CompletableFuture.completedFuture(
          failed("TOOL_UNKNOWN", "The preview executor only handles build.preview.create.", false));
    }
    if (closed.get()) {
      return CompletableFuture.completedFuture(
          failed("CLIENT_TOOL_EXECUTOR_CLOSED", "The client tool executor is closed.", false));
    }
    var task = new RunningTask(call);
    if (running.putIfAbsent(call.toolCallId(), task) != null) {
      return CompletableFuture.completedFuture(
          failed("DUPLICATE_TOOL_CALL", "The client tool call is already active.", false));
    }
    if (running.size() > MAXIMUM_ACTIVE_TOOLS && running.remove(call.toolCallId(), task)) {
      return CompletableFuture.completedFuture(
          failed("CLIENT_TOOL_CAPACITY", "The client tool capacity is exhausted.", true));
    }
    try {
      task.attach(
          executor.submit(
              () -> {
                try {
                  task.checkCancelled();
                  task.result.complete(run(task));
                } catch (CancellationException exception) {
                  task.result.cancel(false);
                } catch (PreviewFailure failure) {
                  task.result.complete(failure.error);
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  task.result.cancel(false);
                } catch (RuntimeException exception) {
                  task.result.complete(
                      failed(
                          "PREVIEW_EXECUTION_FAILED",
                          "The build preview could not be created.",
                          true));
                } finally {
                  running.remove(call.toolCallId(), task);
                }
              }));
    } catch (RejectedExecutionException exception) {
      running.remove(call.toolCallId(), task);
      task.result.complete(
          failed("CLIENT_TOOL_CAPACITY", "The client tool capacity is exhausted.", true));
    }
    return task.result;
  }

  @Override
  public void cancel(ClientToolCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    var task = running.get(cancellation.toolCallId());
    if (task != null
        && task.matches(cancellation)
        && running.remove(cancellation.toolCallId(), task)) {
      task.cancel();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.values().forEach(RunningTask::cancel);
    running.clear();
    if (ownsExecutor) {
      executor.shutdownNow();
    }
  }

  private ClientToolOutcome run(RunningTask task) throws InterruptedException {
    final PreviewRequest request;
    try {
      request = PreviewArguments.parse(task.call.arguments());
    } catch (PreviewLimitException failure) {
      throw failure("PREVIEW_LIMIT_EXCEEDED", failure.getMessage());
    } catch (IllegalArgumentException failure) {
      throw failure(
          "TOOL_ARGUMENTS_INVALID",
          "The build preview arguments do not match the tool schema: " + failure.getMessage());
    }
    final PreviewTargets targets;
    try {
      targets = PreviewEngine.prepare(request);
    } catch (PreviewLimitException failure) {
      throw failure("PREVIEW_LIMIT_EXCEEDED", failure.getMessage());
    }
    task.checkCancelled();
    var minecraft = Minecraft.getInstance();
    final List<PreviewCell> region;
    if (minecraft.isSameThread()) {
      region = snapshotOnClient(minecraft, request, targets);
    } else {
      var hop = new CompletableFuture<List<PreviewCell>>();
      minecraft.execute(
          () -> {
            try {
              hop.complete(snapshotOnClient(minecraft, request, targets));
            } catch (Throwable failure) {
              hop.completeExceptionally(failure);
            }
          });
      try {
        region = hop.get(CLIENT_HOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (ExecutionException failure) {
        if (failure.getCause() instanceof PreviewFailure previewFailure) {
          throw previewFailure;
        }
        throw failure(
            "PREVIEW_EXECUTION_FAILED",
            "The client world could not read the preview region.",
            true);
      } catch (TimeoutException failure) {
        throw failure("PREVIEW_WORLD_UNAVAILABLE", "The client world could not be read in time.");
      }
    }
    task.checkCancelled();
    var preview = PreviewEngine.build(request, targets, region);
    store.put(preview);
    created.accept(preview);
    return new ClientToolResult(preview.toResultMap());
  }

  /**
   * Validates the request against the live world and snapshots the union region. Runs on the client
   * thread only.
   */
  private static List<PreviewCell> snapshotOnClient(
      Minecraft minecraft, PreviewRequest request, PreviewTargets targets) {
    var level = minecraft.level;
    var player = minecraft.player;
    if (level == null || player == null) {
      throw failure("PREVIEW_WORLD_UNAVAILABLE", "No client world and player are loaded.");
    }
    if (!level.dimension().location().toString().equals(request.dimension())) {
      throw failure(
          "PREVIEW_DIMENSION_MISMATCH", "The preview must target the player's current dimension.");
    }
    var union = targets.unionBounds();
    var minY = level.getMinBuildHeight();
    if (union.min().y() < minY || union.max().y() >= minY + level.getHeight()) {
      throw failure(
          "PREVIEW_CHUNK_UNAVAILABLE", "The preview region is outside the world build height.");
    }
    for (var chunkX = union.min().x() >> 4; chunkX <= union.max().x() >> 4; chunkX++) {
      for (var chunkZ = union.min().z() >> 4; chunkZ <= union.max().z() >> 4; chunkZ++) {
        if (!level.hasChunk(chunkX, chunkZ)) {
          throw failure("PREVIEW_CHUNK_UNAVAILABLE", "Every target chunk must already be loaded.");
        }
      }
    }
    for (var shape : request.shapes()) {
      if (shape.pattern() != PreviewPattern.CLEAR) {
        try {
          ClientBlockStateResolver.parse(shape.blockState());
        } catch (ClientBlockStateResolver.BlockStateFailure failure) {
          throw failure(failure.code(), failure.getMessage());
        }
      }
    }
    BlockLookup lookup =
        (x, y, z) ->
            level.hasChunk(x >> 4, z >> 4)
                ? ClientBlockStateResolver.serialize(level.getBlockState(new BlockPos(x, y, z)))
                : null;
    final List<PreviewCell> region;
    try {
      region = PreviewEngine.snapshot(union, lookup);
    } catch (PreviewChunkUnavailableException failure) {
      throw failure("PREVIEW_CHUNK_UNAVAILABLE", "Every target chunk must already be loaded.");
    }
    var current = new HashMap<PreviewPosition, String>(region.size() * 2);
    for (var cell : region) {
      current.put(cell.position(), cell.state());
    }
    var playerX = player.getX();
    var playerY = player.getY();
    var playerZ = player.getZ();
    for (var entry : targets.cells().entrySet()) {
      var position = entry.getKey();
      if (entry.getValue().equals(current.get(position))) {
        continue;
      }
      var dx = position.x() + 0.5 - playerX;
      var dz = position.z() + 0.5 - playerZ;
      if (dx * dx + dz * dz > MAXIMUM_DISTANCE * MAXIMUM_DISTANCE
          || Math.abs(position.y() + 0.5 - playerY) > MAXIMUM_DISTANCE) {
        throw failure(
            "PREVIEW_TOO_FAR", "Every changed cell must be within 128 blocks of the player.");
      }
      if (level
          .getBlockState(new BlockPos(position.x(), position.y(), position.z()))
          .hasBlockEntity()) {
        throw failure(
            "PREVIEW_BLOCK_ENTITY_UNSUPPORTED", "Previews cannot replace block entities.");
      }
    }
    return region;
  }

  private static ClientToolError failed(String code, String message, boolean retryable) {
    return new ClientToolError(ClientToolError.Status.FAILED, code, message, retryable);
  }

  private static PreviewFailure failure(String code, String message) {
    return failure(code, message, false);
  }

  private static PreviewFailure failure(String code, String message, boolean retryable) {
    return new PreviewFailure(failed(code, message, retryable));
  }

  private static ExecutorService newToolExecutor() {
    return Executors.newSingleThreadExecutor(
        task -> {
          var thread = new Thread(task, "agma-standalone-preview-tools");
          thread.setDaemon(true);
          return thread;
        });
  }

  @SuppressWarnings("serial")
  private static final class PreviewFailure extends RuntimeException {
    private final ClientToolError error;

    private PreviewFailure(ClientToolError error) {
      super(null, null, false, false);
      this.error = error;
    }
  }

  private static final class RunningTask {
    private final ClientToolCall call;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<ClientToolOutcome> result = new CompletableFuture<>();
    private volatile Future<?> future;

    private RunningTask(ClientToolCall call) {
      this.call = call;
    }

    private void attach(Future<?> supplied) {
      future = supplied;
      if (cancelled.get()) {
        supplied.cancel(true);
      }
    }

    private void cancel() {
      cancelled.set(true);
      var submitted = future;
      if (submitted != null) {
        submitted.cancel(true);
      }
      result.cancel(false);
    }

    private boolean cancelled() {
      return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    private void checkCancelled() {
      if (cancelled()) {
        throw new CancellationException("client tool execution was cancelled");
      }
    }

    private boolean matches(ClientToolCancellation cancellation) {
      return call.requestId().equals(cancellation.requestId())
          && call.toolCallId().equals(cancellation.toolCallId())
          && call.subjectId().equals(cancellation.subjectId())
          && call.tool().equals(cancellation.tool())
          && call.sequence() == cancellation.sequence();
    }
  }
}
