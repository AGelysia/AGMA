package dev.minecraftagent.client.view;

public final class ViewDecodeException extends Exception {
  private final Code code;

  public ViewDecodeException(Code code) {
    super(code.name());
    this.code = code;
  }

  public ViewDecodeException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    PAYLOAD_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    JSON_LIMIT_EXCEEDED,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    METADATA_MISMATCH,
    UNSUPPORTED_VIEW_TYPE,
    CHUNK_INDEX_DUPLICATE,
    CHUNK_SET_INCOMPLETE,
    CHUNK_BASE64_INVALID,
    CHUNK_LENGTH_MISMATCH,
    CHUNK_HASH_MISMATCH,
    CONTENT_COMPRESSED_LENGTH_MISMATCH,
    CONTENT_DECOMPRESSION_FAILED,
    CONTENT_UNCOMPRESSED_LENGTH_MISMATCH,
    CONTENT_HASH_MISMATCH,
    CONTENT_JSON_INVALID,
    CONTENT_NOT_CANONICAL,
    CONTENT_SHAPE_INVALID,
    PALETTE_HASH_MISMATCH,
    PALETTE_INVALID,
    BOUNDS_INVALID,
    BLOCK_CONTENT_INVALID,
    BASE_REGION_HASH_INVALID,
    CHANGE_LIMIT_EXCEEDED
  }
}
