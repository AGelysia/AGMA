package dev.minecraftagent.paper.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record AgentStatus(
    AgentState state,
    DesiredMode desiredMode,
    AgentHealth health,
    OfflineReason offlineReason,
    String failureCode,
    List<String> warningCodes) {
  private static final Pattern DIAGNOSTIC_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

  public AgentStatus {
    Objects.requireNonNull(state);
    Objects.requireNonNull(desiredMode);
    Objects.requireNonNull(health);
    warningCodes = List.copyOf(warningCodes);
    validateCode(failureCode, "failureCode");
    for (var warningCode : warningCodes) {
      validateCode(Objects.requireNonNull(warningCode), "warningCode");
    }

    switch (state) {
      case UNREGISTERED -> {
        require(desiredMode == DesiredMode.ENABLED);
        require(health == AgentHealth.UNAVAILABLE);
        require(offlineReason == null);
      }
      case STARTING -> {
        require(health == AgentHealth.UNAVAILABLE);
        require(failureCode == null);
      }
      case ONLINE -> {
        require(desiredMode == DesiredMode.ENABLED);
        require(offlineReason == null);
        require(failureCode == null);
        require(
            warningCodes.isEmpty()
                ? health == AgentHealth.HEALTHY
                : health == AgentHealth.DEGRADED);
      }
      case STOPPING -> {
        require(desiredMode == DesiredMode.DISABLED);
        require(health == AgentHealth.UNAVAILABLE);
        require(offlineReason == OfflineReason.MANUAL);
        require(failureCode == null);
      }
      case OFFLINE -> {
        require(health == AgentHealth.UNAVAILABLE);
        require(offlineReason != null);
        if (offlineReason == OfflineReason.MANUAL) {
          require(desiredMode == DesiredMode.DISABLED);
        }
      }
    }
  }

  public static AgentStatus unregistered(String failureCode) {
    return new AgentStatus(
        AgentState.UNREGISTERED,
        DesiredMode.ENABLED,
        AgentHealth.UNAVAILABLE,
        null,
        failureCode,
        List.of());
  }

  public static AgentStatus starting(DesiredMode desiredMode) {
    return starting(desiredMode, null, List.of());
  }

  public static AgentStatus starting(
      DesiredMode desiredMode, OfflineReason offlineReason, List<String> warningCodes) {
    return new AgentStatus(
        AgentState.STARTING,
        desiredMode,
        AgentHealth.UNAVAILABLE,
        offlineReason,
        null,
        warningCodes);
  }

  public static AgentStatus online(List<String> warningCodes) {
    return online(DesiredMode.ENABLED, warningCodes);
  }

  public static AgentStatus online(DesiredMode desiredMode, List<String> warningCodes) {
    return new AgentStatus(
        AgentState.ONLINE,
        desiredMode,
        warningCodes.isEmpty() ? AgentHealth.HEALTHY : AgentHealth.DEGRADED,
        null,
        null,
        warningCodes);
  }

  public static AgentStatus stopping() {
    return new AgentStatus(
        AgentState.STOPPING,
        DesiredMode.DISABLED,
        AgentHealth.UNAVAILABLE,
        OfflineReason.MANUAL,
        null,
        List.of());
  }

  public static AgentStatus offline(
      DesiredMode desiredMode,
      OfflineReason offlineReason,
      String failureCode,
      List<String> warningCodes) {
    return new AgentStatus(
        AgentState.OFFLINE,
        desiredMode,
        AgentHealth.UNAVAILABLE,
        offlineReason,
        failureCode,
        warningCodes);
  }

  private static void validateCode(String code, String field) {
    if (code != null && !DIAGNOSTIC_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException(field + " must be a stable diagnostic code");
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Invalid agent status");
    }
  }
}
