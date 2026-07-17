package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

public final class AdminToggleAuthorizer implements ToggleAuthorizer {
  private final Supplier<AdminPolicy> policy;

  public AdminToggleAuthorizer(Supplier<AdminPolicy> policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  @Override
  public boolean canToggle(CommandSender sender) {
    Objects.requireNonNull(sender);
    if (sender instanceof ConsoleCommandSender) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    var snapshot = policy.get();
    if (snapshot.owners().contains(player.getUniqueId())) {
      return true;
    }
    return snapshot.allowOpToggle()
        && player.isOp()
        && player.hasPermission(AgentCommand.TOGGLE_PERMISSION);
  }
}
