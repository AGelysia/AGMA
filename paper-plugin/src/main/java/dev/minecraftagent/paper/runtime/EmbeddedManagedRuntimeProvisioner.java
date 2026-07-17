package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.runtime.install.ManagedRuntimeArtifact;
import dev.minecraftagent.paper.runtime.install.ManagedRuntimeInstallException;
import dev.minecraftagent.paper.runtime.install.ManagedRuntimeInstaller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Lazily verifies and installs the sidecar embedded in an offline Paper JAR. */
public final class EmbeddedManagedRuntimeProvisioner implements ProcessFactory {
  private static final String DESCRIPTOR_RESOURCE = "managed-runtime/artifact.properties";
  private static final String SIDECAR_RESOURCE = "managed-runtime/sidecar.zip";
  private static final int MAX_DESCRIPTOR_BYTES = 4096;
  private static final Set<String> DESCRIPTOR_KEYS =
      Set.of("schemaVersion", "resourceName", "byteSize", "sha256");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  private final ClassLoader resources;
  private final ManagedRuntimeInstaller installer;
  private final Path managedRoot;
  private final String runtimeVersion;
  private final ProcessFactory delegate;

  public EmbeddedManagedRuntimeProvisioner(
      ClassLoader resources,
      ManagedRuntimeInstaller installer,
      Path managedRoot,
      String runtimeVersion,
      ProcessFactory delegate) {
    this.resources = Objects.requireNonNull(resources);
    this.installer = Objects.requireNonNull(installer);
    this.managedRoot = Objects.requireNonNull(managedRoot).toAbsolutePath().normalize();
    this.runtimeVersion = Objects.requireNonNull(runtimeVersion);
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public Process start(ProcessBuilder builder) throws IOException {
    requireNotInterrupted();
    if (!supportedPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))) {
      throw failure("MANAGED_RUNTIME_PLATFORM_UNSUPPORTED");
    }
    var artifact = loadArtifact();
    final Path installed;
    try {
      installed =
          installer.install(
              managedRoot,
              artifact,
              () -> {
                var stream = resources.getResourceAsStream(SIDECAR_RESOURCE);
                if (stream == null) {
                  throw new IOException("Embedded sidecar is unavailable");
                }
                return stream;
              });
    } catch (ManagedRuntimeInstallException error) {
      throw failure(error.code());
    }
    var expected = managedRoot.resolve("current").resolve(runtimeVersion).normalize();
    if (!installed.equals(expected)) {
      throw failure("MANAGED_RUNTIME_VERSION_MISMATCH");
    }
    requireNotInterrupted();
    return delegate.start(builder);
  }

  private static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("INSTALL_CANCELLED");
    }
  }

  private ManagedRuntimeArtifact loadArtifact() {
    byte[] bytes;
    try (var input = resources.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
      if (input == null) {
        throw failure("MANAGED_RUNTIME_ARTIFACT_MISSING");
      }
      bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
      if (bytes.length > MAX_DESCRIPTOR_BYTES) {
        throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
      }
    } catch (IOException error) {
      throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
    }
    for (var value : bytes) {
      if ((value & 0x80) != 0 || (value < 0x20 && value != '\n' && value != '\r')) {
        throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
      }
    }
    var values = parseDescriptor(new String(bytes, StandardCharsets.US_ASCII));
    if (!"1".equals(values.get("schemaVersion"))
        || !SIDECAR_RESOURCE.equals(values.get("resourceName"))) {
      throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
    }
    var sha256 = values.get("sha256");
    if (sha256 == null || !SHA256.matcher(sha256).matches()) {
      throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
    }
    try {
      return new ManagedRuntimeArtifact(
          SIDECAR_RESOURCE, Long.parseLong(values.get("byteSize")), sha256);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
    }
  }

  private static Map<String, String> parseDescriptor(String source) {
    var values = new LinkedHashMap<String, String>();
    for (var line : source.split("\\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      var separator = line.indexOf('=');
      if (separator <= 0
          || separator == line.length() - 1
          || line.indexOf('=', separator + 1) >= 0) {
        throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
      }
      var key = line.substring(0, separator);
      var value = line.substring(separator + 1);
      if (!DESCRIPTOR_KEYS.contains(key) || values.putIfAbsent(key, value) != null) {
        throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
      }
    }
    if (!values.keySet().equals(DESCRIPTOR_KEYS)) {
      throw failure("MANAGED_RUNTIME_ARTIFACT_INVALID");
    }
    return Map.copyOf(values);
  }

  private static boolean supportedPlatform(String operatingSystem, String architecture) {
    if (operatingSystem == null || architecture == null) {
      return false;
    }
    var os = operatingSystem.toLowerCase(java.util.Locale.ROOT);
    var arch = architecture.toLowerCase(java.util.Locale.ROOT);
    return os.contains("linux")
        && (arch.equals("amd64") || arch.equals("x86_64"))
        && !java.nio.file.Files.exists(Path.of("/etc/alpine-release"));
  }

  private static RuntimeSupervisorException failure(String code) {
    return new RuntimeSupervisorException(code, "Managed runtime provisioning failed");
  }
}
