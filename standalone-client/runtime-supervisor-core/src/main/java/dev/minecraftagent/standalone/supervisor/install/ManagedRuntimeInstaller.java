package dev.minecraftagent.standalone.supervisor.install;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Cross-platform installer for one pinned, platform-specific standalone Runtime sidecar. */
public final class ManagedRuntimeInstaller {
  private static final String LOCK_NAME = ".install.lock";
  private static final String STAGING_NAME = ".staging";
  private static final String VERSIONS_NAME = "versions";
  private static final int BUFFER_BYTES = 64 * 1024;
  private static final int MAXIMUM_ARCHIVE_ENTRIES = SidecarManifest.MAXIMUM_FILES * 2 + 2;
  private static final int MAXIMUM_MAINTENANCE_DIRECTORIES = 128;
  private static final int MAXIMUM_STAGING_ENTRIES = MAXIMUM_ARCHIVE_ENTRIES + 8;
  private static final long MAXIMUM_STAGING_BYTES = 1536L * 1024L * 1024L;

  public InstalledRuntime install(
      Path managedRoot,
      RuntimePlatform currentPlatform,
      ManagedRuntimeArtifact artifact,
      RuntimeArtifactSource source)
      throws ManagedRuntimeInstallException {
    if (managedRoot == null || currentPlatform == null || artifact == null || source == null) {
      throw failure("INSTALL_ARGUMENT_INVALID");
    }
    if (artifact.platform() != currentPlatform) {
      throw failure("ARTIFACT_PLATFORM_MISMATCH");
    }

    Path staging = null;
    try {
      checkInterrupted();
      var root = managedRoot.toAbsolutePath().normalize();
      if (!root.equals(managedRoot)) {
        throw failure("INSTALL_ROOT_UNSAFE");
      }
      var security = PrivatePathPolicy.prepare(root);
      try (var lockChannel = security.openLock(root.resolve(LOCK_NAME));
          var ignored = acquireLock(lockChannel)) {
        checkInterrupted();
        var stagingParent = security.createOrVerifyDirectory(root.resolve(STAGING_NAME));
        var versions = security.createOrVerifyDirectory(root.resolve(VERSIONS_NAME));
        staging =
            security.createOrVerifyDirectory(stagingParent.resolve("install-" + UUID.randomUUID()));
        var archive = staging.resolve("artifact.zip");
        copyAndVerifyArtifact(archive, artifact, source, security);

        try (var zip = new ZipFile(archive.toFile(), ZipFile.OPEN_READ)) {
          var content = inspectArchive(zip, artifact);
          var target = versions.resolve(targetName(artifact));
          if (Files.exists(target, NOFOLLOW_LINKS)) {
            return verifyInstalled(target, security, artifact, content);
          }

          var payload = security.createOrVerifyDirectory(staging.resolve("payload"));
          extract(zip, payload, security, content);
          verifyInstalled(payload, security, artifact, content);
          publish(payload, target);
          forceDirectoryBestEffort(versions);
          return verifyInstalled(target, security, artifact, content);
        }
      }
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (AtomicMoveNotSupportedException failure) {
      throw failure("ATOMIC_PUBLISH_UNSUPPORTED");
    } catch (ZipException failure) {
      throw failure("ARCHIVE_INVALID");
    } catch (SecurityException | UnsupportedOperationException failure) {
      throw failure("INSTALL_ROOT_UNSAFE");
    } catch (IOException failure) {
      throw failure("INSTALL_IO_FAILED");
    } catch (RuntimeException failure) {
      throw failure("INSTALL_INTERNAL_ERROR");
    } finally {
      deleteTreeBestEffort(staging);
    }
  }

  /** Removes only verified private crash leftovers under this installer's staging directory. */
  public ManagedRuntimeMaintenanceResult cleanupStaleStaging(Path managedRoot)
      throws ManagedRuntimeInstallException {
    return maintain(managedRoot, null, MaintenanceMode.CLEAN_STAGING);
  }

  /** Removes validated old versions while preserving the fully verified current installation. */
  public ManagedRuntimeMaintenanceResult pruneVersions(
      Path managedRoot, InstalledRuntime currentRuntime) throws ManagedRuntimeInstallException {
    if (currentRuntime == null) {
      throw failure("MAINTENANCE_ARGUMENT_INVALID");
    }
    return maintain(managedRoot, currentRuntime, MaintenanceMode.PRUNE_VERSIONS);
  }

  /** Removes every validated installed version, leaving only the reusable private root and lock. */
  public ManagedRuntimeMaintenanceResult uninstall(Path managedRoot)
      throws ManagedRuntimeInstallException {
    return maintain(managedRoot, null, MaintenanceMode.UNINSTALL);
  }

  private static ManagedRuntimeMaintenanceResult maintain(
      Path managedRoot, InstalledRuntime currentRuntime, MaintenanceMode mode)
      throws ManagedRuntimeInstallException {
    if (managedRoot == null || mode == null) {
      throw failure("MAINTENANCE_ARGUMENT_INVALID");
    }
    try {
      checkInterrupted();
      var root = managedRoot.toAbsolutePath().normalize();
      if (!root.equals(managedRoot)) {
        throw failure("INSTALL_ROOT_UNSAFE");
      }
      if (Files.notExists(root, NOFOLLOW_LINKS)) {
        if (mode == MaintenanceMode.PRUNE_VERSIONS) {
          throw failure("CURRENT_RUNTIME_INVALID");
        }
        return new ManagedRuntimeMaintenanceResult(0, 0);
      }

      var security = PrivatePathPolicy.prepare(root);
      try (var lockChannel = security.openLock(root.resolve(LOCK_NAME));
          var ignored = acquireLock(lockChannel)) {
        checkInterrupted();
        var staging = root.resolve(STAGING_NAME);
        var versions = root.resolve(VERSIONS_NAME);
        var stagingRemoved = 0;
        if (mode != MaintenanceMode.PRUNE_VERSIONS) {
          stagingRemoved = cleanStaging(staging, security);
        }

        var versionsRemoved = 0;
        if (mode != MaintenanceMode.CLEAN_STAGING) {
          var installed = validateVersions(versions, security);
          if (mode == MaintenanceMode.PRUNE_VERSIONS) {
            var current = requireCurrent(root, versions, currentRuntime, installed);
            installed =
                installed.stream()
                    .filter(version -> !version.root().equals(current.root()))
                    .toList();
          }
          if (!installed.isEmpty()) {
            var quarantine = security.createOrVerifyDirectory(staging);
            quarantineVersions(installed, versions, quarantine);
            versionsRemoved = installed.size();
            deleteIfEmpty(quarantine, mode == MaintenanceMode.UNINSTALL);
          }
          if (mode == MaintenanceMode.UNINSTALL) {
            deleteIfEmpty(versions, true);
          }
        }
        return new ManagedRuntimeMaintenanceResult(stagingRemoved, versionsRemoved);
      }
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (AtomicMoveNotSupportedException failure) {
      throw failure("ATOMIC_MAINTENANCE_UNSUPPORTED");
    } catch (SecurityException | UnsupportedOperationException failure) {
      throw failure("INSTALL_ROOT_UNSAFE");
    } catch (IOException failure) {
      throw failure("MAINTENANCE_IO_FAILED");
    } catch (RuntimeException failure) {
      throw failure("INSTALL_INTERNAL_ERROR");
    }
  }

  private static String targetName(ManagedRuntimeArtifact artifact) {
    return artifact.runtimeVersion() + "-" + artifact.platform().id() + "-" + artifact.sha256();
  }

  private static int cleanStaging(Path staging, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    if (Files.notExists(staging, NOFOLLOW_LINKS)) {
      return 0;
    }
    security.verifyDirectory(staging);
    var children = directChildren(staging);
    if (children.size() > MAXIMUM_MAINTENANCE_DIRECTORIES) {
      throw failure("MAINTENANCE_LIMIT_EXCEEDED");
    }
    for (var child : children) {
      security.verifyDirectory(child);
      if (!maintenanceDirectoryName(child.getFileName().toString())) {
        throw failure("MAINTENANCE_STATE_INVALID");
      }
      validateStagingTree(child, security);
    }
    for (var child : children) {
      deleteTree(child);
    }
    deleteIfEmpty(staging, true);
    return children.size();
  }

  private static void validateStagingTree(Path root, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    var entries = 0;
    long bytes = 0;
    try (var paths = Files.walk(root)) {
      for (var path : paths.toList()) {
        checkInterrupted();
        if (++entries > MAXIMUM_STAGING_ENTRIES) {
          throw failure("MAINTENANCE_LIMIT_EXCEEDED");
        }
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
          security.verifyDirectory(path);
        } else {
          security.verifyManagedFile(path);
          var size = Files.size(path);
          if (size < 0 || bytes > MAXIMUM_STAGING_BYTES - size) {
            throw failure("MAINTENANCE_LIMIT_EXCEEDED");
          }
          bytes += size;
        }
      }
    }
  }

  private static List<ValidatedVersion> validateVersions(Path versions, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    if (Files.notExists(versions, NOFOLLOW_LINKS)) {
      return List.of();
    }
    security.verifyDirectory(versions);
    var children = directChildren(versions);
    if (children.size() > MAXIMUM_MAINTENANCE_DIRECTORIES) {
      throw failure("MAINTENANCE_LIMIT_EXCEEDED");
    }
    var result = new ArrayList<ValidatedVersion>(children.size());
    for (var child : children) {
      security.verifyDirectory(child);
      var manifestPath = child.resolve(SidecarManifest.NAME);
      security.verifyFile(manifestPath, false, null);
      var manifestBytes = readRegularFile(manifestPath, SidecarManifest.MAXIMUM_BYTES);
      var manifest = SidecarManifest.parse(manifestBytes);
      var manifestSha256 = sha256(manifestBytes);
      verifyInstalledTree(child, security, manifest, manifestSha256);
      var prefix = manifest.runtimeVersion() + "-" + manifest.platform().id() + "-";
      var name = child.getFileName().toString();
      if (!name.startsWith(prefix)
          || name.length() != prefix.length() + 64
          || !name.substring(prefix.length()).matches("[0-9a-f]{64}")) {
        throw failure("MAINTENANCE_STATE_INVALID");
      }
      result.add(new ValidatedVersion(child, manifest, manifestSha256));
    }
    return List.copyOf(result);
  }

  private static ValidatedVersion requireCurrent(
      Path root, Path versions, InstalledRuntime current, List<ValidatedVersion> installed)
      throws ManagedRuntimeInstallException {
    if (current == null
        || !current.installationRoot().getParent().equals(versions)
        || !current.installationRoot().startsWith(root)
        || !current
            .installationRoot()
            .getFileName()
            .toString()
            .equals(
                current.runtimeVersion()
                    + "-"
                    + current.platform().id()
                    + "-"
                    + current.artifactSha256())) {
      throw failure("CURRENT_RUNTIME_INVALID");
    }
    for (var candidate : installed) {
      if (!candidate.root().equals(current.installationRoot())) {
        continue;
      }
      var manifest = candidate.manifest();
      if (!candidate.manifestSha256().equals(current.manifestSha256())
          || manifest.platform() != current.platform()
          || !manifest.runtimeVersion().equals(current.runtimeVersion())
          || !manifest.nodeVersion().equals(current.nodeVersion())
          || !candidate.root().resolve(manifest.nodeExecutable()).equals(current.executable())
          || !candidate.root().resolve(manifest.entrypoint()).equals(current.entrypoint())) {
        throw failure("CURRENT_RUNTIME_INVALID");
      }
      return candidate;
    }
    throw failure("CURRENT_RUNTIME_INVALID");
  }

  private static void quarantineVersions(
      List<ValidatedVersion> versions, Path versionsRoot, Path staging)
      throws IOException, ManagedRuntimeInstallException {
    var moves = new ArrayList<MaintenanceMove>(versions.size());
    try {
      for (var version : versions) {
        checkInterrupted();
        var quarantine = staging.resolve("remove-" + UUID.randomUUID());
        Files.move(version.root(), quarantine, ATOMIC_MOVE);
        moves.add(new MaintenanceMove(version.root(), quarantine));
      }
    } catch (IOException | ManagedRuntimeInstallException | RuntimeException failure) {
      rollbackMoves(moves);
      throw failure;
    }
    forceDirectoryBestEffort(versionsRoot);
    for (var move : moves) {
      deleteTree(move.quarantine());
    }
    forceDirectoryBestEffort(staging);
  }

  private static void rollbackMoves(List<MaintenanceMove> moves)
      throws ManagedRuntimeInstallException {
    for (var index = moves.size() - 1; index >= 0; index--) {
      var move = moves.get(index);
      try {
        Files.move(move.quarantine(), move.original(), ATOMIC_MOVE);
      } catch (IOException | RuntimeException rollbackFailure) {
        throw failure("MAINTENANCE_ROLLBACK_FAILED");
      }
    }
  }

  private static List<Path> directChildren(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
    }
  }

