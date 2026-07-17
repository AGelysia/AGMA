package dev.minecraftagent.paper.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class IndependentCommandRegistrationTest {
  @Test
  void registersBareAndAgmaFallbackLabelsAndRefreshesPlayers() {
    var map = new FakeCommandMap();
    var refreshes = new int[1];
    var registration = registration(map, refreshes, () -> true);
    var command = command();

    registration.register(command);

    assertTrue(registration.isRegistered());
    assertSame(command, map.getCommand("agma"));
    assertSame(command, map.getCommand("agma:agma"));
    assertEquals(1, refreshes[0]);
  }

  @Test
  void rejectsEitherLabelConflictWithoutChangingTheMap() {
    for (var label : List.of("agma", "agma:agma")) {
      var map = new FakeCommandMap();
      var existing = new StubCommand("other");
      map.known.put(label, existing);
      var registration = registration(map, new int[1], () -> true);

      var failure =
          assertThrows(CommandRegistrationFailure.class, () -> registration.register(command()));

      assertEquals("COMMAND_LABEL_CONFLICT", failure.code());
      assertSame(existing, map.getCommand(label));
      assertFalse(map.known.containsValue(command()));
    }
  }

  @Test
  void rollsBackEveryIdentityMappingWhenRegistrationReturnsFalseOrThrows() {
    for (var throwsAfterMapping : List.of(false, true)) {
      var map = new FakeCommandMap();
      map.failAfterFallbackRegistration = !throwsAfterMapping;
      map.throwAfterFallbackRegistration = throwsAfterMapping;
      var registration = registration(map, new int[1], () -> true);
      var command = command();

      var failure =
          assertThrows(CommandRegistrationFailure.class, () -> registration.register(command));

      assertEquals("COMMAND_REGISTRATION_FAILED", failure.code());
      assertFalse(map.known.containsValue(command));
      assertFalse(registration.isRegistered());
    }
  }

  @Test
  void closeUnregistersOnlyTheOwnedCommandAndRefreshesPlayers() {
    var map = new FakeCommandMap();
    var refreshes = new int[1];
    var registration = registration(map, refreshes, () -> true);
    var command = command();
    var other = new StubCommand("other");
    map.known.put("other", other);
    registration.register(command);

    registration.close();

    assertFalse(registration.isRegistered());
    assertFalse(map.known.containsValue(command));
    assertSame(other, map.getCommand("other"));
    assertEquals(2, refreshes[0]);
  }

  @Test
  void refusesRegisterAndCloseOffThePrimaryThread() {
    var primary = new boolean[] {false};
    var map = new FakeCommandMap();
    var registration = registration(map, new int[1], () -> primary[0]);

    assertThrows(IllegalStateException.class, () -> registration.register(command()));
    assertTrue(map.known.isEmpty());

    primary[0] = true;
    registration.register(command());
    primary[0] = false;
    assertThrows(IllegalStateException.class, registration::close);
    assertTrue(registration.isRegistered());
  }

  @Test
  void requiresTheRegisteredCommandNameToMatchTheLabel() {
    var map = new FakeCommandMap();
    var registration = registration(map, new int[1], () -> true);

    assertThrows(IllegalArgumentException.class, () -> registration.register(new StubCommand("x")));
    assertNull(map.getCommand("agma"));
  }

  private static IndependentCommandRegistration registration(
      FakeCommandMap map, int[] refreshes, java.util.function.BooleanSupplier primaryThread) {
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
    return new IndependentCommandRegistration(
        map, "agma", "agma", () -> List.of(player), primaryThread, ignored -> {});
  }

  private static Command command() {
    return new StubCommand("agma");
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

  private static final class FakeCommandMap implements CommandMap {
    private final Map<String, Command> known = new LinkedHashMap<>();
    private boolean failAfterFallbackRegistration;
    private boolean throwAfterFallbackRegistration;

    @Override
    public void registerAll(String fallbackPrefix, List<Command> commands) {
      commands.forEach(command -> register(command.getName(), fallbackPrefix, command));
    }

    @Override
    public boolean register(String label, String fallbackPrefix, Command command) {
      known.put(fallbackPrefix + ":" + label, command);
      command.register(this);
      if (throwAfterFallbackRegistration) {
        throw new IllegalStateException("registration failed");
      }
      if (failAfterFallbackRegistration) {
        return false;
      }
      known.put(label, command);
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
      return known;
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
