package dev.minecraftagent.client.ui;

import dev.minecraftagent.client.view.StructuredView;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Client-owned overlay state. Server view updates cannot pin, move, resize, or clear it. */
public final class OverlayController {
  public static final int MAX_OPEN_VIEWS = 8;
  public static final int SCREEN_MARGIN = 4;

  private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
  private final OverlayPreferenceStore store;
  private final OverlayPreferenceStore.LoadStatus loadStatus;
  private OverlayPreferences preferences;
  private UUID activeViewId;
  private PersistenceStatus persistenceStatus;

  public OverlayController(OverlayPreferences preferences) {
    this(preferences, null, OverlayPreferenceStore.LoadStatus.MISSING);
  }

  public OverlayController(OverlayPreferenceStore store) {
    this(load(store), Objects.requireNonNull(store, "store"));
  }

  private OverlayController(
      OverlayPreferenceStore.LoadResult result, OverlayPreferenceStore store) {
    this(result.preferences(), store, result.status());
  }

  private OverlayController(
      OverlayPreferences preferences,
      OverlayPreferenceStore store,
      OverlayPreferenceStore.LoadStatus loadStatus) {
    this.preferences = Objects.requireNonNull(preferences, "preferences");
    this.store = store;
    this.loadStatus = Objects.requireNonNull(loadStatus, "loadStatus");
    persistenceStatus = store == null ? PersistenceStatus.NOT_CONFIGURED : PersistenceStatus.CLEAN;
  }

  private static OverlayPreferenceStore.LoadResult load(OverlayPreferenceStore store) {
    return Objects.requireNonNull(store, "store").load();
  }

  public synchronized ViewUpdateResult show(StructuredView view) {
    Objects.requireNonNull(view, "view");
    Entry existing = entries.get(view.viewId());
    if (existing != null) {
      ViewUpdateResult result = updateExisting(existing, view);
      if (result == ViewUpdateResult.UPDATED) {
        activeViewId = view.viewId();
      }
      return result;
    }

    for (Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
        iterator.hasNext(); ) {
      Map.Entry<UUID, Entry> entry = iterator.next();
      if (!entry.getValue().pinned) {
        iterator.remove();
      }
    }
    if (entries.size() >= MAX_OPEN_VIEWS) {
      return ViewUpdateResult.CAPACITY_REJECTED;
    }
    boolean pinned = preferences.pinned() && view.pinnable();
    entries.put(view.viewId(), new Entry(view, pinned));
    activeViewId = view.viewId();
    return ViewUpdateResult.ADDED;
  }

  /** Updates an existing view without allowing a server update to steal HUD focus. */
  public synchronized ViewUpdateResult update(StructuredView view) {
    Objects.requireNonNull(view, "view");
    Entry existing = entries.get(view.viewId());
    if (existing == null) {
      return ViewUpdateResult.UNKNOWN_VIEW;
    }
    return updateExisting(existing, view);
  }

  public synchronized Optional<OverlaySnapshot> snapshot() {
    Entry entry = activeEntry();
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(
        new OverlaySnapshot(entry.view, entry.pinned, entry.scroll, entry.maximumScroll));
  }

  /** Returns the connection-scoped view identities that are still displayed in the HUD. */
  public synchronized Set<UUID> openViewIds() {
    return Set.copyOf(entries.keySet());
  }

  public synchronized boolean pin() {
    return pin(activeViewId);
  }

  /** Applies a user-originated pin command to a specific open view. */
  public synchronized boolean pin(UUID viewId) {
    Entry entry = viewId == null ? null : entries.get(viewId);
    if (entry == null || !entry.view.pinnable() || entry.pinned) {
      return false;
    }
    entry.pinned = true;
    updatePinnedPreference(true);
    return true;
  }

  public synchronized boolean unpin() {
    return unpin(activeViewId);
  }

  /** Applies a user-originated unpin command to a specific open view. */
  public synchronized boolean unpin(UUID viewId) {
    Entry entry = viewId == null ? null : entries.get(viewId);
    if (entry == null || !entry.pinned) {
      return false;
    }
    entry.pinned = false;
    updatePinnedPreference(false);
    return true;
  }

  public synchronized boolean togglePin() {
    Entry entry = activeEntry();
    if (entry == null) {
      return false;
    }
    if (entry.pinned) {
      return unpin();
    }
    return entry.view.pinnable() && pin();
  }

  public synchronized boolean close() {
    if (activeViewId == null || entries.remove(activeViewId) == null) {
      return false;
    }
    activeViewId = lastViewId();
    return true;
  }

  /** Applies a transport lifecycle clear without allowing the server to remove pinned content. */
  public synchronized boolean dismiss(UUID viewId) {
    Entry entry = entries.get(Objects.requireNonNull(viewId, "viewId"));
    if (entry == null || entry.pinned) {
      return false;
    }
    entries.remove(viewId);
    if (viewId.equals(activeViewId)) {
      activeViewId = lastViewId();
    }
    return true;
  }

  /** Removes all transient server views while preserving client-pinned views. */
  public synchronized int dismissTransient() {
    int before = entries.size();
    entries.entrySet().removeIf(entry -> !entry.getValue().pinned);
    if (activeViewId != null && !entries.containsKey(activeViewId)) {
      activeViewId = lastViewId();
    }
    return before - entries.size();
  }

  public synchronized boolean clear() {
    if (entries.isEmpty()) {
      return false;
    }
    entries.clear();
    activeViewId = null;
    return true;
  }

  /** Clears connection-scoped content while retaining and flushing local preferences. */
  public synchronized void disconnect() {
    entries.clear();
    activeViewId = null;
    flushPreferences();
  }

