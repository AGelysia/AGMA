package dev.minecraftagent.paper.command;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface ToggleAuthorizer {
  boolean canToggle(CommandSender sender);
}
