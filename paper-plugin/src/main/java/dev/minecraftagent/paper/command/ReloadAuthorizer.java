package dev.minecraftagent.paper.command;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface ReloadAuthorizer {
  boolean canReload(CommandSender sender);
}
