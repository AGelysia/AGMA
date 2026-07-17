package dev.minecraftagent.paper.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, closed feature-version declaration from an untrusted client. */
public final class ClientCapabilities {
  private final Map<ClientFeature, Integer> versions;

  public ClientCapabilities(Map<ClientFeature, Integer> versions) {
    Objects.requireNonNull(versions);
    if (versions.size() != ClientFeature.values().length) {
      throw new ClientProtocolException("CLIENT_CAPABILITIES_INCOMPLETE");
    }
    var copy = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      var version = versions.get(feature);
      if (version == null || version < 0 || version > feature.maximumVersion()) {
        throw new ClientProtocolException("CLIENT_CAPABILITY_VERSION_INVALID");
      }
      copy.put(feature, version);
    }
    this.versions = Map.copyOf(copy);
  }

  public int version(ClientFeature feature) {
    return versions.get(Objects.requireNonNull(feature));
  }

  public boolean supports(ClientFeature feature, int version) {
    return version > 0 && version(feature) >= version;
  }

  public Map<ClientFeature, Integer> versions() {
    return versions;
  }
}
