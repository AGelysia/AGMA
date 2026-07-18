package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Optional viewer bridge with no references to viewer API classes. */
public final class OptionalViewerRegistry {
  private static final Object LOCK = new Object();
  private static final LinkedHashMap<String, CatalogAdapter> ADAPTERS = new LinkedHashMap<>();
  private static Runnable refresh = () -> {};

  private OptionalViewerRegistry() {}

  public static void setRefresh(Runnable action) {
    synchronized (LOCK) {
      refresh = action == null ? () -> {} : action;
    }
  }

  public static void register(CatalogAdapter adapter) {
    Runnable action;
    synchronized (LOCK) {
      ADAPTERS.put(adapter.descriptor().id(), adapter);
      action = refresh;
    }
    action.run();
  }

  public static void unregister(String adapterId) {
    Runnable action;
    synchronized (LOCK) {
      ADAPTERS.remove(adapterId);
      action = refresh;
    }
    action.run();
  }

  public static void clear() {
    synchronized (LOCK) {
      ADAPTERS.clear();
    }
  }

  public static Optional<CatalogAdapter> selected() {
    synchronized (LOCK) {
      return ADAPTERS.values().stream()
          .filter(OptionalViewerRegistry::ready)
          .sorted(
              Comparator.comparingInt((CatalogAdapter value) -> value.descriptor().priority())
                  .reversed()
                  .thenComparing(value -> value.descriptor().id()))
          .findFirst();
    }
  }

  public static Optional<String> hoveredItemId() {
    synchronized (LOCK) {
      return ADAPTERS.values().stream()
          .sorted(
              Comparator.comparingInt((CatalogAdapter value) -> value.descriptor().priority())
                  .reversed()
                  .thenComparing(value -> value.descriptor().id()))
          .map(OptionalViewerRegistry::hovered)
          .flatMap(Optional::stream)
          .findFirst();
    }
  }

  private static Optional<String> hovered(CatalogAdapter adapter) {
    try {
      return adapter.hoveredItemId();
    } catch (RuntimeException | LinkageError failure) {
      return Optional.empty();
    }
  }

  private static boolean ready(CatalogAdapter adapter) {
    try {
      return adapter.probe().status() == CatalogAdapter.Status.READY;
    } catch (RuntimeException | LinkageError failure) {
      return false;
    }
  }
}
