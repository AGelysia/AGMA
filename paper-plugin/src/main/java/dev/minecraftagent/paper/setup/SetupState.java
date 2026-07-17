package dev.minecraftagent.paper.setup;

/** Redacted lifecycle states exposed by the setup command. */
public enum SetupState {
  SETUP_REQUIRED,
  INSTALLING,
  STARTING,
  READY,
  FAILED,
  EXTERNAL
}
