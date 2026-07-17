package dev.minecraftagent.paper.transport;

import java.util.concurrent.CompletionStage;

public interface RuntimeConnector {
  CompletionStage<AuthenticatedRuntimeConnection> connect(RuntimeConnectionSettings settings);

  default RuntimeConnectAttempt begin(RuntimeConnectionSettings settings) {
    var result = connect(settings);
    return new RuntimeConnectAttempt() {
      @Override
      public CompletionStage<AuthenticatedRuntimeConnection> result() {
        return result;
      }

      @Override
      public void cancel() {
        // Legacy/test connectors still rely on generation checks to close a late result.
      }
    };
  }
}
