package dev.minecraftagent.paper.proposal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe data from which the command layer can build literal Adventure components. */
public record ProposalView(
    UUID proposalId,
    String tool,
    String displayName,
    RiskLevel risk,
    String argumentHash,
    Instant expiresAt,
    Action confirmAction,
    Action rejectAction) {
  public enum ActionKind {
    CONFIRM,
    REJECT
  }

  public record Action(ActionKind kind, UUID proposalId) {
    public Action {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(proposalId);
    }
  }

  public ProposalView {
    Objects.requireNonNull(proposalId);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(displayName);
    Objects.requireNonNull(risk);
    Objects.requireNonNull(argumentHash);
    Objects.requireNonNull(expiresAt);
    Objects.requireNonNull(confirmAction);
    Objects.requireNonNull(rejectAction);
    if (!proposalId.equals(confirmAction.proposalId())
        || confirmAction.kind() != ActionKind.CONFIRM
        || !proposalId.equals(rejectAction.proposalId())
        || rejectAction.kind() != ActionKind.REJECT) {
      throw new IllegalArgumentException("Proposal actions do not match the view");
    }
  }

  static ProposalView from(StoredProposal proposal) {
    return new ProposalView(
        proposal.proposalId(),
        proposal.tool(),
        proposal.displayName(),
        proposal.risk(),
        proposal.arguments().sha256(),
        proposal.expiresAt(),
        new Action(ActionKind.CONFIRM, proposal.proposalId()),
        new Action(ActionKind.REJECT, proposal.proposalId()));
  }

  @Override
  public String toString() {
    return "ProposalView[proposalId="
        + proposalId
        + ", tool="
        + tool
        + ", displayName="
        + displayName
        + ", risk="
        + risk
        + ", argumentHash=<redacted>, expiresAt="
        + expiresAt
        + "]";
  }
}
