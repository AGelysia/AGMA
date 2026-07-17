package dev.minecraftagent.paper.management.reload;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import dev.minecraftagent.paper.startup.PaperStartupConfig;
import dev.minecraftagent.paper.startup.SecurityPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A completely parsed Paper configuration candidate with no publication side effects. */
public final class ReloadCandidate {
  private final String serverId;
  private final Set<UUID> owners;
  private final RuntimeDeploymentMode runtimeMode;
  private final URI runtimeEndpoint;
  private final String serverToken;
  private final Duration connectTimeout;
  private final Duration handshakeTimeout;
  private final Path stateDirectory;
  private final SecurityPolicy securityPolicy;
  private final Path capabilityDirectory;
  private final Set<CapabilityApproval> capabilityApprovals;

  private ReloadCandidate(PaperStartupConfig config) {
    serverId = Objects.requireNonNull(config.serverId());
    owners = Set.copyOf(config.owners());
    var runtime = Objects.requireNonNull(config.runtime());
    runtimeMode = Objects.requireNonNull(runtime.mode());
    runtimeEndpoint = Objects.requireNonNull(runtime.endpoint());
    serverToken = Objects.requireNonNull(runtime.serverToken());
    connectTimeout = Objects.requireNonNull(runtime.connectTimeout());
    handshakeTimeout = Objects.requireNonNull(runtime.handshakeTimeout());
    stateDirectory = Objects.requireNonNull(config.stateDirectory());
    securityPolicy = Objects.requireNonNull(config.securityPolicy());
    capabilityDirectory = Objects.requireNonNull(config.optionalCapabilityDirectory());
    capabilityApprovals = Set.copyOf(config.capabilityApprovals());
  }

  RuntimeDeploymentMode runtimeMode() {
    return runtimeMode;
  }

  public static ReloadCandidate from(PaperStartupConfig config) {
    return new ReloadCandidate(Objects.requireNonNull(config));
  }

  String serverId() {
    return serverId;
  }

  Set<UUID> owners() {
    return owners;
  }

  URI runtimeEndpoint() {
    return runtimeEndpoint;
  }

  String serverToken() {
    return serverToken;
  }

  Duration connectTimeout() {
    return connectTimeout;
  }

  Duration handshakeTimeout() {
    return handshakeTimeout;
  }

  Path stateDirectory() {
    return stateDirectory;
  }

  SecurityPolicy securityPolicy() {
    return securityPolicy;
  }

  Path capabilityDirectory() {
    return capabilityDirectory;
  }

  Set<CapabilityApproval> capabilityApprovals() {
    return capabilityApprovals;
  }

  ReloadPolicySnapshot policySnapshot(long generation) {
    return new ReloadPolicySnapshot(generation, owners, securityPolicy);
  }

  @Override
  public String toString() {
    return "ReloadCandidate[serverId="
        + serverId
        + ", ownersCount="
        + owners.size()
        + ", runtimeMode="
        + runtimeMode
        + ", runtimeEndpoint=<redacted>, serverToken=<redacted>, stateDirectory=<redacted>"
        + ", securityPolicy="
        + securityPolicy
        + ", capabilityDirectory=<redacted>, capabilityApprovalsCount="
        + capabilityApprovals.size()
        + "]";
  }
}
