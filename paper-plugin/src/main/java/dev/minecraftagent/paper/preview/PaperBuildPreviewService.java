package dev.minecraftagent.paper.preview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Bounds;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Cell;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Pattern;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Position;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Request;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/** Bounded two-pass Bukkit snapshotter for Paper-owned build previews. It never mutates a world. */
public final class PaperBuildPreviewService {
  @FunctionalInterface
  public interface MainThreadScheduler {
    void execute(Runnable task);
  }

  private static final int CELLS_PER_SLICE = 128;
  private static final long SLICE_NANOS = 2_000_000L;
  private static final double MAXIMUM_HORIZONTAL_DISTANCE = 128.0;

  private final Server server;
  private final MainThreadScheduler mainThread;
  private final Executor worker;
  private final Supplier<String> serverId;
  private final BuildPreviewArtifactRepository artifacts;
  private final BuildPreviewArtifactFactory factory;
  private final Function<String, BlockData> blockDataParser;

  public PaperBuildPreviewService(
      Server server,
      MainThreadScheduler mainThread,
      Executor worker,
      Supplier<String> serverId,
      BuildPreviewArtifactRepository artifacts) {
    this(
        server,
        mainThread,
        worker,
        serverId,
        artifacts,
        new BuildPreviewArtifactFactory(),
        Bukkit::createBlockData);
  }

  PaperBuildPreviewService(
      Server server,
      MainThreadScheduler mainThread,
      Executor worker,
      Supplier<String> serverId,
      BuildPreviewArtifactRepository artifacts,
      BuildPreviewArtifactFactory factory,
      Function<String, BlockData> blockDataParser) {
    this.server = Objects.requireNonNull(server);
    this.mainThread = Objects.requireNonNull(mainThread);
    this.worker = Objects.requireNonNull(worker);
    this.serverId = Objects.requireNonNull(serverId);
    this.artifacts = Objects.requireNonNull(artifacts);
    this.factory = Objects.requireNonNull(factory);
    this.blockDataParser = Objects.requireNonNull(blockDataParser);
  }

  public CompletionStage<ReadToolResult> execute(ReadToolCall call) {
    discard(call.requestId());
    var future = new CompletableFuture<ReadToolResult>();
    try {
      var player = livePlayer(call);
      if (player == null) {
        future.complete(
            rejected("PLAYER_UNAVAILABLE", "The requesting player is no longer online."));
        return future;
      }
      if (!player.hasPermission("minecraftagent.use")) {
        future.complete(
            rejected("PERMISSION_DENIED", "The player is not allowed to use this tool."));
        return future;
      }
      var parsed = parse(call, player);
      var validation = validateWorld(player, parsed);
      if (validation != null) {
        future.complete(validation);
        return future;
      }
      snapshotSlice(new Snapshot(call, parsed, player.getWorld(), future));
    } catch (BuildFailure error) {
      future.complete(rejected(error.code, error.safeMessage));
    } catch (RuntimeException error) {
      future.complete(failed("BUILD_PREVIEW_FAILED", "The server could not create this preview."));
    }
    return future;
  }

  public void discard(UUID requestId) {
    artifacts.discard(Objects.requireNonNull(requestId));
  }

