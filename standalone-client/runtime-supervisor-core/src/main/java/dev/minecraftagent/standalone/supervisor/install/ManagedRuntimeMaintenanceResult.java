package dev.minecraftagent.standalone.supervisor.install;

/** Bounded maintenance outcome without exposing private installation paths. */
public record ManagedRuntimeMaintenanceResult(int stagingDirectoriesRemoved, int versionsRemoved) {
  public ManagedRuntimeMaintenanceResult {
    if (stagingDirectoriesRemoved < 0 || versionsRemoved < 0) {
      throw new IllegalArgumentException("maintenance counts must be non-negative");
    }
  }
}
