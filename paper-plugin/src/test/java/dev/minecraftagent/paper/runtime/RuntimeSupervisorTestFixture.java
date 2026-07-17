package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.net.URI;
import java.time.Duration;

final class RuntimeSupervisorTestFixture {
  private RuntimeSupervisorTestFixture() {}

  static RuntimeConnectionSettings connectionSettings() {
    return new RuntimeConnectionSettings(
        URI.create("ws://127.0.0.1:8765/agent"),
        "test-server",
        "test-token-that-is-at-least-32-characters",
        "test-version",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }
}
