package dev.minecraftagent.client.transfer;

import java.util.Objects;
import java.util.Optional;

public record ViewTransferAcceptance(
    Status status,
    Optional<VerifiedViewPayload> completedPayload,
    Optional<ViewTransferFailure> failure) {
  public enum Status {
    ACCEPTED,
    COMPLETE,
    REJECTED
  }

  public ViewTransferAcceptance {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(completedPayload, "completedPayload");
    Objects.requireNonNull(failure, "failure");
    if ((status == Status.COMPLETE) != completedPayload.isPresent()
        || (status == Status.REJECTED) != failure.isPresent()) {
      throw new IllegalArgumentException("transfer result fields do not match status");
    }
  }

  static ViewTransferAcceptance acceptedChunk() {
    return new ViewTransferAcceptance(Status.ACCEPTED, Optional.empty(), Optional.empty());
  }

  static ViewTransferAcceptance complete(VerifiedViewPayload payload) {
    return new ViewTransferAcceptance(Status.COMPLETE, Optional.of(payload), Optional.empty());
  }

  static ViewTransferAcceptance rejected(ViewTransferFailure failure) {
    return new ViewTransferAcceptance(Status.REJECTED, Optional.empty(), Optional.of(failure));
  }
}
