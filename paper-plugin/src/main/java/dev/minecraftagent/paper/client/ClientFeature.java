package dev.minecraftagent.paper.client;

import java.util.Arrays;

/** Closed client feature names from the version 1.0 handshake. */
public enum ClientFeature {
  OVERLAY("overlay", 1),
  ITEM_ICONS("itemIcons", 1),
  RECIPE_VIEW("recipeView", 2),
  LITEMATICA_PREVIEW("litematicaPreview", 1),
  LITEMATICA_MATERIAL_LIST("litematicaMaterialList", 1);

  private final String wireName;
  private final int maximumVersion;

  ClientFeature(String wireName, int maximumVersion) {
    this.wireName = wireName;
    this.maximumVersion = maximumVersion;
  }

  public String wireName() {
    return wireName;
  }

  public int maximumVersion() {
    return maximumVersion;
  }

  public static ClientFeature fromWireName(String wireName) {
    return Arrays.stream(values())
        .filter(feature -> feature.wireName.equals(wireName))
        .findFirst()
        .orElseThrow(() -> new ClientProtocolException("CLIENT_FEATURE_UNKNOWN"));
  }
}
