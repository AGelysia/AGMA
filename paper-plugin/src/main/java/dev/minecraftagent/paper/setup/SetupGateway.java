package dev.minecraftagent.paper.setup;

import java.util.concurrent.CompletionStage;

/** Provides only the redacted state and the bounded retry operation used by /agma. */
public interface SetupGateway {
  RedactedSetupSnapshot snapshot();

  CompletionStage<RedactedSetupSnapshot> retry();
}
