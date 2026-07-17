package dev.minecraftagent.paper.client;

import java.util.Arrays;
import java.util.Set;

/** Closed structured view types and their mandatory client features. */
public enum ClientViewType {
  TEXT("text", Set.of(ClientFeature.OVERLAY)),
  ITEM_STACK("item_stack", Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS)),
  ITEM_LIST("item_list", Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS)),
  RECIPE(
      "recipe", Set.of(ClientFeature.OVERLAY, ClientFeature.ITEM_ICONS, ClientFeature.RECIPE_VIEW)),
  BUILD_PREVIEW("build_preview", Set.of(ClientFeature.LITEMATICA_PREVIEW)),
  PROPOSAL("proposal", Set.of(ClientFeature.OVERLAY)),
  SELECTION_LIST("selection_list", Set.of(ClientFeature.OVERLAY));

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
