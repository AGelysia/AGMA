package dev.minecraftagent.paper.command;

import java.util.UUID;

/** Sends a fixed presentation-only action to the calling player's negotiated client. */
@FunctionalInterface
public interface AgentUiControl {
  Result invoke(UUID playerUuid, Action action, UUID viewId);

  static AgentUiControl unavailable() {
    return (playerUuid, action, viewId) -> Result.CLIENT_UNAVAILABLE;
  }

  enum Action {
    PIN,
    UNPIN,
    CLEAR,
    PREVIEW,
    REMOVE,
    MATERIALS
  }

  enum Result {
    SENT,
    CLIENT_UNAVAILABLE
  }
}
