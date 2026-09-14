package dev.minecraftagent.standalone.ui.preview;

import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded in-memory store of the most recent build preview artifacts. Insertion-ordered LRU:
 * reading an entry refreshes it, and the eldest entry is evicted beyond the capacity. Thread-safe;
 * tool workers put while the client thread reads for the overlay.
 */
public final class StandalonePreviewStore {
  public static final int CAPACITY = 8;

  @SuppressWarnings("serial")
  private final LinkedHashMap<UUID, StandalonePreview> previews =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, StandalonePreview> eldest) {
          return size() > CAPACITY;
        }
      };

  private StandalonePreview latest;

  public synchronized void put(StandalonePreview preview) {
    Objects.requireNonNull(preview, "preview");
    previews.put(preview.previewId(), preview);
    latest = preview;
  }

  public synchronized Optional<StandalonePreview> get(UUID previewId) {
    Objects.requireNonNull(previewId, "previewId");
    return Optional.ofNullable(previews.get(previewId));
  }

  /** The most recently created preview, empty when it was evicted or the store was cleared. */
  public synchronized Optional<StandalonePreview> latest() {
    var current = latest;
    if (current != null && previews.containsKey(current.previewId())) {
      return Optional.of(current);
    }
    latest = null;
    return Optional.empty();
  }

  /** Every retained preview in LRU order, eldest first. */
  public synchronized List<StandalonePreview> list() {
    return List.copyOf(new ArrayList<>(previews.values()));
  }

  public synchronized void clear() {
    previews.clear();
    latest = null;
  }
}
