package dev.minecraftagent.client.transfer;

import java.util.Objects;
import java.util.UUID;

/** Strictly typed contents of a {@code view.begin} payload. */
public record ViewTransferDescriptor(
    long generation,
    UUID transferId,
    UUID viewId,
    UUID requestId,
    int revision,
    ViewTransferMode mode,
    ViewTransferEncoding encoding,
    int compressedBytes,
    int uncompressedBytes,
    int chunkCount,
    String contentSha256) {
  public ViewTransferDescriptor {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(encoding, "encoding");
    Objects.requireNonNull(contentSha256, "contentSha256");
  }
}
