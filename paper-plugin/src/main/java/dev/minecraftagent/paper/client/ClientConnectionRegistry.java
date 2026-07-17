package dev.minecraftagent.paper.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe registry binding immutable client state to server-observed player connections. */
public final class ClientConnectionRegistry {
  private final Map<UUID, ClientConnection> connections = new LinkedHashMap<>();
  private long nextGeneration = 1;

  /** Registers a server-observed join. A hello is accepted only after this call. */
  public synchronized ClientConnection join(UUID playerUuid) {
    Objects.requireNonNull(playerUuid);
    var connection = new ClientConnection(playerUuid, newGeneration(), null);
    connections.put(playerUuid, connection);
    return connection;
  }

  /** Atomically replaces the declaration while retaining the server-bound player identity. */
  public synchronized ClientConnection replace(UUID playerUuid, ClientHandshake handshake) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(handshake);
    if (!connections.containsKey(playerUuid)) {
      throw new ClientProtocolException("CLIENT_CONNECTION_UNKNOWN");
    }
    var connection = new ClientConnection(playerUuid, newGeneration(), handshake);
    connections.put(playerUuid, connection);
    return connection;
  }

  /** Replaces a rejected or failed negotiation with a fresh vanilla-only generation. */
  public synchronized Optional<ClientConnection> reset(UUID playerUuid) {
    Objects.requireNonNull(playerUuid);
    if (!connections.containsKey(playerUuid)) {
      return Optional.empty();
    }
    var connection = new ClientConnection(playerUuid, newGeneration(), null);
    connections.put(playerUuid, connection);
    return Optional.of(connection);
  }

  public synchronized Optional<ClientConnection> lookup(UUID playerUuid) {
    return Optional.ofNullable(connections.get(Objects.requireNonNull(playerUuid)));
  }

  public synchronized Optional<ClientConnection> quit(UUID playerUuid) {
    return Optional.ofNullable(connections.remove(Objects.requireNonNull(playerUuid)));
  }

  public synchronized Map<UUID, ClientConnection> snapshot() {
    return Map.copyOf(connections);
  }

  public synchronized void clear() {
    connections.clear();
  }

  private long newGeneration() {
    if (nextGeneration > Integer.MAX_VALUE) {
      throw new IllegalStateException("Client connection generation exhausted");
    }
    return nextGeneration++;
  }

  public record ClientConnection(UUID playerUuid, long generation, ClientHandshake handshake) {
    public ClientConnection {
      Objects.requireNonNull(playerUuid);
      if (generation < 1 || generation > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("generation must be positive");
      }
    }

    public boolean negotiated() {
      return handshake != null;
    }
  }
}
