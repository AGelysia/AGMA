package dev.minecraftagent.standalone.supervisor.install;

import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fully verified immutable installation and the only source of production launch paths. */
public record InstalledRuntime(
    Path installationRoot,
    RuntimePlatform platform,
    String runtimeVersion,
    String nodeVersion,
    String artifactSha256,
    String manifestSha256,
    Path executable,
    Path entrypoint) {
  public InstalledRuntime {
    installationRoot = absoluteNormalized(installationRoot, "installationRoot");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(runtimeVersion, "runtimeVersion");
    Objects.requireNonNull(nodeVersion, "nodeVersion");
    Objects.requireNonNull(artifactSha256, "artifactSha256");
    Objects.requireNonNull(manifestSha256, "manifestSha256");
    executable = child(installationRoot, executable, "executable");
    entrypoint = child(installationRoot, entrypoint, "entrypoint");
  }

  public RuntimeLaunchSpec launchSpec(
      UUID instanceId, Path stateRoot, Path configFile, Map<String, String> environment) {
    var state = absoluteNormalized(stateRoot, "stateRoot");
    var config = child(state, configFile, "configFile");
    return new RuntimeLaunchSpec(
        instanceId,
        state,
        executable,
        List.of(entrypoint.toString(), "--config", config.toString(), "--managed"),
        environment);
  }

  private static Path child(Path root, Path value, String field) {
    var candidate = absoluteNormalized(value, field);
    if (candidate.equals(root) || !candidate.startsWith(root)) {
      throw new IllegalArgumentException(field + " must remain beneath its private root");
    }
    return candidate;
  }

  private static Path absoluteNormalized(Path value, String field) {
    Objects.requireNonNull(value, field);
    var normalized = value.normalize();
    if (!normalized.isAbsolute() || !normalized.equals(value)) {
      throw new IllegalArgumentException(field + " must be an absolute normalized path");
    }
    return normalized;
  }
}