  private void snapshotSlice(Snapshot snapshot) {
    if (snapshot.future.isDone()) {
      return;
    }
    try {
      var player = livePlayer(snapshot.call);
      if (player == null || player.getWorld() != snapshot.world) {
        snapshot.future.complete(
            rejected(
                "PLAYER_UNAVAILABLE", "The requesting player is no longer in the target world."));
        return;
      }
      if (!player.hasPermission("minecraftagent.use")) {
        snapshot.future.complete(
            rejected("PERMISSION_DENIED", "The preview permission was revoked."));
        return;
      }
      var deadline = System.nanoTime() + SLICE_NANOS;
      var count = 0;
      while (snapshot.index < snapshot.positions.size()
          && count++ < CELLS_PER_SLICE
          && System.nanoTime() < deadline) {
        var position = snapshot.positions.get(snapshot.index);
        if (!snapshot.world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
          throw new BuildFailure(
              "BUILD_CHUNK_UNLOADED", "Every target chunk must already be loaded.");
        }
        var block = snapshot.world.getBlockAt(position.x(), position.y(), position.z());
        var state =
            BuildPreviewArtifactFactory.canonicalBlockState(
                block.getBlockData().getAsString(false));
        var target = targetState(snapshot.request, position);
        if (!state.equals(target) && block.getState(false) instanceof TileState) {
          throw new BuildFailure(
              "BUILD_BLOCK_ENTITY_UNSUPPORTED", "Previews cannot replace block entities.");
        }
        if (!snapshot.verifying) {
          snapshot.cells.add(new Cell(position, state));
        } else if (!snapshot.cells.get(snapshot.index).state().equals(state)) {
          throw new BuildFailure(
              "BUILD_REGION_CHANGED", "The target region changed while it was being read.");
        }
        snapshot.index++;
      }
      if (snapshot.index < snapshot.positions.size()) {
        mainThread.execute(() -> snapshotSlice(snapshot));
        return;
      }
      if (!snapshot.verifying) {
        snapshot.verifying = true;
        snapshot.index = 0;
        mainThread.execute(() -> snapshotSlice(snapshot));
        return;
      }
      worker.execute(() -> finish(snapshot));
    } catch (BuildFailure error) {
      snapshot.future.complete(rejected(error.code, error.safeMessage));
    } catch (RuntimeException error) {
      snapshot.future.complete(
          failed("BUILD_PREVIEW_FAILED", "The server could not read the target region."));
    }
  }

