package dev.minecraftagent.client.transfer;

import dev.minecraftagent.protocol.ClientChannelContract;
import java.util.Optional;

public enum ViewTransferMode {
  SHOW(ClientChannelContract.VIEW_MODE_SHOW),
  UPDATE(ClientChannelContract.VIEW_MODE_UPDATE);

  private final String wireName;

  ViewTransferMode(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static Optional<ViewTransferMode> fromWireName(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (var mode : values()) {
      if (mode.wireName.equals(value)) {
        return Optional.of(mode);
      }
    }
    return Optional.empty();
  }
}
