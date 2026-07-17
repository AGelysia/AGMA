package dev.minecraftagent.paper.runtime.install;

import java.util.Objects;
import java.util.regex.Pattern;

public record ManagedRuntimeArtifact(String resourceName, long byteSize, String sha256) {
  private static final long MAXIMUM_ARTIFACT_BYTES = 512L * 1024L * 1024L;
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  public ManagedRuntimeArtifact {
    Objects.requireNonNull(resourceName, "resourceName");
    Objects.requireNonNull(sha256, "sha256");
    if (!validResourceName(resourceName)
        || byteSize < 1
        || byteSize > MAXIMUM_ARTIFACT_BYTES
        || !SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("Invalid managed runtime artifact");
    }
  }

  private static boolean validResourceName(String value) {
    if (value.isEmpty()
        || value.length() > 256
        || value.startsWith("/")
        || value.endsWith("/")
        || value.indexOf('\\') >= 0
        || value.indexOf('\0') >= 0) {
      return false;
    }
    for (var segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }
}
