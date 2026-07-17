package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class OwnerReloadAuthorizerTest {
  private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void consoleAndConfiguredOwnerAreAuthorizedButAnOpPermissionIsInsufficient() {
    var authorizer = new OwnerReloadAuthorizer(() -> new AdminPolicy(Set.of(OWNER), true));

    assertTrue(authorizer.canReload(console()));
    assertTrue(authorizer.canReload(player(OWNER, false, false)));
    assertFalse(authorizer.canReload(player(OPERATOR, true, true)));
  }

  private static ConsoleCommandSender console() {
    return (ConsoleCommandSender)
        Proxy.newProxyInstance(
            ConsoleCommandSender.class.getClassLoader(),
            new Class<?>[] {ConsoleCommandSender.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static Player player(UUID playerId, boolean operator, boolean reloadPermission) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getUniqueId")) {
                return playerId;
              }
              if (method.getName().equals("isOp")) {
                return operator;
              }
              if (method.getName().equals("hasPermission")) {
                return reloadPermission;
              }
              return defaultValue(method.getReturnType());
            });
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
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    return 0;
  }
}
