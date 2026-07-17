package dev.minecraftagent.client.transfer;

import java.time.Duration;
import java.util.Objects;

public record ViewTransferLimits(
    int maxActiveTransfers,
    int maxChunkCount,
    int maxChunkBytes,
    int maxCompressedBytes,
    int maxUncompressedBytes,
    int maxConnectionBytes,
    Duration timeout) {
  public static final int DEFAULT_MAX_CHUNK_COUNT = 64;
  public static final int DEFAULT_MAX_CHUNK_BYTES = 24 * 1024;
  public static final int DEFAULT_MAX_VIEW_BYTES = 1024 * 1024;
  public static final int DEFAULT_MAX_CONNECTION_BYTES = 2 * 1024 * 1024;
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

  public ViewTransferLimits {
    Objects.requireNonNull(timeout, "timeout");
    if (maxActiveTransfers < 1
        || maxChunkCount < 1
        || maxChunkBytes < 1
        || maxCompressedBytes < 1
        || maxUncompressedBytes < 1
        || maxConnectionBytes < 1
        || timeout.isZero()
        || timeout.isNegative()) {
      throw new IllegalArgumentException("transfer limits must be positive");
    }
    if (maxConnectionBytes < maxCompressedBytes) {
      throw new IllegalArgumentException("connection budget must fit one maximum transfer");
    }
  }

  public static ViewTransferLimits defaults() {
    return new ViewTransferLimits(
        2,
        DEFAULT_MAX_CHUNK_COUNT,
        DEFAULT_MAX_CHUNK_BYTES,
        DEFAULT_MAX_VIEW_BYTES,
        DEFAULT_MAX_VIEW_BYTES,
        DEFAULT_MAX_CONNECTION_BYTES,
        DEFAULT_TIMEOUT);
  }
}
