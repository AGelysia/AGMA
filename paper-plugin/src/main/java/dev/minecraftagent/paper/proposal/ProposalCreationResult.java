package dev.minecraftagent.paper.proposal;

import java.util.Objects;

public record ProposalCreationResult(Status status, ProposalView proposal, String code) {
  public enum Status {
    CREATED,
    REJECTED
  }

  public ProposalCreationResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(code);
    if ((status == Status.CREATED) != (proposal != null)) {
      throw new IllegalArgumentException("Creation result has inconsistent data");
    }
  }

  public static ProposalCreationResult created(ProposalView proposal) {
    return new ProposalCreationResult(Status.CREATED, proposal, "PROPOSAL_CREATED");
  }

  public static ProposalCreationResult rejected(String code) {
    return new ProposalCreationResult(Status.REJECTED, null, code);
  }
}
