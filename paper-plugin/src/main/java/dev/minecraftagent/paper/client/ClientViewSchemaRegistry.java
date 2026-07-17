package dev.minecraftagent.paper.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Server-owned compatibility table; client claims cannot add schema versions to it. */
public final class ClientViewSchemaRegistry {
  public static final String VIEW_SCHEMA_V1 = "1.0";

  private final Map<ClientViewType, Set<String>> versions;

  public ClientViewSchemaRegistry(Map<ClientViewType, Set<String>> versions) {
    Objects.requireNonNull(versions);
    var copy = new EnumMap<ClientViewType, Set<String>>(ClientViewType.class);
    for (var type : ClientViewType.values()) {
      var supported = Set.copyOf(versions.getOrDefault(type, Set.of()));
      for (var version : supported) {
        if (!VIEW_SCHEMA_V1.equals(version)) {
          throw new IllegalArgumentException("Unsupported server view schema version");
        }
      }
      copy.put(type, supported);
    }
    this.versions = Map.copyOf(copy);
  }

  public static ClientViewSchemaRegistry versionOne() {
    return versionOne(false);
  }

  public static ClientViewSchemaRegistry versionOne(boolean buildPreviewEnabled) {
    var versions = new EnumMap<ClientViewType, Set<String>>(ClientViewType.class);
    for (var type :
        Set.of(
            ClientViewType.TEXT,
            ClientViewType.ITEM_STACK,
            ClientViewType.ITEM_LIST,
            ClientViewType.RECIPE)) {
      versions.put(type, Set.of(VIEW_SCHEMA_V1));
    }
    if (buildPreviewEnabled) {
      versions.put(ClientViewType.BUILD_PREVIEW, Set.of(VIEW_SCHEMA_V1));
    }
    return new ClientViewSchemaRegistry(versions);
  }

  public boolean canPublish(
      ClientConnectionRegistry.ClientConnection connection,
      ClientViewType type,
      String viewSchemaVersion) {
    Objects.requireNonNull(connection);
    Objects.requireNonNull(type);
    Objects.requireNonNull(viewSchemaVersion);
    if (!connection.negotiated() || !versions.get(type).contains(viewSchemaVersion)) {
      return false;
    }
    var capabilities = connection.handshake().capabilities();
    return type.requiredFeatures().stream().allMatch(feature -> capabilities.supports(feature, 1));
  }
}
