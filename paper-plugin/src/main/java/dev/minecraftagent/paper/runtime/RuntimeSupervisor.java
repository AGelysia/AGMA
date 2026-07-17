package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;

public interface RuntimeSupervisor extends AutoCloseable {
  RuntimeStartAttempt prepare(RuntimeConnectionSettings settings);

  void stop();

  @Override
  void close();
}
