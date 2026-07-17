package dev.minecraftagent.paper.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Bounded sender-side framing and untrusted ACK correlation for structured views. */
public final class ClientTransferManager {
  private final Limits limits;
  private final Map<UUID, ConnectionTransfers> connections = new LinkedHashMap<>();

  public ClientTransferManager(Limits limits) {
    this.limits = Objects.requireNonNull(limits);
  }

  public static ClientTransferManager withProductionLimits() {
    return new ClientTransferManager(Limits.production());
  }

  /** Starts or replaces a network generation and discards every older pending transfer. */
  public synchronized void open(UUID playerUuid, long generation) {
    Objects.requireNonNull(playerUuid);
    if (generation < 1 || generation > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("generation must be positive");
    }
    connections.put(playerUuid, new ConnectionTransfers(generation));
  }

  public synchronized TransferPlan prepare(
      UUID playerUuid, long generation, ClientStructuredView view, Instant now) {
    return prepare(playerUuid, generation, view, Mode.SHOW, now);
  }

  public synchronized TransferPlan prepare(
      UUID playerUuid, long generation, ClientStructuredView view, Mode mode, Instant now) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(view);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(now);
    var content = view.toJsonBytes();
    if (content.length == 0 || content.length > limits.maxViewBytes()) {
      throw new ClientProtocolException("CLIENT_VIEW_TOO_LARGE");
    }
    var connection = requireConnection(playerUuid, generation);
    if (connection.pending.size() >= limits.maxPendingTransfers()) {
      throw new ClientProtocolException("CLIENT_PENDING_TRANSFER_LIMIT");
    }
    if (connection.pendingBytes + content.length > limits.maxPendingBytes()) {
      throw new ClientProtocolException("CLIENT_PENDING_BYTE_BUDGET");
    }

