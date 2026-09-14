package dev.minecraftagent.standalone.ui.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.minecraftagent.standalone.common.preview.PreviewMirror;
import dev.minecraftagent.standalone.common.preview.PreviewProjection;
import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MaterialColor;

/**
 * Fixed-position HUD panel for the latest build preview: summary, origin/transform, the
 * added/replaced/removed diff, the top-view color map, and a legend with item icons. Rendering is a
 * simplified port of the server line's client-mod build-preview overlay (no drag, pin, or resize
 * machinery). Visible state is thread-safe because tool workers auto-show the panel.
 */
public final class StandalonePreviewOverlay {
  private static final int PANEL_X = 8;
  private static final int PANEL_Y = 8;
  private static final int PADDING = 8;
  private static final int BACKGROUND = 0xe6191b1f;
  private static final int BORDER = 0xff737a83;
  private static final int ACCENT = 0xff43b5a0;
  private static final int PINNED = 0xffffc857;
  private static final int PRIMARY_TEXT = 0xfff3f5f7;
  private static final int SECONDARY_TEXT = 0xffb9c0c7;
  private static final int MISSING = 0xffff5c6c;
  private static final int MAP_UNKNOWN_BLOCK = 0xff6b6f75;
  private static final int MAP_NO_MAPCOLOR = 0xff3a3f45;
  private static final int MAP_BACKGROUND = 0xff101315;

  private final StandalonePreviewStore store;
  private final AtomicBoolean visible = new AtomicBoolean();
  private final Map<String, Integer> blockColorCache = new HashMap<>();
  private final Map<String, ItemStack> iconCache = new HashMap<>();

  private volatile KeyMapping toggleKey;
  private String cachedPreviewId;
  private PreviewProjection.Projection cachedProjection;

