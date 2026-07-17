package dev.minecraftagent.paper.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PaperBuildPreviewServiceTest {
  @Test
  void rejectsARegionThatChangesBetweenCaptureAndVerificationPasses() {
    var playerId = UUID.randomUUID();
    var world = mock(World.class);
    when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    var border = mock(WorldBorder.class);
    when(border.isInside(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(true);
    when(world.getWorldBorder()).thenReturn(border);
    var block = mock(Block.class);
    var blockData = mock(BlockData.class);
    when(blockData.getAsString(false)).thenReturn("minecraft:stone", "minecraft:dirt");
    when(block.getBlockData()).thenReturn(blockData);
    when(block.getState(false)).thenReturn(mock(BlockState.class));
    when(world.getBlockAt(0, 64, 0)).thenReturn(block);

    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.isOnline()).thenReturn(true);
    when(player.hasPermission("minecraftagent.use")).thenReturn(true);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
    var server = mock(Server.class);
    when(server.getPlayer(playerId)).thenReturn(player);
    var tasks = new ArrayDeque<Runnable>();
    var service =
        new PaperBuildPreviewService(
            server,
            tasks::add,
            Runnable::run,
            () -> "test-server",
            new BuildPreviewArtifactRepository());
    var call =
        new ReadToolCall(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            playerId,
            AgentModule.BUILD,
            "build.preview.create",
            clearPlan(),
            0);

    var future = service.execute(call).toCompletableFuture();
    assertFalse(future.isDone());
    tasks.remove().run();

    assertEquals(ReadToolResult.Status.REJECTED, future.join().status());
    assertEquals("BUILD_REGION_CHANGED", future.join().error().code());
  }

  @Test
  void failedRetryAfterSuccessDiscardsTheEarlierArtifactAndRechecksPermission() {
    var fixture = new PreviewFixture(true, mock(BlockState.class));
    var requestId = UUID.randomUUID();
    var call = fixture.call(requestId);

    assertEquals(ReadToolResult.Status.SUCCEEDED, fixture.execute(call).status());
    assertEquals(1, fixture.artifacts.size());

    fixture.permitted.set(false);
    var retry = fixture.execute(call);

    assertEquals(ReadToolResult.Status.REJECTED, retry.status());
    assertEquals("PERMISSION_DENIED", retry.error().code());
    assertEquals(0, fixture.artifacts.size());
  }

  @Test
  void rejectsReplacingAnExistingBlockEntity() {
    var fixture = new PreviewFixture(true, mock(TileState.class));

    var result = fixture.execute(fixture.call(UUID.randomUUID()));

    assertEquals(ReadToolResult.Status.REJECTED, result.status());
    assertEquals("BUILD_BLOCK_ENTITY_UNSUPPORTED", result.error().code());
  }

  @Test
  void rejectsAnUnloadedTargetChunkBeforeReadingBlocks() {
    var fixture = new PreviewFixture(false, mock(BlockState.class));

    var result = fixture.execute(fixture.call(UUID.randomUUID()));

    assertEquals(ReadToolResult.Status.REJECTED, result.status());
    assertEquals("BUILD_CHUNK_UNLOADED", result.error().code());
  }

  @Test
  void rejectsTargetStatesThatCreateBlockEntitiesBeforeReadingTheWorld() {
    var playerId = UUID.randomUUID();
    var world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.isOnline()).thenReturn(true);
    when(player.hasPermission("minecraftagent.use")).thenReturn(true);
    when(player.getWorld()).thenReturn(world);
    var server = mock(Server.class);
    when(server.getPlayer(playerId)).thenReturn(player);

    var target = mock(BlockData.class);
    when(target.getAsString(false))
        .thenReturn("minecraft:chest[facing=north,type=single,waterlogged=false]");
    when(target.getMaterial()).thenReturn(Material.CHEST);
    when(target.createBlockState()).thenReturn(mock(TileState.class));
    var service =
        new PaperBuildPreviewService(
            server,
            Runnable::run,
            Runnable::run,
            () -> "test-server",
            new BuildPreviewArtifactRepository(),
            new BuildPreviewArtifactFactory(),
            ignored -> target);
    var call =
        new ReadToolCall(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            playerId,
            AgentModule.BUILD,
            "build.preview.create",
            solidPlan("minecraft:chest[facing=north,type=single,waterlogged=false]"),
            0);

    var result = service.execute(call).toCompletableFuture().join();

    assertEquals(ReadToolResult.Status.REJECTED, result.status());
    assertEquals("BUILD_BLOCK_ENTITY_UNSUPPORTED", result.error().code());
  }

  private static JsonObject clearPlan() {
    return plan("clear", JsonNull.INSTANCE);
  }

  private static JsonObject solidPlan(String blockState) {
    return plan("solid", new JsonPrimitive(blockState));
  }

  private static JsonObject plan(String pattern, JsonElement blockState) {
    var position = new JsonObject();
    position.addProperty("x", 0);
    position.addProperty("y", 64);
    position.addProperty("z", 0);
    var bounds = new JsonObject();
    bounds.add("min", position.deepCopy());
    bounds.add("max", position.deepCopy());
    var result = new JsonObject();
    result.addProperty("projectId", "99999999-9999-4999-8999-999999999999");
    result.addProperty("revision", 1);
    result.addProperty("operation", "modify");
    result.addProperty("dimension", "minecraft:overworld");
    result.add("bounds", bounds);
    result.add("origin", position);
    result.addProperty("pattern", pattern);
    result.add("blockState", blockState);
    result.addProperty("rotation", 0);
    result.addProperty("mirror", "NONE");
    return result;
  }

  private static final class PreviewFixture {
    private final UUID playerId = UUID.randomUUID();
    private final AtomicBoolean permitted = new AtomicBoolean(true);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final BuildPreviewArtifactRepository artifacts = new BuildPreviewArtifactRepository();
    private final PaperBuildPreviewService service;

    private PreviewFixture(boolean chunkLoaded, BlockState existingState) {
      var world = mock(World.class);
      when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
      when(world.getUID()).thenReturn(UUID.randomUUID());
      when(world.getMinHeight()).thenReturn(-64);
      when(world.getMaxHeight()).thenReturn(320);
      when(world.isChunkLoaded(0, 0)).thenReturn(chunkLoaded);
      var border = mock(WorldBorder.class);
      when(border.isInside(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(true);
      when(world.getWorldBorder()).thenReturn(border);
      var block = mock(Block.class);
      var blockData = mock(BlockData.class);
      when(blockData.getAsString(false)).thenReturn("minecraft:stone");
      when(block.getBlockData()).thenReturn(blockData);
      when(block.getState(false)).thenReturn(existingState);
      when(world.getBlockAt(0, 64, 0)).thenReturn(block);

      var player = mock(Player.class);
      when(player.getUniqueId()).thenReturn(playerId);
      when(player.isOnline()).thenReturn(true);
      when(player.hasPermission("minecraftagent.use")).thenAnswer(ignored -> permitted.get());
      when(player.getWorld()).thenReturn(world);
      when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
      var server = mock(Server.class);
      when(server.getPlayer(playerId)).thenReturn(player);
      service =
          new PaperBuildPreviewService(
              server, tasks::add, Runnable::run, () -> "test-server", artifacts);
    }

    private ReadToolCall call(UUID requestId) {
      return new ReadToolCall(
          UUID.randomUUID(),
          requestId,
          UUID.randomUUID(),
          playerId,
          AgentModule.BUILD,
          "build.preview.create",
          clearPlan(),
          0);
    }

    private ReadToolResult execute(ReadToolCall call) {
      var future = service.execute(call).toCompletableFuture();
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
      return future.join();
    }
  }
}
