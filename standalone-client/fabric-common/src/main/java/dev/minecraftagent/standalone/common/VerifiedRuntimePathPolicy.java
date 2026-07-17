package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import dev.minecraftagent.standalone.supervisor.RuntimePathPolicy;
import dev.minecraftagent.standalone.supervisor.SupervisorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Rechecks managed installation and private state containment immediately before launch. */
public final class VerifiedRuntimePathPolicy implements RuntimePathPolicy {
  private final Path installationRoot;
  private final Path stateRoot;

  public VerifiedRuntimePathPolicy(Path installationRoot, Path stateRoot) {
    this.installationRoot = absolute(installationRoot, "installationRoot");
    this.stateRoot = absolute(stateRoot, "stateRoot");
  }

  @Override
  public RuntimeLaunchSpec prepare(RuntimeLaunchSpec requested) throws SupervisorException {
    try {
      if (!requested.stateRoot().equals(stateRoot)) {
        throw failure("PATH_POLICY_FAILED", "Runtime state root changed");
      }
      PrivateFilePermissions.prepareDirectory(stateRoot);
      requireInstalledFile(requested.executable());
      if (requested.arguments().isEmpty()) {
        throw failure("PATH_POLICY_FAILED", "Runtime entrypoint is unavailable");
      }
      requireInstalledFile(Path.of(requested.arguments().get(0)));
      var config = Path.of(requested.arguments().get(2));
      PrivateFilePermissions.verifyFile(stateRoot, config);
      return requested;
    } catch (SupervisorException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new SupervisorException(
          "PATH_POLICY_FAILED", "Runtime launch paths failed verification", exception);
    }
  }

  private void requireInstalledFile(Path requested) throws IOException {
    var path = absolute(requested, "managed Runtime file");
    if (path.equals(installationRoot)
        || !path.startsWith(installationRoot)
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Managed Runtime file is unsafe");
    }
  }

  private static Path absolute(Path value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    var normalized = value.toAbsolutePath().normalize();
    if (!normalized.equals(value)) {
      throw new IllegalArgumentException(field + " must be absolute and normalized");
    }
    return normalized;
  }

  private static SupervisorException failure(String code, String message) {
    return new SupervisorException(code, message);
  }
}
