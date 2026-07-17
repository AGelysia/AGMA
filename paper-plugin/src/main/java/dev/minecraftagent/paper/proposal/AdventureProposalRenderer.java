package dev.minecraftagent.paper.proposal;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Builds a literal, Paper-owned confirmation prompt for a proposal. */
public final class AdventureProposalRenderer {
  private static final String COMMAND_PREFIX = "/minecraftagent:agent ";
  private static final Component CONFIRM_HOVER = Component.text("Confirm this proposal.");
  private static final Component REJECT_HOVER = Component.text("Reject this proposal.");

  public Component render(ProposalView proposal) {
    Objects.requireNonNull(proposal);
    var proposalId = proposal.proposalId().toString();
    var confirm =
        Component.text("[Confirm]", NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand(COMMAND_PREFIX + "confirm " + proposalId))
            .hoverEvent(HoverEvent.showText(CONFIRM_HOVER));
    var reject =
        Component.text("[Reject]", NamedTextColor.RED, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand(COMMAND_PREFIX + "reject " + proposalId))
            .hoverEvent(HoverEvent.showText(REJECT_HOVER));

    return Component.text()
        .append(Component.text("AI proposal: ", NamedTextColor.GRAY))
        .append(Component.text(proposal.displayName(), NamedTextColor.WHITE))
        .append(Component.newline())
        .append(Component.text("Risk: ", NamedTextColor.GRAY))
        .append(Component.text(proposal.risk().name(), riskColor(proposal.risk())))
        .append(Component.text(" | Expires: " + proposal.expiresAt(), NamedTextColor.GRAY))
        .append(Component.newline())
        .append(confirm)
        .append(Component.space())
        .append(reject)
        .build();
  }

  private static NamedTextColor riskColor(RiskLevel risk) {
    return switch (risk) {
      case READ -> NamedTextColor.GRAY;
      case WRITE_TEMPORARY -> NamedTextColor.YELLOW;
      case WRITE_WORLD -> NamedTextColor.GOLD;
      case WRITE_PLAYER -> NamedTextColor.RED;
      case SERVER_ADMIN -> NamedTextColor.DARK_RED;
    };
  }
}
