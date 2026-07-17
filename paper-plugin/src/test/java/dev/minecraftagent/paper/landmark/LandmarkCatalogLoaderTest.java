package dev.minecraftagent.paper.landmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LandmarkCatalogLoaderTest {
  @TempDir Path directory;

  @BeforeEach
  void preparePrivateDirectory() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void installsPrivateEmptyDefaultAndLoadsBoundedClosedEntries() throws Exception {
    var loader = new LandmarkCatalogLoader();
    assertEquals(0, loader.loadOrCreate(directory).size());
    var file = directory.resolve(LandmarkCatalogLoader.FILE_NAME);
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));

    writePrivate(
        """
        version: 1
        landmarks:
          - id: spawn-market
            name: Spawn Market
            aliases: [shops]
            tags: [trade, services]
            dimension: minecraft:overworld
            x: 12.5
            y: 64
            z: -8.5
            permission: minecraftagent.landmark.market
        """);
    assertEquals(1, loader.loadOrCreate(directory).size());
  }

  @Test
  void rejectsUnsafeFilesystemShapeAndDoesNotFollowLinks() throws Exception {
    var file = directory.resolve(LandmarkCatalogLoader.FILE_NAME);
    writePrivate("version: 1\nlandmarks: []\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertFailure(StartupFailure.Code.LANDMARK_CATALOG_UNSAFE);

    Files.delete(file);
    var target = directory.resolve("target.yml");
    Files.writeString(target, "version: 1\nlandmarks: []\n");
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
    Files.createSymbolicLink(file, target.getFileName());
    assertFailure(StartupFailure.Code.LANDMARK_CATALOG_UNSAFE);
  }

  @Test
  void rejectsDuplicateYamlKeysUnknownFieldsAndUnsafeText() throws Exception {
    writePrivate("version: 1\nversion: 1\nlandmarks: []\n");
    assertFailure(StartupFailure.Code.LANDMARK_CATALOG_INVALID);

    writePrivate(
        """
        version: 1
        landmarks:
          - id: home
            name: "Home\u202e"
            aliases: []
            tags: []
            dimension: minecraft:overworld
            x: 0
            y: 64
            z: 0
            radius: 500
        """);
    assertFailure(StartupFailure.Code.LANDMARK_CATALOG_INVALID);
  }

  private void assertFailure(StartupFailure.Code code) {
    var failure =
        assertThrows(
            StartupFailure.class, () -> new LandmarkCatalogLoader().loadOrCreate(directory));
    assertEquals(code, failure.code());
    assertEquals(StartupFailure.Stage.CORE_TOOLS, failure.stage());
  }

  private void writePrivate(String content) throws Exception {
    var file = directory.resolve(LandmarkCatalogLoader.FILE_NAME);
    Files.deleteIfExists(file);
    Files.writeString(file, content);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    assertTrue(Files.isRegularFile(file));
  }
}
