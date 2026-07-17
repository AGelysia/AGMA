package dev.minecraftagent.paper.proposal;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies the configured Owner policy and live Bukkit-backed player checks. */
public final class ProposalAuthorizer {
  public enum WriteAccess {
    OP,
    OWNER
  }

  public record Policy(Set<UUID> owners, WriteAccess worldWrite, WriteAccess playerWrite) {
    public Policy {
      owners = Set.copyOf(owners);
      Objects.requireNonNull(worldWrite);
      Objects.requireNonNull(playerWrite);
    }
  }

  public interface LivePlayerPolicy {
    boolean isOnline(UUID playerUuid);

    boolean isOperator(UUID playerUuid);

    boolean hasPermission(UUID playerUuid, String permission);
  }

  public record Decision(boolean allowed, String code) {
    public Decision {
      Objects.requireNonNull(code);
      if (allowed && !"AUTHORIZED".equals(code)) {
        throw new IllegalArgumentException("Allowed authorization must use AUTHORIZED");
      }
    }

    public static Decision authorized() {
      return new Decision(true, "AUTHORIZED");
    }

    public static Decision rejected(String code) {
      return new Decision(false, code);
    }
  }

  private final Supplier<Policy> policy;
  private final LivePlayerPolicy livePlayers;

  public ProposalAuthorizer(Supplier<Policy> policy, LivePlayerPolicy livePlayers) {
    this.policy = Objects.requireNonNull(policy);
    this.livePlayers = Objects.requireNonNull(livePlayers);
  }

  public Decision authorize(UUID playerUuid, RiskLevel risk, String requiredPermission) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(risk);
    Objects.requireNonNull(requiredPermission);
    try {
      return authorizeLive(playerUuid, risk, requiredPermission);
    } catch (RuntimeException error) {
      return Decision.rejected("AUTHORIZATION_UNAVAILABLE");
    }
  }

  private Decision authorizeLive(UUID playerUuid, RiskLevel risk, String requiredPermission) {
    if (!livePlayers.isOnline(playerUuid)) {
      return Decision.rejected("PLAYER_OFFLINE");
    }
    var current = Objects.requireNonNull(policy.get());
    return switch (risk) {
      case READ -> Decision.rejected("PROPOSAL_NOT_REQUIRED");
      case WRITE_TEMPORARY ->
          livePlayers.hasPermission(playerUuid, requiredPermission)
              ? Decision.authorized()
              : Decision.rejected("PERMISSION_REQUIRED");
      case WRITE_WORLD, WRITE_PLAYER -> {
        if (!livePlayers.isOperator(playerUuid)) {
          yield Decision.rejected("OP_REQUIRED");
        }
        var access = risk == RiskLevel.WRITE_WORLD ? current.worldWrite() : current.playerWrite();
        if (access == WriteAccess.OWNER && !current.owners().contains(playerUuid)) {
          yield Decision.rejected("OWNER_REQUIRED");
        }
        yield livePlayers.hasPermission(playerUuid, requiredPermission)
            ? Decision.authorized()
            : Decision.rejected("PERMISSION_REQUIRED");
      }
      case SERVER_ADMIN -> {
        if (!current.owners().contains(playerUuid)) {
          yield Decision.rejected("OWNER_REQUIRED");
        }
        yield livePlayers.hasPermission(playerUuid, requiredPermission)
            ? Decision.authorized()
            : Decision.rejected("PERMISSION_REQUIRED");
      }
    };
  }
}
