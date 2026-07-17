package dev.minecraftagent.paper.capability.load;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;

/** Pure Java owner-approval lookup. Implementations must match the complete key exactly. */
@FunctionalInterface
public interface CapabilityApprovalStore {
  boolean isApproved(CapabilityApproval approval);
}
