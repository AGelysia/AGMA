package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;

@FunctionalInterface
public interface HealthProbe {
  boolean isReady(RuntimeConnectionSettings settings) throws Exception;
}
