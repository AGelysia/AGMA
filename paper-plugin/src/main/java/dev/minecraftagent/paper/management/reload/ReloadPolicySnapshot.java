package dev.minecraftagent.paper.management.reload;

import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import dev.minecraftagent.paper.proposal.ProposalAuthorizer;
import dev.minecraftagent.paper.startup.SecurityPolicy;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One immutable generation of every policy that the first live reload can replace. */
public final class ReloadPolicySnapshot {
  private final long generation;
  private final Set<UUID> owners;
  private final SecurityPolicy securityPolicy;

  ReloadPolicySnapshot(long generation, Set<UUID> owners, SecurityPolicy securityPolicy) {
    if (generation < 0) {
      throw new IllegalArgumentException("Invalid reload generation");
    }
    this.generation = generation;
    this.owners = Set.copyOf(owners);
    this.securityPolicy = Objects.requireNonNull(securityPolicy);
  }

  public long generation() {
    return generation;
  }

  public Set<UUID> owners() {
    return owners;
  }

  public SecurityPolicy securityPolicy() {
    return securityPolicy;
  }

  public AdminPolicy adminPolicy() {
    return new AdminPolicy(owners, securityPolicy.allowOpToggle());
  }

  public ProposalAuthorizer.Policy proposalPolicy() {
    return new ProposalAuthorizer.Policy(
        owners,
        writeAccess(securityPolicy.worldWrite()),
        writeAccess(securityPolicy.playerWrite()));
  }

  private static ProposalAuthorizer.WriteAccess writeAccess(
      SecurityPolicy.AccessLevel accessLevel) {
    return switch (accessLevel) {
      case OP -> ProposalAuthorizer.WriteAccess.OP;
      case OWNER -> ProposalAuthorizer.WriteAccess.OWNER;
    };
  }

  @Override
  public String toString() {
    return "ReloadPolicySnapshot[generation="
        + generation
        + ", ownersCount="
        + owners.size()
        + ", securityPolicy="
        + securityPolicy
        + "]";
  }
}
