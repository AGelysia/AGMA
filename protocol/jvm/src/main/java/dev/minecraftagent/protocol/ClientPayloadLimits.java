package dev.minecraftagent.protocol;

import java.util.regex.Pattern;

/**
 * The numeric bounds of the {@code minecraftagent:client} channel.
 *
 * <p>Each constant is transcribed from {@code protocol/schemas/client-payload.schema.json}, which
 * is authoritative: when the two ends disagree, this class is reconciled against the schema rather
 * than against whichever end happened to be updated last.
 */
public final class ClientPayloadLimits {
  /** Largest frame the client may send to the server. */
  public static final int MAX_CLIENT_TO_SERVER_FRAME_BYTES = 16 * 1024;

  /** Largest frame the server may send to the client. */
  public static final int MAX_SERVER_TO_CLIENT_FRAME_BYTES = 40 * 1024;

  /** Inclusive bounds of a connection generation. */
  public static final long GENERATION_MIN = 1;

  /** Inclusive bounds of a connection generation. */
  public static final long GENERATION_MAX = Integer.MAX_VALUE;

  /** Inclusive bounds of a view revision. */
  public static final int REVISION_MIN = 1;

  /** Inclusive bounds of a view revision. */
  public static final int REVISION_MAX = Integer.MAX_VALUE;

  /** Largest compressed or uncompressed payload a single transfer may declare. */
  public static final int MAX_TRANSFER_BYTES = 1024 * 1024;

  /** Inclusive bounds of a {@code view.begin} chunk count. */
  public static final int CHUNK_COUNT_MIN = 1;

  /** Inclusive bounds of a {@code view.begin} chunk count. */
  public static final int CHUNK_COUNT_MAX = 64;

  /** Inclusive bounds of a {@code view.chunk} index. */
  public static final int CHUNK_INDEX_MIN = 0;

  /** Inclusive bounds of a {@code view.chunk} index. */
  public static final int CHUNK_INDEX_MAX = 63;

  /** Largest single chunk, in bytes. */
  public static final int MAX_CHUNK_BYTES = 24 * 1024;

  /** Inclusive bounds of a chunk byte length. */
  public static final int CHUNK_BYTES_MIN = 1;

  /** Longest base64 encoding of a {@link #MAX_CHUNK_BYTES} chunk. */
  public static final int MAX_CHUNK_BASE64_CHARS = 32768;

  /** Inclusive bounds of a chunk payload base64 string length. */
  public static final int CHUNK_BASE64_CHARS_MIN = 4;

  /** Longest message type string the envelope accepts. */
  public static final int MAX_TYPE_CHARS = 32;

  /** Exact length of a canonical UUID. */
  public static final int UUID_CHARS = 36;

  /** Exact length of a lowercase hexadecimal SHA-256 digest. */
  public static final int SHA256_CHARS = 64;

  /** Lowercase hexadecimal SHA-256 digest. */
  public static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

  /** Inclusive bounds of a stable status code length. */
  public static final int STABLE_CODE_MIN_CHARS = 2;

  /** Inclusive bounds of a stable status code length. */
  public static final int STABLE_CODE_MAX_CHARS = 64;

  /** Stable status code shape shared by {@code client.ack} and {@code client.error}. */
  public static final Pattern STABLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

  /**
   * Number of maximum-size chunks needed to carry {@code compressedBytes}.
   *
   * <p>{@code view.begin} must declare exactly this value, which is what keeps the descriptor and
   * the chunk stream honest about each other.
   */
  public static int expectedChunkCount(int compressedBytes) {
    return (compressedBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
  }

  private ClientPayloadLimits() {}
}
