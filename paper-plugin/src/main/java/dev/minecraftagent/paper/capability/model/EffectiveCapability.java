package dev.minecraftagent.paper.capability.model;

import dev.minecraftagent.paper.capability.model.CapabilityManifest.PermissionMinimum;
import dev.minecraftagent.paper.proposal.RiskLevel;
import java.util.Objects;
import java.util.Optional;

/** Approved manifest data in a registry snapshot. This type has no execution operation. */
public record EffectiveCapability(CapabilityIdentity identity, CapabilityManifest manifest) {
  public EffectiveCapability {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(manifest);
    if (!identity.id().equals(manifest.id()) || identity.version() != manifest.version()) {
      throw new IllegalArgumentException("Capability identity does not match manifest");
    }
  }

  public EffectivePolicy policy() {
    var proposalRisk =
        switch (manifest.effects().category()) {
          case READ -> RiskLevel.READ;
          case WRITE_TEMPORARY -> RiskLevel.WRITE_TEMPORARY;
          case WRITE_WORLD -> RiskLevel.WRITE_WORLD;
          case WRITE_PLAYER -> RiskLevel.WRITE_PLAYER;
          case SERVER_ADMIN -> RiskLevel.SERVER_ADMIN;
        };
    return new EffectivePolicy(
        proposalRisk,
        manifest.permission().minimum(),
        manifest.permission().node(),
        manifest.confirmation().required(),
        manifest.effects().maximumBlocks());
  }

  public record EffectivePolicy(
      RiskLevel proposalRisk,
      PermissionMinimum minimum,
      Optional<String> permissionNode,
      boolean confirmationRequired,
      Optional<Integer> maximumBlocks) {
    public EffectivePolicy {
      Objects.requireNonNull(proposalRisk);
      Objects.requireNonNull(minimum);
      permissionNode = Objects.requireNonNull(permissionNode);
      maximumBlocks = Objects.requireNonNull(maximumBlocks);
    }
  }
}
