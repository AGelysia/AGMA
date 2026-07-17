package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/** Reload remains Console/Owner-only and is never granted by OP status alone. */
public final class OwnerReloadAuthorizer implements ReloadAuthorizer {
  private final Supplier<AdminPolicy> policy;

  public OwnerReloadAuthorizer(Supplier<AdminPolicy> policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  @Override
  public boolean canReload(CommandSender sender) {
    Objects.requireNonNull(sender);
    if (sender instanceof ConsoleCommandSender) {
      return true;
    }
    return sender instanceof Player player && policy.get().owners().contains(player.getUniqueId());
  }
}