  private static boolean maintenanceDirectoryName(String name) {
    var prefix =
        name.startsWith("install-") ? "install-" : name.startsWith("remove-") ? "remove-" : null;
    if (prefix == null) {
      return false;
    }
    var identifier = name.substring(prefix.length());
    try {
      return UUID.fromString(identifier).toString().equals(identifier);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private static void deleteTree(Path root) throws IOException, ManagedRuntimeInstallException {
    checkInterrupted();
    try (var paths = Files.walk(root)) {
      var ordered = paths.sorted(Comparator.reverseOrder()).toList();
      for (var path : ordered) {
        checkInterrupted();
        Files.delete(path);
      }
    }
  }

  private static void deleteIfEmpty(Path directory, boolean required)
      throws IOException, ManagedRuntimeInstallException {
    if (Files.notExists(directory, NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.delete(directory);
    } catch (DirectoryNotEmptyException failure) {
      if (required) {
        throw failure("MAINTENANCE_STATE_INVALID");
      }
    }
  }

  private static FileLock acquireLock(FileChannel channel)
      throws IOException, ManagedRuntimeInstallException {
    try {
      var lock = channel.tryLock();
      if (lock == null) {
        throw failure("INSTALL_BUSY");
      }
      return lock;
    } catch (OverlappingFileLockException failure) {
      throw failure("INSTALL_BUSY");
    }
  }

  private static void copyAndVerifyArtifact(
      Path archive,
      ManagedRuntimeArtifact artifact,
      RuntimeArtifactSource source,
      PrivatePathPolicy security)
      throws ManagedRuntimeInstallException {
    final InputStream input;
    try {
      input = source.open();
    } catch (IOException | RuntimeException failure) {
      throw failure("ARTIFACT_OPEN_FAILED");
    }
    if (input == null) {
      throw failure("ARTIFACT_OPEN_FAILED");
    }

    var digest = sha256Digest();
    long written = 0;
    try (input;
        var output = security.createFile(archive, false)) {
      var buffer = new byte[BUFFER_BYTES];
      while (written <= artifact.byteSize()) {
        checkInterrupted();
        var maximumRead = (int) Math.min(buffer.length, artifact.byteSize() + 1 - written);
        var count = readProgress(input, buffer, maximumRead);
        if (count < 0) {
          break;
        }
        digest.update(buffer, 0, count);
        writeFully(output, buffer, count);
        written += count;
      }
      output.force(true);
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (IOException | SecurityException failure) {
      throw failure("ARTIFACT_READ_FAILED");
    }
    if (written != artifact.byteSize()) {
      throw failure("ARTIFACT_SIZE_MISMATCH");
    }
    if (!hex(digest.digest()).equals(artifact.sha256())) {
      throw failure("ARTIFACT_HASH_MISMATCH");
    }
    try {
      security.finishFile(archive, false, written);
    } catch (IOException failure) {
      throw failure("ARTIFACT_READ_FAILED");
    }
  }

  private static PackageContent inspectArchive(ZipFile zip, ManagedRuntimeArtifact artifact)
      throws IOException, ManagedRuntimeInstallException {
    if (zip.getComment() != null && !zip.getComment().isEmpty()) {
      throw failure("ARCHIVE_INVALID");
    }
    var entries = new LinkedHashMap<String, ZipEntry>();
    var occupied = new HashSet<String>();
    var directories = new HashSet<String>();
    ZipEntry manifestEntry = null;
    var enumeration = zip.entries();
    var count = 0;
    while (enumeration.hasMoreElements()) {
      checkInterrupted();
      if (++count > MAXIMUM_ARCHIVE_ENTRIES) {
        throw failure("ARCHIVE_LIMIT_EXCEEDED");
      }
      var entry = enumeration.nextElement();
      if (entry.getComment() != null && !entry.getComment().isEmpty()) {
        throw failure("ARCHIVE_INVALID");
      }
      var path = InstallPathRules.archivePath(entry.getName(), entry.isDirectory());
      if (!occupied.add(InstallPathRules.folded(path))) {
        throw failure("ARCHIVE_ENTRY_DUPLICATE");
      }
      if (entry.isDirectory()) {
        if (!directories.add(path) || entry.getSize() > 0) {
          throw failure("ARCHIVE_ENTRY_DUPLICATE");
        }
      } else if (path.equals(SidecarManifest.NAME)) {
        if (manifestEntry != null) {
          throw failure("ARCHIVE_ENTRY_DUPLICATE");
        }
        manifestEntry = entry;
      } else {
        entries.put(path, entry);
      }
    }
    if (manifestEntry == null) {
      throw failure("MANIFEST_INVALID");
    }

    var manifestBytes =
        readEntry(zip, manifestEntry, SidecarManifest.MAXIMUM_BYTES, "MANIFEST_INVALID");
    var manifest = SidecarManifest.parse(manifestBytes);
    if (!manifest.runtimeVersion().equals(artifact.runtimeVersion())
        || !manifest.nodeVersion().equals(artifact.nodeVersion())
        || manifest.platform() != artifact.platform()) {
      throw failure("MANIFEST_ARTIFACT_MISMATCH");
    }
    if (!entries.keySet().equals(manifest.files().keySet())) {
      var undeclared = new HashSet<>(entries.keySet());
      undeclared.removeAll(manifest.files().keySet());
      throw failure(undeclared.isEmpty() ? "ARCHIVE_ENTRY_MISSING" : "ARCHIVE_ENTRY_UNDECLARED");
    }
    if (!declaredParents(entries.keySet()).containsAll(directories)) {
      throw failure("ARCHIVE_ENTRY_UNDECLARED");
    }
    return new PackageContent(manifest, Map.copyOf(entries), manifestBytes, sha256(manifestBytes));
  }

  private static void extract(
      ZipFile zip, Path payload, PrivatePathPolicy security, PackageContent content)
      throws IOException, ManagedRuntimeInstallException {
    for (var file : content.manifest().files().values()) {
      checkInterrupted();
      var target = resolve(payload, file.path(), "ARCHIVE_ENTRY_PATH_INVALID");
      ensureParents(payload, target.getParent(), security);
      extractFile(zip, content.entries().get(file.path()), target, file, security);
    }
    writeFile(payload.resolve(SidecarManifest.NAME), content.manifestBytes(), false, security);
  }

  private static void extractFile(
      ZipFile zip, ZipEntry entry, Path target, ManifestFile expected, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    if (entry == null || entry.getSize() > expected.size()) {
      throw failure("PAYLOAD_SIZE_MISMATCH");
    }
    var digest = sha256Digest();
    long written = 0;
    try (var input = zip.getInputStream(entry);
        var output = security.createFile(target, expected.executable())) {
      var buffer = new byte[BUFFER_BYTES];
      while (written <= expected.size()) {
        checkInterrupted();
        var maximumRead = (int) Math.min(buffer.length, expected.size() + 1 - written);
        var count = readProgress(input, buffer, maximumRead);
        if (count < 0) {
          break;
        }
        digest.update(buffer, 0, count);
        writeFully(output, buffer, count);
        written += count;
      }
      output.force(true);
    }
    if (written != expected.size()) {
      throw failure("PAYLOAD_SIZE_MISMATCH");
    }
    if (!hex(digest.digest()).equals(expected.sha256())) {
      throw failure("PAYLOAD_HASH_MISMATCH");
    }
    security.finishFile(target, expected.executable(), written);
  }

  private static void writeFile(
      Path target, byte[] bytes, boolean executable, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    try (var output = security.createFile(target, executable)) {
      writeFully(output, bytes, bytes.length);
      output.force(true);
    }
    security.finishFile(target, executable, bytes.length);
  }

  private static InstalledRuntime verifyInstalled(
      Path root,
      PrivatePathPolicy security,
      ManagedRuntimeArtifact artifact,
      PackageContent expected)
      throws IOException, ManagedRuntimeInstallException {
    verifyInstalledTree(root, security, expected.manifest(), expected.manifestSha256());
    return new InstalledRuntime(
        root.toAbsolutePath().normalize(),
        artifact.platform(),
        artifact.runtimeVersion(),
        artifact.nodeVersion(),
        artifact.sha256(),
        expected.manifestSha256(),
        root.resolve(expected.manifest().nodeExecutable()).toAbsolutePath().normalize(),
        root.resolve(expected.manifest().entrypoint()).toAbsolutePath().normalize());
  }

  private static void verifyInstalledTree(
      Path root,
      PrivatePathPolicy security,
      SidecarManifest expected,
      String expectedManifestSha256)
      throws IOException, ManagedRuntimeInstallException {
    security.verifyDirectory(root);
    var manifestPath = root.resolve(SidecarManifest.NAME);
    security.verifyFile(manifestPath, false, null);
    var manifestBytes = readRegularFile(manifestPath, SidecarManifest.MAXIMUM_BYTES);
    if (!sha256(manifestBytes).equals(expectedManifestSha256)
        || !SidecarManifest.parse(manifestBytes).equals(expected)) {
      throw failure("INSTALLED_STATE_INVALID");
    }

    var actual = new HashSet<String>();
    var actualDirectories = new HashSet<String>();
    try (var paths = Files.walk(root)) {
      for (var path : paths.toList()) {
        if (path.equals(root)) {
          continue;
        }
        var relative = root.relativize(path).toString().replace('\\', '/');
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
          security.verifyDirectory(path);
          actualDirectories.add(relative);
        } else {
          actual.add(relative);
        }
      }
    }
    var expectedPaths = new HashSet<>(expected.files().keySet());
    expectedPaths.add(SidecarManifest.NAME);
    if (!actual.equals(expectedPaths)) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    if (!actualDirectories.equals(declaredParents(expected.files().keySet()))) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    for (var file : expected.files().values()) {
      verifyInstalledFile(resolve(root, file.path(), "INSTALLED_STATE_INVALID"), security, file);
    }
  }

  private static void verifyInstalledFile(
      Path path, PrivatePathPolicy security, ManifestFile expected)
      throws IOException, ManagedRuntimeInstallException {
    security.verifyFile(path, expected.executable(), expected.size());
    var digest = sha256Digest();
    long read = 0;
    try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
      var buffer = new byte[BUFFER_BYTES];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        checkInterrupted();
        if (count == 0) {
          continue;
        }
        read += count;
        if (read > expected.size()) {
          throw failure("INSTALLED_STATE_INVALID");
        }
        digest.update(buffer, 0, count);
      }
    }
    if (read != expected.size() || !hex(digest.digest()).equals(expected.sha256())) {
      throw failure("INSTALLED_STATE_INVALID");
    }
  }

  private static void publish(Path payload, Path target)
      throws IOException, ManagedRuntimeInstallException {
    try {
      Files.move(payload, target, ATOMIC_MOVE);
    } catch (FileAlreadyExistsException failure) {
      throw failure("INSTALL_TARGET_CONFLICT");
    }
  }

  private static void ensureParents(Path root, Path parent, PrivatePathPolicy security)
      throws IOException, ManagedRuntimeInstallException {
    var current = root;
    for (var segment : root.relativize(parent)) {
      current = security.createOrVerifyDirectory(current.resolve(segment.toString()));
    }
  }

  private static Path resolve(Path root, String relative, String code)
      throws ManagedRuntimeInstallException {
    var resolved = root.resolve(relative).normalize();
    if (resolved.equals(root) || !resolved.startsWith(root)) {
      throw failure(code);
    }
    return resolved;
  }

  private static Set<String> declaredParents(Set<String> paths) {
    var parents = new HashSet<String>();
    for (var path : paths) {
      var separator = path.lastIndexOf('/');
      while (separator > 0) {
        parents.add(path.substring(0, separator));
        separator = path.lastIndexOf('/', separator - 1);
      }
    }
    return parents;
  }

  private static byte[] readEntry(ZipFile zip, ZipEntry entry, int maximum, String code)
      throws IOException, ManagedRuntimeInstallException {
    if (entry.getSize() > maximum) {
      throw failure(code);
    }
    try (var input = zip.getInputStream(entry)) {
      var bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum || (entry.getSize() >= 0 && bytes.length != entry.getSize())) {
        throw failure(code);
      }
      return bytes;
    }
  }

