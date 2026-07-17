package dev.minecraftagent.client.transfer;

import java.util.Objects;
import java.util.UUID;

/** Strictly typed contents of a {@code view.chunk} payload. */
public record ViewTransferChunk(
    long generation, UUID transferId, int index, int byteLength, String sha256, String data) {
  public ViewTransferChunk {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(data, "data");
  }
}
