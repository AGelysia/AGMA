package dev.minecraftagent.standalone.supervisor.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedRuntimeInstallerTest {
  private static final byte[] NODE = "offline-node-fixture\n".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  @Test
  void detectsOnlyReviewedPlatforms() {
    assertEquals(RuntimePlatform.LINUX_X86_64, RuntimePlatform.detect("Linux", "amd64"));
    assertEquals(RuntimePlatform.WINDOWS_X86_64, RuntimePlatform.detect("Windows 11", "x86_64"));
    assertThrows(
        IllegalArgumentException.class, () -> RuntimePlatform.detect("Mac OS X", "aarch64"));
    assertThrows(IllegalArgumentException.class, () -> RuntimePlatform.detect("Linux", "aarch64"));
  }

  @Test
  void installsBothPlatformLayoutsAtomicallyAndResolvesManagedLaunchPaths() throws Exception {
    for (var platform : RuntimePlatform.values()) {
      var archive = archive(platform, "0.2.0", Map.of(), null);
      var root = temporaryDirectory.resolve(platform.id()).toAbsolutePath().normalize();
      var artifact = artifact(platform, "0.2.0", archive);
      var installer = new ManagedRuntimeInstaller();

      var installed = installer.install(root, platform, artifact, source(archive));
      var repeated = installer.install(root, platform, artifact, source(archive));

      assertEquals(installed, repeated);
      assertArrayEquals(NODE, Files.readAllBytes(installed.executable()));
      assertTrue(installed.installationRoot().startsWith(root.resolve("versions")));
      assertTrue(installed.installationRoot().getFileName().toString().endsWith(artifact.sha256()));
      assertTrue(isEmpty(root.resolve(".staging")));

      var state = root.resolve("instances/example").toAbsolutePath().normalize();
      var config = state.resolve("runtime-config.json");
      var launch =
          installed.launchSpec(
              UUID.fromString("11111111-1111-4111-8111-111111111111"),
              state,
              config,
              Map.of("AGMA_CLIENT_CONNECTOR_TOKEN", "fixture-secret"));
      assertEquals(installed.executable(), launch.executable());
      assertEquals(installed.entrypoint().toString(), launch.arguments().get(0));
      assertEquals("--managed", launch.arguments().get(3));
      assertFalse(launch.toString().contains("fixture-secret"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              installed.launchSpec(
                  UUID.randomUUID(), state, root.resolve("outside.json"), Map.of()));
    }
  }

  @Test
  void rejectsWrongPlatformAndOuterArtifactTampering() throws Exception {
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed").toAbsolutePath().normalize();

    assertFailure(
        "ARTIFACT_PLATFORM_MISMATCH",
        () -> installer.install(root, RuntimePlatform.WINDOWS_X86_64, artifact, source(archive)));
    var tampered = archive.clone();
    tampered[tampered.length / 2] ^= 1;
    assertFailure(
        "ARTIFACT_HASH_MISMATCH",
        () -> installer.install(root, artifact.platform(), artifact, source(tampered)));
  }

  @Test
  void rejectsZipSlipCaseCollisionsAndForbiddenPlanPayloads() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    for (var extra :
        List.of("../escape", "BIN/NODE", "docs/01-standalone-client-development-plan.md")) {
      var archive =
          archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(extra, new byte[] {1}), null);
      var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
      assertThrows(
          ManagedRuntimeInstallException.class,
          () ->
              installer.install(
                  temporaryDirectory
                      .resolve(UUID.randomUUID().toString())
                      .toAbsolutePath()
                      .normalize(),
                  artifact.platform(),
                  artifact,
                  source(archive)));
      assertFalse(Files.exists(temporaryDirectory.resolve("escape")));
    }
  }

  @Test
  void acceptsOnlyTheStandaloneRuntimeBundleUnderAppDist() throws Exception {
    assertEquals(
        "app/dist/standalone/bootstrap/index.js",
        InstallPathRules.manifestPath("app/dist/standalone/bootstrap/index.js"));
    for (var forbidden :
        List.of(
            "app/dist/bootstrap/index.js",
            "app/dist/transport/paper-handshake.js",
            "app/dist/modules/server-tool.js")) {
      assertFailure("MANIFEST_INVALID", () -> InstallPathRules.manifestPath(forbidden));
    }
  }

  @Test
  void permitsDependencyMetadataYamlButRejectsRuntimeYamlConfiguration() throws Exception {
    assertEquals(
        "app/node_modules/example/.github/workflows/ci.yml",
        InstallPathRules.manifestPath("app/node_modules/example/.github/workflows/ci.yml"));
    assertFailure("MANIFEST_INVALID", () -> InstallPathRules.manifestPath("app/config.yml"));
  }

  @Test
  void rejectsDuplicateManifestKeysAndPayloadHashMismatch() throws Exception {
    var duplicate =
        archive(
            RuntimePlatform.LINUX_X86_64,
            "0.2.0",
            Map.of(),
            "{\"schemaVersion\":1,\"schemaVersion\":1}");
    var corrupt =
        archive(
            RuntimePlatform.LINUX_X86_64,
            "0.2.1",
            Map.of(
                "app/dist/standalone/bootstrap/index.js",
                "changed-after-hash".getBytes(StandardCharsets.UTF_8)),
            manifest(
                RuntimePlatform.LINUX_X86_64, "0.2.1", baseFiles(RuntimePlatform.LINUX_X86_64)));
    var installer = new ManagedRuntimeInstaller();

    assertFailure("MANIFEST_INVALID", () -> install(installer, "duplicate", duplicate, "0.2.0"));
    assertFailure("PAYLOAD_SIZE_MISMATCH", () -> install(installer, "corrupt", corrupt, "0.2.1"));
  }

  @Test
  void crossProcessLockFailsBusyWithoutOpeningArtifact() throws Exception {
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
    var root = temporaryDirectory.resolve("managed").toAbsolutePath().normalize();
    var installer = new ManagedRuntimeInstaller();
    installer.install(root, artifact.platform(), artifact, source(archive));
    var opens = new int[] {0};
    var ready = temporaryDirectory.resolve("lock-holder.ready");
    var javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
    var testClasses =
        Path.of(
            CrossProcessLockHolder.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    var holder =
        new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                testClasses.toString(),
                CrossProcessLockHolder.class.getName(),
                root.resolve(".install.lock").toString(),
                ready.toString())
            .redirectErrorStream(true)
            .start();

    try {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Files.exists(ready)) {
        if (!holder.isAlive()) {
          throw new AssertionError(
              "cross-process lock holder exited early: "
                  + new String(holder.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        if (System.nanoTime() >= deadline) {
          throw new AssertionError("cross-process lock holder did not become ready");
        }
        Thread.sleep(10);
      }
      assertFailure(
          "INSTALL_BUSY",
          () ->
              installer.install(
                  root,
                  artifact.platform(),
                  artifact,
                  () -> {
                    opens[0]++;
                    return new ByteArrayInputStream(archive);
                  }));
      assertFailure("INSTALL_BUSY", () -> installer.uninstall(root));
    } finally {
      holder.getOutputStream().write('\n');
      holder.getOutputStream().close();
      if (!holder.waitFor(10, TimeUnit.SECONDS)) {
        holder.destroyForcibly();
        assertTrue(holder.waitFor(10, TimeUnit.SECONDS));
      }
    }
    assertEquals(0, opens[0]);
    assertEquals(0, holder.exitValue());
  }

  @Test
  void failedUpgradePreservesPreviousVersionAndCorruptInstallsFailClosed() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed").toAbsolutePath().normalize();
    var firstArchive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var firstArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", firstArchive);
    var first =
        installer.install(root, firstArtifact.platform(), firstArtifact, source(firstArchive));
    var original = Files.readAllBytes(first.entrypoint());

    var badUpgrade =
        archive(
            RuntimePlatform.LINUX_X86_64,
            "0.3.0",
            Map.of("app/dist/standalone/bootstrap/index.js", new byte[0]),
            manifest(
                RuntimePlatform.LINUX_X86_64, "0.3.0", baseFiles(RuntimePlatform.LINUX_X86_64)));
    var badArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.3.0", badUpgrade);
    assertThrows(
        ManagedRuntimeInstallException.class,
        () -> installer.install(root, badArtifact.platform(), badArtifact, source(badUpgrade)));
    assertArrayEquals(original, Files.readAllBytes(first.entrypoint()));
    assertTrue(isEmpty(root.resolve(".staging")));

    Files.writeString(first.entrypoint(), "tampered");
    assertFailure(
        "INSTALLED_STATE_INVALID",
        () ->
            installer.install(root, firstArtifact.platform(), firstArtifact, source(firstArchive)));
  }

  @Test
  void cleansCrashStagingAndIsIdempotent() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed-cleanup").toAbsolutePath().normalize();
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
    installer.install(root, artifact.platform(), artifact, source(archive));
    var security = PrivatePathPolicy.prepare(root);
    var staging = security.createOrVerifyDirectory(root.resolve(".staging"));
    var stale =
        security.createOrVerifyDirectory(
            staging.resolve("install-11111111-1111-4111-8111-111111111111"));
    var nested = security.createOrVerifyDirectory(stale.resolve("payload"));
    var partial = nested.resolve("partial.bin");
    try (var output = security.createFile(partial, false)) {
      output.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
      output.force(true);
    }
    security.finishFile(partial, false, 3);

    var removed = installer.cleanupStaleStaging(root);
    var repeated = installer.cleanupStaleStaging(root);

    assertEquals(1, removed.stagingDirectoriesRemoved());
    assertEquals(0, removed.versionsRemoved());
    assertEquals(new ManagedRuntimeMaintenanceResult(0, 0), repeated);
    assertFalse(Files.exists(root.resolve(".staging")));
  }

  @Test
  void prunesOnlyValidatedNonCurrentVersionsAndPreservesCurrentByDefault() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed-prune").toAbsolutePath().normalize();
    var firstArchive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var secondArchive = archive(RuntimePlatform.LINUX_X86_64, "0.3.0", Map.of(), null);
    var firstArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", firstArchive);
    var secondArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.3.0", secondArchive);
    var first =
        installer.install(root, firstArtifact.platform(), firstArtifact, source(firstArchive));
    var current =
        installer.install(root, secondArtifact.platform(), secondArtifact, source(secondArchive));

    var removed = installer.pruneVersions(root, current);
    var repeated = installer.pruneVersions(root, current);

    assertEquals(0, removed.stagingDirectoriesRemoved());
    assertEquals(1, removed.versionsRemoved());
    assertFalse(Files.exists(first.installationRoot()));
    assertTrue(Files.isRegularFile(current.entrypoint()));
    assertEquals(new ManagedRuntimeMaintenanceResult(0, 0), repeated);
  }

  @Test
  void corruptOldVersionMakesPruneFailBeforeDeletingAnyVersion() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed-prune-invalid").toAbsolutePath().normalize();
    var firstArchive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var secondArchive = archive(RuntimePlatform.LINUX_X86_64, "0.3.0", Map.of(), null);
    var firstArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", firstArchive);
    var secondArtifact = artifact(RuntimePlatform.LINUX_X86_64, "0.3.0", secondArchive);
    var first =
        installer.install(root, firstArtifact.platform(), firstArtifact, source(firstArchive));
    var current =
        installer.install(root, secondArtifact.platform(), secondArtifact, source(secondArchive));
    Files.writeString(first.entrypoint(), "tampered");

    assertFailure("INSTALLED_STATE_INVALID", () -> installer.pruneVersions(root, current));

    assertTrue(Files.exists(first.installationRoot()));
    assertTrue(Files.exists(current.installationRoot()));
  }

  @Test
  void explicitUninstallRemovesOnlyValidatedVersionsAndInternalEmptyDirectories() throws Exception {
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed-uninstall").toAbsolutePath().normalize();
    for (var version : List.of("0.2.0", "0.3.0")) {
      var archive = archive(RuntimePlatform.LINUX_X86_64, version, Map.of(), null);
      var artifact = artifact(RuntimePlatform.LINUX_X86_64, version, archive);
      installer.install(root, artifact.platform(), artifact, source(archive));
    }

    var removed = installer.uninstall(root);
    var repeated = installer.uninstall(root);

    assertEquals(2, removed.versionsRemoved());
    assertFalse(Files.exists(root.resolve("versions")));
    assertFalse(Files.exists(root.resolve(".staging")));
    assertTrue(Files.isDirectory(root));
    assertTrue(Files.isRegularFile(root.resolve(".install.lock")));
    assertEquals(new ManagedRuntimeMaintenanceResult(0, 0), repeated);
  }

  @Test
  void cleanupRejectsSymlinksAndWidePermissionsWithoutTouchingTheirTargets() throws Exception {
    if (!Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix")) {
      return;
    }
    var installer = new ManagedRuntimeInstaller();
    var root = temporaryDirectory.resolve("managed-cleanup-unsafe").toAbsolutePath().normalize();
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
    installer.install(root, artifact.platform(), artifact, source(archive));
    var target = Files.createDirectory(temporaryDirectory.resolve("outside-staging"));
    var marker = Files.writeString(target.resolve("marker"), "keep");
    var staging = root.resolve(".staging");
    Files.createSymbolicLink(
        staging.resolve("install-11111111-1111-4111-8111-111111111111"), target);

    assertFailure("INSTALL_ROOT_UNSAFE", () -> installer.cleanupStaleStaging(root));
    assertEquals("keep", Files.readString(marker));

    Files.delete(staging.resolve("install-11111111-1111-4111-8111-111111111111"));
    var security = PrivatePathPolicy.prepare(root);
    var wide =
        security.createOrVerifyDirectory(
            staging.resolve("install-22222222-2222-4222-8222-222222222222"));
    Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertFailure("INSTALL_ROOT_UNSAFE", () -> installer.cleanupStaleStaging(root));
    assertTrue(Files.exists(wide));
  }

  @Test
  void installedPosixTreeIsPrivateWhenTheFileSystemSupportsPosix() throws Exception {
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);
    var root = temporaryDirectory.resolve("managed").toAbsolutePath().normalize();
    var installed =
        new ManagedRuntimeInstaller().install(root, artifact.platform(), artifact, source(archive));
    if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
      assertEquals(
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE),
          Files.getPosixFilePermissions(installed.installationRoot()));
      assertEquals(
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE),
          Files.getPosixFilePermissions(installed.executable()));
      assertEquals(
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(installed.entrypoint()));
    }
  }

  @Test
  void rejectsSymlinkInstallationRootWithoutMutatingItsTarget() throws Exception {
    if (!Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix")) {
      return;
    }
    var target = Files.createDirectory(temporaryDirectory.resolve("target"));
    var originalPermissions = PosixFilePermissions.fromString("rwxr-x---");
    Files.setPosixFilePermissions(target, originalPermissions);
    var root = temporaryDirectory.resolve("managed-link").toAbsolutePath().normalize();
    Files.createSymbolicLink(root, target);
    var archive = archive(RuntimePlatform.LINUX_X86_64, "0.2.0", Map.of(), null);
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, "0.2.0", archive);

    assertFailure(
        "INSTALL_ROOT_UNSAFE",
        () ->
            new ManagedRuntimeInstaller()
                .install(root, artifact.platform(), artifact, source(archive)));

    assertEquals(originalPermissions, Files.getPosixFilePermissions(target));
    assertTrue(isEmpty(target));
  }

  private InstalledRuntime install(
      ManagedRuntimeInstaller installer, String directory, byte[] archive, String version)
      throws ManagedRuntimeInstallException {
    var artifact = artifact(RuntimePlatform.LINUX_X86_64, version, archive);
    return installer.install(
        temporaryDirectory.resolve(directory).toAbsolutePath().normalize(),
        artifact.platform(),
        artifact,
        source(archive));
  }

  private static byte[] archive(
      RuntimePlatform platform,
      String version,
      Map<String, byte[]> overrides,
      String manifestOverride)
      throws IOException {
    var declared = baseFiles(platform);
    var payload = new LinkedHashMap<>(declared);
    payload.putAll(overrides);
    var manifest =
        manifestOverride == null ? manifest(platform, version, declared) : manifestOverride;
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      var paths = new ArrayList<>(payload.keySet());
      paths.sort(Comparator.naturalOrder());
      for (var path : paths) {
        writeEntry(zip, path, payload.get(path));
      }
      writeEntry(zip, "sidecar-manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
    }
    return output.toByteArray();
  }

  private static Map<String, byte[]> baseFiles(RuntimePlatform platform) {
    var files = new LinkedHashMap<String, byte[]>();
    files.put(
        "app/dist/standalone/bootstrap/index.js",
        "console.log('fixture');\n".getBytes(StandardCharsets.UTF_8));
    files.put(
        "app/package-lock.json",
        "{\"name\":\"agma-runtime\",\"version\":\"0.2.0\",\"lockfileVersion\":3}\n"
            .getBytes(StandardCharsets.UTF_8));
    files.put(
        "app/package.json",
        "{\"name\":\"agma-runtime\",\"version\":\"0.2.0\",\"type\":\"module\"}\n"
            .getBytes(StandardCharsets.UTF_8));
    files.put(platform.nodeExecutable(), NODE);
    files.put("licenses/node/LICENSE", "fixture license\n".getBytes(StandardCharsets.UTF_8));
    files.put(
        "licenses/npm-production-packages.json",
        ("{\"schemaVersion\":1,\"packages\":[{\"path\":\"fixture\",\"name\":\"fixture\","
                + "\"version\":\"1.0.0\",\"license\":\"MIT\"}]}\n")
            .getBytes(StandardCharsets.UTF_8));
    files.put(
        "standalone-client/contracts/schemas/connector-envelope.schema.json",
        "{}\n".getBytes(StandardCharsets.UTF_8));
    return files;
  }

  private static String manifest(
      RuntimePlatform platform, String version, Map<String, byte[]> declared) {
    var paths = new ArrayList<>(declared.keySet());
    paths.sort(Comparator.naturalOrder());
    var files = new StringBuilder();
    for (var index = 0; index < paths.size(); index++) {
      var path = paths.get(index);
      var value = declared.get(path);
      if (index > 0) {
        files.append(',');
      }
      files
          .append("{\"path\":\"")
          .append(path)
          .append("\",\"size\":")
          .append(value.length)
          .append(",\"sha256\":\"")
          .append(sha256(value))
          .append("\",\"executable\":")
          .append(path.equals(platform.nodeExecutable()))
          .append('}');
    }
    return "{\"schemaVersion\":1,\"product\":\"agma-standalone-runtime\","
        + "\"runtimeVersion\":\""
        + version
        + "\",\"nodeVersion\":\"22.23.1\",\"platform\":\""
        + platform.id()
        + "\",\"nodeExecutable\":\""
        + platform.nodeExecutable()
        + "\",\"entrypoint\":\"app/dist/standalone/bootstrap/index.js\",\"files\":["
        + files
        + "]}\n";
  }

  private static void writeEntry(ZipOutputStream zip, String path, byte[] value)
      throws IOException {
    var entry = new ZipEntry(path);
    entry.setTime(315_532_800_000L);
    zip.putNextEntry(entry);
    zip.write(value);
    zip.closeEntry();
  }

  private static ManagedRuntimeArtifact artifact(
      RuntimePlatform platform, String version, byte[] archive) {
    return new ManagedRuntimeArtifact(
        platform, version, "22.23.1", archive.length, sha256(archive));
  }

  private static RuntimeArtifactSource source(byte[] archive) {
    return () -> new ByteArrayInputStream(archive);
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static boolean isEmpty(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths.findAny().isEmpty();
    }
  }

  private static void assertFailure(String code, ThrowingOperation operation) {
    var failure = assertThrows(ManagedRuntimeInstallException.class, operation::run);
    assertEquals(code, failure.code());
    assertEquals(code, failure.getMessage());
    assertEquals(0, failure.getStackTrace().length);
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws Exception;
  }
}
