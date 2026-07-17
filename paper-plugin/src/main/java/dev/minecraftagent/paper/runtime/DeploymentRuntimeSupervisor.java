package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.util.Objects;

/** Routes one fixed startup configuration without weakening the external deployment path. */
public final class DeploymentRuntimeSupervisor implements RuntimeSupervisor {
  private final RuntimeSupervisor external;
  private final RuntimeSupervisor managed;

  public DeploymentRuntimeSupervisor(RuntimeSupervisor managed) {
    this(new ExternalRuntimeSupervisor(), managed);
  }

  DeploymentRuntimeSupervisor(RuntimeSupervisor external, RuntimeSupervisor managed) {
    this.external = Objects.requireNonNull(external);
    this.managed = Objects.requireNonNull(managed);
  }

  @Override
  public RuntimeStartAttempt prepare(RuntimeConnectionSettings settings) {
    Objects.requireNonNull(settings);
    return delegate(settings.deploymentMode()).prepare(settings);
  }

  @Override
  public void stop() {
    managed.stop();
  }

  @Override
  public void close() {
    try {
      managed.close();
    } finally {
      external.close();
    }
  }

  private RuntimeSupervisor delegate(RuntimeDeploymentMode mode) {
    return mode == RuntimeDeploymentMode.MANAGED ? managed : external;
  }
}