    var compressed = content.length >= 1024 ? gzip(content) : content;
    var encoding = compressed.length < content.length ? Encoding.GZIP : Encoding.IDENTITY;
    var encoded = encoding == Encoding.GZIP ? compressed : content;
    var chunkCount = (encoded.length + limits.maxChunkBytes() - 1) / limits.maxChunkBytes();
    if (chunkCount < 1 || chunkCount > limits.maxChunks()) {
      throw new ClientProtocolException("CLIENT_TRANSFER_CHUNK_LIMIT");
    }
    var transferId = UUID.randomUUID();
    var contentHash = sha256(content);
    var chunks = new ArrayList<TransferChunk>(chunkCount);
    for (var index = 0; index < chunkCount; index++) {
      var start = index * limits.maxChunkBytes();
      var end = Math.min(encoded.length, start + limits.maxChunkBytes());
      var bytes = Arrays.copyOfRange(encoded, start, end);
      chunks.add(new TransferChunk(transferId, index, bytes, sha256(bytes)));
    }
    var plan =
        new TransferPlan(
            playerUuid,
            generation,
            transferId,
            view.viewId(),
            view.requestId(),
            view.revision(),
            mode,
            encoding,
            encoded.length,
            content.length,
            contentHash,
            chunks);
    connection.pending.put(
        transferId,
        new PendingTransfer(
            transferId,
            view.viewId(),
            view.requestId(),
            contentHash,
            content.length,
            now.plus(limits.timeout())));
    connection.pendingBytes += content.length;
    return plan;
  }

  /**
   * Consumes an ACK as a transport/display fact only. Callers must never translate this result into
   * permission, proposal confirmation, or execution authority.
   */
  public synchronized AcknowledgementResult acknowledge(
      UUID playerUuid, ClientInboundMessage.Ack ack) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(ack);
    var connection = connections.get(playerUuid);
    if (connection == null) {
      return AcknowledgementResult.UNKNOWN_CONNECTION;
    }
    if (connection.generation != ack.generation()) {
      return AcknowledgementResult.STALE_GENERATION;
    }
    var pending = remove(connection, ack.transferId());
    if (pending == null) {
      return AcknowledgementResult.UNKNOWN_TRANSFER;
    }
    return ack.status() == ClientInboundMessage.Ack.Status.DISPLAYED
        ? AcknowledgementResult.DISPLAY_REPORTED
        : AcknowledgementResult.REJECTION_REPORTED;
  }

  public synchronized AcknowledgementResult clientError(
      UUID playerUuid, ClientInboundMessage.Error error) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(error);
    var connection = connections.get(playerUuid);
    if (connection == null) {
      return AcknowledgementResult.UNKNOWN_CONNECTION;
    }
    if (connection.generation != error.generation()) {
      return AcknowledgementResult.STALE_GENERATION;
    }
    if (error.transferId() == null) {
      return AcknowledgementResult.UNSCOPED_ERROR_REPORTED;
    }
    return remove(connection, error.transferId()) == null
        ? AcknowledgementResult.UNKNOWN_TRANSFER
        : AcknowledgementResult.REJECTION_REPORTED;
  }

  public synchronized List<ExpiredTransfer> expire(Instant now) {
    Objects.requireNonNull(now);
    var expired = new ArrayList<ExpiredTransfer>();
    for (var entry : connections.entrySet()) {
      var connection = entry.getValue();
      var matches =
          connection.pending.values().stream()
              .filter(pending -> !pending.deadline.isAfter(now))
              .sorted(Comparator.comparing(PendingTransfer::deadline))
              .toList();
      for (var pending : matches) {
        remove(connection, pending.transferId);
        expired.add(
            new ExpiredTransfer(
                entry.getKey(), connection.generation, pending.transferId, pending.viewId));
      }
    }
    return List.copyOf(expired);
  }

  public synchronized void disconnect(UUID playerUuid) {
    connections.remove(Objects.requireNonNull(playerUuid));
  }

  public synchronized boolean cancel(UUID playerUuid, long generation, UUID transferId) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(transferId);
    var connection = connections.get(playerUuid);
    return connection != null
        && connection.generation == generation
        && remove(connection, transferId) != null;
  }

  public synchronized void clear() {
    connections.clear();
  }

  /** Drops pending bytes while retaining current connection generations for later publications. */
  public synchronized void clearPending() {
    for (var connection : connections.values()) {
      connection.pending.clear();
      connection.pendingBytes = 0;
    }
  }

  public synchronized boolean clearPending(UUID playerUuid, long generation) {
    Objects.requireNonNull(playerUuid);
    var connection = connections.get(playerUuid);
    if (connection == null || connection.generation != generation) {
      return false;
    }
    connection.pending.clear();
    connection.pendingBytes = 0;
    return true;
  }

  public synchronized int pendingCount(UUID playerUuid) {
    var connection = connections.get(Objects.requireNonNull(playerUuid));
    return connection == null ? 0 : connection.pending.size();
  }

  public synchronized boolean isPending(UUID playerUuid, long generation, UUID transferId) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(transferId);
    var connection = connections.get(playerUuid);
    return connection != null
        && connection.generation == generation
        && connection.pending.containsKey(transferId);
  }

  private ConnectionTransfers requireConnection(UUID playerUuid, long generation) {
    var connection = connections.get(playerUuid);
    if (connection == null) {
      throw new ClientProtocolException("CLIENT_CONNECTION_UNKNOWN");
    }
    if (connection.generation != generation) {
      throw new ClientProtocolException("CLIENT_GENERATION_STALE");
    }
    return connection;
  }

  private static PendingTransfer remove(ConnectionTransfers connection, UUID transferId) {
    var removed = connection.pending.remove(transferId);
    if (removed != null) {
      connection.pendingBytes -= removed.uncompressedBytes;
    }
    return removed;
  }

  private static byte[] gzip(byte[] content) {
    try {
      var output = new ByteArrayOutputStream(content.length);
      try (var gzip = new GZIPOutputStream(output)) {
        gzip.write(content);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("In-memory gzip failed", error);
    }
  }

  static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  public record Limits(
      int maxChunkBytes,
      int maxViewBytes,
      int maxChunks,
      int maxPendingBytes,
      int maxPendingTransfers,
      Duration timeout) {
    public Limits {
      if (maxChunkBytes < 1
          || maxChunkBytes > 24 * 1024
          || maxViewBytes < 1
          || maxViewBytes > 1024 * 1024
          || maxChunks < 1
          || maxChunks > 64
          || maxPendingBytes < maxViewBytes
          || maxPendingBytes > 2 * 1024 * 1024
          || maxPendingTransfers < 1
          || timeout == null
          || timeout.isNegative()
          || timeout.isZero()
          || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
        throw new IllegalArgumentException("Invalid client transfer limits");
      }
    }

    public static Limits production() {
      return new Limits(24 * 1024, 1024 * 1024, 64, 2 * 1024 * 1024, 8, Duration.ofSeconds(15));
    }
  }

  public enum Encoding {
    IDENTITY("identity"),
    GZIP("gzip");

    private final String wireName;

    Encoding(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  public enum Mode {
    SHOW("show"),
    UPDATE("update");

    private final String wireName;

    Mode(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  public enum AcknowledgementResult {
    DISPLAY_REPORTED,
    REJECTION_REPORTED,
    UNSCOPED_ERROR_REPORTED,
    UNKNOWN_CONNECTION,
    STALE_GENERATION,
    UNKNOWN_TRANSFER
  }

  public record TransferChunk(UUID transferId, int index, byte[] bytes, String sha256) {
    public TransferChunk {
      Objects.requireNonNull(transferId);
      bytes = Objects.requireNonNull(bytes).clone();
      if (index < 0
          || index > 63
          || bytes.length < 1
          || bytes.length > 24 * 1024
          || !validSha256(sha256)
          || !ClientTransferManager.sha256(bytes).equals(sha256)) {
        throw new ClientProtocolException("CLIENT_TRANSFER_CHUNK_INVALID");
      }
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record TransferPlan(
      UUID playerUuid,
      long generation,
      UUID transferId,
      UUID viewId,
      UUID requestId,
      int revision,
      Mode mode,
      Encoding encoding,
      int compressedBytes,
      int uncompressedBytes,
      String contentSha256,
      List<TransferChunk> chunks) {
    public TransferPlan {
      Objects.requireNonNull(playerUuid);
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(viewId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(encoding);
      chunks = List.copyOf(chunks);
      if (generation < 1
          || generation > Integer.MAX_VALUE
          || revision < 1
          || compressedBytes < 1
          || compressedBytes > 1024 * 1024
          || uncompressedBytes < 1
          || uncompressedBytes > 1024 * 1024
          || !validSha256(contentSha256)
          || chunks.isEmpty()
          || chunks.size() > 64) {
        throw new ClientProtocolException("CLIENT_TRANSFER_PLAN_INVALID");
      }
      var transferred = 0;
      for (var index = 0; index < chunks.size(); index++) {
        var chunk = chunks.get(index);
        if (!transferId.equals(chunk.transferId()) || chunk.index() != index) {
          throw new ClientProtocolException("CLIENT_TRANSFER_PLAN_INVALID");
        }
        transferred += chunk.bytes().length;
      }
      if (transferred != compressedBytes) {
        throw new ClientProtocolException("CLIENT_TRANSFER_PLAN_INVALID");
      }
    }

    public int chunkCount() {
      return chunks.size();
    }
  }

  public record ExpiredTransfer(UUID playerUuid, long generation, UUID transferId, UUID viewId) {}

  private static final class ConnectionTransfers {
    private final long generation;
    private final Map<UUID, PendingTransfer> pending = new LinkedHashMap<>();
    private int pendingBytes;

    private ConnectionTransfers(long generation) {
      this.generation = generation;
    }
  }

  private record PendingTransfer(
      UUID transferId,
      UUID viewId,
      UUID requestId,
      String contentSha256,
      int uncompressedBytes,
      Instant deadline) {}

  private static boolean validSha256(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }
}
