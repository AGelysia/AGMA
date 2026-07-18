package dev.minecraftagent.standalone.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientProfileSnapshot;
import dev.minecraftagent.standalone.common.ClientRuntimeView;
import dev.minecraftagent.standalone.common.ConnectorSessionState;
import dev.minecraftagent.standalone.supervisor.SupervisorSnapshot;
import dev.minecraftagent.standalone.supervisor.SupervisorState;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RuntimePanelStatusTest {
  private static final UUID INSTALLATION = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void exposesClearActionsForEveryProfileLifecycle() {
    assertStatus(
        view(ClientLifecycleState.UNCONFIGURED, null, null),
        RuntimePanelStatus.State.UNCONFIGURED,
        RuntimePanelStatus.Action.CONFIGURE);
    assertStatus(
        view(ClientLifecycleState.STOPPED, null, null),
        RuntimePanelStatus.State.STOPPED,
        RuntimePanelStatus.Action.START);
    assertStatus(
        view(ClientLifecycleState.STARTING, supervisor(SupervisorState.STARTING, 0, null), null),
        RuntimePanelStatus.State.STARTING,
        RuntimePanelStatus.Action.CANCEL_START);
    assertStatus(
        view(ClientLifecycleState.STOPPING, supervisor(SupervisorState.STOPPING, 0, null), null),
        RuntimePanelStatus.State.STOPPING,
        RuntimePanelStatus.Action.WAIT);
    assertStatus(
        view(
            ClientLifecycleState.ERROR,
            supervisor(SupervisorState.ERROR, 1, "RUNTIME_EXITED"),
            null),
        RuntimePanelStatus.State.ERROR,
        RuntimePanelStatus.Action.RETRY);
  }

  @Test
  void requiresBothAReadyProcessAndAuthenticatedConnectorToShowRunning() {
    var ready = supervisor(SupervisorState.READY, 0, null);
    assertStatus(
        view(ClientLifecycleState.STARTING, ready, ConnectorSessionState.CONNECTING),
        RuntimePanelStatus.State.CONNECTING,
        RuntimePanelStatus.Action.CANCEL_START);
    assertStatus(
        view(ClientLifecycleState.READY, ready, ConnectorSessionState.AUTHENTICATED),
        RuntimePanelStatus.State.RUNNING,
        RuntimePanelStatus.Action.STOP);
    assertStatus(
        view(ClientLifecycleState.READY, ready, ConnectorSessionState.CLOSED),
        RuntimePanelStatus.State.ERROR,
        RuntimePanelStatus.Action.RESTART);
    assertStatus(
        view(
            ClientLifecycleState.READY,
            supervisor(SupervisorState.STARTING, 1, null),
            ConnectorSessionState.AUTHENTICATED),
        RuntimePanelStatus.State.RECOVERING,
        RuntimePanelStatus.Action.STOP);
    assertEquals(
        RuntimePanelStatus.State.ERROR,
        RuntimePanelStatus.from(
                view(ClientLifecycleState.READY, ready, ConnectorSessionState.AUTHENTICATED), true)
            .state());
    assertEquals(
        RuntimePanelStatus.Action.RESTART,
        RuntimePanelStatus.from(
                view(ClientLifecycleState.READY, ready, ConnectorSessionState.AUTHENTICATED), true)
            .action());
  }

  private static void assertStatus(
      ClientRuntimeView view, RuntimePanelStatus.State state, RuntimePanelStatus.Action action) {
    var presentation = RuntimePanelStatus.from(view, false);
    assertEquals(state, presentation.state());
    assertEquals(action, presentation.action());
  }

  private static ClientRuntimeView view(
      ClientLifecycleState lifecycle,
      SupervisorSnapshot supervisor,
      ConnectorSessionState connector) {
    var profile =
        lifecycle == ClientLifecycleState.UNCONFIGURED
            ? new ClientProfileSnapshot(lifecycle, 0, null, null, null, null)
            : new ClientProfileSnapshot(
                lifecycle,
                1,
                INSTALLATION,
                "openai",
                "gpt-4.1-mini",
                lifecycle == ClientLifecycleState.ERROR ? "RUNTIME_START_FAILED" : null);
    return new ClientRuntimeView(profile, Set.of(), profile.failureCode(), supervisor, connector);
  }

  private static SupervisorSnapshot supervisor(
      SupervisorState state, int attempts, String failureCode) {
    return new SupervisorSnapshot(
        state, attempts, state == SupervisorState.READY ? 42L : null, failureCode);
  }
}
