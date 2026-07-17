package dev.minecraftagent.client.transfer;

import java.util.Optional;

public enum ViewTransferEncoding {
  IDENTITY("identity"),
  GZIP("gzip");

  private final String wireName;

  ViewTransferEncoding(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static Optional<ViewTransferEncoding> fromWireName(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (var encoding : values()) {
      if (encoding.wireName.equals(value)) {
        return Optional.of(encoding);
      }
    }
    return Optional.empty();
  }
}
