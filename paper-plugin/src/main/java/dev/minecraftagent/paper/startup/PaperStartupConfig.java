package dev.minecraftagent.paper.startup;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PaperStartupConfig {
  public static final class RuntimeSettings {
    private final RuntimeDeploymentMode mode;
    private final URI endpoint;
    private final String serverToken;
    private final Duration connectTimeout;
    private final Duration handshakeTimeout;

    public RuntimeSettings(
        URI endpoint, String serverToken, Duration connectTimeout, Duration handshakeTimeout) {
      this(RuntimeDeploymentMode.EXTERNAL, endpoint, serverToken, connectTimeout, handshakeTimeout);
    }

    public RuntimeSettings(
        RuntimeDeploymentMode mode,
        URI endpoint,
        String serverToken,
        Duration connectTimeout,
        Duration handshakeTimeout) {
      this.mode = Objects.requireNonNull(mode);
      this.endpoint = Objects.requireNonNull(endpoint);
      this.serverToken = Objects.requireNonNull(serverToken);
      this.connectTimeout = Objects.requireNonNull(connectTimeout);
      this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout);
    }

    public RuntimeDeploymentMode mode() {
      return mode;
    }

    public URI endpoint() {
      return endpoint;
    }

    public String serverToken() {
      return serverToken;
    }

    public Duration connectTimeout() {
      return connectTimeout;
    }

    public Duration handshakeTimeout() {
      return handshakeTimeout;
    }

    @Override
    public String toString() {
      return "RuntimeSettings[mode="
          + mode
          + ", endpoint=loopback, serverToken=<redacted>, connectTimeout="
          + connectTimeout
          + ", handshakeTimeout="
          + handshakeTimeout
          + "]";
    }
  }

  private final String serverId;
  private final Set<UUID> owners;
  private final RuntimeSettings runtime;
  private final Path stateDirectory;
  private final SecurityPolicy securityPolicy;
  private final Path optionalCapabilityDirectory;
  private final Set<CapabilityApproval> capabilityApprovals;

  public PaperStartupConfig(
      String serverId,
      Set<UUID> owners,
      RuntimeSettings runtime,
      Path stateDirectory,
      SecurityPolicy securityPolicy,
      Path optionalCapabilityDirectory,
      Set<CapabilityApproval> capabilityApprovals) {
    this.serverId = Objects.requireNonNull(serverId);
    this.owners = Set.copyOf(owners);
    this.runtime = Objects.requireNonNull(runtime);
    this.stateDirectory = Objects.requireNonNull(stateDirectory);
    this.securityPolicy = Objects.requireNonNull(securityPolicy);
    this.optionalCapabilityDirectory = Objects.requireNonNull(optionalCapabilityDirectory);
    this.capabilityApprovals = Set.copyOf(capabilityApprovals);
  }

  public String serverId() {
    return serverId;
  }

  public Set<UUID> owners() {
    return owners;
  }

  public RuntimeSettings runtime() {
    return runtime;
  }

  public Path stateDirectory() {
    return stateDirectory;
  }

  public SecurityPolicy securityPolicy() {
    return securityPolicy;
  }

  public Path optionalCapabilityDirectory() {
    return optionalCapabilityDirectory;
  }

  public Set<CapabilityApproval> capabilityApprovals() {
    return capabilityApprovals;
  }

  @Override
  public String toString() {
    return "PaperStartupConfig[serverId="
        + serverId
        + ", ownersCount="
        + owners.size()
        + ", runtime="
        + runtime
        + ", stateDirectory=<redacted>, securityPolicy="
        + securityPolicy
        + ", optionalCapabilityDirectory=<redacted>, capabilityApprovalsCount="
        + capabilityApprovals.size()
        + "]";
  }
}
