package dev.minecraftagent.standalone.common;

import java.util.Objects;
import java.util.UUID;

public record RuntimeStatus(UUID requestId, State state, int activeRequests, int queuedRequests) {
  public RuntimeStatus {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(state, "state");
    if (activeRequests < 0 || activeRequests > 8 || queuedRequests < 0 || queuedRequests > 128) {
      throw new IllegalArgumentException("Runtime status counts are out of bounds");
    }
  }

  public enum State {
    READY,
    STOPPING
  }
}
