package dev.minecraftagent.paper.transport;

import java.util.concurrent.CompletionStage;

public interface RuntimeConnectAttempt {
  CompletionStage<AuthenticatedRuntimeConnection> result();

  void cancel();
}
