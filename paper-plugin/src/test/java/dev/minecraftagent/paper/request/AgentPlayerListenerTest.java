package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class AgentPlayerListenerTest {
  @Test
  void legacyConstructorStillCancelsThePlayersRequest() {
    var requests = mock(AgentRequestService.class);
    var playerId = UUID.randomUUID();

    new AgentPlayerListener(requests).onPlayerQuit(event(playerId));

    verify(requests).cancelPlayer(playerId);
  }

  @Test
  void quitCancelsTheRequestAndInvalidatesProposalsForTheSamePlayer() {
    var requests = mock(AgentRequestService.class);
    var invalidated = new ArrayList<UUID>();
    var playerId = UUID.randomUUID();

    new AgentPlayerListener(requests, invalidated::add).onPlayerQuit(event(playerId));

    verify(requests).cancelPlayer(playerId);
    assertEquals(List.of(playerId), invalidated);
  }

  @Test
  void proposalInvalidationFailureCannotSkipRequestCancellation() {
    var requests = mock(AgentRequestService.class);
    Consumer<UUID> failingInvalidator =
        ignored -> {
          throw new IllegalStateException("failed");
        };
    var playerId = UUID.randomUUID();

    assertThrows(
        IllegalStateException.class,
        () -> new AgentPlayerListener(requests, failingInvalidator).onPlayerQuit(event(playerId)));

    verify(requests).cancelPlayer(playerId);
  }

  @Test
  void requestCancellationFailureCannotSkipProposalInvalidation() {
    var requests = mock(AgentRequestService.class);
    var invalidated = new ArrayList<UUID>();
    var playerId = UUID.randomUUID();
    doThrow(new IllegalStateException("failed")).when(requests).cancelPlayer(playerId);

    assertThrows(
        IllegalStateException.class,
        () -> new AgentPlayerListener(requests, invalidated::add).onPlayerQuit(event(playerId)));

    assertEquals(List.of(playerId), invalidated);
  }

  private static PlayerQuitEvent event(UUID playerId) {
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    var event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }
}
