package dev.minecraftagent.client.ui;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Transparent input capture for HUD movement, resizing, scrolling, and controls. */
public final class OverlayInteractionScreen extends Screen {
  private final OverlayRenderer renderer;

  public OverlayInteractionScreen(OverlayRenderer renderer) {
    super(Component.empty());
    this.renderer = Objects.requireNonNull(renderer, "renderer");
  }

  /** Opens only from normal gameplay and never replaces another client screen. */
  public static boolean open(Minecraft minecraft, OverlayRenderer renderer) {
    Objects.requireNonNull(minecraft, "minecraft");
    Objects.requireNonNull(renderer, "renderer");
    if (minecraft.screen != null || renderer.controller().snapshot().isEmpty()) {
      return false;
    }
    minecraft.setScreen(new OverlayInteractionScreen(renderer));
    return true;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // The registered HUD element remains visible beneath this transparent input surface.
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    return renderer.mouseClicked(event.x(), event.y(), event.button())
        || super.mouseClicked(event, doubleClick);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    return renderer.mouseReleased(event.x(), event.y(), event.button())
        || super.mouseReleased(event);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
    return renderer.mouseDragged(event.x(), event.y(), event.button())
        || super.mouseDragged(event, dragX, dragY);
  }

  @Override
  public boolean mouseScrolled(
      double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
    return renderer.mouseScrolled(mouseX, mouseY, verticalAmount)
        || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    return super.keyPressed(event);
  }

  @Override
  public void tick() {
    if (renderer.controller().snapshot().isEmpty()) {
      onClose();
    }
  }

  @Override
  public void removed() {
    super.removed();
    renderer.cancelInteraction();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean isInGameUi() {
    return true;
  }

  @Override
  public boolean isAllowedInPortal() {
    return true;
  }
}
