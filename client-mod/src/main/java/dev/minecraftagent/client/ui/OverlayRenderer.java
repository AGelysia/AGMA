package dev.minecraftagent.client.ui;

import dev.minecraftagent.client.ui.OverlayController.OverlaySnapshot;
import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.ItemListView;
import dev.minecraftagent.client.view.ItemStackView;
import dev.minecraftagent.client.view.MinecraftItemStackResolver;
import dev.minecraftagent.client.view.MinecraftItemStackResolver.ResolvedItemStack;
import dev.minecraftagent.client.view.RecipeView;
import dev.minecraftagent.client.view.RecipeView.GridLayout;
import dev.minecraftagent.client.view.RecipeView.IngredientChoice;
import dev.minecraftagent.client.view.RecipeView.IngredientSlot;
import dev.minecraftagent.client.view.RecipeView.Recipe;
import dev.minecraftagent.client.view.RecipeView.SingleInputLayout;
import dev.minecraftagent.client.view.RecipeView.SmithingLayout;
import dev.minecraftagent.client.view.RecipeView.TransmuteLayout;
import dev.minecraftagent.client.view.RecipeView.UnsupportedLayout;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Stable-size HUD renderer with explicit mouse entry points for the client initializer. */
public final class OverlayRenderer {
  public static final int HEADER_HEIGHT = 22;
  public static final int CONTENT_PADDING = 8;

  private static final int BUTTON_SIZE = 16;
  private static final int RESIZE_HANDLE = 10;
  private static final int BACKGROUND = 0xe6191b1f;
  private static final int HEADER_BACKGROUND = 0xf02a2d32;
  private static final int BORDER = 0xff737a83;
  private static final int ACCENT = 0xff43b5a0;
  private static final int PINNED = 0xffffc857;
  private static final int PRIMARY_TEXT = 0xfff3f5f7;
  private static final int SECONDARY_TEXT = 0xffb9c0c7;
  private static final int SLOT_BACKGROUND = 0xff30343a;
  private static final int MISSING = 0xffff5c6c;

  private final Minecraft minecraft;
  private final OverlayController controller;
  private final MinecraftItemStackResolver itemResolver;
  private final LongSupplier clock;
  private final Map<ItemStackView, ResolvedItemStack> itemCache = new HashMap<>();
  private final RecipePresentationState recipeState = new RecipePresentationState();

  private volatile OverlayBounds lastBounds;
  private UUID cachedViewId;
  private int cachedRevision;
  private int cachedTextWidth = -1;
  private List<FormattedCharSequence> cachedTextLines = List.of();
  private OverlayBounds previousRecipeButton;
  private OverlayBounds nextRecipeButton;
  private Interaction interaction = Interaction.NONE;
  private int dragOffsetX;
  private int dragOffsetY;

  public OverlayRenderer(Minecraft minecraft, OverlayController controller) {
    this(minecraft, controller, new MinecraftItemStackResolver());
  }

  public OverlayRenderer(
      Minecraft minecraft, OverlayController controller, MinecraftItemStackResolver itemResolver) {
    this(minecraft, controller, itemResolver, System::currentTimeMillis);
  }

  OverlayRenderer(
      Minecraft minecraft,
      OverlayController controller,
      MinecraftItemStackResolver itemResolver,
      LongSupplier clock) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.controller = Objects.requireNonNull(controller, "controller");
    this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public OverlayController controller() {
    return controller;
  }

