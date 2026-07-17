package dev.minecraftagent.paper.startup;

import java.util.Objects;

public record CoreToolDescriptor(
    String id, AccessMode accessMode, boolean schemaClosed, boolean executionCapable) {
  public enum AccessMode {
    READ,
    WRITE
  }

  public CoreToolDescriptor {
    Objects.requireNonNull(id);
    Objects.requireNonNull(accessMode);
  }
}
