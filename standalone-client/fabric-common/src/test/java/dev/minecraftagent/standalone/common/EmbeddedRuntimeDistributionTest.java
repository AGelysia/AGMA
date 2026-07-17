package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class EmbeddedRuntimeDistributionTest {
  @Test
  void readsTheClosedEmbeddedArtifactDescriptor() throws Exception {
    var sha = "a".repeat(64);
    var descriptor =
        "{\"schemaVersion\":1,\"product\":\"agma-standalone-runtime-artifact\","
            + "\"platform\":\"linux-x86_64\",\"runtimeVersion\":\"0.2.0\","
            + "\"nodeVersion\":\"22.23.1\",\"byteSize\":3,\"sha256\":\""
            + sha
            + "\",\"archive\":\"META-INF/agma-standalone/runtime.zip\"}";
    var distribution =
        EmbeddedRuntimeDistribution.load(
            resources(descriptor.getBytes(StandardCharsets.UTF_8), new byte[] {1, 2, 3}));
    assertEquals("0.2.0", distribution.artifact().runtimeVersion());
    assertEquals(3, distribution.source().open().readAllBytes().length);
  }

  @Test
  void rejectsMissingOrOpenDescriptors() {
    var missing =
        assertThrows(
            ClientRuntimeException.class,
            () -> EmbeddedRuntimeDistribution.load(resources(null, null)));
    assertEquals("RUNTIME_ARTIFACT_MISSING", missing.code());
    var open =
        "{\"schemaVersion\":1,\"product\":\"agma-standalone-runtime-artifact\","
            + "\"platform\":\"linux-x86_64\",\"runtimeVersion\":\"0.2.0\","
            + "\"nodeVersion\":\"22.23.1\",\"byteSize\":3,\"sha256\":\""
            + "a".repeat(64)
            + "\",\"archive\":\"META-INF/agma-standalone/runtime.zip\",\"extra\":true}";
    assertThrows(
        ClientRuntimeException.class,
        () ->
            EmbeddedRuntimeDistribution.load(
                resources(open.getBytes(StandardCharsets.UTF_8), new byte[3])));
  }

  private static ClassLoader resources(byte[] descriptor, byte[] archive) {
    return new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(String name) {
        if (EmbeddedRuntimeDistribution.DESCRIPTOR_RESOURCE.equals(name) && descriptor != null) {
          return new ByteArrayInputStream(descriptor);
        }
        if (EmbeddedRuntimeDistribution.ARCHIVE_RESOURCE.equals(name) && archive != null) {
          return new ByteArrayInputStream(archive);
        }
        return null;
      }
    };
  }
}
