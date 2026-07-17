package dev.minecraftagent.client.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ViewTransferAccumulatorTest {
  private static final UUID TRANSFER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID VIEW_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
  private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");

  @Test
  void reassemblesOutOfOrderIdentityChunksAndReturnsStrictUtf8() {
    var content = "{\"content\":{\"text\":\"ok\"}}".getBytes(StandardCharsets.UTF_8);
    var first = Arrays.copyOfRange(content, 0, 9);
    var second = Arrays.copyOfRange(content, 9, content.length);
    var descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 2);
    var accumulator = new ViewTransferAccumulator();

    assertTrue(accumulator.begin(descriptor).accepted());
    assertEquals(
        ViewTransferAcceptance.Status.ACCEPTED,
        accumulator.accept(chunk(descriptor, 1, second)).status());
    var complete = accumulator.accept(chunk(descriptor, 0, first));

    assertEquals(ViewTransferAcceptance.Status.COMPLETE, complete.status());
    var payload = complete.completedPayload().orElseThrow();
    assertEquals(new String(content, StandardCharsets.UTF_8), payload.json());
    assertArrayEquals(content, payload.contentBytes());
    assertEquals(0, accumulator.activeTransferCount());
    assertEquals(0, accumulator.reservedConnectionBytes());
  }

  @Test
  void decodesExactlyOneGzipMember() throws IOException {
    var content = "{\"viewSchemaVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
    var compressed = gzip(content);
    var descriptor = descriptor(compressed, content, ViewTransferEncoding.GZIP, 1);
    var accumulator = new ViewTransferAccumulator();

    assertTrue(accumulator.begin(descriptor).accepted());
    assertEquals(
        ViewTransferAcceptance.Status.COMPLETE,
        accumulator.accept(chunk(descriptor, 0, compressed)).status());

    var secondMember = concatenate(compressed, gzip("{}".getBytes(StandardCharsets.UTF_8)));
    var concatenatedDescriptor =
        descriptor(secondMember, content, ViewTransferEncoding.GZIP, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(concatenatedDescriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(concatenatedDescriptor, 0, secondMember)),
        ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);

    var trailing = concatenate(compressed, new byte[] {0});
    var trailingDescriptor =
        descriptor(trailing, content, ViewTransferEncoding.GZIP, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(trailingDescriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(trailingDescriptor, 0, trailing)),
        ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);
  }

  @Test
  void rejectsTheUnsupportedGzipHeaderCrcFlag() throws IOException {
    var content = "{\"viewSchemaVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
    var compressed = gzip(content);
    compressed[3] |= 0x02;
    var descriptor = descriptor(compressed, content, ViewTransferEncoding.GZIP, 1);
    var accumulator = new ViewTransferAccumulator();

    assertTrue(accumulator.begin(descriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(descriptor, 0, compressed)),
        ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);
  }

  @Test
  void rejectsExpansionPastDeclaredLengthAndBadTrailer() throws IOException {
    var content = "{\"long\":\"xxxxxxxxxxxxxxxxxxxxxxxx\"}".getBytes(StandardCharsets.UTF_8);
    var compressed = gzip(content);
    var shortDescriptor =
        new ViewTransferDescriptor(
            3,
            TRANSFER_ID,
            VIEW_ID,
            REQUEST_ID,
            1,
            ViewTransferMode.SHOW,
            ViewTransferEncoding.GZIP,
            compressed.length,
            content.length - 1,
            1,
            sha256(content));
    var accumulator = new ViewTransferAccumulator();
    assertTrue(accumulator.begin(shortDescriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(shortDescriptor, 0, compressed)),
        ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);

    var corrupt = Arrays.copyOf(compressed, compressed.length);
    corrupt[corrupt.length - 8] ^= 1;
    var corruptDescriptor =
        descriptor(corrupt, content, ViewTransferEncoding.GZIP, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(corruptDescriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(corruptDescriptor, 0, corrupt)),
        ViewTransferFailure.CONTENT_DECOMPRESSION_FAILED);
  }

  @Test
  void rejectsNonCanonicalBase64LengthAndChunkHashThenDiscardsTransfer() {
    var content = "{}".getBytes(StandardCharsets.UTF_8);
    var descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1);
    var accumulator = new ViewTransferAccumulator();
    assertTrue(accumulator.begin(descriptor).accepted());

    var nonCanonical =
        new ViewTransferChunk(
            descriptor.generation(), descriptor.transferId(), 0, 2, sha256(content), "e30");
    assertFailure(accumulator.accept(nonCanonical), ViewTransferFailure.CHUNK_BASE64_INVALID);
    assertEquals(0, accumulator.activeTransferCount());

    descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1);
    assertTrue(accumulator.begin(descriptor).accepted());
    var wrongLength =
        new ViewTransferChunk(
            descriptor.generation(),
            descriptor.transferId(),
            0,
            2,
            sha256(content),
            Base64.getEncoder().encodeToString(new byte[] {1}));
    assertFailure(accumulator.accept(wrongLength), ViewTransferFailure.CHUNK_LENGTH_MISMATCH);

    descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(descriptor).accepted());
    var badIndex =
        new ViewTransferChunk(
            descriptor.generation(),
            descriptor.transferId(),
            2,
            content.length,
            sha256(content),
            Base64.getEncoder().encodeToString(content));
    assertFailure(accumulator.accept(badIndex), ViewTransferFailure.CHUNK_INDEX_OUT_OF_RANGE);

    descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(descriptor).accepted());
    var wrongHash =
        new ViewTransferChunk(
            descriptor.generation(),
            descriptor.transferId(),
            0,
            content.length,
            "0".repeat(64),
            Base64.getEncoder().encodeToString(content));
    assertFailure(accumulator.accept(wrongHash), ViewTransferFailure.CHUNK_HASH_MISMATCH);
  }

  @Test
  void rejectsDuplicateIndexGenerationMismatchAndWholeContentHashMismatch() {
    var content = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    var first = Arrays.copyOfRange(content, 0, 2);
    var second = Arrays.copyOfRange(content, 2, content.length);
    var accumulator = new ViewTransferAccumulator();
    var descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 2);

    assertTrue(accumulator.begin(descriptor).accepted());
    assertEquals(
        ViewTransferAcceptance.Status.ACCEPTED,
        accumulator.accept(chunk(descriptor, 0, first)).status());
    assertFailure(
        accumulator.accept(chunk(descriptor, 0, first)), ViewTransferFailure.CHUNK_DUPLICATE);

    descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(descriptor).accepted());
    var stale =
        new ViewTransferChunk(
            descriptor.generation() + 1,
            descriptor.transferId(),
            0,
            content.length,
            sha256(content),
            Base64.getEncoder().encodeToString(content));
    assertFailure(accumulator.accept(stale), ViewTransferFailure.GENERATION_MISMATCH);

    descriptor =
        new ViewTransferDescriptor(
            3,
            UUID.randomUUID(),
            VIEW_ID,
            REQUEST_ID,
            1,
            ViewTransferMode.UPDATE,
            ViewTransferEncoding.IDENTITY,
            content.length,
            content.length,
            1,
            "0".repeat(64));
    assertTrue(accumulator.begin(descriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(descriptor, 0, content)),
        ViewTransferFailure.CONTENT_HASH_MISMATCH);
  }

  @Test
  void rejectsInvalidUtf8AfterHashVerification() {
    var content = new byte[] {(byte) 0xc3, 0x28};
    var descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1);
    var accumulator = new ViewTransferAccumulator();
    assertTrue(accumulator.begin(descriptor).accepted());
    assertFailure(
        accumulator.accept(chunk(descriptor, 0, content)),
        ViewTransferFailure.CONTENT_UTF8_INVALID);
  }

  @Test
  void enforcesDescriptorConnectionAndActiveTransferBudgets() {
    var clock = new AtomicLong();
    var limits = new ViewTransferLimits(2, 4, 8, 10, 10, 10, Duration.ofSeconds(15));
    var accumulator = new ViewTransferAccumulator(limits, clock::get);
    var content = "123456".getBytes(StandardCharsets.UTF_8);
    var first = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1);
    var second = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1, UUID.randomUUID());

    assertTrue(accumulator.begin(first).accepted());
    var rejected = accumulator.begin(second);
    assertFalse(rejected.accepted());
    assertEquals(
        ViewTransferFailure.CONNECTION_BYTE_BUDGET_EXCEEDED, rejected.failure().orElseThrow());
    assertEquals(6, accumulator.reservedConnectionBytes());

    var oversized = new byte[11];
    var invalid =
        descriptor(oversized, oversized, ViewTransferEncoding.IDENTITY, 2, UUID.randomUUID());
    assertEquals(
        ViewTransferFailure.INVALID_DESCRIPTOR, accumulator.begin(invalid).failure().orElseThrow());
  }

  @Test
  void expiresWithoutSlidingAndDisconnectClearsAllState() {
    var clock = new AtomicLong(100);
    var limits = new ViewTransferLimits(2, 64, 24 * 1024, 1024, 1024, 2048, Duration.ofNanos(10));
    var accumulator = new ViewTransferAccumulator(limits, clock::get);
    var content = "{}".getBytes(StandardCharsets.UTF_8);
    var descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1);
    assertTrue(accumulator.begin(descriptor).accepted());

    clock.set(110);
    assertFailure(
        accumulator.accept(chunk(descriptor, 0, content)), ViewTransferFailure.TRANSFER_EXPIRED);
    assertEquals(0, accumulator.reservedConnectionBytes());

    descriptor = descriptor(content, content, ViewTransferEncoding.IDENTITY, 1, UUID.randomUUID());
    assertTrue(accumulator.begin(descriptor).accepted());
    assertEquals(1, accumulator.disconnect());
    assertEquals(0, accumulator.activeTransferCount());
    assertEquals(0, accumulator.reservedConnectionBytes());
  }

  private static ViewTransferDescriptor descriptor(
      byte[] compressed, byte[] content, ViewTransferEncoding encoding, int chunks) {
    return descriptor(compressed, content, encoding, chunks, TRANSFER_ID);
  }

  private static ViewTransferDescriptor descriptor(
      byte[] compressed,
      byte[] content,
      ViewTransferEncoding encoding,
      int chunks,
      UUID transferId) {
    return new ViewTransferDescriptor(
        3,
        transferId,
        VIEW_ID,
        REQUEST_ID,
        1,
        ViewTransferMode.SHOW,
        encoding,
        compressed.length,
        content.length,
        chunks,
        sha256(content));
  }

  private static ViewTransferChunk chunk(
      ViewTransferDescriptor descriptor, int index, byte[] content) {
    return new ViewTransferChunk(
        descriptor.generation(),
        descriptor.transferId(),
        index,
        content.length,
        sha256(content),
        Base64.getEncoder().encodeToString(content));
  }

  private static void assertFailure(
      ViewTransferAcceptance acceptance, ViewTransferFailure expected) {
    assertEquals(ViewTransferAcceptance.Status.REJECTED, acceptance.status());
    assertEquals(expected, acceptance.failure().orElseThrow());
  }

  private static byte[] gzip(byte[] content) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(content);
    }
    return output.toByteArray();
  }

  private static byte[] concatenate(byte[] first, byte[] second) {
    var result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
