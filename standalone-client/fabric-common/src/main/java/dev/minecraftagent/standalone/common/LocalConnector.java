package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Authenticated local-only connector port. Implementations may never route through a game server.
 */
public interface LocalConnector extends AutoCloseable {
  CompletionStage<Session> connect(RuntimeClientProfile profile, SecretMaterial connectorToken);

  @Override
  void close();

  interface Session {
    ConnectorSessionState state();

    CompletionStage<TextCompletion> request(TextRequest request);

    boolean cancel(UUID requestId);

    boolean cancel(UUID requestId, CancelReason reason);

    CompletionStage<RuntimeStatus> queryStatus(Duration timeout);

    /** Installs the version-shell executor used only for tools negotiated in the handshake. */
    void setToolHandler(ClientToolHandler handler);

    Set<String> activeTools();

    CompletionStage<Void> close();
  }
}
