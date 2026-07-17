package dev.minecraftagent.paper.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProposalAuthorizerTest {
  private static final String PERMISSION = "minecraftagent.write.world";
  private final UUID owner = UUID.randomUUID();
  private final MutablePlayers players = new MutablePlayers();
  private final AtomicReference<ProposalAuthorizer.Policy> policy =
      new AtomicReference<>(
          new ProposalAuthorizer.Policy(
              Set.of(owner), ProposalAuthorizer.WriteAccess.OP, ProposalAuthorizer.WriteAccess.OP));
  private final ProposalAuthorizer authorizer = new ProposalAuthorizer(policy::get, players);

  @Test
  void worldAndPlayerWritesAlwaysRequireLiveOpAndPermissionEvenForOwner() {
    players.online.add(owner);
    players.permissions.add(owner);

    assertEquals(
        "OP_REQUIRED", authorizer.authorize(owner, RiskLevel.WRITE_WORLD, PERMISSION).code());

    players.operators.add(owner);
    assertTrue(authorizer.authorize(owner, RiskLevel.WRITE_WORLD, PERMISSION).allowed());
    assertTrue(authorizer.authorize(owner, RiskLevel.WRITE_PLAYER, PERMISSION).allowed());

    players.permissions.clear();
    assertEquals(
        "PERMISSION_REQUIRED",
        authorizer.authorize(owner, RiskLevel.WRITE_WORLD, PERMISSION).code());
  }

  @Test
  void ownerRestrictedWriteUsesTheLatestPolicySnapshot() {
    var ordinaryOp = UUID.randomUUID();
    players.online.add(ordinaryOp);
    players.operators.add(ordinaryOp);
    players.permissions.add(ordinaryOp);
    assertTrue(authorizer.authorize(ordinaryOp, RiskLevel.WRITE_WORLD, PERMISSION).allowed());

    policy.set(
        new ProposalAuthorizer.Policy(
            Set.of(owner),
            ProposalAuthorizer.WriteAccess.OWNER,
            ProposalAuthorizer.WriteAccess.OP));
    assertEquals(
        "OWNER_REQUIRED",
        authorizer.authorize(ordinaryOp, RiskLevel.WRITE_WORLD, PERMISSION).code());
  }

  @Test
  void serverAdminIsOwnerOnlyAndStillRequiresThePlayerToBeOnline() {
    players.online.add(owner);
    players.permissions.add(owner);
    assertTrue(
        authorizer.authorize(owner, RiskLevel.SERVER_ADMIN, "minecraftagent.admin").allowed());

    var ordinaryOp = UUID.randomUUID();
    players.online.add(ordinaryOp);
    players.operators.add(ordinaryOp);
    players.permissions.add(ordinaryOp);
    assertEquals(
        "OWNER_REQUIRED",
        authorizer.authorize(ordinaryOp, RiskLevel.SERVER_ADMIN, "minecraftagent.admin").code());

    players.online.remove(owner);
    assertEquals(
        "PLAYER_OFFLINE",
        authorizer.authorize(owner, RiskLevel.SERVER_ADMIN, "minecraftagent.admin").code());
  }

  private static final class MutablePlayers implements ProposalAuthorizer.LivePlayerPolicy {
    private final Set<UUID> online = new HashSet<>();
    private final Set<UUID> operators = new HashSet<>();
    private final Set<UUID> permissions = new HashSet<>();

    @Override
    public boolean isOnline(UUID playerUuid) {
      return online.contains(playerUuid);
    }

    @Override
    public boolean isOperator(UUID playerUuid) {
      return operators.contains(playerUuid);
    }

    @Override
    public boolean hasPermission(UUID playerUuid, String permission) {
      return permissions.contains(playerUuid);
    }
  }
}
