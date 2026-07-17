package dev.minecraftagent.paper.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStatusTest {
  @Test
  void factoriesKeepOperationalStateSeparateFromHealth() {
    var unregistered = AgentStatus.unregistered("RUNTIME_UNREACHABLE");
    assertEquals(AgentState.UNREGISTERED, unregistered.state());
    assertEquals(DesiredMode.ENABLED, unregistered.desiredMode());
    assertEquals(AgentHealth.UNAVAILABLE, unregistered.health());
    assertEquals("RUNTIME_UNREACHABLE", unregistered.failureCode());

    var starting =
        AgentStatus.starting(
            DesiredMode.DISABLED, OfflineReason.MANUAL, List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));
    assertEquals(AgentState.STARTING, starting.state());
    assertEquals(OfflineReason.MANUAL, starting.offlineReason());

    var healthy = AgentStatus.online(List.of());
    assertEquals(AgentHealth.HEALTHY, healthy.health());
    assertNull(healthy.offlineReason());

    var degraded = AgentStatus.online(List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));
    assertEquals(AgentState.ONLINE, degraded.state());
    assertEquals(AgentHealth.DEGRADED, degraded.health());

    var stopping = AgentStatus.stopping();
    assertEquals(AgentState.STOPPING, stopping.state());
    assertEquals(DesiredMode.DISABLED, stopping.desiredMode());
    assertEquals(OfflineReason.MANUAL, stopping.offlineReason());

    var automaticOffline =
        AgentStatus.offline(
            DesiredMode.ENABLED,
            OfflineReason.RUNTIME_UNAVAILABLE,
            "RUNTIME_CONNECTION_LOST",
            List.of());
    assertEquals(AgentState.OFFLINE, automaticOffline.state());
    assertEquals(DesiredMode.ENABLED, automaticOffline.desiredMode());
    assertEquals(AgentHealth.UNAVAILABLE, automaticOffline.health());
  }

  @Test
  void copiesWarningCodesAndExposesAnImmutableSnapshot() {
    var warnings = new ArrayList<>(List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));
    var status = AgentStatus.online(warnings);

    warnings.clear();

    assertEquals(List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"), status.warningCodes());
    assertThrows(
        UnsupportedOperationException.class,
        () -> status.warningCodes().add("COMMAND_TREE_REFRESH_FAILED"));
  }

  @Test
  void rejectsContradictoryStatusCombinationsAndUnsafeDiagnosticCodes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentStatus(
                AgentState.ONLINE,
                DesiredMode.DISABLED,
                AgentHealth.HEALTHY,
                null,
                null,
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentStatus(
                AgentState.ONLINE,
                DesiredMode.ENABLED,
                AgentHealth.HEALTHY,
                null,
                null,
                List.of("OPTIONAL_CAPABILITY_UNAVAILABLE")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentStatus(
                AgentState.OFFLINE,
                DesiredMode.ENABLED,
                AgentHealth.UNAVAILABLE,
                null,
                null,
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentStatus.offline(DesiredMode.ENABLED, OfflineReason.MANUAL, null, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> AgentStatus.unregistered("unsafe diagnostic"));
    assertThrows(
        IllegalArgumentException.class, () -> AgentStatus.online(List.of("unsafe-warning")));
  }
}
