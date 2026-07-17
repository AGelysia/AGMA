package dev.minecraftagent.client.transfer;

import java.util.Objects;
import java.util.Optional;

public record ViewTransferStart(boolean accepted, Optional<ViewTransferFailure> failure) {
  public ViewTransferStart {
    Objects.requireNonNull(failure, "failure");
    if (accepted == failure.isPresent()) {
      throw new IllegalArgumentException("accepted starts cannot carry a failure");
    }
  }

  static ViewTransferStart acceptedStart() {
    return new ViewTransferStart(true, Optional.empty());
  }

  static ViewTransferStart rejectedStart(ViewTransferFailure failure) {
    return new ViewTransferStart(false, Optional.of(failure));
  }
}