  private static byte[] readRegularFile(Path path, int maximum)
      throws IOException, ManagedRuntimeInstallException {
    var size = Files.size(path);
    if (size < 1 || size > maximum) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
      var bytes = input.readNBytes(maximum + 1);
      if (bytes.length != size) {
        throw failure("INSTALLED_STATE_INVALID");
      }
      return bytes;
    }
  }

  private static void writeFully(FileChannel output, byte[] bytes, int count) throws IOException {
    var buffer = ByteBuffer.wrap(bytes, 0, count);
    while (buffer.hasRemaining()) {
      output.write(buffer);
    }
  }

  private static int readProgress(InputStream input, byte[] buffer, int maximum)
      throws IOException {
    var count = input.read(buffer, 0, maximum);
    if (count != 0) {
      return count;
    }
    var value = input.read();
    if (value < 0) {
      return -1;
    }
    buffer[0] = (byte) value;
    return 1;
  }

  private static void checkInterrupted() throws ManagedRuntimeInstallException {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("INSTALL_CANCELLED");
    }
  }

  private static String sha256(byte[] value) {
    var digest = sha256Digest();
    digest.update(value);
    return hex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String hex(byte[] value) {
    return java.util.HexFormat.of().formatHex(value);
  }

  private static void forceDirectoryBestEffort(Path directory) {
    try (var channel = FileChannel.open(directory, READ)) {
      channel.force(true);
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // The directory move is already atomic; directory fsync is not portable in Java.
    }
  }

  private static void deleteTreeBestEffort(Path root) {
    if (root == null || !Files.exists(root, NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      var ordered = new ArrayList<>(paths.toList());
      for (var index = ordered.size() - 1; index >= 0; index--) {
        try {
          Files.deleteIfExists(ordered.get(index));
        } catch (IOException | SecurityException ignored) {
          // Cleanup never leaves this invocation's random staging directory.
        }
      }
    } catch (IOException | SecurityException ignored) {
      // The stable installation diagnostic remains authoritative.
    }
  }

  private static ManagedRuntimeInstallException failure(String code) {
    return new ManagedRuntimeInstallException(code);
  }

  private record PackageContent(
      SidecarManifest manifest,
      Map<String, ZipEntry> entries,
      byte[] manifestBytes,
      String manifestSha256) {}

  private record ValidatedVersion(Path root, SidecarManifest manifest, String manifestSha256) {}

  private record MaintenanceMove(Path original, Path quarantine) {}

  private enum MaintenanceMode {
    CLEAN_STAGING,
    PRUNE_VERSIONS,
    UNINSTALL
  }
}