  private void finish(Snapshot snapshot) {
    try {
      if (snapshot.future.isDone()) {
        return;
      }
      var artifact = factory.create(snapshot.request, snapshot.cells);
      artifacts.put(snapshot.call.requestId(), snapshot.call.playerUuid(), artifact.view());
      snapshot.future.complete(
          ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, artifact.toolResult()));
    } catch (RuntimeException error) {
      snapshot.future.complete(
          failed("BUILD_PREVIEW_FAILED", "The server could not encode the preview."));
    }
  }

  private Request parse(ReadToolCall call, Player player) throws BuildFailure {
    var arguments = call.arguments();
    try {
      var projectId = UUID.fromString(string(arguments, "projectId"));
      if (!projectId.toString().equals(string(arguments, "projectId"))) {
        throw invalid();
      }
      var revision = integer(arguments, "revision");
      var operation = string(arguments, "operation");
      var dimension = string(arguments, "dimension");
      var boundsObject = object(arguments, "bounds");
      var bounds = new Bounds(position(boundsObject, "min"), position(boundsObject, "max"));
      var origin = position(arguments, "origin");
      if (!bounds.contains(origin)) {
        throw new BuildFailure(
            "BUILD_ORIGIN_INVALID", "The preview origin must be inside its bounds.");
      }
      var pattern =
          Pattern.valueOf(string(arguments, "pattern").toUpperCase(java.util.Locale.ROOT));
      String blockState = null;
      if (!arguments.get("blockState").isJsonNull()) {
        var supplied = string(arguments, "blockState");
        var parsed = blockDataParser.apply(supplied);
        blockState = BuildPreviewArtifactFactory.canonicalBlockState(parsed.getAsString(false));
        if (!supplied.equals(blockState)) {
          throw new BuildFailure(
              "BUILD_BLOCK_STATE_INVALID",
              "The block state must be a complete canonical server state.");
        }
        if (parsed.createBlockState() instanceof TileState) {
          throw new BuildFailure(
              "BUILD_BLOCK_ENTITY_UNSUPPORTED", "Previews cannot contain block entities.");
        }
        if (parsed.getMaterial().isAir()) {
          throw new BuildFailure(
              "BUILD_BLOCK_STATE_INVALID",
              "The block state must be a complete canonical server state.");
        }
      }
      return new Request(
          call.requestId(),
          Objects.requireNonNull(serverId.get()),
          player.getWorld().getUID(),
          dimension,
          projectId,
          revision,
          operation,
          bounds,
          origin,
          pattern,
          blockState,
          integer(arguments, "rotation"),
          string(arguments, "mirror"));
    } catch (BuildFailure error) {
      throw error;
    } catch (IllegalArgumentException | NullPointerException error) {
      throw invalid();
    }
  }

  private ReadToolResult validateWorld(Player player, Request request) {
    var world = player.getWorld();
    if (!world.getKey().toString().equals(request.dimension())) {
      return rejected(
          "BUILD_DIMENSION_MISMATCH", "The target must be in the player's current dimension.");
    }
    if (request.bounds().min().y() < world.getMinHeight()
        || request.bounds().max().y() >= world.getMaxHeight()) {
      return rejected("BUILD_BOUNDS_INVALID", "The target is outside the world's build height.");
    }
    var location = player.getLocation();
    for (var corner : List.of(request.bounds().min(), request.bounds().max())) {
      var dx = corner.x() + 0.5 - location.getX();
      var dz = corner.z() + 0.5 - location.getZ();
      if (dx * dx + dz * dz > MAXIMUM_HORIZONTAL_DISTANCE * MAXIMUM_HORIZONTAL_DISTANCE
          || Math.abs(corner.y() - location.getY()) > MAXIMUM_HORIZONTAL_DISTANCE) {
        return rejected(
            "BUILD_TARGET_TOO_FAR", "The bounded target must be near the requesting player.");
      }
      if (!world
          .getWorldBorder()
          .isInside(new Location(world, corner.x() + 0.5, corner.y(), corner.z() + 0.5))) {
        return rejected("BUILD_BOUNDS_INVALID", "The target is outside the world border.");
      }
    }
    for (var chunkX = request.bounds().min().x() >> 4;
        chunkX <= request.bounds().max().x() >> 4;
        chunkX++) {
      for (var chunkZ = request.bounds().min().z() >> 4;
          chunkZ <= request.bounds().max().z() >> 4;
          chunkZ++) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
          return rejected("BUILD_CHUNK_UNLOADED", "Every target chunk must already be loaded.");
        }
      }
    }
    return null;
  }

  private Player livePlayer(ReadToolCall call) {
    var player = server.getPlayer(call.playerUuid());
    return player != null && player.isOnline() && player.getUniqueId().equals(call.playerUuid())
        ? player
        : null;
  }

  private static String targetState(Request request, Position position) {
    if (request.pattern() == Pattern.CLEAR) {
      return "minecraft:air";
    }
    return request.pattern().contains(request.bounds(), position)
        ? request.blockState()
        : "minecraft:air";
  }

  private static Position position(JsonObject source, String name) throws BuildFailure {
    var value = object(source, name);
    if (!value.keySet().equals(Set.of("x", "y", "z"))) {
      throw invalid();
    }
    return new Position(integer(value, "x"), integer(value, "y"), integer(value, "z"));
  }

  private static JsonObject object(JsonObject source, String name) throws BuildFailure {
    var value = source.get(name);
    if (value == null || !value.isJsonObject()) {
      throw invalid();
    }
    return value.getAsJsonObject();
  }

  private static String string(JsonObject source, String name) throws BuildFailure {
    JsonElement value = source.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return value.getAsString();
  }

  private static int integer(JsonObject source, String name) throws BuildFailure {
    JsonElement value = source.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw invalid();
    }
    try {
      return value.getAsInt();
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  private static BuildFailure invalid() {
    return new BuildFailure(
        "TOOL_ARGUMENTS_INVALID", "The build plan does not match its closed schema.");
  }

  private static ReadToolResult rejected(String code, String message) {
    return ReadToolResult.rejected(ReadToolResult.Source.PAPER_POLICY, code, message);
  }

  private static ReadToolResult failed(String code, String message) {
    return ReadToolResult.failed(ReadToolResult.Source.PAPER_API, code, message, false);
  }

  private static final class Snapshot {
    private final ReadToolCall call;
    private final Request request;
    private final World world;
    private final CompletableFuture<ReadToolResult> future;
    private final List<Position> positions;
    private final ArrayList<Cell> cells;
    private int index;
    private boolean verifying;

    private Snapshot(
        ReadToolCall call, Request request, World world, CompletableFuture<ReadToolResult> future) {
      this.call = call;
      this.request = request;
      this.world = world;
      this.future = future;
      positions = request.bounds().positions();
      cells = new ArrayList<>(positions.size());
    }
  }

  private static final class BuildFailure extends Exception {
    private final String code;
    private final String safeMessage;

    private BuildFailure(String code, String safeMessage) {
      this.code = code;
      this.safeMessage = safeMessage;
    }
  }
}
