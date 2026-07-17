package dev.minecraftagent.client.ui;

public record OverlayBounds(int x, int y, int width, int height) {
  public int right() {
    return x + width;
  }

  public int bottom() {
    return y + height;
  }

  public boolean contains(double pointX, double pointY) {
    return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
  }
}
