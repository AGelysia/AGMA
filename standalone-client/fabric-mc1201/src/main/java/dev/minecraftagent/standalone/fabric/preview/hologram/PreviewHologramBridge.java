package dev.minecraftagent.standalone.fabric.preview.hologram;

import dev.minecraftagent.standalone.common.preview.StandalonePreview;

/**
 * Hook between the build preview feature and an in-world hologram presentation. The Litematica
 * adapter is provided by a follow-up change; the default bridge is a no-op so the keybind and the
 * executor can be wired without it. Implementations must tolerate calls from any thread.
 */
public interface PreviewHologramBridge extends AutoCloseable {
  /** Loads one preview as the current hologram, replacing any previously loaded one. */
  void load(StandalonePreview preview);

  /** Removes the current hologram, if any. */
  void removeCurrent();

  @Override
  void close();

  /** The default bridge for installations without a hologram adapter; every call is a no-op. */
  static PreviewHologramBridge unsupported() {
    return UnsupportedBridge.INSTANCE;
  }
}

final class UnsupportedBridge implements PreviewHologramBridge {
  static final UnsupportedBridge INSTANCE = new UnsupportedBridge();

  private UnsupportedBridge() {}

  @Override
  public void load(StandalonePreview preview) {}

  @Override
  public void removeCurrent() {}

  @Override
  public void close() {}
}
