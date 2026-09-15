package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ClientRuntimeStateTextTest {
  @Test
  void mapsEveryLifecycleStateToAStandaloneRuntimeKey() {
    assertEquals(
        "screen.agma_standalone.runtime_unconfigured",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.UNCONFIGURED));
    assertEquals(
        "screen.agma_standalone.runtime_stopped_state",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.STOPPED));
    assertEquals(
        "screen.agma_standalone.runtime_starting_state",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.STARTING));
    assertEquals(
        "screen.agma_standalone.runtime_running_state",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.READY));
    assertEquals(
        "screen.agma_standalone.runtime_stopping_state",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.STOPPING));
    assertEquals(
        "screen.agma_standalone.runtime_error_state",
        ClientRuntimeStateText.stateKey(ClientLifecycleState.ERROR));
  }

  @Test
  void mapsStartupFailuresToLocalizedKeys() {
    assertEquals(
        "screen.agma_standalone.runtime_start_failed",
        ClientRuntimeStateText.startupFailureKey(null));
    assertEquals(
        "screen.agma_standalone.runtime_start_cancelled",
        ClientRuntimeStateText.startupFailureKey("RUNTIME_START_CANCELLED"));
    assertEquals(
        "screen.agma_standalone.runtime_start_failed_code",
        ClientRuntimeStateText.startupFailureKey("RUNTIME_START_FAILED"));
    assertEquals(
        "screen.agma_standalone.runtime_start_failed_code",
        ClientRuntimeStateText.startupFailureKey("RUNTIME_NOT_CONFIGURED"));
  }
}
