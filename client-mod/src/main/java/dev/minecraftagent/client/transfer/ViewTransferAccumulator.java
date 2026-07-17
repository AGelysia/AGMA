package dev.minecraftagent.client.transfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Per-connection bounded transfer state. A protocol error discards the affected transfer; callers
 * must invoke {@link #disconnect()} when the network connection closes or its generation changes.
 */
public final class ViewTransferAccumulator {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private final ViewTransferLimits limits;
  private final LongSupplier nanoTime;
  private final long timeoutNanos;
  private final Map<UUID, ActiveTransfer> active = new HashMap<>();
  private long reservedConnectionBytes;

  public ViewTransferAccumulator() {
    this(ViewTransferLimits.defaults(), System::nanoTime);
  }

  public ViewTransferAccumulator(ViewTransferLimits limits, LongSupplier nanoTime) {
    this.limits = Objects.requireNonNull(limits);
    this.nanoTime = Objects.requireNonNull(nanoTime);
    try {
      this.timeoutNanos = limits.timeout().toNanos();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("transfer timeout is too large", exception);
    }
  }

  public synchronized ViewTransferStart begin(ViewTransferDescriptor descriptor) {
    Objects.requireNonNull(descriptor);
    long now = nanoTime.getAsLong();
    expireAt(now);

    if (!validDescriptor(descriptor)) {
      return ViewTransferStart.rejectedStart(ViewTransferFailure.INVALID_DESCRIPTOR);
    }
    if (active.containsKey(descriptor.transferId())) {
      remove(descriptor.transferId());
      return ViewTransferStart.rejectedStart(ViewTransferFailure.DUPLICATE_TRANSFER);
    }
    if (active.size() >= limits.maxActiveTransfers()) {
      return ViewTransferStart.rejectedStart(ViewTransferFailure.ACTIVE_TRANSFER_LIMIT);
    }
    if (reservedConnectionBytes + descriptor.compressedBytes() > limits.maxConnectionBytes()) {
      return ViewTransferStart.rejectedStart(ViewTransferFailure.CONNECTION_BYTE_BUDGET_EXCEEDED);
    }

    active.put(descriptor.transferId(), new ActiveTransfer(descriptor, deadline(now)));
    reservedConnectionBytes += descriptor.compressedBytes();
    return ViewTransferStart.acceptedStart();
  }

  public synchronized ViewTransferAcceptance accept(ViewTransferChunk chunk) {
    Objects.requireNonNull(chunk);
    long now = nanoTime.getAsLong();
    var transfer = active.get(chunk.transferId());
    if (transfer != null && expired(transfer, now)) {
      remove(chunk.transferId());
      expireAt(now);
      return ViewTransferAcceptance.rejected(ViewTransferFailure.TRANSFER_EXPIRED);
    }
    expireAt(now);
    if (transfer == null) {
      return ViewTransferAcceptance.rejected(ViewTransferFailure.UNKNOWN_TRANSFER);
    }
    if (chunk.generation() != transfer.descriptor.generation()) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.GENERATION_MISMATCH);
    }
    if (chunk.index() < 0 || chunk.index() >= transfer.descriptor.chunkCount()) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_INDEX_OUT_OF_RANGE);
    }
    if (transfer.chunks[chunk.index()] != null) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_DUPLICATE);
    }
    if (chunk.byteLength() < 1 || chunk.byteLength() > limits.maxChunkBytes()) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_LENGTH_OUT_OF_RANGE);
    }

    long expectedBase64Length = ((long) chunk.byteLength() + 2L) / 3L * 4L;
    if (chunk.data().length() != expectedBase64Length
        || chunk.data().length() > maximumEncodedChunkLength()) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_BASE64_INVALID);
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(chunk.data());
    } catch (IllegalArgumentException exception) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_BASE64_INVALID);
    }
    if (!Base64.getEncoder().encodeToString(decoded).equals(chunk.data())) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_BASE64_INVALID);
    }
    if (decoded.length != chunk.byteLength()) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_LENGTH_MISMATCH);
    }
    if (!validHash(chunk.sha256()) || !hash(decoded).equals(chunk.sha256())) {
      return rejectAndRemove(chunk.transferId(), ViewTransferFailure.CHUNK_HASH_MISMATCH);
    }
    if ((long) transfer.receivedBytes + decoded.length > transfer.descriptor.compressedBytes()) {
      return rejectAndRemove(
          chunk.transferId(), ViewTransferFailure.CONTENT_COMPRESSED_LENGTH_MISMATCH);
    }

    transfer.chunks[chunk.index()] = decoded;
    transfer.receivedBytes += decoded.length;
    transfer.receivedChunks++;
    if (transfer.receivedChunks < transfer.chunks.length) {
      return ViewTransferAcceptance.acceptedChunk();
    }

    remove(chunk.transferId());
    if (transfer.receivedBytes != transfer.descriptor.compressedBytes()) {
      return ViewTransferAcceptance.rejected(
          ViewTransferFailure.CONTENT_COMPRESSED_LENGTH_MISMATCH);
    }
    return complete(transfer);
  }

  public synchronized int expireTimedOut() {
    return expireAt(nanoTime.getAsLong());
  }

  public synchronized int disconnect() {
    int discarded = active.size();
    active.clear();
    reservedConnectionBytes = 0;
    return discarded;
  }

  public synchronized int activeTransferCount() {
    return active.size();
  }

  public synchronized long reservedConnectionBytes() {
    return reservedConnectionBytes;
  }

  private ViewTransferAcceptance complete(ActiveTransfer transfer) {
    var compressed = new ByteArrayOutputStream(transfer.descriptor.compressedBytes());
    for (var chunk : transfer.chunks) {
      compressed.writeBytes(chunk);
    }

    byte[] content;
    if (transfer.descriptor.encoding() == ViewTransferEncoding.IDENTITY) {
      content = compressed.toByteArray();
    } else {
      try {
        content =
            StrictGzipDecoder.decode(
                compressed.toByteArray(), transfer.descriptor.uncompressedBytes());
      } catch (IOException | ArithmeticException exception) {
        return ViewTransferAcceptance.rejected(ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);
      }
    }

    if (content.length != transfer.descriptor.uncompressedBytes()) {
      return ViewTransferAcceptance.rejected(
          ViewTransferFailure.CONTENT_UNCOMPRESSED_LENGTH_MISMATCH);
    }
    if (!hash(content).equals(transfer.descriptor.contentSha256())) {
      return ViewTransferAcceptance.rejected(ViewTransferFailure.CONTENT_HASH_MISMATCH);
    }

    String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      return ViewTransferAcceptance.rejected(ViewTransferFailure.CONTENT_UTF8_INVALID);
    }
    return ViewTransferAcceptance.complete(
        new VerifiedViewPayload(transfer.descriptor, content, json));
  }

  private boolean validDescriptor(ViewTransferDescriptor descriptor) {
    if (descriptor.generation() < 1
        || descriptor.generation() > Integer.MAX_VALUE
        || descriptor.revision() < 1
        || descriptor.chunkCount() < 1
        || descriptor.chunkCount() > limits.maxChunkCount()
        || descriptor.compressedBytes() < 1
        || descriptor.compressedBytes() > limits.maxCompressedBytes()
        || descriptor.uncompressedBytes() < 1
        || descriptor.uncompressedBytes() > limits.maxUncompressedBytes()
        || !validHash(descriptor.contentSha256())) {
      return false;
    }
    if (descriptor.compressedBytes() < descriptor.chunkCount()
        || descriptor.compressedBytes() > (long) descriptor.chunkCount() * limits.maxChunkBytes()) {
      return false;
    }
    return descriptor.encoding() != ViewTransferEncoding.IDENTITY
        || descriptor.compressedBytes() == descriptor.uncompressedBytes();
  }

  private int maximumEncodedChunkLength() {
    return Math.toIntExact(((long) limits.maxChunkBytes() + 2L) / 3L * 4L);
  }

  private ViewTransferAcceptance rejectAndRemove(UUID transferId, ViewTransferFailure failure) {
    remove(transferId);
    return ViewTransferAcceptance.rejected(failure);
  }

  private void remove(UUID transferId) {
    var removed = active.remove(transferId);
    if (removed != null) {
      reservedConnectionBytes -= removed.descriptor.compressedBytes();
    }
  }

  private int expireAt(long now) {
    int before = active.size();
    var iterator = active.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (expired(entry.getValue(), now)) {
        reservedConnectionBytes -= entry.getValue().descriptor.compressedBytes();
        iterator.remove();
      }
    }
    return before - active.size();
  }

  private boolean expired(ActiveTransfer transfer, long now) {
    return now - transfer.deadlineNanos >= 0;
  }

  private long deadline(long now) {
    long deadline = now + timeoutNanos;
    if (((now ^ deadline) & (timeoutNanos ^ deadline)) < 0) {
      return Long.MAX_VALUE;
    }
    return deadline;
  }

  private static boolean validHash(String value) {
    return SHA_256.matcher(value).matches();
  }

  private static String hash(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static final class ActiveTransfer {
    private final ViewTransferDescriptor descriptor;
    private final long deadlineNanos;
    private final byte[][] chunks;
    private int receivedChunks;
    private int receivedBytes;

    private ActiveTransfer(ViewTransferDescriptor descriptor, long deadlineNanos) {
      this.descriptor = descriptor;
      this.deadlineNanos = deadlineNanos;
      this.chunks = new byte[descriptor.chunkCount()][];
    }
  }
}
