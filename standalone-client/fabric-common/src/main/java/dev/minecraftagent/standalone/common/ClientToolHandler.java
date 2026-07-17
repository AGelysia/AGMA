package dev.minecraftagent.standalone.common;

import java.util.concurrent.CompletionStage;

/** Version-shell port for executing negotiated client-visible tools. */
@FunctionalInterface
public interface ClientToolHandler {
  CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call);

  default void cancel(ClientToolCancellation cancellation) {}
}
