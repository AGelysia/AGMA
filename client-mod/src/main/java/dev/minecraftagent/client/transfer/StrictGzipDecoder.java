package dev.minecraftagent.client.transfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Decodes exactly one RFC 1952 member and rejects trailing or concatenated members. */
public final class StrictGzipDecoder {
  private static final int FLAG_HEADER_CRC = 0x02;
  private static final int FLAG_EXTRA = 0x04;
  private static final int FLAG_NAME = 0x08;
  private static final int FLAG_COMMENT = 0x10;
  private static final int RESERVED_FLAGS = 0xe0;

  private StrictGzipDecoder() {}

  public static byte[] decode(byte[] input, int expectedBytes) throws IOException {
    if (input.length < 18 || expectedBytes < 1) {
      throw new IOException("invalid gzip length");
    }
    if ((input[0] & 0xff) != 0x1f || (input[1] & 0xff) != 0x8b || (input[2] & 0xff) != 8) {
      throw new IOException("invalid gzip header");
    }

    int flags = input[3] & 0xff;
    if ((flags & RESERVED_FLAGS) != 0) {
      throw new IOException("reserved gzip flags are set");
    }
    if ((flags & FLAG_HEADER_CRC) != 0) {
      throw new IOException("gzip header checksum flag is unsupported");
    }

    int offset = 10;
    if ((flags & FLAG_EXTRA) != 0) {
      requireAvailable(input, offset, 2);
      int extraLength = (input[offset] & 0xff) | ((input[offset + 1] & 0xff) << 8);
      offset += 2;
      requireAvailable(input, offset, extraLength);
      offset += extraLength;
    }
    if ((flags & FLAG_NAME) != 0) {
      offset = skipZeroTerminated(input, offset);
    }
    if ((flags & FLAG_COMMENT) != 0) {
      offset = skipZeroTerminated(input, offset);
    }
    if (input.length - offset < 9) {
      throw new IOException("gzip member is truncated");
    }

    var inflater = new Inflater(true);
    var output = new ByteArrayOutputStream(Math.min(expectedBytes, 8192));
    try {
      inflater.setInput(input, offset, input.length - offset);
      var buffer = new byte[8192];
      while (!inflater.finished()) {
        int count;
        try {
          count = inflater.inflate(buffer);
        } catch (DataFormatException exception) {
          throw new IOException("invalid deflate stream", exception);
        }
        if (count > 0) {
          if ((long) output.size() + count > expectedBytes) {
            throw new IOException("gzip output exceeds declared limit");
          }
          output.write(buffer, 0, count);
          continue;
        }
        if (inflater.needsDictionary() || inflater.needsInput()) {
          throw new IOException("incomplete deflate stream");
        }
        throw new IOException("deflate stream made no progress");
      }

      long deflateBytes = inflater.getBytesRead();
      if (deflateBytes < 1 || deflateBytes > input.length - offset - 8L) {
        throw new IOException("invalid gzip trailer position");
      }
      int trailerOffset = Math.toIntExact(offset + deflateBytes);
      if (trailerOffset + 8 != input.length) {
        throw new IOException("gzip contains trailing data or multiple members");
      }

      byte[] decoded = output.toByteArray();
      long suppliedCrc = littleEndianUnsignedInt(input, trailerOffset);
      long suppliedSize = littleEndianUnsignedInt(input, trailerOffset + 4);
      var contentCrc = new CRC32();
      contentCrc.update(decoded);
      if (contentCrc.getValue() != suppliedCrc) {
        throw new IOException("gzip content checksum mismatch");
      }
      if ((((long) decoded.length) & 0xffff_ffffL) != suppliedSize) {
        throw new IOException("gzip content size mismatch");
      }
      return decoded;
    } finally {
      inflater.end();
    }
  }

  private static int skipZeroTerminated(byte[] input, int offset) throws IOException {
    while (offset < input.length) {
      if (input[offset++] == 0) {
        return offset;
      }
    }
    throw new IOException("unterminated gzip header field");
  }

  private static void requireAvailable(byte[] input, int offset, int length) throws IOException {
    if (offset < 0 || length < 0 || offset > input.length - length) {
      throw new IOException("truncated gzip header");
    }
  }

  private static long littleEndianUnsignedInt(byte[] input, int offset) {
    return ((long) input[offset] & 0xff)
        | (((long) input[offset + 1] & 0xff) << 8)
        | (((long) input[offset + 2] & 0xff) << 16)
        | (((long) input[offset + 3] & 0xff) << 24);
  }
}
