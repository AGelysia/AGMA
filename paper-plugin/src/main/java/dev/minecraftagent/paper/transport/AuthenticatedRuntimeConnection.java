package dev.minecraftagent.paper.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface AuthenticatedRuntimeConnection extends AutoCloseable {
  boolean isOpen();

  CompletionStage<Void> whenClosed();

  default CompletionStage<Void> sendApplication(String message) {
    return CompletableFuture.failedFuture(
        new RuntimeConnectionFailure("APPLICATION_CHANNEL_UNAVAILABLE", "protocol"));
  }

  default void setApplicationHandler(Consumer<String> handler) {
    throw new IllegalStateException("Application channel is unavailable");
  }

  @Override
  void close();
}
