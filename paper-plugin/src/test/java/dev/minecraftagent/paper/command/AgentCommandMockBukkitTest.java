package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.request.AgentRequestGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkitExtension;
import org.mockbukkit.mockbukkit.MockBukkitInject;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

@ExtendWith(MockBukkitExtension.class)
class AgentCommandMockBukkitTest {
  @MockBukkitInject private ServerMock server;

  @MockBukkitInject(name = "MinecraftAgentCommandTest")
  private PluginMock plugin;

  @Test
  void livePlayerPermissionAndIdentityReachTheRequestGatewayAtTheBukkitBoundary() {
    var player = server.addPlayer("RequestPlayer");
    var observer = server.addPlayer("RequestObserver");
    var submittedPlayer = new AtomicReference<UUID>();
    var submittedMessage = new AtomicReference<String>();
    AgentRequestGateway requests =
        (playerId, message) -> {
          submittedPlayer.set(playerId);
          submittedMessage.set(message);
          return AgentRequestGateway.Submission.ACCEPTED;
        };
    var command = command(requests);

    command.execute(player, "agent", new String[] {"say", "smoke", "request"});
    assertEquals("You do not have permission to use this command.", player.nextMessage());
    assertNull(observer.nextMessage());
    assertNull(submittedPlayer.get());

    player.addAttachment(plugin, AgentCommand.USE_PERMISSION, true);
    command.execute(player, "agent", new String[] {"say", "smoke", "request"});

    assertEquals(player.getUniqueId(), submittedPlayer.get());
    assertEquals("smoke request", submittedMessage.get());
    assertNull(player.nextMessage());
    assertNull(observer.nextMessage());
  }

  private AgentCommand command(AgentRequestGateway requests) {
    AgentControl control =
        new AgentControl() {
          @Override
          public void turnOff() {}

          @Override
          public RecoveryRequest turnOn() {
            return new RecoveryRequest(
                RecoveryDisposition.ALREADY_ONLINE, CompletableFuture.completedFuture(true));
          }
        };
    return new AgentCommand(
        plugin,
        AgentCommandMockBukkitTest::healthy,
        control,
        requests,
        ignored -> false,
        Runnable::run);
  }

  private static AgentStatus healthy() {
    return new AgentStatus(
        AgentState.ONLINE, DesiredMode.ENABLED, AgentHealth.HEALTHY, null, null, List.of());
  }
}
