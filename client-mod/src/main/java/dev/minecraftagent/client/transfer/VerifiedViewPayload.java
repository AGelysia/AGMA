package dev.minecraftagent.client.transfer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Hash-verified, strictly decoded UTF-8 view JSON ready for the schema decoder. */
public final class VerifiedViewPayload {
  private final ViewTransferDescriptor descriptor;
  private final byte[] content;
  private final String json;

  VerifiedViewPayload(ViewTransferDescriptor descriptor, byte[] content, String json) {
    this.descriptor = Objects.requireNonNull(descriptor);
    this.content = Arrays.copyOf(content, content.length);
    this.json = Objects.requireNonNull(json);
    if (!Arrays.equals(this.content, json.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("JSON must be the exact UTF-8 decoding of content");
    }
  }

  public ViewTransferDescriptor descriptor() {
    return descriptor;
  }

  public byte[] contentBytes() {
    return Arrays.copyOf(content, content.length);
  }

  public String json() {
    return json;
  }
}
