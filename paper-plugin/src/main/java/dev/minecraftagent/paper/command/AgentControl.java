package dev.minecraftagent.paper.command;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface AgentControl {
  void turnOff();

  RecoveryRequest turnOn();

  enum RecoveryDisposition {
    STARTED,
    ALREADY_STARTING,
    ALREADY_ONLINE,
    UNAVAILABLE
  }

  record RecoveryRequest(RecoveryDisposition disposition, CompletionStage<Boolean> completion) {
    public RecoveryRequest {
      Objects.requireNonNull(disposition);
      Objects.requireNonNull(completion);
    }
  }
}
