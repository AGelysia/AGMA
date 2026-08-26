package dev.minecraftagent.client.view;

import dev.minecraftagent.protocol.StructuredViewContract;

public enum ViewType {
  TEXT(StructuredViewContract.VIEW_TYPE_TEXT),
  ITEM_STACK(StructuredViewContract.VIEW_TYPE_ITEM_STACK),
  ITEM_LIST(StructuredViewContract.VIEW_TYPE_ITEM_LIST),
  RECIPE(StructuredViewContract.VIEW_TYPE_RECIPE),
  BUILD_PREVIEW(StructuredViewContract.VIEW_TYPE_BUILD_PREVIEW);

  private final String wireName;

  ViewType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static ViewType fromWireName(String wireName) {
    for (ViewType value : values()) {
      if (value.wireName.equals(wireName)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unsupported view type");
  }
}
