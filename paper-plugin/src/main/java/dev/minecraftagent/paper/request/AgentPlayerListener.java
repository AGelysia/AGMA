package dev.minecraftagent.paper.request;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AgentPlayerListener implements Listener {
  private final AgentRequestService requests;
  private final Consumer<UUID> proposalInvalidator;

  public AgentPlayerListener(AgentRequestService requests) {
    this(requests, ignored -> {});
  }

  public AgentPlayerListener(AgentRequestService requests, Consumer<UUID> proposalInvalidator) {
    this.requests = Objects.requireNonNull(requests);
    this.proposalInvalidator = Objects.requireNonNull(proposalInvalidator);
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    var playerId = event.getPlayer().getUniqueId();
    try {
      requests.cancelPlayer(playerId);
    } finally {
      proposalInvalidator.accept(playerId);
    }
  }
}
