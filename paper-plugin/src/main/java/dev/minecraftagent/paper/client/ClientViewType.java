package dev.minecraftagent.paper.client;

import dev.minecraftagent.protocol.StructuredViewContract;
import java.util.Arrays;
import java.util.Set;

/** Closed structured view types and their mandatory client features. */
public enum ClientViewType {
  TEXT(StructuredViewContract.VIEW_TYPE_TEXT, Set.of(ClientFeature.OVERLAY)),
  ITEM_STACK(
      StructuredViewContract.VIEW_TYPE_ITEM_STACK,
      Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS)),
  ITEM_LIST(
      StructuredViewContract.VIEW_TYPE_ITEM_LIST,
      Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS)),
  RECIPE(
      StructuredViewContract.VIEW_TYPE_RECIPE,
      Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS, ClientFeature.RECIPE_VIEW)),
  BUILD_PREVIEW(
      StructuredViewContract.VIEW_TYPE_BUILD_PREVIEW, Set.of(ClientFeature.LITEMATICA_PREVIEW)),
  PROPOSAL(StructuredViewContract.VIEW_TYPE_PROPOSAL, Set.of(ClientFeature.OVERLAY)),
  SELECTION_LIST(StructuredViewContract.VIEW_TYPE_SELECTION_LIST, Set.of(ClientFeature.OVERLAY));

  private final String wireName;
  private final Set<ClientFeature> requiredFeatures;

  ClientViewType(String wireName, Set<ClientFeature> requiredFeatures) {
    this.wireName = wireName;
    this.requiredFeatures = requiredFeatures;
  }

  public String wireName() {
    return wireName;
  }

  public Set<ClientFeature> requiredFeatures() {
    return requiredFeatures;
  }

  public static ClientViewType fromWireName(String wireName) {
    return Arrays.stream(values())
        .filter(type -> type.wireName.equals(wireName))
        .findFirst()
        .orElseThrow(() -> new ClientProtocolException("CLIENT_VIEW_TYPE_UNKNOWN"));
  }
}
