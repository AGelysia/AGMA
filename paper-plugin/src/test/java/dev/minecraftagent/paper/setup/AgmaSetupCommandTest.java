package dev.minecraftagent.paper.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AgmaSetupCommandTest {
  @Test
  void rootCommandHasNoFrameworkPermissionThatCanHideItDuringSetup() {
    var command = command(new RecordingGateway(), Runnable::run);

    assertNull(command.getPermission());
  }

  @Test
  void authorizesOnlyConsoleOperatorsAndPlayersWithTheSetupPermission() {
    var gateway = new RecordingGateway();
    var command = command(gateway, Runnable::run);

    var consoleMessages = new ArrayList<String>();
    command.execute(console(consoleMessages), "agma", new String[] {"doctor"});
    var opMessages = new ArrayList<String>();
    command.execute(player(opMessages, true, false), "agma", new String[] {"doctor"});
    var permittedMessages = new ArrayList<String>();
    command.execute(player(permittedMessages, false, true), "agma", new String[] {"doctor"});
    var playerMessages = new ArrayList<String>();
    command.execute(player(playerMessages, false, false), "agma", new String[] {"doctor"});
    var otherMessages = new ArrayList<String>();
    command.execute(sender(otherMessages), "agma", new String[] {"doctor"});

    assertEquals(3, gateway.snapshotCalls);
    assertTrue(consoleMessages.contains("AGMA setup state: SETUP_REQUIRED"));
    assertTrue(opMessages.contains("AGMA setup state: SETUP_REQUIRED"));
    assertTrue(permittedMessages.contains("AGMA setup state: SETUP_REQUIRED"));
    assertEquals(List.of("You do not have permission to manage AGMA setup."), playerMessages);
    assertEquals(List.of("You do not have permission to manage AGMA setup."), otherMessages);
  }

  @Test
  void rejectsConfigurationAndSecretArgumentsWithoutCallingTheGatewayOrEchoingInput() {
    var gateway = new RecordingGateway();
    var command = command(gateway, Runnable::run);
    var messages = new ArrayList<String>();
    var console = console(messages);

    command.execute(console, "agma", new String[] {"setup", "sk-sensitive-value"});
    command.execute(console, "agma", new String[] {"retry", "token=sensitive-value"});
    command.execute(console, "agma", new String[] {"doctor", "https://private.invalid"});

    assertEquals(0, gateway.snapshotCalls);
    assertEquals(0, gateway.retryCalls);
    assertEquals(3, messages.size());
    assertTrue(
        messages.stream()
            .allMatch(
                message ->
                    message.equals(
                        "AGMA setup commands do not accept configuration values or secrets. Edit"
                            + " the server-side configuration file.")));
    var rendered = String.join("\n", messages);
    assertFalse(rendered.contains("sensitive-value"));
    assertFalse(rendered.contains("private.invalid"));
  }

  @Test
  void externalModePointsOnlyToTheMainPluginConfiguration() {
    var gateway = new RecordingGateway();
    gateway.snapshot = RedactedSetupSnapshot.of(SetupState.EXTERNAL);
    var messages = new ArrayList<String>();

    command(gateway, Runnable::run).execute(console(messages), "agma", new String[] {"setup"});

    assertEquals(
        List.of(
            "AGMA setup state: EXTERNAL",
            "Configuration file: plugins/AGMA/config.yml",
            "Next step: Manage the external runtime, then run /agma retry."),
        messages);
    assertFalse(String.join("\n", messages).contains("/managed/"));
  }

  @Test
  void setupAndDoctorRenderOnlyFixedRelativePathsAndValidatedDiagnostics() {
    var gateway = new RecordingGateway();
    gateway.snapshot = RedactedSetupSnapshot.failed("RUNTIME_START_FAILED");
    var command = command(gateway, Runnable::run);
    var messages = new ArrayList<String>();
    var console = console(messages);

    command.execute(console, "agma", new String[] {"setup"});
    command.execute(console, "agma", new String[] {"doctor"});

    assertEquals(
        List.of(
            "AGMA setup state: FAILED",
            "Configuration file: plugins/AGMA/managed/config.yml",
            "Next step: Check the server console, then run /agma retry.",
            "AGMA setup state: FAILED",
            "Configuration file: plugins/AGMA/managed/config.yml",
            "Diagnostic: RUNTIME_START_FAILED"),
        messages);
    assertFalse(String.join("\n", messages).contains("/home/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RedactedSetupSnapshot.failed("/home/server/config.yml has a secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RedactedSetupSnapshot(SetupState.READY, "UNEXPECTED_DETAIL"));
  }

  @Test
  void asynchronousRetryRepliesOnlyThroughTheInjectedMainThreadDispatcher() {
    var gateway = new RecordingGateway();
    var completion = new CompletableFuture<RedactedSetupSnapshot>();
    gateway.retry = completion;
    var replies = new ArrayDeque<Runnable>();
    var command = command(gateway, replies::add);
    var messages = new ArrayList<String>();

    command.execute(console(messages), "agma", new String[] {"retry"});
    assertEquals(List.of("AGMA retry started."), messages);
    assertEquals(1, gateway.retryCalls);

    completion.complete(RedactedSetupSnapshot.of(SetupState.READY));
    assertEquals(List.of("AGMA retry started."), messages);
    assertEquals(1, replies.size());

    replies.remove().run();
    assertEquals(List.of("AGMA retry started.", "AGMA is ready."), messages);
  }

  @Test
  void retryFailureNeverRendersExceptionalDetails() {
    var gateway = new RecordingGateway();
    gateway.retry =
        CompletableFuture.failedFuture(
            new IllegalStateException("api-key at /home/server/private.yml"));
    var replies = new ArrayDeque<Runnable>();
    var command = command(gateway, replies::add);
    var messages = new ArrayList<String>();

    command.execute(console(messages), "agma", new String[] {"retry"});
    replies.remove().run();

    assertEquals(
        List.of("AGMA retry started.", "AGMA retry failed. Check the server console."), messages);
  }

  @Test
  void tabCompletionIsPermissionFilteredAndBounded() {
    var command = command(new RecordingGateway(), Runnable::run);

    assertEquals(
        List.of("setup"),
        command.tabComplete(player(List.of(), true, false), "agma", new String[] {"s"}));
    assertEquals(
        List.of(), command.tabComplete(player(List.of(), false, false), "agma", new String[] {""}));
    assertEquals(
        List.of(),
        command.tabComplete(
            player(List.of(), true, false), "agma", new String[] {"setup", "secret"}));
  }

  private static AgmaSetupCommand command(
      SetupGateway gateway, java.util.function.Consumer<Runnable> dispatcher) {
    return new AgmaSetupCommand(plugin(), gateway, dispatcher);
  }

  private static Plugin plugin() {
    return (Plugin)
        Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static ConsoleCommandSender console(List<String> messages) {
    return (ConsoleCommandSender)
        Proxy.newProxyInstance(
            ConsoleCommandSender.class.getClassLoader(),
            new Class<?>[] {ConsoleCommandSender.class},
            (proxy, method, arguments) ->
                invokeSender(
                    method.getName(), method.getReturnType(), arguments, messages, true, true));
  }

  private static Player player(List<String> messages, boolean operator, boolean permission) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                invokeSender(
                    method.getName(),
                    method.getReturnType(),
                    arguments,
                    messages,
                    operator,
                    permission));
  }

  private static CommandSender sender(List<String> messages) {
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) ->
                invokeSender(
                    method.getName(), method.getReturnType(), arguments, messages, false, false));
  }

  private static Object invokeSender(
      String method,
      Class<?> returnType,
      Object[] arguments,
      List<String> messages,
      boolean operator,
      boolean permission) {
    if (method.equals("isOp")) {
      return operator;
    }
    if (method.equals("hasPermission")) {
      return permission;
    }
    if (method.equals("sendMessage") && arguments != null) {
      for (var argument : arguments) {
        if (argument instanceof String message) {
          messages.add(message);
        } else if (argument instanceof String[] batch) {
          messages.addAll(List.of(batch));
        }
      }
    }
    return defaultValue(returnType);
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

  private static final class RecordingGateway implements SetupGateway {
    private int snapshotCalls;
    private int retryCalls;
    private RedactedSetupSnapshot snapshot = RedactedSetupSnapshot.of(SetupState.SETUP_REQUIRED);
    private CompletableFuture<RedactedSetupSnapshot> retry =
        CompletableFuture.completedFuture(RedactedSetupSnapshot.of(SetupState.STARTING));

    @Override
    public RedactedSetupSnapshot snapshot() {
      snapshotCalls++;
      return snapshot;
    }

    @Override
    public CompletableFuture<RedactedSetupSnapshot> retry() {
      retryCalls++;
      return retry;
    }
  }
}
