package dev.minecraftagent.paper.client;

import java.util.Objects;
import java.util.UUID;

/** Keeps handshake and transfer generations in lockstep across join, re-hello, and cleanup. */
public final class ClientStateCoordinator implements AutoCloseable {
  private final ClientConnectionRegistry connections;
  private final ClientTransferManager transfers;

  public ClientStateCoordinator(
      ClientConnectionRegistry connections, ClientTransferManager transfers) {
    this.connections = Objects.requireNonNull(connections);
    this.transfers = Objects.requireNonNull(transfers);
  }

  public synchronized ClientConnectionRegistry.ClientConnection join(UUID playerUuid) {
    var connection = connections.join(playerUuid);
    transfers.open(playerUuid, connection.generation());
    return connection;
  }

  public synchronized ClientConnectionRegistry.ClientConnection negotiate(
      UUID playerUuid, ClientHandshake handshake) {
    var connection = connections.replace(playerUuid, handshake);
    transfers.open(playerUuid, connection.generation());
    return connection;
  }

  /** Atomically drops advertised capabilities and pending transfers after a protocol rejection. */
  public synchronized java.util.Optional<ClientConnectionRegistry.ClientConnection> reject(
      UUID playerUuid) {
    var connection = connections.reset(playerUuid);
    connection.ifPresent(value -> transfers.open(playerUuid, value.generation()));
    return connection;
  }

  public synchronized void quit(UUID playerUuid) {
    transfers.disconnect(playerUuid);
    connections.quit(playerUuid);
  }

  public ClientCapabilitySnapshot capabilitySnapshot(UUID playerUuid) {
    var connection = connections.lookup(Objects.requireNonNull(playerUuid));
    if (connection.isEmpty() || !connection.orElseThrow().negotiated()) {
      return ClientCapabilitySnapshot.disconnected();
    }
    var state = connection.orElseThrow();
    return new ClientCapabilitySnapshot(
        true,
        state.handshake().clientProtocolVersion(),
        state.handshake().capabilities(),
        state.generation());
  }

  public java.util.Optional<ClientConnectionRegistry.ClientConnection> connection(UUID playerUuid) {
    return connections.lookup(Objects.requireNonNull(playerUuid));
  }

  /** Returns current-generation diagnostic counts without exposing player identities. */
  public ClientDiagnosticsSnapshot diagnosticsSnapshot() {
    return ClientDiagnosticsSnapshot.from(connections.snapshot().values());
  }

  /** Offline cleanup cancels transfers without discarding a still-connected client's handshake. */
  public synchronized void clearTransientTransfers() {
    transfers.clearPending();
  }

  /** Suitable for plugin disable and the lifecycle Offline client-state cleanup port. */
  public synchronized void clearTransientState() {
    transfers.clear();
    connections.clear();
  }

  @Override
  public void close() {
    clearTransientState();
  }
}
