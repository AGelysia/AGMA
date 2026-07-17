package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ExternalRuntimeSupervisor implements RuntimeSupervisor {
  private static final RuntimeStartAttempt READY =
      new RuntimeStartAttempt() {
        private final CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<Void> ready() {
          return ready;
        }

        @Override
        public void cancel() {}
      };

  @Override
  public RuntimeStartAttempt prepare(RuntimeConnectionSettings settings) {
    Objects.requireNonNull(settings);
    return READY;
  }

  @Override
  public void stop() {}

  @Override
  public void close() {}
}
