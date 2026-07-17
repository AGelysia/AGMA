package dev.minecraftagent.paper.preview;

import dev.minecraftagent.paper.client.ClientStructuredView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded one-shot storage that binds Paper-owned previews to the originating request/player. */
public final class BuildPreviewArtifactRepository {
  private static final int MAXIMUM_ENTRIES = 32;
  private static final Duration TTL = Duration.ofMinutes(2);

  private final Clock clock;
  private final LinkedHashMap<UUID, Stored> byRequest = new LinkedHashMap<>();

  public BuildPreviewArtifactRepository() {
    this(Clock.systemUTC());
  }

  BuildPreviewArtifactRepository(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public synchronized void put(UUID requestId, UUID playerId, ClientStructuredView view) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(view);
    if (!requestId.equals(view.requestId())) {
      throw new IllegalArgumentException("Preview request binding mismatch");
    }
    purge();
    byRequest.put(requestId, new Stored(playerId, view, clock.instant().plus(TTL)));
    while (byRequest.size() > MAXIMUM_ENTRIES) {
      byRequest.remove(byRequest.keySet().iterator().next());
    }
  }

  public synchronized List<ClientStructuredView> consume(UUID requestId, UUID playerId) {
    purge();
    var stored = byRequest.remove(requestId);
    if (stored == null || !stored.playerId().equals(playerId)) {
      return List.of();
    }
    return List.of(stored.view());
  }

  public synchronized void discard(UUID requestId) {
    byRequest.remove(requestId);
  }

  synchronized int size() {
    purge();
    return byRequest.size();
  }

  private void purge() {
    var now = clock.instant();
    byRequest.values().removeIf(stored -> !stored.expiresAt().isAfter(now));
  }

  private record Stored(UUID playerId, ClientStructuredView view, Instant expiresAt) {}
}
