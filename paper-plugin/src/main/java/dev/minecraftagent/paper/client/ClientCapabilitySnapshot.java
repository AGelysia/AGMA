package dev.minecraftagent.paper.client;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Immutable projection used by the Runtime {@code agent.request} payload. */
public record ClientCapabilitySnapshot(
    boolean connected,
    String clientProtocolVersion,
    ClientCapabilities capabilities,
    Long generation) {
  public ClientCapabilitySnapshot {
    Objects.requireNonNull(capabilities);
    if (connected) {
      if (!ClientHandshake.isSupportedProtocolVersion(clientProtocolVersion)
          || generation == null
          || generation < 1
          || generation > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("Connected client snapshot is incomplete");
      }
    } else if (clientProtocolVersion != null || generation != null) {
      throw new IllegalArgumentException("Disconnected client snapshot carries connection state");
    }
  }

  public static ClientCapabilitySnapshot disconnected() {
    var versions = new java.util.EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      versions.put(feature, 0);
    }
    return new ClientCapabilitySnapshot(false, null, new ClientCapabilities(versions), null);
  }

  public JsonObject toAgentRequestJson() {
    var features = new JsonObject();
    for (var feature : ClientFeature.values()) {
      features.addProperty(feature.wireName(), capabilities.version(feature));
    }
    var result = new JsonObject();
    result.addProperty("connected", connected);
    if (clientProtocolVersion == null) {
      result.add("clientProtocolVersion", JsonNull.INSTANCE);
    } else {
      result.addProperty("clientProtocolVersion", clientProtocolVersion);
    }
    result.add("features", features);
    return result;
  }
}