  public synchronized void scrollBy(int pixels) {
    Entry entry = activeEntry();
    if (entry == null) {
      return;
    }
    entry.scroll = clamp(entry.scroll + pixels, 0, entry.maximumScroll);
  }

  public synchronized void setViewportMetrics(int contentHeight, int viewportHeight) {
    Entry entry = activeEntry();
    if (entry == null) {
      return;
    }
    entry.maximumScroll = Math.max(0, contentHeight - Math.max(0, viewportHeight));
    clampScroll(entry);
  }

  public synchronized OverlayBounds bounds(int screenWidth, int screenHeight) {
    int availableWidth = Math.max(1, screenWidth - SCREEN_MARGIN * 2);
    int availableHeight = Math.max(1, screenHeight - SCREEN_MARGIN * 2);
    int width = Math.min(preferences.width(), availableWidth);
    int height = Math.min(preferences.height(), availableHeight);
    int maximumX = Math.max(SCREEN_MARGIN, screenWidth - SCREEN_MARGIN - width);
    int maximumY = Math.max(SCREEN_MARGIN, screenHeight - SCREEN_MARGIN - height);
    int x = clamp(preferences.x(), SCREEN_MARGIN, maximumX);
    int y = clamp(preferences.y(), SCREEN_MARGIN, maximumY);
    return new OverlayBounds(x, y, width, height);
  }

  public synchronized void moveTo(int x, int y, int screenWidth, int screenHeight) {
    int maximumX = Math.max(0, screenWidth - SCREEN_MARGIN - preferences.width());
    int maximumY = Math.max(0, screenHeight - SCREEN_MARGIN - preferences.height());
    int nextX = clamp(x, 0, Math.min(maximumX, OverlayPreferences.MAX_COORDINATE));
    int nextY = clamp(y, 0, Math.min(maximumY, OverlayPreferences.MAX_COORDINATE));
    if (nextX != preferences.x() || nextY != preferences.y()) {
      preferences = preferences.withPosition(nextX, nextY);
      markDirty();
    }
  }

  public synchronized void resizeTo(int width, int height, int screenWidth, int screenHeight) {
    int maximumWidth =
        Math.max(
            OverlayPreferences.MIN_WIDTH,
            Math.min(OverlayPreferences.MAX_WIDTH, screenWidth - SCREEN_MARGIN - preferences.x()));
    int maximumHeight =
        Math.max(
            OverlayPreferences.MIN_HEIGHT,
            Math.min(
                OverlayPreferences.MAX_HEIGHT, screenHeight - SCREEN_MARGIN - preferences.y()));
    int nextWidth = clamp(width, OverlayPreferences.MIN_WIDTH, maximumWidth);
    int nextHeight = clamp(height, OverlayPreferences.MIN_HEIGHT, maximumHeight);
    if (nextWidth != preferences.width() || nextHeight != preferences.height()) {
      preferences = preferences.withSize(nextWidth, nextHeight);
      markDirty();
    }
  }

  public synchronized OverlayPreferences preferences() {
    return preferences;
  }

  public OverlayPreferenceStore.LoadStatus preferenceLoadStatus() {
    return loadStatus;
  }

  public synchronized PersistenceStatus persistenceStatus() {
    return persistenceStatus;
  }

  /** Flushes drag/resize changes once an input gesture completes. */
  public synchronized void flushPreferences() {
    if (persistenceStatus == PersistenceStatus.DIRTY
        || persistenceStatus == PersistenceStatus.FAILED) {
      persist();
    }
  }

  private void updatePinnedPreference(boolean pinned) {
    if (preferences.pinned() != pinned) {
      preferences = preferences.withPinned(pinned);
      persist();
    }
  }

  private void persist() {
    if (store == null) {
      persistenceStatus = PersistenceStatus.NOT_CONFIGURED;
      return;
    }
    try {
      store.save(preferences);
      persistenceStatus = PersistenceStatus.CLEAN;
    } catch (IOException exception) {
      persistenceStatus = PersistenceStatus.FAILED;
    }
  }

  private void markDirty() {
    persistenceStatus = store == null ? PersistenceStatus.NOT_CONFIGURED : PersistenceStatus.DIRTY;
  }

  private Entry activeEntry() {
    return activeViewId == null ? null : entries.get(activeViewId);
  }

  private static ViewUpdateResult updateExisting(Entry existing, StructuredView view) {
    if (view.revision() <= existing.view.revision()) {
      return ViewUpdateResult.IGNORED_STALE;
    }
    existing.view = view;
    clampScroll(existing);
    return ViewUpdateResult.UPDATED;
  }

  private UUID lastViewId() {
    UUID last = null;
    for (UUID viewId : entries.keySet()) {
      last = viewId;
    }
    return last;
  }

  private static void clampScroll(Entry entry) {
    entry.scroll = clamp(entry.scroll, 0, entry.maximumScroll);
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  public enum ViewUpdateResult {
    ADDED,
    UPDATED,
    IGNORED_STALE,
    UNKNOWN_VIEW,
    CAPACITY_REJECTED
  }

  public enum PersistenceStatus {
    NOT_CONFIGURED,
    DIRTY,
    CLEAN,
    FAILED
  }

  public record OverlaySnapshot(
      StructuredView view, boolean pinned, int scroll, int maximumScroll) {}

  private static final class Entry {
    private StructuredView view;
    private boolean pinned;
    private int scroll;
    private int maximumScroll;

    private Entry(StructuredView view, boolean pinned) {
      this.view = view;
      this.pinned = pinned;
    }
  }
}
