package dev.minecraftagent.standalone.supervisor.install;

import java.util.Objects;
import java.util.regex.Pattern;

/** Build-time metadata for one embedded, platform-specific standalone Runtime ZIP. */
public record ManagedRuntimeArtifact(
    RuntimePlatform platform,
    String runtimeVersion,
    String nodeVersion,
    long byteSize,
    String sha256) {
  private static final long MAXIMUM_ARCHIVE_BYTES = 512L * 1024L * 1024L;
  private static final Pattern VERSION =
      Pattern.compile(
          "^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
              + "(?:[-+][0-9A-Za-z.-]+)?$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  public ManagedRuntimeArtifact {
    Objects.requireNonNull(platform, "platform");
    runtimeVersion = version(runtimeVersion, "runtimeVersion");
    nodeVersion = version(nodeVersion, "nodeVersion");
    if (byteSize < 1 || byteSize > MAXIMUM_ARCHIVE_BYTES) {
      throw new IllegalArgumentException("byteSize is outside the managed Runtime archive limit");
    }
    Objects.requireNonNull(sha256, "sha256");
    if (!SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
    }
  }

  private static String version(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.length() > 64 || !VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded semantic version");
    }
    return value;
  }
}
