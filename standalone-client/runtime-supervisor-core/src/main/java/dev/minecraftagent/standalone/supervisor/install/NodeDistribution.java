package dev.minecraftagent.standalone.supervisor.install;

import java.net.URI;

/** One official Node binary distribution pinned for standalone sidecar assembly. */
public record NodeDistribution(
    RuntimePlatform platform,
    String nodeVersion,
    String archive,
    ArchiveType archiveType,
    String rootDirectory,
    URI url,
    String sha256) {
  public enum ArchiveType {
    TAR_XZ("tar.xz"),
    ZIP("zip");

    private final String id;

    ArchiveType(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    static ArchiveType fromId(String id) {
      for (var value : values()) {
        if (value.id.equals(id)) {
          return value;
        }
      }
      throw new IllegalArgumentException("NODE_DISTRIBUTION_MANIFEST_INVALID");
    }
  }
}
