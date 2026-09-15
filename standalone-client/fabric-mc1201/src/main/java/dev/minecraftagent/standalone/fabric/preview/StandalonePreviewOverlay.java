package dev.minecraftagent.standalone.fabric.preview;

import dev.minecraftagent.standalone.common.preview.PreviewMirror;
import dev.minecraftagent.standalone.common.preview.PreviewProjection;
import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;

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
  private volatile KeyMapping hologramKey;
  private final Set<UUID> hologramHintedPreviews = ConcurrentHashMap.newKeySet();
  private String cachedPreviewId;
  private PreviewProjection.Projection cachedProjection;

  public StandalonePreviewOverlay(StandalonePreviewStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** The key shown in the hint line; set after the keybinding is registered. */
  public void toggleKey(KeyMapping key) {
    toggleKey = key;
  }

  /** The hologram key shown in the hint line and the one-time chat hint. */
  public void hologramKey(KeyMapping key) {
    hologramKey = key;
  }

  /** Auto-shows the panel when a new preview is created; called from tool worker threads. */
  public void onPreviewCreated(StandalonePreview preview) {
    visible.set(true);
    hintHologramOnce(preview);
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
    hologramHintedPreviews.clear();
    cachedPreviewId = null;
    cachedProjection = null;
  }

  /** Tells the player once per preview how to show the hologram; safe from tool worker threads. */
  private void hintHologramOnce(StandalonePreview preview) {
    var key = hologramKey;
    if (key == null || !hologramHintedPreviews.add(preview.previewId())) {
      return;
    }
    var minecraft = Minecraft.getInstance();
    minecraft.execute(
        () -> {
          if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                Component.translatable(
                    "chat.agma_standalone.hologram_hint", key.getTranslatedKeyMessage()),
                false);
          }
        });
  }

  public void render(GuiGraphics graphics, float ignoredTickDelta) {
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

    graphics.fill(PANEL_X, PANEL_Y, PANEL_X + panelWidth, PANEL_Y + height, BACKGROUND);
    graphics.renderOutline(PANEL_X, PANEL_Y, panelWidth, height, BORDER);
    graphics.fill(PANEL_X, PANEL_Y, PANEL_X + 3, PANEL_Y + height, ACCENT);

    var x = PANEL_X + PADDING;
    var cursorY = PANEL_Y + PADDING;
    var textWidth = panelWidth - PADDING * 2;
    var bounds = preview.bounds();
    var operation =
        Component.translatable(
                "preview.agma_standalone.operation." + preview.operation().wireName())
            .getString();
    var dimension = preview.dimension();
    var displayDimension =
        dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    var summary =
        Component.translatable(
                "preview.agma_standalone.summary",
                operation,
                bounds.sizeX(),
                bounds.sizeY(),
                bounds.sizeZ(),
                displayDimension,
                preview.revision())
            .getString();
    graphics.drawString(
        font, font.plainSubstrByWidth(summary, textWidth), x, cursorY, PRIMARY_TEXT, false);
    cursorY += lineHeight;

    var origin = preview.origin();
    var originLine =
        Component.translatable(
                    "preview.agma_standalone.origin",
                    origin.x(),
                    origin.y(),
                    origin.z(),
                    preview.rotation())
                .getString()
            + (preview.mirror() == PreviewMirror.NONE
                ? ""
                : Component.translatable("preview.agma_standalone.mirrored").getString());
    graphics.drawString(
        font, font.plainSubstrByWidth(originLine, textWidth), x, cursorY, SECONDARY_TEXT, false);
    cursorY += lineHeight;

    var difference = preview.difference();
    var textX = x;
    var added = "+" + difference.added();
    graphics.drawString(font, added, textX, cursorY, ACCENT, false);
    textX +=
        font.width(
            added
                + "  "
                + Component.translatable("preview.agma_standalone.diff.added").getString()
                + "   ");
    var replaced = "~" + difference.replaced();
    graphics.drawString(font, replaced, textX, cursorY, PINNED, false);
    textX +=
        font.width(
            replaced
                + "  "
                + Component.translatable("preview.agma_standalone.diff.replaced").getString()
                + "   ");
    graphics.drawString(font, "-" + difference.removed(), textX, cursorY, MISSING, false);
    cursorY += lineHeight;

    renderPreviewMap(graphics, projection, x, cursorY + 1);
    cursorY += projection.mapHeight() + 8;

    if (legendRows > 0) {
      graphics.drawString(
          font,
          Component.translatable("preview.agma_standalone.top_blocks"),
          x,
          cursorY,
          SECONDARY_TEXT,
          false);
      cursorY += lineHeight;
      for (var row : projection.legend()) {
        var stack = icon(row.blockId());
        if (stack.isEmpty()) {
          graphics.fill(x + 2, cursorY + 2, x + 10, cursorY + 10, MAP_UNKNOWN_BLOCK);
        } else {
          graphics.pose().pushPose();
          graphics.pose().translate(x, cursorY, 0);
          graphics.pose().scale(0.75f, 0.75f, 1.0f);
          graphics.renderItem(stack, 0, 0);
          graphics.pose().popPose();
        }
        var name = stack.isEmpty() ? row.blockId() : stack.getHoverName().getString();
        graphics.drawString(
            font,
            font.plainSubstrByWidth("x" + row.count() + "  " + name, textWidth - 14),
            x + 14,
            cursorY + 2,
            stack.isEmpty() ? MISSING : PRIMARY_TEXT,
            false);
        cursorY += 16;
      }
    }

    var key = toggleKey;
    if (key != null) {
      var hologram = hologramKey;
      var hint =
          (hologram == null
                  ? Component.translatable(
                      "screen.agma_standalone.preview_hint", key.getTranslatedKeyMessage())
                  : Component.translatable(
                      "screen.agma_standalone.preview_hint_hologram",
                      key.getTranslatedKeyMessage(),
                      hologram.getTranslatedKeyMessage()))
              .getString();
      graphics.drawString(
          font, font.plainSubstrByWidth(hint, textWidth), x, cursorY, SECONDARY_TEXT, false);
    }
  }

  private void renderPreviewMap(
      GuiGraphics graphics, PreviewProjection.Projection projection, int x, int y) {
    graphics.fill(
        x - 1, y - 1, x + projection.mapWidth() + 1, y + projection.mapHeight() + 1, BORDER);
    graphics.fill(x, y, x + projection.mapWidth(), y + projection.mapHeight(), MAP_BACKGROUND);
    var cell = projection.cell();
    for (var index = 0; index < projection.topColors().length; index++) {
      var color = projection.topColors()[index];
      if (color == 0) {
        continue;
      }
      var cellX = (index % projection.sizeX()) * cell;
      var cellZ = (index / projection.sizeX()) * cell;
      graphics.fill(x + cellX, y + cellZ, x + cellX + cell, y + cellZ + cell, color);
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

  /** Mirrors the client-mod MapColor lookup: registry block to its map color, with fallbacks. */
  private int blockColor(String blockId) {
    return blockColorCache.computeIfAbsent(
        blockId,
        id -> {
          var identifier = ResourceLocation.tryParse(id);
          var block =
              identifier == null
                  ? null
                  : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
          if (block == null) {
            return MAP_UNKNOWN_BLOCK;
          }
          MapColor mapColor = block.defaultMapColor();
          if (mapColor == null || mapColor == MapColor.NONE) {
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
          var block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
          if (block == null) {
            return ItemStack.EMPTY;
          }
          var stack = new ItemStack(block.asItem());
          return stack.isEmpty() ? ItemStack.EMPTY : stack;
        });
  }
}
