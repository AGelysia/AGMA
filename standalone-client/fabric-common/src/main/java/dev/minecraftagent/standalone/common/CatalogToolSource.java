package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.catalog.CatalogSnapshot;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Platform-neutral source of one immutable catalog generation and explicitly authorized inventory.
 */
public interface CatalogToolSource {
  CatalogView catalogView();

  /**
   * Returns an index for the supplied immutable snapshot. Implementations may return a
   * generation-bound cached index.
   */
  default ResourceSearchIndex searchIndex(CatalogSnapshot snapshot) {
    return new ResourceSearchIndex(Objects.requireNonNull(snapshot, "snapshot"));
  }

  /**
   * Reads only the resource ids in {@code request}. This method runs on the tool worker; a
   * game-backed implementation must marshal live inventory access onto the client thread before
   * returning DTOs.
   */
  InventorySnapshot inventorySnapshot(InventoryRequest request) throws Exception;

  enum State {
    READY,
    RELOADING,
    UNAVAILABLE
  }

  enum Visibility {
    MAIN_MENU,
    NO_WORLD,
    SINGLEPLAYER,
    MULTIPLAYER
  }

  enum Completeness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
  }

  record CatalogView(
      String generationId,
      State state,
      Visibility visibility,
      Completeness completeness,
      CatalogSnapshot snapshot,
      List<String> warnings) {
    public CatalogView {
      generationId = CatalogToolSource.generationId(generationId);
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(visibility, "visibility");
      Objects.requireNonNull(completeness, "completeness");
      warnings = CatalogToolSource.warnings(warnings, 32);
      if (state == State.READY) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!generationId.equals(snapshot.generationId())
            || completeness == Completeness.UNAVAILABLE) {
          throw new IllegalArgumentException("ready catalog view is inconsistent");
        }
      } else if (snapshot != null || completeness != Completeness.UNAVAILABLE) {
        throw new IllegalArgumentException("unavailable catalog view is inconsistent");
      }
    }

    public static CatalogView ready(
        Visibility visibility,
        Completeness completeness,
        CatalogSnapshot snapshot,
        List<String> warnings) {
      Objects.requireNonNull(snapshot, "snapshot");
      return new CatalogView(
          snapshot.generationId(), State.READY, visibility, completeness, snapshot, warnings);
    }

    public static CatalogView unavailable(
        String generationId, State state, Visibility visibility, List<String> warnings) {
      if (state == State.READY) {
        throw new IllegalArgumentException("use ready() for a published catalog");
      }
      return new CatalogView(
          generationId, state, visibility, Completeness.UNAVAILABLE, null, warnings);
    }
  }

  record InventoryRequest(
      String generationId, List<String> resourceIds, Cancellation cancellation) {
    public InventoryRequest {
      generationId = CatalogToolSource.generationId(generationId);
      Objects.requireNonNull(resourceIds, "resourceIds");
      var sorted = resourceIds.stream().map(CatalogToolSource::resourceId).sorted().toList();
      if (sorted.isEmpty()
          || sorted.size() > 64
          || new HashSet<>(sorted).size() != sorted.size()
          || !sorted.equals(resourceIds)) {
        throw new IllegalArgumentException("inventory resource ids must be unique and sorted");
      }
      resourceIds = sorted;
      Objects.requireNonNull(cancellation, "cancellation");
    }
  }

  record InventoryEntry(
      String resourceId, String componentsFingerprint, BigDecimal count, List<Integer> slots) {
    public InventoryEntry {
      resourceId = CatalogToolSource.resourceId(resourceId);
      if (componentsFingerprint != null && !componentsFingerprint.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("inventory component fingerprint is invalid");
      }
      count = decimal(count, true);
      Objects.requireNonNull(slots, "slots");
      if (slots.size() > 64
          || slots.stream().anyMatch(slot -> slot == null || slot < 0 || slot > 255)) {
        throw new IllegalArgumentException("inventory slots are invalid");
      }
      var sorted = new ArrayList<>(slots);
      sorted.sort(Comparator.naturalOrder());
      if (new HashSet<>(sorted).size() != sorted.size()) {
        throw new IllegalArgumentException("inventory slots are invalid");
      }
      slots = List.copyOf(sorted);
    }

    public InventoryEntry(String resourceId, BigDecimal count, List<Integer> slots) {
      this(resourceId, null, count, slots);
    }
  }

  record InventorySnapshot(List<InventoryEntry> entries, boolean truncated, List<String> warnings) {
    public InventorySnapshot {
      Objects.requireNonNull(entries, "entries");
      if (entries.size() > 64
          || entries.stream().anyMatch(Objects::isNull)
          || entries.stream()
                  .map(entry -> entry.resourceId() + "\u0000" + entry.componentsFingerprint())
                  .collect(java.util.stream.Collectors.toSet())
                  .size()
              != entries.size()) {
        throw new IllegalArgumentException("inventory entries are invalid");
      }
      entries = List.copyOf(entries);
      warnings = CatalogToolSource.warnings(warnings, 32);
    }
  }

  @FunctionalInterface
  interface Cancellation {
    boolean cancelled();

    default void throwIfCancelled() throws InterruptedException {
      if (cancelled() || Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("client tool execution was cancelled");
      }
    }
  }

  private static String generationId(String value) {
    if (value == null || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value)) {
      throw new IllegalArgumentException("catalog generation id is invalid");
    }
    return value;
  }

  private static String resourceId(String value) {
    if (value == null || !Pattern.matches("[a-z0-9_.-]+:[a-z0-9_./-]+", value)) {
      throw new IllegalArgumentException("inventory resource id is invalid");
    }
    return value;
  }

  private static BigDecimal decimal(BigDecimal value, boolean zeroAllowed) {
    Objects.requireNonNull(value, "value");
    if ((zeroAllowed ? value.signum() < 0 : value.signum() <= 0)
        || value.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0
        || value.precision() > 24
        || value.scale() > 9) {
      throw new IllegalArgumentException("inventory quantity is invalid");
    }
    return value.stripTrailingZeros();
  }

  private static List<String> warnings(List<String> source, int maximum) {
    Objects.requireNonNull(source, "warnings");
    if (source.size() > maximum) {
      throw new IllegalArgumentException("too many catalog warnings");
    }
    return source.stream()
        .map(
            warning -> {
              Objects.requireNonNull(warning, "warning");
              if (warning.isEmpty() || warning.codePointCount(0, warning.length()) > 512) {
                throw new IllegalArgumentException("catalog warning is invalid");
              }
              return warning;
            })
        .toList();
  }
}
