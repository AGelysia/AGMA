package dev.minecraftagent.paper.runtime.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRuntimeInstallerTest {
  private static final byte[] EXECUTABLE =
      "#!/bin/sh\necho ready\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CONFIG =
      "{\"transport\":\"stdio\"}\n".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  @Test
  void installsAtomicallyWithPrivatePermissionsAndIsIdempotent() throws Exception {
    var archive = validArchive("1.2.3");
    var artifact = artifact(archive);
    var opens = new AtomicInteger();
    ArtifactSource source =
        () -> {
          opens.incrementAndGet();
          return new ByteArrayInputStream(archive);
        };
    var root = temporaryDirectory.resolve("managed");
    var installer = new ManagedRuntimeInstaller();

    var installed = installer.install(root, artifact, source);
    var repeated = installer.install(root, artifact, source);

    assertEquals(root.resolve("current/1.2.3").toAbsolutePath(), installed);
    assertEquals(installed, repeated);
    assertArrayEquals(EXECUTABLE, Files.readAllBytes(installed.resolve("bin/agma-runtime")));
    assertArrayEquals(CONFIG, Files.readAllBytes(installed.resolve("config/default.json")));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(installed.resolve("bin/agma-runtime")));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(installed.resolve("config/default.json")));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(installed));
    assertTrue(Files.isRegularFile(installed.resolve("sidecar-manifest.json")));
    assertTrue(Files.isRegularFile(installed.resolve(".installed.json")));
    assertEquals(2, opens.get());
    assertTrue(stagingEntries(root).isEmpty());
  }

  @Test
  void idempotentInstallRevalidatesEveryInstalledFile() throws Exception {
    var archive = validArchive("1.2.3");
    var artifact = artifact(archive);
    var root = temporaryDirectory.resolve("managed");
    var installer = new ManagedRuntimeInstaller();
    var installed = installer.install(root, artifact, source(archive));
    Files.writeString(
        installed.resolve("config/default.json"),
        "corrupt",
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    assertFailure(
        "INSTALLED_STATE_INVALID", () -> installer.install(root, artifact, source(archive)));
  }

  @Test
  void rejectsArtifactSizeAndHashMismatches() throws Exception {
    var archive = validArchive("1.2.3");
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed");
    var wrongSize = new ManagedRuntimeArtifact("runtime.zip", archive.length - 1L, sha256(archive));
    var wrongHash = new ManagedRuntimeArtifact("runtime.zip", archive.length, "0".repeat(64));

    assertFailure(
        "ARTIFACT_SIZE_MISMATCH", () -> installer.install(root, wrongSize, source(archive)));
    assertFailure(
        "ARTIFACT_HASH_MISMATCH", () -> installer.install(root, wrongHash, source(archive)));
    assertTrue(stagingEntries(root).isEmpty());
  }

  @Test
  void rejectsZipSlipPathsWithoutWritingOutsideManagedRoot() throws Exception {
    var files = files(Map.of("bin/agma-runtime", new DeclaredFile(EXECUTABLE, true)));
    var archive =
        archive(
            manifest("2.0.0", files),
            List.of(
                file("bin/agma-runtime", EXECUTABLE),
                file("../escaped", "owned".getBytes(StandardCharsets.UTF_8))));
    var root = temporaryDirectory.resolve("managed");

    assertFailure(
        "ARCHIVE_ENTRY_PATH_INVALID",
        () -> new ManagedRuntimeInstaller().install(root, artifact(archive), source(archive)));
    assertFalse(Files.exists(temporaryDirectory.resolve("escaped")));
    assertTrue(stagingEntries(root).isEmpty());
  }

  @Test
  void rejectsDuplicateCanonicalZipEntries() throws Exception {
    var files = files(Map.of("bin/agma-runtime", new DeclaredFile(EXECUTABLE, true)));
    var archive =
        archive(
            manifest("2.0.0", files),
            List.of(file("bin/agma-runtime", EXECUTABLE), directory("bin/agma-runtime/")));

    assertFailure(
        "ARCHIVE_ENTRY_DUPLICATE",
        () ->
            new ManagedRuntimeInstaller()
                .install(
                    temporaryDirectory.resolve("managed"), artifact(archive), source(archive)));
  }

  @Test
  void rejectsUndeclaredAndMissingEntries() throws Exception {
    var declared = files(Map.of("bin/agma-runtime", new DeclaredFile(EXECUTABLE, true)));
    var extraArchive =
        archive(
            manifest("2.0.0", declared),
            List.of(
                file("bin/agma-runtime", EXECUTABLE),
                file("unexpected.txt", "extra".getBytes(StandardCharsets.UTF_8))));
    var missingArchive = archive(manifest("2.0.1", declared), List.of());
    var installer = new ManagedRuntimeInstaller();

    assertFailure(
        "ARCHIVE_ENTRY_UNDECLARED",
        () ->
            installer.install(
                temporaryDirectory.resolve("extra"), artifact(extraArchive), source(extraArchive)));
    assertFailure(
        "ARCHIVE_ENTRY_MISSING",
        () ->
            installer.install(
                temporaryDirectory.resolve("missing"),
                artifact(missingArchive),
                source(missingArchive)));
  }

  @Test
  void rejectsPayloadHashMismatch() throws Exception {
    var declaration = new DeclaredFile(EXECUTABLE, true, "f".repeat(64));
    var archive =
        archive(
            manifest("2.0.0", files(Map.of("bin/agma-runtime", declaration))),
            List.of(file("bin/agma-runtime", EXECUTABLE)));

    assertFailure(
        "FILE_HASH_MISMATCH",
        () ->
            new ManagedRuntimeInstaller()
                .install(
                    temporaryDirectory.resolve("managed"), artifact(archive), source(archive)));
  }

  @Test
  void rejectsPayloadSizeMismatch() throws Exception {
    var declaration = new DeclaredFile(EXECUTABLE, true);
    var archive =
        archive(
            manifest("2.0.0", files(Map.of("bin/agma-runtime", declaration))),
            List.of(file("bin/agma-runtime", new byte[] {1})));

    assertFailure(
        "FILE_SIZE_MISMATCH",
        () ->
            new ManagedRuntimeInstaller()
                .install(
                    temporaryDirectory.resolve("managed"), artifact(archive), source(archive)));
  }

  @Test
  void rejectsInvalidZipContainer() {
    var invalidArchive = "not-a-zip".getBytes(StandardCharsets.UTF_8);

    assertFailure(
        "ARCHIVE_INVALID",
        () ->
            new ManagedRuntimeInstaller()
                .install(
                    temporaryDirectory.resolve("managed"),
                    artifact(invalidArchive),
                    source(invalidArchive)));
  }

  @Test
  void supportsProductionArchivesWhoseManifestExceedsTheProtocolJsonBudget() throws Exception {
    var declarations = new LinkedHashMap<String, DeclaredFile>();
    var entries = new ArrayList<ArchiveEntry>();
    for (var index = 0; index < 120; index++) {
      var content = ("module-" + index).getBytes(StandardCharsets.UTF_8);
      var path = "app/node_modules/package-" + index + "/index.js";
      declarations.put(path, new DeclaredFile(content, false));
      entries.add(file(path, content));
    }
    var archive = archive(manifest("2.0.0", files(declarations)), entries);

    var installed =
        new ManagedRuntimeInstaller()
            .install(temporaryDirectory.resolve("managed"), artifact(archive), source(archive));

    assertEquals(120, declarations.size());
    assertArrayEquals(
        "module-119".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(installed.resolve("app/node_modules/package-119/index.js")));
  }

  @Test
  void rejectsUnknownDuplicateAndMissingManifestFields() throws Exception {
    var valid =
        manifest("2.0.0", files(Map.of("bin/agma-runtime", new DeclaredFile(EXECUTABLE, true))));
    var unknown = valid.substring(0, valid.length() - 1) + ",\"unknown\":true}";
    var duplicate = valid.replaceFirst("\\{", "{\"schemaVersion\":1,");
    var missing = valid.replace("\"platform\":\"linux-x64\",", "");

    for (var invalid : List.of(unknown, duplicate, missing)) {
      var archive = archive(invalid, List.of(file("bin/agma-runtime", EXECUTABLE)));
      assertFailure(
          "MANIFEST_INVALID",
          () ->
              new ManagedRuntimeInstaller()
                  .install(
                      temporaryDirectory.resolve("invalid-" + sha256(archive).substring(0, 8)),
                      artifact(archive),
                      source(archive)));
    }
  }

  @Test
  void returnsStableBusyCodeWhenAnotherInstallerHoldsTheLock() throws Exception {
    var archive = validArchive("1.2.3");
    var artifact = artifact(archive);
    var root = temporaryDirectory.resolve("managed");
    var installer = new ManagedRuntimeInstaller();
    installer.install(root, artifact, source(archive));
    var opens = new AtomicInteger();

    try (var channel = FileChannel.open(root.resolve(".install.lock"), StandardOpenOption.WRITE);
        var ignored = channel.lock()) {
      assertFailure(
          "INSTALL_BUSY",
          () ->
              installer.install(
                  root,
                  artifact,
                  () -> {
                    opens.incrementAndGet();
                    return new ByteArrayInputStream(archive);
                  }));
    }
    assertEquals(0, opens.get());
  }

  @Test
  void failedUpgradePreservesPublishedVersionAndCleansOnlyItsStaging() throws Exception {
    var firstArchive = validArchive("1.2.3");
    var root = temporaryDirectory.resolve("managed");
    var installer = new ManagedRuntimeInstaller();
    var first = installer.install(root, artifact(firstArchive), source(firstArchive));
    var original = Files.readAllBytes(first.resolve("bin/agma-runtime"));

    var invalidFile = new DeclaredFile(CONFIG, false, "e".repeat(64));
    var upgradeArchive =
        archive(
            manifest("2.0.0", files(Map.of("config/default.json", invalidFile))),
            List.of(file("config/default.json", CONFIG)));
    assertFailure(
        "FILE_HASH_MISMATCH",
        () -> installer.install(root, artifact(upgradeArchive), source(upgradeArchive)));

    assertArrayEquals(original, Files.readAllBytes(first.resolve("bin/agma-runtime")));
    assertFalse(Files.exists(root.resolve("current/2.0.0")));
    assertTrue(stagingEntries(root).isEmpty());
  }

  @Test
  void installationFailureDoesNotExposeFilesystemPaths() throws Exception {
    var archive = validArchive("1.2.3");
    var root = temporaryDirectory.resolve("managed-secret-path");
    var bad = new ManagedRuntimeArtifact("runtime.zip", archive.length, "0".repeat(64));

    var failure =
        assertThrows(
            ManagedRuntimeInstallException.class,
            () -> new ManagedRuntimeInstaller().install(root, bad, source(archive)));

    assertFalse(failure.getMessage().contains(root.toString()));
    assertEquals(0, failure.getStackTrace().length);
    assertSame(null, failure.getCause());
  }

  @Test
  void abortsBeforeOpeningAnArtifactWhenTheInstallThreadIsInterrupted() throws Exception {
    var archive = validArchive("1.2.3");
    var opens = new AtomicInteger();
    Thread.currentThread().interrupt();
    try {
      assertFailure(
          "INSTALL_CANCELLED",
          () ->
              new ManagedRuntimeInstaller()
                  .install(
                      temporaryDirectory.resolve("cancelled"),
                      artifact(archive),
                      () -> {
                        opens.incrementAndGet();
                        return new ByteArrayInputStream(archive);
                      }));
    } finally {
      Thread.interrupted();
    }

    assertEquals(0, opens.get());
  }

  private static byte[] validArchive(String version) throws IOException {
    var declared = new LinkedHashMap<String, DeclaredFile>();
    declared.put("bin/agma-runtime", new DeclaredFile(EXECUTABLE, true));
    declared.put("config/default.json", new DeclaredFile(CONFIG, false));
    return archive(
        manifest(version, files(declared)),
        List.of(
            directory("bin/"),
            file("bin/agma-runtime", EXECUTABLE),
            directory("config/"),
            file("config/default.json", CONFIG)));
  }

  private static String manifest(String version, JsonArray files) {
    var manifest = new JsonObject();
    manifest.addProperty("schemaVersion", 1);
    manifest.addProperty("runtimeVersion", version);
    manifest.addProperty("platform", "linux-x64");
    manifest.add("files", files);
    return manifest.toString();
  }

  private static JsonArray files(Map<String, DeclaredFile> files) {
    var result = new JsonArray();
    files.forEach(
        (path, declaration) -> {
          var file = new JsonObject();
          file.addProperty("path", path);
          file.addProperty("size", declaration.bytes().length);
          file.addProperty("sha256", declaration.sha256());
          file.addProperty("executable", declaration.executable());
          result.add(file);
        });
    return result;
  }

  private static byte[] archive(String manifest, List<ArchiveEntry> entries) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      writeEntry(
          zip,
          file(ManagedRuntimeInstaller.MANIFEST_NAME, manifest.getBytes(StandardCharsets.UTF_8)));
      for (var entry : entries) {
        writeEntry(zip, entry);
      }
    }
    return output.toByteArray();
  }

  private static void writeEntry(ZipOutputStream zip, ArchiveEntry value) throws IOException {
    var entry = new ZipEntry(value.name());
    zip.putNextEntry(entry);
    if (!value.directory()) {
      zip.write(value.bytes());
    }
    zip.closeEntry();
  }

  private static ArchiveEntry file(String name, byte[] bytes) {
    return new ArchiveEntry(name, bytes, false);
  }

  private static ArchiveEntry directory(String name) {
    return new ArchiveEntry(name, new byte[0], true);
  }

  private static ManagedRuntimeArtifact artifact(byte[] archive) {
    return new ManagedRuntimeArtifact("embedded/runtime.zip", archive.length, sha256(archive));
  }

  private static ArtifactSource source(byte[] archive) {
    return () -> new ByteArrayInputStream(archive);
  }

  private static List<Path> stagingEntries(Path root) throws IOException {
    var staging = root.resolve(".staging");
    if (!Files.exists(staging)) {
      return List.of();
    }
    try (var entries = Files.list(staging)) {
      return new ArrayList<>(entries.toList());
    }
  }

  private static void assertFailure(String expectedCode, ThrowingInstall operation) {
    var failure = assertThrows(ManagedRuntimeInstallException.class, operation::run);
    assertEquals(expectedCode, failure.code());
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  @FunctionalInterface
  private interface ThrowingInstall {
    void run() throws Exception;
  }

  private record ArchiveEntry(String name, byte[] bytes, boolean directory) {}

  private record DeclaredFile(byte[] bytes, boolean executable, String sha256) {
    private DeclaredFile(byte[] bytes, boolean executable) {
      this(bytes, executable, ManagedRuntimeInstallerTest.sha256(bytes));
    }
  }
}
