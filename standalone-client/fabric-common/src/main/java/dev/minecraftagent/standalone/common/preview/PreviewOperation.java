package dev.minecraftagent.standalone.common.preview;

import java.util.Locale;

/** Whether the preview describes a new build or a modification of existing blocks. */
public enum PreviewOperation {
  CREATE("create"),
  MODIFY("modify");

  private final String wireName;

  PreviewOperation(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static PreviewOperation fromWireName(String wireName) {
    for (var operation : values()) {
      if (operation.wireName.equals(wireName)) {
        return operation;
      }
    }
    throw new IllegalArgumentException("preview operation is invalid");
  }

  @Override
  public String toString() {
    return name().toLowerCase(Locale.ROOT);
  }
}