  public StandalonePreviewOverlay(StandalonePreviewStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** The key shown in the hint line; set after the keybinding is registered. */
  public void toggleKey(KeyMapping key) {
    toggleKey = key;
  }

  /** Auto-shows the panel when a new preview is created; called from tool worker threads. */
  public void onPreviewCreated(StandalonePreview ignored) {
    visible.set(true);
  }

  public void toggle() {
    visible.set(!visible.get());
  }

  public void hide() {
    visible.set(false);
  }

  public boolean visible() {
    return visible.get();
  }

  /** Hides the panel and drops cached projections, for example on disconnect. */
  public void reset() {
    hide();
    cachedPreviewId = null;
    cachedProjection = null;
  }

  public void render(PoseStack poseStack, float partialTick) {
    if (!visible.get()) {
      return;
    }
    var preview = store.latest().orElse(null);
    if (preview == null) {
      return;
    }
    var minecraft = Minecraft.getInstance();
    var projection = projection(preview);
    var font = minecraft.font;
    var lineHeight = font.lineHeight + 2;
    var mapWidth = projection.mapWidth();
    var legendRows = projection.legend().size();
    var contentWidth = Math.max(mapWidth, 200);
    var panelWidth = contentWidth + PADDING * 2;
    var height = PADDING + lineHeight * 3 + projection.mapHeight() + 8;
    if (legendRows > 0) {
      height += lineHeight + legendRows * 16;
    }
    height += lineHeight + PADDING;

    GuiComponent.fill(
        poseStack, PANEL_X, PANEL_Y, PANEL_X + panelWidth, PANEL_Y + height, BACKGROUND);
    GuiComponent.fill(poseStack, PANEL_X, PANEL_Y, PANEL_X + panelWidth, PANEL_Y + 1, BORDER);
    GuiComponent.fill(
        poseStack, PANEL_X, PANEL_Y + height - 1, PANEL_X + panelWidth, PANEL_Y + height, BORDER);
    GuiComponent.fill(poseStack, PANEL_X, PANEL_Y, PANEL_X + 1, PANEL_Y + height, BORDER);
    GuiComponent.fill(
        poseStack,
        PANEL_X + panelWidth - 1,
        PANEL_Y,
        PANEL_X + panelWidth,
        PANEL_Y + height,
        BORDER);
    GuiComponent.fill(poseStack, PANEL_X, PANEL_Y, PANEL_X + 3, PANEL_Y + height, ACCENT);

    var x = PANEL_X + PADDING;
    var cursorY = PANEL_Y + PADDING;
    var textWidth = panelWidth - PADDING * 2;
    var bounds = preview.bounds();
    var operation =
        new TranslatableComponent(
                "preview.agma_standalone.operation." + preview.operation().wireName())
            .getString();
    var dimension = preview.dimension();
    var displayDimension =
        dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    var summary =
        new TranslatableComponent(
                "preview.agma_standalone.summary",
                operation,
                bounds.sizeX(),
                bounds.sizeY(),
                bounds.sizeZ(),
                displayDimension,
                preview.revision())
            .getString();
    font.draw(poseStack, font.plainSubstrByWidth(summary, textWidth), x, cursorY, PRIMARY_TEXT);
    cursorY += lineHeight;

    var origin = preview.origin();
    var originLine =
        new TranslatableComponent(
                    "preview.agma_standalone.origin",
                    origin.x(),
                    origin.y(),
                    origin.z(),
                    preview.rotation())
                .getString()
            + (preview.mirror() == PreviewMirror.NONE
                ? ""
                : new TranslatableComponent("preview.agma_standalone.mirrored").getString());
    font.draw(
        poseStack, font.plainSubstrByWidth(originLine, textWidth), x, cursorY, SECONDARY_TEXT);
    cursorY += lineHeight;

    var difference = preview.difference();
    var textX = x;
    var added = "+" + difference.added();
    font.draw(poseStack, added, textX, cursorY, ACCENT);
    textX +=
        font.width(
            added
                + "  "
                + new TranslatableComponent("preview.agma_standalone.diff.added").getString()
                + "   ");
    var replaced = "~" + difference.replaced();
    font.draw(poseStack, replaced, textX, cursorY, PINNED);
    textX +=
        font.width(
            replaced
                + "  "
                + new TranslatableComponent("preview.agma_standalone.diff.replaced").getString()
                + "   ");
    font.draw(poseStack, "-" + difference.removed(), textX, cursorY, MISSING);
    cursorY += lineHeight;

    renderPreviewMap(poseStack, projection, x, cursorY + 1);
    cursorY += projection.mapHeight() + 8;

    if (legendRows > 0) {
      font.draw(
          poseStack,
          new TranslatableComponent("preview.agma_standalone.top_blocks"),
          x,
          cursorY,
          SECONDARY_TEXT);
      cursorY += lineHeight;
      for (var row : projection.legend()) {
        var stack = icon(row.blockId());
        if (stack.isEmpty()) {
          GuiComponent.fill(poseStack, x + 2, cursorY + 2, x + 10, cursorY + 10, MAP_UNKNOWN_BLOCK);
        } else {
          minecraft.getItemRenderer().renderAndDecorateItem(stack, x, cursorY);
        }
        var name = stack.isEmpty() ? row.blockId() : stack.getHoverName().getString();
        font.draw(
            poseStack,
            font.plainSubstrByWidth("x" + row.count() + "  " + name, textWidth - 18),
            x + 18,
            cursorY + 4,
            stack.isEmpty() ? MISSING : PRIMARY_TEXT);
        cursorY += 16;
      }
    }

    var key = toggleKey;
    if (key != null) {
      var hint =
          new TranslatableComponent(
                  "screen.agma_standalone.preview_hint", key.getTranslatedKeyMessage())
              .getString();
      font.draw(poseStack, font.plainSubstrByWidth(hint, textWidth), x, cursorY, SECONDARY_TEXT);
    }
  }

  private void renderPreviewMap(
      PoseStack poseStack, PreviewProjection.Projection projection, int x, int y) {
    GuiComponent.fill(
        poseStack,
        x - 1,
        y - 1,
        x + projection.mapWidth() + 1,
        y + projection.mapHeight() + 1,
        BORDER);
    GuiComponent.fill(
        poseStack, x, y, x + projection.mapWidth(), y + projection.mapHeight(), MAP_BACKGROUND);
    var cell = projection.cell();
    for (var index = 0; index < projection.topColors().length; index++) {
      var color = projection.topColors()[index];
      if (color == 0) {
        continue;
      }
      var cellX = (index % projection.sizeX()) * cell;
      var cellZ = (index / projection.sizeX()) * cell;
      GuiComponent.fill(poseStack, x + cellX, y + cellZ, x + cellX + cell, y + cellZ + cell, color);
    }
  }

  private PreviewProjection.Projection projection(StandalonePreview preview) {
    var id = preview.previewId().toString();
    if (!id.equals(cachedPreviewId)) {
      cachedPreviewId = id;
      cachedProjection = PreviewProjection.compute(preview, this::blockColor);
    }
    return cachedProjection;
  }

  /**
   * Mirrors the client-mod MaterialColor lookup: registry block to its map color, with fallbacks.
   */
  private int blockColor(String blockId) {
    return blockColorCache.computeIfAbsent(
        blockId,
        id -> {
          var identifier = ResourceLocation.tryParse(id);
          var block =
              identifier == null ? null : Registry.BLOCK.getOptional(identifier).orElse(null);
          if (block == null) {
            return MAP_UNKNOWN_BLOCK;
          }
          MaterialColor mapColor = block.defaultBlockState().getMapColor(null, BlockPos.ZERO);
          if (mapColor == null || mapColor == MaterialColor.NONE) {
            return MAP_NO_MAPCOLOR;
          }
          return 0xff000000 | mapColor.col;
        });
  }

  private ItemStack icon(String blockId) {
    return iconCache.computeIfAbsent(
        blockId,
        id -> {
          var identifier = ResourceLocation.tryParse(id);
          if (identifier == null) {
            return ItemStack.EMPTY;
          }
          var block = Registry.BLOCK.getOptional(identifier).orElse(null);
          if (block == null) {
            return ItemStack.EMPTY;
          }
          var stack = new ItemStack(block.asItem());
          return stack.isEmpty() ? ItemStack.EMPTY : stack;
        });
  }
}
