package dev.minecraftagent.standalone.supervisor.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NodeDistributionCatalogTest {
  @Test
  void officialLinuxAndWindowsDistributionsArePinnedBySha256() throws Exception {
    var path =
        Path.of(System.getProperty("minecraftAgent.standaloneDir"))
            .resolve("managed-runtime/node-distributions.json");
    var catalog = NodeDistributionCatalog.load(Files.newInputStream(path));

    assertEquals("22.23.1", catalog.nodeVersion());
    assertEquals(Set.of(RuntimePlatform.values()), catalog.platforms());
    assertEquals(
        "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578",
        catalog.distribution(RuntimePlatform.LINUX_X86_64).sha256());
    assertEquals(
        "7df0bc9375723f4a86b3aa1b7cc73342423d9677a8df4538aca31a049e309c29",
        catalog.distribution(RuntimePlatform.WINDOWS_X86_64).sha256());
    assertEquals(
        NodeDistribution.ArchiveType.TAR_XZ,
        catalog.distribution(RuntimePlatform.LINUX_X86_64).archiveType());
    assertEquals(
        NodeDistribution.ArchiveType.ZIP,
        catalog.distribution(RuntimePlatform.WINDOWS_X86_64).archiveType());
  }

  @Test
  void rejectsUnknownFieldsDuplicatesAndNonOfficialUrls() {
    for (var invalid :
        Set.of(
            "{\"schemaVersion\":1,\"schemaVersion\":1}",
            "{\"schemaVersion\":1,\"nodeVersion\":\"22.23.1\","
                + "\"checksumSource\":\"https://example.com/SHASUMS256.txt\","
                + "\"distributions\":[]}",
            "{\"schemaVersion\":1,\"nodeVersion\":\"22.23.1\","
                + "\"checksumSource\":\"https://nodejs.org/dist/v22.23.1/SHASUMS256.txt\","
                + "\"distributions\":[],\"unexpected\":true}")) {
      var bytes = invalid.getBytes(StandardCharsets.UTF_8);
      assertThrows(
          java.io.IOException.class,
          () -> NodeDistributionCatalog.load(new ByteArrayInputStream(bytes)));
    }
  }

  @Test
  void rootFieldOrderDoesNotChangeCatalogMeaning() throws Exception {
    var path =
        Path.of(System.getProperty("minecraftAgent.standaloneDir"))
            .resolve("managed-runtime/node-distributions.json");
    var canonical = Files.readString(path);
    var reordered =
        canonical.replace(
            "  \"nodeVersion\": \"22.23.1\",\n"
                + "  \"checksumSource\": \"https://nodejs.org/dist/v22.23.1/SHASUMS256.txt\",\n"
                + "  \"distributions\": [",
            "  \"distributions\": [");
    reordered =
        reordered.replace(
            "\n  ]\n}",
            "\n  ],\n"
                + "  \"checksumSource\": \"https://nodejs.org/dist/v22.23.1/SHASUMS256.txt\",\n"
                + "  \"nodeVersion\": \"22.23.1\"\n}");

    var catalog =
        NodeDistributionCatalog.load(
            new ByteArrayInputStream(reordered.getBytes(StandardCharsets.UTF_8)));

    assertEquals("22.23.1", catalog.nodeVersion());
    assertEquals(Set.of(RuntimePlatform.values()), catalog.platforms());
  }
}
