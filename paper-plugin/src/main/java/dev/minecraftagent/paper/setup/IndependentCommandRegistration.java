package dev.minecraftagent.paper.setup;

import dev.minecraftagent.paper.command.CommandRegistrationFailure;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

/** Registers one dynamic command without relying on the readiness-gated /agent command. */
public final class IndependentCommandRegistration implements AutoCloseable {
  private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  private final CommandMap commandMap;
  private final String label;
  private final String fallbackPrefix;
  private final String namespacedLabel;
  private final Supplier<? extends Collection<? extends Player>> onlinePlayers;
  private final BooleanSupplier primaryThread;
  private final Consumer<String> warningSink;
  private Command registeredCommand;

  public IndependentCommandRegistration(
      CommandMap commandMap,
      String label,
      String fallbackPrefix,
      Supplier<? extends Collection<? extends Player>> onlinePlayers,
      BooleanSupplier primaryThread,
      Consumer<String> warningSink) {
    this.commandMap = Objects.requireNonNull(commandMap);
    this.label = requireLabel(label, "label");
    this.fallbackPrefix = requireLabel(fallbackPrefix, "fallbackPrefix");
    this.namespacedLabel = this.fallbackPrefix + ":" + this.label;
    this.onlinePlayers = Objects.requireNonNull(onlinePlayers);
    this.primaryThread = Objects.requireNonNull(primaryThread);
    this.warningSink = Objects.requireNonNull(warningSink);
  }

  public void register(Command command) {
    requirePrimaryThread();
    Objects.requireNonNull(command);
    if (!label.equals(command.getName())) {
      throw new IllegalArgumentException("Command name does not match the registration label");
    }
    if (registeredCommand != null) {
      throw new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED");
    }
    if (commandMap.getCommand(label) != null || commandMap.getCommand(namespacedLabel) != null) {
      throw new CommandRegistrationFailure("COMMAND_LABEL_CONFLICT");
    }

    try {
      var registered = commandMap.register(label, fallbackPrefix, command);
      if (!registered
          || commandMap.getCommand(label) != command
          || commandMap.getCommand(namespacedLabel) != command) {
        rollback(command);
        throw new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED");
      }
    } catch (CommandRegistrationFailure failure) {
      throw failure;
    } catch (RuntimeException error) {
      rollback(command);
      throw new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED");
    }

    registeredCommand = command;
    refreshOnlinePlayers();
  }

  public void unregister() {
    requirePrimaryThread();
    var command = registeredCommand;
    registeredCommand = null;
    if (command == null) {
      return;
    }
    removeIdentityMappings(command);
    command.unregister(commandMap);
    refreshOnlinePlayers();
  }

  public boolean isRegistered() {
    return registeredCommand != null;
  }

  @Override
  public void close() {
    unregister();
  }

  private void rollback(Command command) {
    try {
      removeIdentityMappings(command);
    } catch (RuntimeException ignored) {
      warningSink.accept("COMMAND_ROLLBACK_FAILED");
    }
    try {
      command.unregister(commandMap);
    } catch (RuntimeException ignored) {
      warningSink.accept("COMMAND_ROLLBACK_FAILED");
    }
  }

  private void removeIdentityMappings(Command command) {
    var knownCommands = commandMap.getKnownCommands();
    var ownedLabels =
        knownCommands.entrySet().stream()
            .filter(entry -> entry.getValue() == command)
            .map(java.util.Map.Entry::getKey)
            .toList();
    for (var ownedLabel : ownedLabels) {
      knownCommands.remove(ownedLabel, command);
    }
  }

  private void refreshOnlinePlayers() {
    Collection<? extends Player> players;
    try {
      players = onlinePlayers.get();
    } catch (RuntimeException error) {
      warningSink.accept("COMMAND_TREE_REFRESH_FAILED");
      return;
    }
    if (players == null) {
      warningSink.accept("COMMAND_TREE_REFRESH_FAILED");
      return;
    }
    for (var player : players) {
      try {
        player.updateCommands();
      } catch (RuntimeException error) {
        warningSink.accept("COMMAND_TREE_REFRESH_FAILED");
      }
    }
  }

  private void requirePrimaryThread() {
    if (!primaryThread.getAsBoolean()) {
      throw new IllegalStateException("Command registration requires the primary thread");
    }
  }

  private static String requireLabel(String value, String name) {
    Objects.requireNonNull(value);
    if (!LABEL.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }
}
