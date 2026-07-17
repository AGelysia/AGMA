package dev.minecraftagent.paper.capability.load;

/** Bounded discovery and YAML parsing limits for one capability root. */
public record CapabilityLoadLimits(
    int maximumEntries,
    int maximumFiles,
    int maximumFileBytes,
    long maximumTotalBytes,
    int maximumDirectoryDepth,
    int maximumAliases,
    int maximumYamlDepth) {
  public CapabilityLoadLimits {
    if (maximumEntries < 1
        || maximumEntries > 100_000
        || maximumFiles < 1
        || maximumFiles > maximumEntries
        || maximumFileBytes < 1
        || maximumFileBytes > 16 * 1024 * 1024
        || maximumTotalBytes < maximumFileBytes
        || maximumTotalBytes > 256L * 1024 * 1024
        || maximumDirectoryDepth < 0
        || maximumDirectoryDepth > 32
        || maximumAliases < 0
        || maximumAliases > 32
        || maximumYamlDepth < 1) {
      throw new IllegalArgumentException("Invalid capability load limits");
    }
  }

  public static CapabilityLoadLimits defaults() {
    return new CapabilityLoadLimits(512, 128, 256 * 1024, 4L * 1024 * 1024, 8, 0, 32);
  }
}
