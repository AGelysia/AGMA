package dev.minecraftagent.paper.proposal;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProposalConfirmationResult(Status status, String code) {
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  public enum Status {
    EXECUTED,
    REJECTED,
    FAILED
  }

  public ProposalConfirmationResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(code);
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Invalid non-sensitive proposal result code");
    }
  }

  public static ProposalConfirmationResult executed(String code) {
    return new ProposalConfirmationResult(Status.EXECUTED, code);
  }

  public static ProposalConfirmationResult rejected(String code) {
    return new ProposalConfirmationResult(Status.REJECTED, code);
  }

  public static ProposalConfirmationResult failed(String code) {
    return new ProposalConfirmationResult(Status.FAILED, code);
  }
}
