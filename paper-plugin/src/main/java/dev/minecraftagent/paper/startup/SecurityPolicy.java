package dev.minecraftagent.paper.startup;

import java.util.Objects;

public record SecurityPolicy(
    int policyVersion,
    AccessLevel worldWrite,
    AccessLevel playerWrite,
    AccessLevel serverAdmin,
    boolean allowOpToggle) {
  public enum AccessLevel {
    OP,
    OWNER
  }

  public SecurityPolicy {
    Objects.requireNonNull(worldWrite);
    Objects.requireNonNull(playerWrite);
    Objects.requireNonNull(serverAdmin);
  }

  public void validate() throws StartupFailure {
    if (policyVersion != 1
        || serverAdmin != AccessLevel.OWNER
        || !isWriteAccessRestricted(worldWrite)
        || !isWriteAccessRestricted(playerWrite)) {
      throw new StartupFailure(
          StartupFailure.Code.CORE_POLICY_INVALID, StartupFailure.Stage.SECURITY_POLICY);
    }
  }

  private static boolean isWriteAccessRestricted(AccessLevel accessLevel) {
    return accessLevel == AccessLevel.OP || accessLevel == AccessLevel.OWNER;
  }
}
