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

  /** Whether this bridge can actually present a hologram; the default bridge cannot. */
  default boolean available() {
    return false;
  }

  /** Registers the hologram load outcome listener; the default bridge never reports outcomes. */
  default void loadListener(LoadListener listener) {}

  /** The default bridge for installations without a hologram adapter; every call is a no-op. */
  static PreviewHologramBridge unsupported() {
    return UnsupportedBridge.INSTANCE;
  }

  /** Receives the outcome of an asynchronous hologram load on the client thread. */
  interface LoadListener {
    /**
     * Reports one finished load attempt: {@code reason} is null on success and otherwise a
     * machine-readable failure tag such as {@code STAGE_FAILED} or a display failure code name.
     */
    void onLoad(boolean loaded, String reason);
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
