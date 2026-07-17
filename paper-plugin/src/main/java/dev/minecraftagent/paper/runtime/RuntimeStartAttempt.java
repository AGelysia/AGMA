package dev.minecraftagent.paper.runtime;

import java.util.concurrent.CompletionStage;

public interface RuntimeStartAttempt {
  CompletionStage<Void> ready();

  void cancel();
}
