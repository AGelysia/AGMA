package dev.minecraftagent.standalone.supervisor;

import java.util.Objects;

/** Secret-free lifecycle state suitable for a game UI. */
public record SupervisorSnapshot(
    SupervisorState state, int restartAttempts, Long ownedPid, String failureCode) {
  public SupervisorSnapshot {
    Objects.requireNonNull(state, "state");
    if (restartAttempts < 0 || (ownedPid != null && ownedPid < 1)) {
      throw new IllegalArgumentException("Supervisor snapshot is invalid");
    }
    if ((state == SupervisorState.ERROR) != (failureCode != null)) {
      throw new IllegalArgumentException("Only an error snapshot carries a failure code");
    }
  }
}
