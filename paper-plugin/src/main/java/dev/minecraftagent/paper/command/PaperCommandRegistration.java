package dev.minecraftagent.paper.command;

import java.util.Collection;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

public final class PaperCommandRegistration {
  static final String LABEL = "agent";
  static final String NAMESPACED_LABEL = "minecraftagent:agent";

  private final CommandMap commandMap;
  private final Supplier<? extends Collection<? extends Player>> onlinePlayers;
  private final BooleanSupplier primaryThread;
  private final Consumer<String> warningSink;
  private AgentCommand registeredCommand;

  public PaperCommandRegistration(
      CommandMap commandMap,
      Supplier<? extends Collection<? extends Player>> onlinePlayers,
      BooleanSupplier primaryThread,
      Consumer<String> warningSink) {
    this.commandMap = Objects.requireNonNull(commandMap);
    this.onlinePlayers = Objects.requireNonNull(onlinePlayers);
    this.primaryThread = Objects.requireNonNull(primaryThread);
    this.warningSink = Objects.requireNonNull(warningSink);
  }

  public void register(AgentCommand command) {
    requirePrimaryThread();
    if (registeredCommand != null) {
      throw new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED");
    }
    if (commandMap.getCommand(LABEL) != null || commandMap.getCommand(NAMESPACED_LABEL) != null) {
      throw new CommandRegistrationFailure("COMMAND_LABEL_CONFLICT");
    }

    boolean registered;
    try {
      registered = commandMap.register(LABEL, "minecraftagent", command);
    } catch (RuntimeException error) {
      removeIdentityMappings(command);
      command.unregister(commandMap);
      throw new CommandRegistrationFailure("COMMAND_REGISTRATION_FAILED");
    }
    if (!registered
        || commandMap.getCommand(LABEL) != command
        || commandMap.getCommand(NAMESPACED_LABEL) != command) {
      removeIdentityMappings(command);
      command.unregister(commandMap);
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

  private void removeIdentityMappings(AgentCommand command) {
    var knownCommands = commandMap.getKnownCommands();
    var ownedLabels =
        knownCommands.entrySet().stream()
            .filter(entry -> entry.getValue() == command)
            .map(java.util.Map.Entry::getKey)
            .toList();
    for (var label : ownedLabels) {
      knownCommands.remove(label, command);
    }
  }

  private void refreshOnlinePlayers() {
    for (var player : onlinePlayers.get()) {
      try {
        player.updateCommands();
      } catch (RuntimeException error) {
        warningSink.accept("COMMAND_TREE_REFRESH_FAILED");
      }
    }
  }

  private void requirePrimaryThread() {
    if (!primaryThread.getAsBoolean()) {
      throw new IllegalStateException("Paper command registration requires the primary thread");
    }
  }
}
