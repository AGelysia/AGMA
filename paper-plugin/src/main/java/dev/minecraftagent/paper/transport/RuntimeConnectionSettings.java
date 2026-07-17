package dev.minecraftagent.paper.transport;

import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class RuntimeConnectionSettings {
  private final RuntimeDeploymentMode deploymentMode;
  private final URI endpoint;
  private final String serverId;
  private final String serverToken;
  private final String componentVersion;
  private final Duration connectTimeout;
  private final Duration handshakeTimeout;

  public RuntimeConnectionSettings(
      URI endpoint,
      String serverId,
      String serverToken,
      String componentVersion,
      Duration connectTimeout,
      Duration handshakeTimeout) {
    this(
        RuntimeDeploymentMode.EXTERNAL,
        endpoint,
        serverId,
        serverToken,
        componentVersion,
        connectTimeout,
        handshakeTimeout);
  }

  public RuntimeConnectionSettings(
      RuntimeDeploymentMode deploymentMode,
      URI endpoint,
      String serverId,
      String serverToken,
      String componentVersion,
      Duration connectTimeout,
      Duration handshakeTimeout) {
    this.deploymentMode = Objects.requireNonNull(deploymentMode);
    this.endpoint = Objects.requireNonNull(endpoint);
    this.serverId = Objects.requireNonNull(serverId);
    this.serverToken = Objects.requireNonNull(serverToken);
    this.componentVersion = Objects.requireNonNull(componentVersion);
    this.connectTimeout = Objects.requireNonNull(connectTimeout);
    this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout);
  }

  public RuntimeDeploymentMode deploymentMode() {
    return deploymentMode;
  }

  public URI endpoint() {
    return endpoint;
  }

  public String serverId() {
    return serverId;
  }

  String serverToken() {
    return serverToken;
  }

  public String componentVersion() {
    return componentVersion;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration handshakeTimeout() {
    return handshakeTimeout;
  }

  @Override
  public String toString() {
    return "RuntimeConnectionSettings[deploymentMode="
        + deploymentMode
        + ", endpoint=loopback, serverId=<redacted>, serverToken=<redacted>]";
  }
}
