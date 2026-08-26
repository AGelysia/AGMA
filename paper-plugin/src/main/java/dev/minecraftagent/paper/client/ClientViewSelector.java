package dev.minecraftagent.paper.client;

import dev.minecraftagent.protocol.StructuredViewContract;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Chooses exact-version structured views while preserving an unconditional text fallback. */
public final class ClientViewSelector {
  private final ClientConnectionRegistry connections;
  private final ClientViewSchemaRegistry schemas;

  public ClientViewSelector(
      ClientConnectionRegistry connections, ClientViewSchemaRegistry schemas) {
    this.connections = Objects.requireNonNull(connections);
    this.schemas = Objects.requireNonNull(schemas);
  }

  public Selection select(UUID playerUuid, String fallbackText, List<ClientStructuredView> views) {
    Objects.requireNonNull(playerUuid);
    requireFallback(fallbackText);
    Objects.requireNonNull(views);
    if (views.size() > 8) {
      throw new ClientProtocolException("CLIENT_VIEW_COUNT_EXCEEDED");
    }
    var ids = new HashSet<UUID>();
    for (var view : views) {
      Objects.requireNonNull(view);
      if (!ids.add(view.viewId()) || !fallbackText.equals(view.fallbackText())) {
        throw new ClientProtocolException("CLIENT_VIEW_SET_INVALID");
      }
    }

    var connection = connections.lookup(playerUuid);
    if (connection.isEmpty()) {
      return new Selection(fallbackText, List.of(), null);
    }
    var selected =
        views.stream()
            .filter(
                view ->
                    schemas.canPublish(
                            connection.orElseThrow(), view.viewType(), view.viewSchemaVersion())
                        && supportsContentVersion(connection.orElseThrow(), view))
            .findFirst()
            .stream()
            .toList();
    return new Selection(fallbackText, selected, connection.orElseThrow().generation());
  }

  private static boolean supportsContentVersion(
      ClientConnectionRegistry.ClientConnection connection, ClientStructuredView view) {
    if (view.viewType() != ClientViewType.RECIPE) {
      return true;
    }
    var schemaVersion = view.content().get("schemaVersion").getAsString();
    var required = "2.0".equals(schemaVersion) ? 2 : 1;
    return connection.handshake().capabilities().supports(ClientFeature.RECIPE_VIEW, required);
  }

  private static void requireFallback(String fallbackText) {
    if (fallbackText == null
        || fallbackText.isBlank()
        || !StructuredViewContract.isWellFormedUtf16(fallbackText)
        || StructuredViewContract.codePointLength(fallbackText)
            > StructuredViewContract.FALLBACK_TEXT_MAX_CHARS
        || fallbackText
            .codePoints()
            .anyMatch(
                codePoint -> StructuredViewContract.isUnsafeVisibleCodePoint(codePoint, true))) {
      throw new ClientProtocolException("CLIENT_FALLBACK_INVALID");
    }
  }

  public record Selection(
      String fallbackText, List<ClientStructuredView> structuredViews, Long connectionGeneration) {
    public Selection {
      Objects.requireNonNull(fallbackText);
      structuredViews = List.copyOf(structuredViews);
    }

    public boolean usesFallbackOnly() {
      return structuredViews.isEmpty();
    }
  }
}
