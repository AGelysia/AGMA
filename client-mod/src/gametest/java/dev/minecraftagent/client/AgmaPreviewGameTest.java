package dev.minecraftagent.client;

import dev.minecraftagent.client.litematica.LitematicaDisplayReport;
import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.ViewType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the build-preview presentation path end to end inside a real singleplayer world: a
 * region is filled with real blocks, captured into a Palette-v1 preview, shown in the overlay
 * panel, and loaded through the real Litematica adapter so a hologram placement appears.
 */
public final class AgmaPreviewGameTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger("agma-preview-gametest");
  private static final BlockPos MIN = new BlockPos(4, 80, 4);
  private static final BlockPos MAX = new BlockPos(10, 83, 10);

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
      world
          .getServer()
          .runCommand(
              "fill "
                  + MIN.getX()
                  + " "
                  + MIN.getY()
                  + " "
                  + MIN.getZ()
                  + " "
                  + MAX.getX()
                  + " "
                  + MAX.getY()
                  + " "
                  + MAX.getZ()
                  + " minecraft:stone hollow");
      context.waitTicks(5);

      var preview = world.getServer().computeOnServer(server -> capturePreview(server.overworld()));
      var view = structuredView(preview);
      if (preview.palette().stream().noneMatch(entry -> entry.blockId().equals("minecraft:stone"))
          || preview.difference().added() == 0) {
        throw new AssertionError("captured preview is missing the filled stone region");
      }
      LOGGER.info(
          "preview captured: palette={}, blocks={}, added={}",
          preview.palette().size(),
          preview.blocks().size(),
          preview.difference().added());

      var overlay = MinecraftAgentClient.overlayController();
      context.computeOnClient(
          minecraft -> {
            overlay.show(view);
            return null;
          });
      context.waitTicks(15);
      context.takeScreenshot("01-preview-panel");

      var controller = MinecraftAgentClient.litematicaController();
      if (!controller.available()) {
        LOGGER.info("Litematica not installed; skipping the hologram load stage");
      } else {
        var viewId = view.viewId();
        var previewId = preview.previewId();
        var staged = context.computeOnClient(minecraft -> controller.stagePreview(preview));
        if (!staged) {
          throw new AssertionError("stagePreview rejected the captured preview");
        }
        var committed =
            context.computeOnClient(minecraft -> controller.commitPreview(preview, Set.of(viewId)));
        if (!committed) {
          throw new AssertionError("commitPreview rejected the captured preview");
        }
        var prepared = controller.prepareLoad(viewId, "Gametest preview");
        var report = context.computeOnClient(minecraft -> controller.load(prepared));
        if (report.state() != LitematicaDisplayReport.State.LOADED) {
          throw new AssertionError("Litematica load failed: " + report.failure());
        }
        LOGGER.info("litematica hologram loaded for preview {}", previewId);

        context.computeOnClient(
            minecraft -> {
              overlay.close();
              return null;
            });
        world
            .getServer()
            .runCommand(
                "tp @p "
                    + ((MIN.getX() + MAX.getX()) / 2)
                    + " "
                    + (MAX.getY() + 20)
                    + " "
                    + (MAX.getZ() + 16)
                    + " 180 55");
        context.waitTicks(15);
        context.takeScreenshot("02-hologram");
        var removed = context.computeOnClient(minecraft -> controller.remove(viewId));
        if (removed.state() != LitematicaDisplayReport.State.REMOVED) {
          throw new AssertionError("Litematica remove failed: " + removed.failure());
        }
      }
      LOGGER.info("AgmaPreviewGameTest PASSED");
    } finally {
      world.close();
    }
  }

  private static BuildPreviewView capturePreview(ServerLevel level) {
    var palette = new ArrayList<BuildPreviewView.PaletteEntry>();
    var paletteIds = new LinkedHashMap<String, Integer>();
    var blocks = new ArrayList<BuildPreviewView.PlacedBlock>();
    var added = 0;
    for (var pos : BlockPos.betweenClosed(MIN, MAX)) {
      var state = level.getBlockState(pos);
      var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      var properties = new LinkedHashMap<String, String>();
      for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
        properties.put(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
      }
      var canonical = canonicalState(blockId, properties);
      var paletteId = paletteIds.get(canonical);
      if (paletteId == null) {
        paletteId = palette.size();
        paletteIds.put(canonical, paletteId);
        palette.add(new BuildPreviewView.PaletteEntry(paletteId, blockId, properties));
      }
      blocks.add(new BuildPreviewView.PlacedBlock(paletteId, position(pos)));
      if (!state.isAir()) {
        added++;
      }
    }
    return new BuildPreviewView(
        "1.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        BuildPreviewView.Operation.CREATE,
        "minecraft:overworld",
        new BuildPreviewView.Bounds(position(MIN), position(MAX)),
        position(MIN),
        new BuildPreviewView.Transform(0, BuildPreviewView.Mirror.NONE),
        "0".repeat(64),
        "0".repeat(64),
        "0".repeat(64),
        "0".repeat(64),
        new BuildPreviewView.Difference(added, 0, 0),
        palette,
        blocks);
  }

  private static StructuredView structuredView(BuildPreviewView preview) {
    return new StructuredView(
        "1.0",
        preview.previewId(),
        UUID.randomUUID(),
        ViewType.BUILD_PREVIEW,
        1,
        "Gametest preview",
        "Build preview gametest: " + preview.difference().added() + " added",
        false,
        preview);
  }

  private static BuildPreviewView.Position position(BlockPos pos) {
    return new BuildPreviewView.Position(pos.getX(), pos.getY(), pos.getZ());
  }

  private static String canonicalState(String blockId, Map<String, String> properties) {
    if (properties.isEmpty()) {
      return blockId;
    }
    var canonical = new StringBuilder(blockId).append('[');
    properties.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              if (canonical.charAt(canonical.length() - 1) != '[') {
                canonical.append(',');
              }
              canonical.append(entry.getKey()).append('=').append(entry.getValue());
            });
    return canonical.append(']').toString();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static String valueName(Property property, Comparable<?> value) {
    return property.getName(value);
  }
}
