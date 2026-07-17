package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.supervisor.install.ManagedRuntimeArtifact;
import dev.minecraftagent.standalone.supervisor.install.RuntimeArtifactSource;
import dev.minecraftagent.standalone.supervisor.install.RuntimePlatform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict descriptor and reopenable source for the one sidecar embedded in a platform JAR. */
public record EmbeddedRuntimeDistribution(
    ManagedRuntimeArtifact artifact, RuntimeArtifactSource source) {
  public static final String DESCRIPTOR_RESOURCE = "META-INF/agma-standalone/runtime-artifact.json";
  public static final String ARCHIVE_RESOURCE = "META-INF/agma-standalone/runtime.zip";
  private static final int MAXIMUM_DESCRIPTOR_BYTES = 4096;

  public EmbeddedRuntimeDistribution {
    Objects.requireNonNull(artifact, "artifact");
    Objects.requireNonNull(source, "source");
  }

  public static EmbeddedRuntimeDistribution load(ClassLoader loader) {
    Objects.requireNonNull(loader, "loader");
    try {
      return parse(loader, descriptor(loader));
    } catch (ClientRuntimeException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw failure(
          "RUNTIME_ARTIFACT_INVALID", "Embedded Runtime descriptor is invalid", exception);
    }
  }

  private static EmbeddedRuntimeDistribution parse(ClassLoader loader, String document) {
    var root =
        JsonFields.exactObject(
            StrictJson.parse(document),
            "/",
            "schemaVersion",
            "product",
            "platform",
            "runtimeVersion",
            "nodeVersion",
            "byteSize",
            "sha256",
            "archive");
    if (JsonFields.integer(root.get("schemaVersion"), "/schemaVersion") != 1
        || !"agma-standalone-runtime-artifact"
            .equals(JsonFields.string(root.get("product"), "/product", 64))
        || !ARCHIVE_RESOURCE.equals(JsonFields.string(root.get("archive"), "/archive", 128))) {
      throw failure("RUNTIME_ARTIFACT_INVALID", "Embedded Runtime descriptor is invalid");
    }
    final RuntimePlatform platform;
    try {
      platform = RuntimePlatform.fromId(JsonFields.string(root.get("platform"), "/platform", 32));
    } catch (IllegalArgumentException exception) {
      throw failure("RUNTIME_ARTIFACT_INVALID", "Embedded Runtime platform is invalid", exception);
    }
    final ManagedRuntimeArtifact artifact;
    try {
      artifact =
          new ManagedRuntimeArtifact(
              platform,
              JsonFields.string(root.get("runtimeVersion"), "/runtimeVersion", 64),
              JsonFields.string(root.get("nodeVersion"), "/nodeVersion", 64),
              JsonFields.longInteger(root.get("byteSize"), "/byteSize"),
              JsonFields.string(root.get("sha256"), "/sha256", 64));
    } catch (IllegalArgumentException exception) {
      throw failure("RUNTIME_ARTIFACT_INVALID", "Embedded Runtime metadata is invalid", exception);
    }
    return new EmbeddedRuntimeDistribution(
        artifact,
        () -> {
          var stream = loader.getResourceAsStream(ARCHIVE_RESOURCE);
          if (stream == null) {
            throw new IOException("Embedded Runtime archive is unavailable");
          }
          return stream;
        });
  }

  private static String descriptor(ClassLoader loader) {
    try (InputStream input = loader.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
      if (input == null) {
        throw failure("RUNTIME_ARTIFACT_MISSING", "This JAR contains no managed Runtime");
      }
      var bytes = input.readNBytes(MAXIMUM_DESCRIPTOR_BYTES + 1);
      if (bytes.length < 2 || bytes.length > MAXIMUM_DESCRIPTOR_BYTES) {
        throw failure("RUNTIME_ARTIFACT_INVALID", "Embedded Runtime descriptor is invalid");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (ClientRuntimeException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw failure(
          "RUNTIME_ARTIFACT_INVALID", "Embedded Runtime descriptor is unreadable", exception);
    }
  }

  private static ClientRuntimeException failure(String code, String message) {
    return new ClientRuntimeException(code, message);
  }

  private static ClientRuntimeException failure(String code, String message, Throwable cause) {
    return new ClientRuntimeException(code, message, cause);
  }
}