  public void render(GuiGraphics graphics, DeltaTracker ignoredTickCounter) {
    Optional<OverlaySnapshot> optionalSnapshot = controller.snapshot();
    if (optionalSnapshot.isEmpty()) {
      lastBounds = null;
      itemCache.clear();
      cachedViewId = null;
      cachedTextLines = List.of();
      cachedTextWidth = -1;
      previousRecipeButton = null;
      nextRecipeButton = null;
      return;
    }

    OverlaySnapshot snapshot = optionalSnapshot.get();
    StructuredView view = snapshot.view();
    refreshItemCache(view);
    OverlayBounds bounds = controller.bounds(graphics.guiWidth(), graphics.guiHeight());
    lastBounds = bounds;

    graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), BACKGROUND);
    graphics.fill(
        bounds.x(), bounds.y(), bounds.right(), bounds.y() + HEADER_HEIGHT, HEADER_BACKGROUND);
    graphics.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), BORDER);
    graphics.fill(bounds.x(), bounds.y(), bounds.x() + 3, bounds.bottom(), ACCENT);

    int controlsWidth =
        view.pinnable() || snapshot.pinned() ? BUTTON_SIZE * 2 + 6 : BUTTON_SIZE + 4;
    int titleWidth = Math.max(8, bounds.width() - CONTENT_PADDING * 2 - controlsWidth);
    String title = minecraft.font.plainSubstrByWidth(view.title(), titleWidth);
    graphics.drawString(
        minecraft.font, title, bounds.x() + CONTENT_PADDING, bounds.y() + 7, PRIMARY_TEXT, false);
    renderHeaderControls(graphics, bounds, snapshot);
    renderResizeHandle(graphics, bounds);

    int contentX = bounds.x() + CONTENT_PADDING;
    int contentY = bounds.y() + HEADER_HEIGHT + 5;
    int contentWidth = Math.max(1, bounds.width() - CONTENT_PADDING * 2);
    int viewportHeight = Math.max(0, bounds.height() - HEADER_HEIGHT - CONTENT_PADDING - 3);
    int contentHeight = measureContent(view, contentWidth);
    controller.setViewportMetrics(contentHeight, viewportHeight);
    snapshot = controller.snapshot().orElse(snapshot);
    previousRecipeButton = null;
    nextRecipeButton = null;

    if (viewportHeight > 0) {
      graphics.enableScissor(
          bounds.x() + 3, contentY, bounds.right() - 3, contentY + viewportHeight);
      renderContent(
          graphics,
          view,
          contentX,
          contentY - snapshot.scroll(),
          contentWidth,
          contentY,
          contentY + viewportHeight);
      graphics.disableScissor();
    }
  }

  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    OverlayBounds bounds = lastBounds;
    if (button != 0 || bounds == null || !bounds.contains(mouseX, mouseY)) {
      return false;
    }
    Optional<OverlaySnapshot> snapshot = controller.snapshot();
    if (snapshot.isEmpty()) {
      return false;
    }
    if (closeButton(bounds).contains(mouseX, mouseY)) {
      controller.close();
      interaction = Interaction.NONE;
      return true;
    }
    if ((snapshot.get().view().pinnable() || snapshot.get().pinned())
        && pinButton(bounds).contains(mouseX, mouseY)) {
      controller.togglePin();
      interaction = Interaction.NONE;
      return true;
    }
    if (snapshot.get().view().content() instanceof RecipeView recipe) {
      if (previousRecipeButton != null && previousRecipeButton.contains(mouseX, mouseY)) {
        recipeState.previous(recipe.recipes().size());
        interaction = Interaction.NONE;
        return true;
      }
      if (nextRecipeButton != null && nextRecipeButton.contains(mouseX, mouseY)) {
        recipeState.next(recipe.recipes().size());
        interaction = Interaction.NONE;
        return true;
      }
    }
    if (resizeButton(bounds).contains(mouseX, mouseY)) {
      interaction = Interaction.RESIZE;
      return true;
    }
    if (mouseY < bounds.y() + HEADER_HEIGHT) {
      interaction = Interaction.DRAG;
      dragOffsetX = (int) mouseX - bounds.x();
      dragOffsetY = (int) mouseY - bounds.y();
      return true;
    }
    return false;
  }

  public boolean mouseDragged(double mouseX, double mouseY, int button) {
    OverlayBounds bounds = lastBounds;
    if (button != 0 || bounds == null || interaction == Interaction.NONE) {
      return false;
    }
    if (interaction == Interaction.DRAG) {
      controller.moveTo(
          (int) mouseX - dragOffsetX,
          (int) mouseY - dragOffsetY,
          minecraft.getWindow().getGuiScaledWidth(),
          minecraft.getWindow().getGuiScaledHeight());
    } else {
      controller.resizeTo(
          (int) mouseX - bounds.x(),
          (int) mouseY - bounds.y(),
          minecraft.getWindow().getGuiScaledWidth(),
          minecraft.getWindow().getGuiScaledHeight());
    }
    return true;
  }

  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (button != 0 || interaction == Interaction.NONE) {
      return false;
    }
    interaction = Interaction.NONE;
    controller.flushPreferences();
    return true;
  }

  /** Ends an interrupted gesture, for example when the interaction screen closes. */
  public void cancelInteraction() {
    interaction = Interaction.NONE;
    controller.flushPreferences();
  }

  public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
    OverlayBounds bounds = lastBounds;
    if (bounds == null || !bounds.contains(mouseX, mouseY) || verticalAmount == 0) {
      return false;
    }
    controller.scrollBy(verticalAmount > 0 ? -18 : 18);
    return true;
  }

  private void refreshItemCache(StructuredView view) {
    if (!view.viewId().equals(cachedViewId) || view.revision() != cachedRevision) {
      itemCache.clear();
      cachedViewId = view.viewId();
      cachedRevision = view.revision();
      cachedTextLines = List.of();
      cachedTextWidth = -1;
    }
  }

  private void renderHeaderControls(
      GuiGraphics graphics, OverlayBounds bounds, OverlaySnapshot snapshot) {
    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    OverlayBounds close = closeButton(bounds);
    graphics.drawCenteredString(
        minecraft.font,
        Component.literal("\u00d7"),
        close.x() + close.width() / 2,
        close.y() + 4,
        PRIMARY_TEXT);
    if (close.contains(mouseX, mouseY)) {
      graphics.setTooltipForNextFrame(
          minecraft.font, Component.literal("Close view"), mouseX, mouseY);
    }

    if (snapshot.view().pinnable() || snapshot.pinned()) {
      OverlayBounds pin = pinButton(bounds);
      graphics.drawCenteredString(
          minecraft.font,
          Component.literal(snapshot.pinned() ? "\u25c6" : "\u25c7"),
          pin.x() + pin.width() / 2,
          pin.y() + 4,
          snapshot.pinned() ? PINNED : SECONDARY_TEXT);
      if (pin.contains(mouseX, mouseY)) {
        graphics.setTooltipForNextFrame(
            minecraft.font,
            Component.literal(snapshot.pinned() ? "Unpin view" : "Pin view"),
            mouseX,
            mouseY);
      }
    }
  }

  private static void renderResizeHandle(GuiGraphics graphics, OverlayBounds bounds) {
    int right = bounds.right() - 3;
    int bottom = bounds.bottom() - 3;
    graphics.fill(right - 2, bottom - 2, right, bottom, BORDER);
    graphics.fill(right - 6, bottom - 2, right - 4, bottom, BORDER);
    graphics.fill(right - 2, bottom - 6, right, bottom - 4, BORDER);
  }

  private int measureContent(StructuredView view, int width) {
    return switch (view.content()) {
      case TextView text ->
          Math.max(
              minecraft.font.lineHeight,
              textLines(text, width).size() * (minecraft.font.lineHeight + 2));
      case ItemStackView ignored -> 52;
      case ItemListView list -> list.items().size() * 22;
      case RecipeView ignored -> 132;
      case BuildPreviewView ignored ->
          Math.max(
              minecraft.font.lineHeight,
              textLines(new TextView(view.fallbackText()), width).size()
                  * (minecraft.font.lineHeight + 2));
    };
  }

  private void renderContent(
      GuiGraphics graphics,
      StructuredView view,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    switch (view.content()) {
      case TextView text -> renderText(graphics, text, x, y, width, clipTop, clipBottom);
      case ItemStackView item ->
          renderItemStackView(graphics, item, x, y, width, clipTop, clipBottom);
      case ItemListView list -> renderItemList(graphics, list, x, y, width, clipTop, clipBottom);
      case RecipeView recipe -> renderRecipe(graphics, recipe, x, y, width, clipTop, clipBottom);
      case BuildPreviewView ignored ->
          renderText(graphics, new TextView(view.fallbackText()), x, y, width, clipTop, clipBottom);
    }
  }

  private void renderText(
      GuiGraphics graphics,
      TextView content,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    List<FormattedCharSequence> lines = textLines(content, width);
    int lineHeight = minecraft.font.lineHeight + 2;
    for (int index = 0; index < lines.size(); index++) {
      int lineY = y + index * lineHeight;
      if (lineY + lineHeight >= clipTop && lineY < clipBottom) {
        graphics.drawString(minecraft.font, lines.get(index), x, lineY, PRIMARY_TEXT, false);
      }
    }
  }

  private void renderItemStackView(
      GuiGraphics graphics,
      ItemStackView item,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    ResolvedItemStack resolved = resolve(item);
    renderItem(graphics, resolved, x, y, clipTop, clipBottom);
    String name = displayName(resolved);
    int labelWidth = Math.max(1, width - 24);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(name, labelWidth),
        x + 24,
        y,
        resolved.available() ? PRIMARY_TEXT : MISSING,
        false);
    graphics.drawString(
        minecraft.font,
        "x" + item.count(),
        x + 24,
        y + minecraft.font.lineHeight + 3,
        SECONDARY_TEXT,
        false);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(item.itemId(), labelWidth),
        x + 24,
        y + (minecraft.font.lineHeight + 3) * 2,
        SECONDARY_TEXT,
        false);
  }

  private void renderItemList(
      GuiGraphics graphics,
      ItemListView list,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    for (int index = 0; index < list.items().size(); index++) {
      int rowY = y + index * 22;
      if (rowY + 20 < clipTop || rowY >= clipBottom) {
        continue;
      }
      ItemStackView item = list.items().get(index);
      ResolvedItemStack resolved = resolve(item);
      renderItem(graphics, resolved, x, rowY, clipTop, clipBottom);
      int labelWidth = Math.max(1, width - 26 - minecraft.font.width("x" + item.count()));
      graphics.drawString(
          minecraft.font,
          minecraft.font.plainSubstrByWidth(displayName(resolved), labelWidth),
          x + 22,
          rowY + 1,
          resolved.available() ? PRIMARY_TEXT : MISSING,
          false);
      String count = "x" + item.count();
      graphics.drawString(
          minecraft.font,
          count,
          x + width - minecraft.font.width(count),
          rowY + 1,
          SECONDARY_TEXT,
          false);
      graphics.drawString(
          minecraft.font,
          minecraft.font.plainSubstrByWidth(item.itemId(), Math.max(1, width - 22)),
          x + 22,
          rowY + 11,
          SECONDARY_TEXT,
          false);
    }
  }

  private void renderRecipe(
      GuiGraphics graphics,
      RecipeView recipeView,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    long nowMillis = clock.getAsLong();
    recipeState.synchronize(
        Objects.requireNonNull(cachedViewId),
        cachedRevision,
        recipeView.selectedRecipe(),
        recipeView.recipes().size(),
        nowMillis);
    int selectedRecipe = recipeState.selectedRecipe();
    Recipe recipe = recipeView.recipes().get(selectedRecipe);
    String index =
        (selectedRecipe + 1)
            + "/"
            + recipeView.recipes().size()
            + " of "
            + recipeView.totalMatches()
            + (recipeView.truncated() ? "+" : "");
    int navigationWidth = recipeView.recipes().size() > 1 ? 38 : 0;
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(
            recipe.recipeId(),
            Math.max(1, width - minecraft.font.width(index) - navigationWidth - 8)),
        x,
        y,
        PRIMARY_TEXT,
        false);
    graphics.drawString(
        minecraft.font,
        index,
        x + width - navigationWidth - minecraft.font.width(index),
        y,
        SECONDARY_TEXT,
        false);
    renderRecipeNavigation(graphics, recipeView.recipes().size(), x, y, width, clipTop, clipBottom);
    String metadata =
        recipe.recipeType().name().toLowerCase(Locale.ROOT)
            + "  "
            + recipe.source().kind().name().toLowerCase(Locale.ROOT);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(metadata, width),
        x,
        y + 12,
        SECONDARY_TEXT,
        false);

    int gridX = x;
    int gridY = y + 28;
    renderRecipeLayout(
        graphics, recipe.layout(), gridX, gridY, width, clipTop, clipBottom, nowMillis);

    int resultX = gridX + 78;
    int resultY = gridY + 20;
    graphics.fill(resultX, resultY, resultX + 20, resultY + 20, SLOT_BACKGROUND);
    graphics.renderOutline(resultX, resultY, 20, 20, ACCENT);
    if (recipe.result().isPresent()) {
      ItemStackView result = recipe.result().orElseThrow();
      renderItem(graphics, resolve(result), resultX + 2, resultY + 2, clipTop, clipBottom);
      String resultCount = "x" + result.count();
      graphics.drawString(
          minecraft.font, resultCount, resultX + 24, resultY + 5, PRIMARY_TEXT, false);
    } else {
      renderUnavailableSlot(
          graphics, resultX + 2, resultY + 2, "Dynamic recipe result", clipTop, clipBottom);
    }

    recipe
        .processing()
        .ifPresent(
            processing -> {
              String detail =
                  processing.timeTicks()
                      + " ticks  +"
                      + formatExperience(processing.experience())
                      + " XP";
              graphics.drawString(
                  minecraft.font,
                  minecraft.font.plainSubstrByWidth(detail, width),
                  x,
                  gridY + 66,
                  SECONDARY_TEXT,
                  false);
            });
    for (int remainingIndex = 0;
        remainingIndex < recipe.remainingItems().size();
        remainingIndex++) {
      renderItem(
          graphics,
          resolve(recipe.remainingItems().get(remainingIndex).item()),
          x + remainingIndex * 18,
          gridY + 82,
          clipTop,
          clipBottom);
    }
  }

  private void renderRecipeNavigation(
      GuiGraphics graphics, int recipeCount, int x, int y, int width, int clipTop, int clipBottom) {
    if (recipeCount <= 1 || y + 14 < clipTop || y >= clipBottom) {
      previousRecipeButton = null;
      nextRecipeButton = null;
      return;
    }
    previousRecipeButton = new OverlayBounds(x + width - 34, y - 2, 15, 15);
    nextRecipeButton = new OverlayBounds(x + width - 16, y - 2, 15, 15);
    renderNavigationButton(graphics, previousRecipeButton, "<", "Previous recipe");
    renderNavigationButton(graphics, nextRecipeButton, ">", "Next recipe");
  }

  private void renderNavigationButton(
      GuiGraphics graphics, OverlayBounds button, String label, String tooltip) {
    graphics.fill(button.x(), button.y(), button.right(), button.bottom(), SLOT_BACKGROUND);
    graphics.renderOutline(button.x(), button.y(), button.width(), button.height(), BORDER);
    graphics.drawCenteredString(
        minecraft.font, label, button.x() + button.width() / 2, button.y() + 3, PRIMARY_TEXT);
    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    if (button.contains(mouseX, mouseY)) {
      graphics.setTooltipForNextFrame(minecraft.font, Component.literal(tooltip), mouseX, mouseY);
    }
  }

  private void renderRecipeLayout(
      GuiGraphics graphics,
      RecipeView.Layout layout,
      int gridX,
      int gridY,
      int width,
      int clipTop,
      int clipBottom,
      long nowMillis) {
    switch (layout) {
      case GridLayout grid ->
          renderGridLayout(graphics, grid, gridX, gridY, clipTop, clipBottom, nowMillis);
      case SingleInputLayout single ->
          renderChoiceSlot(
              graphics,
              single.ingredient(),
              gridX + 20,
              gridY + 20,
              clipTop,
              clipBottom,
              nowMillis);
      case SmithingLayout smithing -> {
        renderChoiceSlot(
            graphics, smithing.template(), gridX, gridY + 20, clipTop, clipBottom, nowMillis);
        renderChoiceSlot(
            graphics, smithing.base(), gridX + 22, gridY + 20, clipTop, clipBottom, nowMillis);
        renderChoiceSlot(
            graphics, smithing.addition(), gridX + 44, gridY + 20, clipTop, clipBottom, nowMillis);
      }
      case TransmuteLayout transmute -> {
        renderChoiceSlot(
            graphics, transmute.input(), gridX + 10, gridY + 20, clipTop, clipBottom, nowMillis);
        renderChoiceSlot(
            graphics, transmute.material(), gridX + 38, gridY + 20, clipTop, clipBottom, nowMillis);
      }
      case UnsupportedLayout ignored ->
          graphics.drawString(
              minecraft.font,
              minecraft.font.plainSubstrByWidth("Unsupported layout", Math.min(width, 70)),
              gridX,
              gridY + 25,
              MISSING,
              false);
    }
    graphics.drawCenteredString(minecraft.font, ">", gridX + 70, gridY + 25, SECONDARY_TEXT);
  }

  private void renderGridLayout(
      GuiGraphics graphics,
      GridLayout layout,
      int gridX,
      int gridY,
      int clipTop,
      int clipBottom,
      long nowMillis) {
    for (int gridYIndex = 0; gridYIndex < 3; gridYIndex++) {
      for (int gridXIndex = 0; gridXIndex < 3; gridXIndex++) {
        int cellX = gridX + gridXIndex * 20;
        int cellY = gridY + gridYIndex * 20;
        graphics.fill(cellX, cellY, cellX + 18, cellY + 18, SLOT_BACKGROUND);
        graphics.renderOutline(cellX, cellY, 18, 18, BORDER);
      }
    }
    for (IngredientSlot ingredient : layout.ingredients()) {
      renderChoiceSlot(
          graphics,
          ingredient.ingredient(),
          gridX + ingredient.x() * 20,
          gridY + ingredient.y() * 20,
          clipTop,
          clipBottom,
          nowMillis);
    }
  }

  private void renderChoiceSlot(
      GuiGraphics graphics,
      IngredientChoice choice,
      int x,
      int y,
      int clipTop,
      int clipBottom,
      long nowMillis) {
    graphics.fill(x, y, x + 18, y + 18, SLOT_BACKGROUND);
    graphics.renderOutline(x, y, 18, 18, BORDER);
    int alternative = recipeState.alternativeIndex(choice.alternatives().size(), nowMillis);
    if (alternative < 0) {
      renderUnavailableSlot(
          graphics, x + 1, y + 1, "Unsupported ingredient choice", clipTop, clipBottom);
      return;
    }
    renderItem(
        graphics,
        resolve(choice.alternatives().get(alternative)),
        x + 1,
        y + 1,
        clipTop,
        clipBottom);
  }

  private void renderUnavailableSlot(
      GuiGraphics graphics, int x, int y, String tooltip, int clipTop, int clipBottom) {
    if (y + 16 < clipTop || y >= clipBottom) {
      return;
    }
    graphics.fill(x, y, x + 16, y + 16, SLOT_BACKGROUND);
    graphics.renderOutline(x, y, 16, 16, MISSING);
    graphics.drawCenteredString(minecraft.font, "?", x + 8, y + 4, MISSING);
    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
      graphics.setTooltipForNextFrame(minecraft.font, Component.literal(tooltip), mouseX, mouseY);
    }
  }

  private void renderItem(
      GuiGraphics graphics, ResolvedItemStack resolved, int x, int y, int clipTop, int clipBottom) {
    if (y + 16 < clipTop || y >= clipBottom) {
      return;
    }
    if (resolved.available()) {
      graphics.renderItem(resolved.minecraftStack(), x, y);
      if (resolved.source().count() > 1) {
        graphics.renderItemDecorations(
            minecraft.font,
            resolved.minecraftStack(),
            x,
            y,
            compactCount(resolved.source().count()));
      }
    } else {
      graphics.fill(x, y, x + 16, y + 16, SLOT_BACKGROUND);
      graphics.renderOutline(x, y, 16, 16, MISSING);
      graphics.drawCenteredString(minecraft.font, "?", x + 8, y + 4, MISSING);
    }

    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
      if (resolved.available()) {
        graphics.setTooltipForNextFrame(minecraft.font, resolved.minecraftStack(), mouseX, mouseY);
      } else {
        graphics.setTooltipForNextFrame(
            minecraft.font,
            Component.literal("Missing item: " + resolved.source().itemId()),
            mouseX,
            mouseY);
      }
    }
  }

  private ResolvedItemStack resolve(ItemStackView item) {
    return itemCache.computeIfAbsent(item, itemResolver::resolve);
  }

  private List<FormattedCharSequence> textLines(TextView text, int width) {
    if (cachedTextWidth != width || cachedTextLines.isEmpty()) {
      cachedTextLines = List.copyOf(minecraft.font.split(Component.literal(text.text()), width));
      cachedTextWidth = width;
    }
    return cachedTextLines;
  }

  private static String displayName(ResolvedItemStack resolved) {
    return resolved.available()
        ? resolved.minecraftStack().getHoverName().getString()
        : "Missing item";
  }

  private static String compactCount(int count) {
    if (count < 1000) {
      return Integer.toString(count);
    }
    if (count < 1_000_000) {
      return (count / 1000) + "k";
    }
    return "999k";
  }

  private static String formatExperience(double experience) {
    if (experience == Math.rint(experience)) {
      return Long.toString((long) experience);
    }
    return String.format(Locale.ROOT, "%.2f", experience)
        .replaceAll("0+$", "")
        .replaceAll("\\.$", "");
  }

  private int scaledMouseX() {
    return (int) minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
  }

  private int scaledMouseY() {
    return (int) minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
  }

  private static OverlayBounds closeButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - BUTTON_SIZE - 3, bounds.y() + 3, BUTTON_SIZE, BUTTON_SIZE);
  }

  private static OverlayBounds pinButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - BUTTON_SIZE * 2 - 5, bounds.y() + 3, BUTTON_SIZE, BUTTON_SIZE);
  }

  private static OverlayBounds resizeButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - RESIZE_HANDLE,
        bounds.bottom() - RESIZE_HANDLE,
        RESIZE_HANDLE,
        RESIZE_HANDLE);
  }

  private enum Interaction {
    NONE,
    DRAG,
    RESIZE
  }
}
