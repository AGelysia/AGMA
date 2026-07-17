package dev.minecraftagent.standalone.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Mutable in-memory secret bytes whose copies are bounded and explicitly cleared on close. */
public final class SecretMaterial implements AutoCloseable {
  private byte[] value;

  private SecretMaterial(byte[] value) {
    this.value = value;
  }

  static SecretMaterial fromUtf8(String value) {
    Objects.requireNonNull(value, "value");
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 1 || bytes.length > 8192) {
      Arrays.fill(bytes, (byte) 0);
      throw new IllegalArgumentException("Secret material is out of bounds");
    }
    return new SecretMaterial(bytes);
  }

  synchronized byte[] copyBytes() {
    if (value == null) {
      throw new IllegalStateException("Secret material is closed");
    }
    return Arrays.copyOf(value, value.length);
  }

  public synchronized boolean isClosed() {
    return value == null;
  }

  @Override
  public synchronized void close() {
    if (value != null) {
      Arrays.fill(value, (byte) 0);
      value = null;
    }
  }

  @Override
  public String toString() {
    return "SecretMaterial[redacted]";
  }
}
