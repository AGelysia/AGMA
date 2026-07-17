package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.DesiredMode;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperCommandRegistrationTest {
  @Test
  void registersBothLabelsAndRefreshesOnlinePlayers() {
    var map = new FakeCommandMap();
    var refreshes = new int[1];
    var registration = registration(map, refreshes);
    var command = command();

    registration.register(command);

    assertTrue(registration.isRegistered());
    assertSame(command, map.getCommand("agent"));
    assertSame(command, map.getCommand("minecraftagent:agent"));
    assertEquals(1, refreshes[0]);
  }

  @Test
  void rejectsABareLabelConflictWithoutChangingTheMap() {
    var map = new FakeCommandMap();
    var existing = new StubCommand("agent");
    map.known.put("agent", existing);
    var registration = registration(map, new int[1]);

    var failure =
        assertThrows(CommandRegistrationFailure.class, () -> registration.register(command()));

    assertEquals("COMMAND_LABEL_CONFLICT", failure.code());
    assertSame(existing, map.getCommand("agent"));
    assertNull(map.getCommand("minecraftagent:agent"));
  }

  @Test
  void rejectsANamespacedConflictBeforePaperCanOverwriteIt() {
    var map = new FakeCommandMap();
    var existing = new StubCommand("agent");
    map.known.put("minecraftagent:agent", existing);
    var registration = registration(map, new int[1]);

    var failure =
        assertThrows(CommandRegistrationFailure.class, () -> registration.register(command()));

    assertEquals("COMMAND_LABEL_CONFLICT", failure.code());
    assertSame(existing, map.getCommand("minecraftagent:agent"));
    assertNull(map.getCommand("agent"));
  }

  @Test
  void rollsBackEveryIdentityMappingWhenPaperReturnsFalse() {
    var map = new FakeCommandMap();
    map.failAfterFallbackRegistration = true;
    var registration = registration(map, new int[1]);
    var command = command();

    var failure =
        assertThrows(CommandRegistrationFailure.class, () -> registration.register(command));

    assertEquals("COMMAND_REGISTRATION_FAILED", failure.code());
    assertFalse(map.known.containsValue(command));
    assertFalse(registration.isRegistered());
  }

  @Test
  void unregisterRemovesOnlyThisPluginsMappingsAndRefreshesPlayers() {
    var map = new FakeCommandMap();
    map.paperLikeEntrySet = true;
    var refreshes = new int[1];
    var registration = registration(map, refreshes);
    var command = command();
    var other = new StubCommand("other");
    map.known.put("other", other);
    registration.register(command);

    registration.unregister();

    assertFalse(registration.isRegistered());
    assertFalse(map.known.containsValue(command));
    assertSame(other, map.getCommand("other"));
    assertEquals(2, refreshes[0]);
  }

  @Test
  void refusesToTouchTheCommandMapOffThePrimaryThread() {
    var map = new FakeCommandMap();
    var registration = new PaperCommandRegistration(map, List::of, () -> false, ignored -> {});

    assertThrows(IllegalStateException.class, () -> registration.register(command()));
    assertTrue(map.known.isEmpty());
  }

  private static PaperCommandRegistration registration(FakeCommandMap map, int[] refreshes) {
    Player player =
        (Player)
            Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("updateCommands")) {
                    refreshes[0]++;
                  }
                  return defaultValue(method.getReturnType());
                });
    return new PaperCommandRegistration(map, () -> List.of(player), () -> true, ignored -> {});
  }

  private static AgentCommand command() {
    Plugin plugin =
        (Plugin)
            Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    return new AgentCommand(
        plugin,
        () ->
            new AgentStatus(
                AgentState.ONLINE, DesiredMode.ENABLED, AgentHealth.HEALTHY, null, null, List.of()),
        new AgentControl() {
          @Override
          public void turnOff() {}

          @Override
          public RecoveryRequest turnOn() {
            return new RecoveryRequest(
                RecoveryDisposition.ALREADY_ONLINE, CompletableFuture.completedFuture(true));
          }
        },
        ignored -> true,
        Runnable::run);
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

  private static final class FakeCommandMap implements CommandMap {
    private final Map<String, Command> known = new LinkedHashMap<>();
    private boolean failAfterFallbackRegistration;
    private boolean paperLikeEntrySet;

    @Override
    public void registerAll(String fallbackPrefix, List<Command> commands) {
      commands.forEach(command -> register(command.getName(), fallbackPrefix, command));
    }

    @Override
    public boolean register(String label, String fallbackPrefix, Command command) {
      known.put(fallbackPrefix + ":" + label, command);
      if (failAfterFallbackRegistration) {
        return false;
      }
      known.put(label, command);
      command.register(this);
      return true;
    }

    @Override
    public boolean register(String fallbackPrefix, Command command) {
      return register(command.getName(), fallbackPrefix, command);
    }

    @Override
    public boolean dispatch(CommandSender sender, String commandLine) throws CommandException {
      return false;
    }

    @Override
    public void clearCommands() {
      known.clear();
    }

    @Override
    public Command getCommand(String name) {
      return known.get(name);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String commandLine)
        throws IllegalArgumentException {
      return List.of();
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String commandLine, Location location)
        throws IllegalArgumentException {
      return List.of();
    }

    @Override
    public Map<String, Command> getKnownCommands() {
      if (!paperLikeEntrySet) {
        return known;
      }
      return new java.util.AbstractMap<>() {
        @Override
        public java.util.Set<Map.Entry<String, Command>> entrySet() {
          return java.util.Collections.unmodifiableMap(known).entrySet();
        }

        @Override
        public Command get(Object key) {
          return known.get(key);
        }

        @Override
        public Command remove(Object key) {
          return known.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
          return known.remove(key, value);
        }
      };
    }
  }

  private static final class StubCommand extends Command {
    private StubCommand(String name) {
      super(name);
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] arguments) {
      return true;
    }
  }
}
