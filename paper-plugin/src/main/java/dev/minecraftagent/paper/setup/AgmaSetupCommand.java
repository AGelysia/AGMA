package dev.minecraftagent.paper.setup;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** An always-available, secret-free administration surface for runtime setup and recovery. */
public final class AgmaSetupCommand extends Command implements PluginIdentifiableCommand {
  public static final String PERMISSION = "minecraftagent.admin.setup";

  static final String MANAGED_CONFIGURATION_PATH = "plugins/AGMA/managed/config.yml";
  static final String EXTERNAL_CONFIGURATION_PATH = "plugins/AGMA/config.yml";

  private static final String USAGE = "/agma <setup|doctor|retry>";
  private static final String PERMISSION_DENIED =
      "You do not have permission to manage AGMA setup.";
  private static final String ARGUMENT_REJECTED =
      "AGMA setup commands do not accept configuration values or secrets. Edit the server-side"
          + " configuration file.";
  private static final String STATUS_UNAVAILABLE =
      "AGMA setup status is unavailable. Check the server console.";

  private final Plugin plugin;
  private final SetupGateway gateway;
  private final Consumer<Runnable> replyDispatcher;

  public AgmaSetupCommand(Plugin plugin, SetupGateway gateway, Consumer<Runnable> replyDispatcher) {
    super("agma", "Manages AGMA setup and recovery", USAGE, List.of());
    this.plugin = Objects.requireNonNull(plugin);
    this.gateway = Objects.requireNonNull(gateway);
    this.replyDispatcher = Objects.requireNonNull(replyDispatcher);
  }

  @Override
  public Plugin getPlugin() {
    return plugin;
  }

  @Override
  public boolean execute(CommandSender sender, String commandLabel, String[] arguments) {
    Objects.requireNonNull(sender);
    Objects.requireNonNull(arguments);
    if (!canManage(sender)) {
      sender.sendMessage(PERMISSION_DENIED);
      return true;
    }
    if (arguments.length > 1) {
      sender.sendMessage(ARGUMENT_REJECTED);
      return true;
    }
    if (arguments.length == 0) {
      sender.sendMessage(USAGE);
      return true;
    }

    switch (arguments[0].toLowerCase(Locale.ROOT)) {
      case "setup" -> renderSetup(sender);
      case "doctor" -> renderDoctor(sender);
      case "retry" -> retry(sender);
      default -> sender.sendMessage(USAGE);
    }
    return true;
  }

  @Override
  public List<String> tabComplete(CommandSender sender, String alias, String[] arguments)
      throws IllegalArgumentException {
    Objects.requireNonNull(sender);
    Objects.requireNonNull(arguments);
    if (!canManage(sender) || arguments.length != 1) {
      return List.of();
    }
    var prefix = arguments[0].toLowerCase(Locale.ROOT);
    return List.of("setup", "doctor", "retry").stream()
        .filter(candidate -> candidate.startsWith(prefix))
        .toList();
  }

  private void renderSetup(CommandSender sender) {
    var snapshot = snapshot(sender);
    if (snapshot == null) {
      return;
    }
    sender.sendMessage("AGMA setup state: " + snapshot.state());
    sender.sendMessage("Configuration file: " + configurationPath(snapshot.state()));
    sender.sendMessage(nextStep(snapshot.state()));
  }

  private void renderDoctor(CommandSender sender) {
    var snapshot = snapshot(sender);
    if (snapshot == null) {
      return;
    }
    sender.sendMessage("AGMA setup state: " + snapshot.state());
    sender.sendMessage("Configuration file: " + configurationPath(snapshot.state()));
    if (snapshot.diagnosticCode() != null) {
      sender.sendMessage("Diagnostic: " + snapshot.diagnosticCode());
    }
  }

  private RedactedSetupSnapshot snapshot(CommandSender sender) {
    try {
      var snapshot = gateway.snapshot();
      if (snapshot == null) {
        throw new IllegalStateException("null setup snapshot");
      }
      return snapshot;
    } catch (RuntimeException error) {
      sender.sendMessage(STATUS_UNAVAILABLE);
      return null;
    }
  }

  private void retry(CommandSender sender) {
    CompletionStage<RedactedSetupSnapshot> retry;
    try {
      retry = Objects.requireNonNull(gateway.retry());
    } catch (RuntimeException error) {
      sender.sendMessage("AGMA retry failed. Check the server console.");
      return;
    }

    sender.sendMessage("AGMA retry started.");
    retry.whenComplete(
        (snapshot, error) ->
            replyDispatcher.accept(
                () -> {
                  if (!canManage(sender)) {
                    sender.sendMessage(PERMISSION_DENIED);
                    return;
                  }
                  sender.sendMessage(retryResult(snapshot, error));
                }));
  }

  private static String nextStep(SetupState state) {
    return switch (state) {
      case SETUP_REQUIRED ->
          "Next step: Configure the provider in that file, then run /agma retry.";
      case INSTALLING, STARTING -> "Next step: Wait for startup to finish, then run /agma doctor.";
      case READY -> "Next step: No setup action is required.";
      case FAILED -> "Next step: Check the server console, then run /agma retry.";
      case EXTERNAL -> "Next step: Manage the external runtime, then run /agma retry.";
    };
  }

  private static String retryResult(RedactedSetupSnapshot snapshot, Throwable error) {
    if (error != null || snapshot == null) {
      return "AGMA retry failed. Check the server console.";
    }
    return switch (snapshot.state()) {
      case READY -> "AGMA is ready.";
      case INSTALLING, STARTING -> "AGMA startup is still in progress.";
      case SETUP_REQUIRED ->
          "AGMA still needs configuration. Edit " + MANAGED_CONFIGURATION_PATH + ".";
      case EXTERNAL -> "AGMA uses an external runtime. Manage it outside the plugin.";
      case FAILED ->
          "AGMA retry failed. Check the server console. Diagnostic: " + snapshot.diagnosticCode();
    };
  }

  private static String configurationPath(SetupState state) {
    return state == SetupState.EXTERNAL ? EXTERNAL_CONFIGURATION_PATH : MANAGED_CONFIGURATION_PATH;
  }

  private static boolean canManage(CommandSender sender) {
    if (sender instanceof ConsoleCommandSender) {
      return true;
    }
    return sender instanceof Player player && (player.isOp() || player.hasPermission(PERMISSION));
  }
}
