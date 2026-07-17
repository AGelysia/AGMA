package dev.minecraftagent.paper.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateDirectoryProbeTest {
  @TempDir Path temporaryDirectory;

  private final StateDirectoryProbe probe = new StateDirectoryProbe();

  @BeforeEach
  void requirePosixPermissions() {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  @Test
  void createsPrivateStateDirectoryAndRemovesForcedWriteProbe() throws Exception {
    var state = temporaryDirectory.resolve("private/state");

    probe.verify(temporaryDirectory, state);

    assertTrue(Files.isDirectory(state));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state));
    try (var files = Files.list(state)) {
      assertFalse(
          files.anyMatch(path -> path.getFileName().toString().startsWith(".startup-probe-")));
    }
  }

  @Test
  void rejectsEscapeSymlinkAndOverbroadPermissionsWithStableSafeErrors() throws Exception {
    var outsideFailure =
        assertThrows(
            StartupFailure.class,
            () -> probe.verify(temporaryDirectory, temporaryDirectory.resolve("../outside")));
    assertEquals(StartupFailure.Code.STATE_DIRECTORY_UNSAFE, outsideFailure.code());
    assertFalse(outsideFailure.getMessage().contains(temporaryDirectory.toString()));

    var outside = Files.createTempDirectory("paper-state-outside-");
    try {
      var link = temporaryDirectory.resolve("linked-state");
      Files.createSymbolicLink(link, outside);
      var linkFailure =
          assertThrows(StartupFailure.class, () -> probe.verify(temporaryDirectory, link));
      assertEquals(StartupFailure.Code.STATE_DIRECTORY_UNSAFE, linkFailure.code());
    } finally {
      Files.deleteIfExists(outside);
    }

    var broad = Files.createDirectory(temporaryDirectory.resolve("broad"));
    Files.setPosixFilePermissions(broad, PosixFilePermissions.fromString("rwxr-xr-x"));
    var broadFailure =
        assertThrows(StartupFailure.class, () -> probe.verify(temporaryDirectory, broad));
    assertEquals(StartupFailure.Code.STATE_DIRECTORY_UNSAFE, broadFailure.code());
  }

  @Test
  void rejectsAnOverbroadPluginDataRoot() throws Exception {
    Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));

    var failure =
        assertThrows(
            StartupFailure.class,
            () -> probe.verify(temporaryDirectory, temporaryDirectory.resolve("state")));

    assertEquals(StartupFailure.Code.STATE_DIRECTORY_UNSAFE, failure.code());
  }
}
