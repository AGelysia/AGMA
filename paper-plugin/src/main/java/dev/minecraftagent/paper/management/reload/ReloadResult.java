package dev.minecraftagent.paper.management.reload;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.util.Objects;
import java.util.Optional;

/** Stable, value-free outcome codes for the management command and audit log. */
public record ReloadResult(
    Status status,
    Code code,
    ReloadPolicySnapshot snapshot,
    Optional<StartupFailure.Code> configFailure) {
  public enum Status {
    APPLIED,
    UNCHANGED,
    REJECTED,
    STALE
  }

  public enum Code {
    RELOAD_APPLIED,
    RELOAD_UNCHANGED,
    RELOAD_IN_PROGRESS,
    RELOAD_MANAGER_CLOSED,
    RELOAD_OPERATION_NOT_ONLINE,
    RELOAD_WORKER_REJECTED,
    RELOAD_CONFIG_REJECTED,
    RELOAD_CANDIDATE_LOAD_FAILED,
    RELOAD_RESTART_REQUIRED_SERVER_ID,
    RELOAD_RESTART_REQUIRED_RUNTIME_MODE,
    RELOAD_RESTART_REQUIRED_RUNTIME_ENDPOINT,
    RELOAD_RESTART_REQUIRED_RUNTIME_TOKEN,
    RELOAD_RESTART_REQUIRED_RUNTIME_CONNECT_TIMEOUT,
    RELOAD_RESTART_REQUIRED_RUNTIME_HANDSHAKE_TIMEOUT,
    RELOAD_RESTART_REQUIRED_STATE_DIRECTORY,
    RELOAD_RESTART_REQUIRED_CAPABILITY_DIRECTORY,
    RELOAD_RESTART_REQUIRED_CAPABILITY_APPROVALS,
    RELOAD_GENERATION_EXHAUSTED,
    RELOAD_STALE_COMPLETION
  }

  public ReloadResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(code);
    Objects.requireNonNull(snapshot);
    configFailure = Objects.requireNonNull(configFailure);
    if ((code == Code.RELOAD_CONFIG_REJECTED) != configFailure.isPresent()) {
      throw new IllegalArgumentException("Config failure is only valid for rejected config");
    }
    var statusMatches =
        switch (status) {
          case APPLIED -> code == Code.RELOAD_APPLIED;
          case UNCHANGED -> code == Code.RELOAD_UNCHANGED;
          case STALE -> code == Code.RELOAD_STALE_COMPLETION;
          case REJECTED ->
              code != Code.RELOAD_APPLIED
                  && code != Code.RELOAD_UNCHANGED
                  && code != Code.RELOAD_STALE_COMPLETION;
        };
    if (!statusMatches) {
      throw new IllegalArgumentException("Reload status and code do not match");
    }
  }

  public long generation() {
    return snapshot.generation();
  }
}
