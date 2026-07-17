package dev.minecraftagent.paper.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDesiredModeStoreTest {
  private static final String ENABLED_STATE = "state-version: 1\ndesired-mode: ENABLED\n";
  private static final String DISABLED_STATE = "state-version: 1\ndesired-mode: DISABLED\n";

  @TempDir Path temporaryDirectory;

  @BeforeEach
  void requirePosixPermissions() {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  @Test
  void missingStateDefaultsToEnabledInsideAPrivateDirectory() throws Exception {
    var stateDirectory = createPrivateDirectory("state");

    assertEquals(DesiredMode.ENABLED, new FileDesiredModeStore(stateDirectory).load());
  }

  @Test
  void atomicallyPersistsBothModesWithCanonicalContentAndPrivatePermissions() throws Exception {
    var stateDirectory = createPrivateDirectory("state");
    var store = new FileDesiredModeStore(stateDirectory);
    var stateFile = stateDirectory.resolve(FileDesiredModeStore.STATE_FILE_NAME);

    store.save(DesiredMode.DISABLED);

    assertEquals(DISABLED_STATE, Files.readString(stateFile));
    assertEquals(DesiredMode.DISABLED, store.load());
    assertEquals(
        PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stateFile));

    store.save(DesiredMode.ENABLED);

    assertEquals(ENABLED_STATE, Files.readString(stateFile));
    assertEquals(DesiredMode.ENABLED, store.load());
    assertFalse(hasTemporaryFiles(stateDirectory));
  }

  @Test
  void rejectsMalformedUnknownDuplicateAliasedAndUnsupportedState() throws Exception {
    var stateDirectory = createPrivateDirectory("state");
    var store = new FileDesiredModeStore(stateDirectory);
    var invalidStates =
        List.of(
            "",
            "state-version: 2\ndesired-mode: ENABLED\n",
            "state-version: 1\ndesired-mode: enabled\n",
            "state-version: 1\ndesired-mode: ENABLED\nunknown: true\n",
            "state-version: 1\nstate-version: 1\ndesired-mode: ENABLED\n",
            "state-version: &version 1\ndesired-mode: *version\n",
            "state-version: 1\ndesired-mode:\n  nested: ENABLED\n",
            "state-version: 1\ndesired-mode: ENABLED\n---\ndesired-mode: DISABLED\n");

    for (var invalidState : invalidStates) {
      writePrivateState(
          stateDirectory, invalidState.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertCode(
          StartupFailure.Code.STATE_FILE_INVALID, assertThrows(StartupFailure.class, store::load));
    }
  }

  @Test
  void rejectsMalformedUtf8AndOversizedStateWithoutLeakingItsPath() throws Exception {
    var stateDirectory = createPrivateDirectory("state");
    var store = new FileDesiredModeStore(stateDirectory);

    writePrivateState(stateDirectory, new byte[] {(byte) 0xc3, (byte) 0x28});
    var malformed = assertThrows(StartupFailure.class, store::load);
    assertCode(StartupFailure.Code.STATE_FILE_INVALID, malformed);
    assertFalse(malformed.getMessage().contains(stateDirectory.toString()));

    writePrivateState(
        stateDirectory, ("#" + "x".repeat(4096)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertCode(
        StartupFailure.Code.STATE_FILE_INVALID, assertThrows(StartupFailure.class, store::load));
  }

  @Test
  void rejectsUnsafeDirectoryFileSymlinkAndNonRegularTargets() throws Exception {
    var broadDirectory = Files.createDirectory(temporaryDirectory.resolve("broad"));
    Files.setPosixFilePermissions(broadDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(StartupFailure.class, () -> new FileDesiredModeStore(broadDirectory).load()));

    var privateDirectory = createPrivateDirectory("private");
    writePrivateState(
        privateDirectory, ENABLED_STATE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var stateFile = privateDirectory.resolve(FileDesiredModeStore.STATE_FILE_NAME);
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-r--r--"));
    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(
            StartupFailure.class, () -> new FileDesiredModeStore(privateDirectory).load()));

    Files.delete(stateFile);
    Files.createDirectory(stateFile);
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rwx------"));
    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(
            StartupFailure.class, () -> new FileDesiredModeStore(privateDirectory).load()));

    Files.delete(stateFile);
    var target = temporaryDirectory.resolve("outside.yml");
    Files.writeString(target, ENABLED_STATE);
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
    Files.createSymbolicLink(stateFile, target);
    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(
            StartupFailure.class, () -> new FileDesiredModeStore(privateDirectory).load()));

    var linkedDirectory = temporaryDirectory.resolve("linked");
    Files.createSymbolicLink(linkedDirectory, privateDirectory);
    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(StartupFailure.class, () -> new FileDesiredModeStore(linkedDirectory).load()));
  }

  @Test
  void rejectsSavingOverAnUnsafeExistingTarget() throws Exception {
    var stateDirectory = createPrivateDirectory("state");
    writePrivateState(
        stateDirectory, ENABLED_STATE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var stateFile = stateDirectory.resolve(FileDesiredModeStore.STATE_FILE_NAME);
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-rw----"));

    assertCode(
        StartupFailure.Code.STATE_FILE_UNSAFE,
        assertThrows(
            StartupFailure.class,
            () -> new FileDesiredModeStore(stateDirectory).save(DesiredMode.DISABLED)));
    assertFalse(hasTemporaryFiles(stateDirectory));
  }

  @Test
  void atomicMoveFailureKeepsThePreviousStateAndCleansTheTemporaryFile() throws Exception {
    var stateDirectory = createPrivateDirectory("state");
    var normalStore = new FileDesiredModeStore(stateDirectory);
    normalStore.save(DesiredMode.ENABLED);
    var failingStore =
        new FileDesiredModeStore(
            stateDirectory,
            (source, target) -> {
              throw new AtomicMoveNotSupportedException(
                  source.toString(), target.toString(), "test filesystem");
            });

    assertCode(
        StartupFailure.Code.STATE_PERSISTENCE_FAILED,
        assertThrows(StartupFailure.class, () -> failingStore.save(DesiredMode.DISABLED)));

    assertEquals(DesiredMode.ENABLED, normalStore.load());
    assertEquals(
        ENABLED_STATE,
        Files.readString(stateDirectory.resolve(FileDesiredModeStore.STATE_FILE_NAME)));
    assertFalse(hasTemporaryFiles(stateDirectory));
  }

  private Path createPrivateDirectory(String name) throws Exception {
    var directory = Files.createDirectory(temporaryDirectory.resolve(name));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    return directory;
  }

  private static void writePrivateState(Path directory, byte[] content) throws Exception {
    var file = directory.resolve(FileDesiredModeStore.STATE_FILE_NAME);
    if (Files.isDirectory(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(file);
    }
    Files.write(file, content);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
  }

  private static boolean hasTemporaryFiles(Path directory) throws Exception {
    try (var files = Files.list(directory)) {
      return files.anyMatch(path -> path.getFileName().toString().startsWith(".agent-state-"));
    }
  }

  private static void assertCode(StartupFailure.Code code, StartupFailure failure) {
    assertEquals(code, failure.code());
    assertEquals(StartupFailure.Stage.STATE, failure.stage());
  }
}
