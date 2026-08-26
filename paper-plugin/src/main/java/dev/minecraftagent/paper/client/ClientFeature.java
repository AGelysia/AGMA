package dev.minecraftagent.paper.client;

import dev.minecraftagent.protocol.ClientChannelContract;
import java.util.Arrays;

/** Closed client feature names from the version 1.0 handshake. */
public enum ClientFeature {
  OVERLAY(ClientChannelContract.FEATURE_OVERLAY, 1),
  ITEM_ICONS(ClientChannelContract.FEATURE_ITEM_ICONS, 1),
  RECIPE_VIEW(ClientChannelContract.FEATURE_RECIPE_VIEW, 2),
  LITEMATICA_PREVIEW(ClientChannelContract.FEATURE_LITEMATICA_PREVIEW, 1),
  LITEMATICA_MATERIAL_LIST(ClientChannelContract.FEATURE_LITEMATICA_MATERIAL_LIST, 1);

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
