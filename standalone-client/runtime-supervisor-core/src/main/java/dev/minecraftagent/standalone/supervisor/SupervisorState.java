package dev.minecraftagent.standalone.supervisor;

/** Lifecycle states visible to the game client. */
public enum SupervisorState {
  UNCONFIGURED,
  STOPPED,
  STARTING,
  READY,
  STOPPING,
  ERROR
}
