package dev.minecraftagent.client.ui;

public record OverlayPreferences(int x, int y, int width, int height, boolean pinned) {
  public static final int MIN_WIDTH = 180;
  public static final int MAX_WIDTH = 420;
  public static final int MIN_HEIGHT = 96;
  public static final int MAX_HEIGHT = 320;
  public static final int MAX_COORDINATE = 32768;

  public OverlayPreferences {
    if (x < 0 || x > MAX_COORDINATE || y < 0 || y > MAX_COORDINATE) {
      throw new IllegalArgumentException("Overlay position is out of range");
    }
    if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT) {
      throw new IllegalArgumentException("Overlay size is out of range");
    }
  }

  public static OverlayPreferences defaults() {
    return new OverlayPreferences(12, 12, 320, 200, false);
  }

  public OverlayPreferences withPosition(int nextX, int nextY) {
    return new OverlayPreferences(nextX, nextY, width, height, pinned);
  }

  public OverlayPreferences withSize(int nextWidth, int nextHeight) {
    return new OverlayPreferences(x, y, nextWidth, nextHeight, pinned);
  }

  public OverlayPreferences withPinned(boolean nextPinned) {
    return new OverlayPreferences(x, y, width, height, nextPinned);
  }
}
