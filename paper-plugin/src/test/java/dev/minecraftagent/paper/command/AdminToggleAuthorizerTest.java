package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AdminToggleAuthorizerTest {
  private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void trustsOnlyTheLocalConsoleBySenderType() {
    var authorizer = new AdminToggleAuthorizer(AdminPolicy::locked);

    assertTrue(authorizer.canToggle(sender(ConsoleCommandSender.class)));
    assertFalse(authorizer.canToggle(sender(CommandSender.class)));
  }

  @Test
  void configuredOwnerDoesNotNeedOperatorStatusOrAPermissionGrant() {
    var authorizer = new AdminToggleAuthorizer(() -> new AdminPolicy(Set.of(OWNER), false));

    assertTrue(authorizer.canToggle(player(OWNER, false, false)));
    assertFalse(authorizer.canToggle(player(OTHER, true, true)));
  }

  @Test
  void optionalOperatorToggleRequiresLiveOpAndTheDedicatedPermission() {
    var authorizer = new AdminToggleAuthorizer(() -> new AdminPolicy(Set.of(), true));

    assertTrue(authorizer.canToggle(player(OTHER, true, true)));
    assertFalse(authorizer.canToggle(player(OTHER, true, false)));
    assertFalse(authorizer.canToggle(player(OTHER, false, true)));
  }

  private static Player player(UUID playerId, boolean operator, boolean permission) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> playerId;
                  case "isOp" -> operator;
                  case "hasPermission" -> permission;
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static CommandSender sender(Class<? extends CommandSender> type) {
    return (CommandSender)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class || type == short.class || type == int.class || type == long.class) {
      return 0;
    }
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    return null;
  }
}
