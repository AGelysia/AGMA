package dev.minecraftagent.paper.capability.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CapabilityIdentityTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void identityAndApprovalValidateTheirCompleteTrustBoundaryKey() {
    assertDoesNotThrow(() -> new CapabilityIdentity("worldedit.undo", 1, HASH));
    assertDoesNotThrow(() -> new CapabilityApproval("worldedit.undo", Integer.MAX_VALUE, HASH));

    assertThrows(IllegalArgumentException.class, () -> new CapabilityIdentity("undo", 1, HASH));
    assertThrows(
        IllegalArgumentException.class, () -> new CapabilityIdentity("WorldEdit.undo", 1, HASH));
    assertThrows(
        IllegalArgumentException.class, () -> new CapabilityIdentity("worldedit.undo", 0, HASH));
    assertThrows(
        IllegalArgumentException.class, () -> new CapabilityApproval("worldedit.undo", 0, HASH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityApproval("worldedit.undo", 1, "A".repeat(64)));
  }
}
