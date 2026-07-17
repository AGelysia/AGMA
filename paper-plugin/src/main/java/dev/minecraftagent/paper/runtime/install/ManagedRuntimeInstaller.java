package dev.minecraftagent.paper.runtime.install;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.minecraftagent.paper.transport.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Installs a pinned sidecar archive into an immutable, versioned runtime directory. */
public final class ManagedRuntimeInstaller {
  static final String MANIFEST_NAME = "sidecar-manifest.json";

  private static final String MARKER_NAME = ".installed.json";
  private static final String LOCK_NAME = ".install.lock";
  private static final String CURRENT_DIRECTORY = "current";
  private static final String STAGING_DIRECTORY = ".staging";
  private static final int BUFFER_BYTES = 64 * 1024;
  private static final int MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;
  private static final int MAXIMUM_FILES = 16 * 1024;
  private static final int MAXIMUM_MANIFEST_VALUES = MAXIMUM_FILES * 6 + 32;
  private static final int MAXIMUM_ZIP_ENTRIES = MAXIMUM_FILES * 2 + 2;
  private static final long MAXIMUM_FILE_BYTES = 256L * 1024L * 1024L;
  private static final long MAXIMUM_EXPANDED_BYTES = 768L * 1024L * 1024L;
  private static final Set<String> MANIFEST_FIELDS =
      Set.of("schemaVersion", "runtimeVersion", "platform", "files");
  private static final Set<String> FILE_FIELDS = Set.of("path", "size", "sha256", "executable");
  private static final Set<String> MARKER_FIELDS =
      Set.of("schemaVersion", "artifactSha256", "manifestSha256");
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> PRIVATE_EXECUTABLE =
      PosixFilePermissions.fromString("rwx------");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern RUNTIME_VERSION =
      Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$");
  private static final Pattern PLATFORM = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

  public Path install(
      Path managedRoot, ManagedRuntimeArtifact artifact, ArtifactSource artifactSource)
      throws ManagedRuntimeInstallException {
    Objects.requireNonNull(managedRoot, "managedRoot");
    Objects.requireNonNull(artifact, "artifact");
    Objects.requireNonNull(artifactSource, "artifactSource");

    Path staging = null;
    try {
      checkInterrupted();
      var root = managedRoot.toAbsolutePath().normalize();
      var owner = prepareRoot(root);
      try (var lockChannel = openLock(root.resolve(LOCK_NAME), owner);
          var ignored = acquireLock(lockChannel)) {
        checkInterrupted();
        var stagingParent = createOrVerifyDirectory(root.resolve(STAGING_DIRECTORY), owner);
        staging = createPrivateDirectory(stagingParent.resolve("install-" + UUID.randomUUID()));
        var archive = staging.resolve("artifact.zip");
        writeAndVerifyArchive(archive, owner, artifact, artifactSource);

        try (var zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
          var packageContent = inspectArchive(zip);
          var current = createOrVerifyDirectory(root.resolve(CURRENT_DIRECTORY), owner);
          var target = current.resolve(packageContent.manifest().runtimeVersion());
          if (Files.exists(target, NOFOLLOW_LINKS)) {
            verifyInstalled(target, owner, artifact, packageContent);
            return target;
          }

          var payload = createPrivateDirectory(staging.resolve("payload"));
          extract(zip, payload, owner, packageContent);
          writeMarker(payload, artifact.sha256(), packageContent.manifestSha256());
          verifyInstalled(payload, owner, artifact, packageContent);
          publish(payload, target);
          verifyInstalled(target, owner, artifact, packageContent);
          return target;
        }
      }
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
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

  private static UserPrincipal prepareRoot(Path root)
      throws IOException, ManagedRuntimeInstallException {
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
      try {
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
      } catch (FileAlreadyExistsException ignored) {
        // The object that won the creation race is verified below.
      }
    }
    return verifyDirectory(root, null).owner();
  }

  private static FileChannel openLock(Path lock, UserPrincipal owner)
      throws IOException, ManagedRuntimeInstallException {
    Set<OpenOption> createOptions = Set.of(CREATE, WRITE, NOFOLLOW_LINKS);
    var channel =
        FileChannel.open(lock, createOptions, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
    try {
      verifyFile(lock, owner, PRIVATE_FILE, null);
      return channel;
    } catch (ManagedRuntimeInstallException failure) {
      channel.close();
      throw failure("INSTALL_ROOT_UNSAFE");
    } catch (IOException failure) {
      channel.close();
      throw failure;
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

  private static Path createOrVerifyDirectory(Path path, UserPrincipal owner)
      throws IOException, ManagedRuntimeInstallException {
    try {
      Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
    } catch (FileAlreadyExistsException ignored) {
      // Existing state is allowed only after the same strict verification.
    }
    verifyDirectory(path, owner);
    return path;
  }

  private static Path createPrivateDirectory(Path path)
      throws IOException, ManagedRuntimeInstallException {
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
    verifyDirectory(path, null);
    return path;
  }

  private static void writeAndVerifyArchive(
      Path archive, UserPrincipal owner, ManagedRuntimeArtifact artifact, ArtifactSource source)
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
        var output =
            FileChannel.open(
                archive,
                Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE))) {
      var buffer = new byte[BUFFER_BYTES];
      while (written <= artifact.byteSize()) {
        checkInterrupted();
        var maximumRead = (int) Math.min(buffer.length, artifact.byteSize() + 1 - written);
        var count = readProgress(input, buffer, maximumRead);
        if (count < 0) {
          break;
        }
        digest.update(buffer, 0, count);
        var bytes = ByteBuffer.wrap(buffer, 0, count);
        while (bytes.hasRemaining()) {
          output.write(bytes);
        }
        written += count;
      }
      output.force(true);
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
      verifyFile(archive, owner, PRIVATE_FILE, artifact.byteSize());
    } catch (IOException | ManagedRuntimeInstallException failure) {
      throw failure("ARTIFACT_READ_FAILED");
    }
  }

  private static PackageContent inspectArchive(ZipFile zip)
      throws IOException, ManagedRuntimeInstallException {
    var entries = new LinkedHashMap<String, ZipEntry>();
    var directories = new HashSet<String>();
    ZipEntry manifestEntry = null;
    var enumeration = zip.entries();
    var entryCount = 0;
    while (enumeration.hasMoreElements()) {
      checkInterrupted();
      if (++entryCount > MAXIMUM_ZIP_ENTRIES) {
        throw failure("ARCHIVE_LIMIT_EXCEEDED");
      }
      var entry = enumeration.nextElement();
      var path = validateEntryPath(entry.getName(), entry.isDirectory());
      if (entry.isDirectory() && entry.getSize() > 0) {
        throw failure("ARCHIVE_ENTRY_INVALID");
      }
      if (path.equals(MANIFEST_NAME) && !entry.isDirectory()) {
        if (manifestEntry != null || entries.containsKey(path) || directories.contains(path)) {
          throw failure("ARCHIVE_ENTRY_DUPLICATE");
        }
        manifestEntry = entry;
      } else if (entry.isDirectory()) {
        if (!directories.add(path) || entries.containsKey(path) || path.equals(MANIFEST_NAME)) {
          throw failure("ARCHIVE_ENTRY_DUPLICATE");
        }
      } else if (entries.putIfAbsent(path, entry) != null
          || directories.contains(path)
          || path.equals(MANIFEST_NAME)) {
        throw failure("ARCHIVE_ENTRY_DUPLICATE");
      }
    }
    if (manifestEntry == null) {
      throw failure("MANIFEST_INVALID");
    }

    var manifestBytes =
        readZipEntry(zip, manifestEntry, MAXIMUM_MANIFEST_BYTES, "MANIFEST_INVALID");
    var manifest = parseManifest(manifestBytes);
    var declaredPaths = manifest.files().keySet();
    for (var entry : entries.keySet()) {
      if (!declaredPaths.contains(entry)) {
        throw failure("ARCHIVE_ENTRY_UNDECLARED");
      }
    }
    for (var declared : declaredPaths) {
      if (!entries.containsKey(declared)) {
        throw failure("ARCHIVE_ENTRY_MISSING");
      }
    }
    var parentPaths = declaredParentPaths(declaredPaths);
    for (var directory : directories) {
      if (!parentPaths.contains(directory)) {
        throw failure("ARCHIVE_ENTRY_UNDECLARED");
      }
    }
    return new PackageContent(manifest, entries, manifestBytes, sha256(manifestBytes));
  }

  private static byte[] readZipEntry(ZipFile zip, ZipEntry entry, int limit, String code)
      throws IOException, ManagedRuntimeInstallException {
    if (entry.getSize() > limit) {
      throw failure(code);
    }
    try (var input = zip.getInputStream(entry)) {
      var content = input.readNBytes(limit + 1);
      if (content.length > limit || (entry.getSize() >= 0 && content.length != entry.getSize())) {
        throw failure(code);
      }
      return content;
    }
  }

  private static SidecarManifest parseManifest(byte[] bytes) throws ManagedRuntimeInstallException {
    final JsonObject root;
    try {
      var text = decodeUtf8(bytes);
      root =
          StrictJson.parseObject(
              text, "MANAGED_RUNTIME_MANIFEST_INVALID", "installation", 8, MAXIMUM_MANIFEST_VALUES);
    } catch (RuntimeException | CharacterCodingException failure) {
      throw failure("MANIFEST_INVALID");
    }
    requireExactFields(root, MANIFEST_FIELDS, "MANIFEST_INVALID");
    if (integer(root.get("schemaVersion"), 1, 1, "MANIFEST_INVALID") != 1) {
      throw failure("MANIFEST_INVALID");
    }
    var runtimeVersion =
        string(root.get("runtimeVersion"), RUNTIME_VERSION, 64, "MANIFEST_INVALID");
    var platform = string(root.get("platform"), PLATFORM, 32, "MANIFEST_INVALID");
    var filesElement = root.get("files");
    if (filesElement == null || !filesElement.isJsonArray()) {
      throw failure("MANIFEST_INVALID");
    }
    var filesArray = filesElement.getAsJsonArray();
    if (filesArray.isEmpty() || filesArray.size() > MAXIMUM_FILES) {
      throw failure("MANIFEST_INVALID");
    }

    var files = new LinkedHashMap<String, ManifestFile>();
    long expandedBytes = 0;
    for (var element : filesArray) {
      checkInterrupted();
      if (!element.isJsonObject()) {
        throw failure("MANIFEST_INVALID");
      }
      var file = element.getAsJsonObject();
      requireExactFields(file, FILE_FIELDS, "MANIFEST_INVALID");
      var path = validateManifestPath(stringValue(file.get("path"), 240, "MANIFEST_INVALID"));
      var size = integer(file.get("size"), 0, MAXIMUM_FILE_BYTES, "MANIFEST_INVALID");
      var hash = string(file.get("sha256"), SHA256, 64, "MANIFEST_INVALID");
      var executable = bool(file.get("executable"), "MANIFEST_INVALID");
      if (files.putIfAbsent(path, new ManifestFile(path, size, hash, executable)) != null) {
        throw failure("MANIFEST_INVALID");
      }
      if (expandedBytes > MAXIMUM_EXPANDED_BYTES - size) {
        throw failure("ARCHIVE_LIMIT_EXCEEDED");
      }
      expandedBytes += size;
    }
    rejectPathConflicts(files.keySet());
    return new SidecarManifest(runtimeVersion, platform, Map.copyOf(files));
  }

  private static void extract(
      ZipFile zip, Path payload, UserPrincipal owner, PackageContent packageContent)
      throws ManagedRuntimeInstallException {
    try {
      for (var file : packageContent.manifest().files().values()) {
        checkInterrupted();
        var target = resolveOutput(payload, file.path());
        ensureParents(payload, target.getParent(), owner);
        extractFile(zip, packageContent.entries().get(file.path()), target, file);
      }
      writePrivateFile(payload.resolve(MANIFEST_NAME), packageContent.manifestBytes(), false);
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (IOException | SecurityException failure) {
      throw failure("INSTALL_IO_FAILED");
    }
  }

  private static void extractFile(ZipFile zip, ZipEntry entry, Path target, ManifestFile file)
      throws IOException, ManagedRuntimeInstallException {
    if (entry.getSize() > file.size()) {
      throw failure("FILE_SIZE_MISMATCH");
    }
    var digest = sha256Digest();
    long written = 0;
    var permissions = file.executable() ? PRIVATE_EXECUTABLE : PRIVATE_FILE;
    try (var input = zip.getInputStream(entry);
        var output =
            FileChannel.open(
                target,
                Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(permissions))) {
      var buffer = new byte[BUFFER_BYTES];
      while (written <= file.size()) {
        checkInterrupted();
        var maximumRead = (int) Math.min(buffer.length, file.size() + 1 - written);
        var count = readProgress(input, buffer, maximumRead);
        if (count < 0) {
          break;
        }
        digest.update(buffer, 0, count);
        var bytes = ByteBuffer.wrap(buffer, 0, count);
        while (bytes.hasRemaining()) {
          output.write(bytes);
        }
        written += count;
      }
      output.force(true);
    }
    if (written != file.size()) {
      throw failure("FILE_SIZE_MISMATCH");
    }
    if (!hex(digest.digest()).equals(file.sha256())) {
      throw failure("FILE_HASH_MISMATCH");
    }
  }

  private static void writeMarker(Path payload, String artifactHash, String manifestHash)
      throws ManagedRuntimeInstallException {
    var marker = new JsonObject();
    marker.addProperty("schemaVersion", 1);
    marker.addProperty("artifactSha256", artifactHash);
    marker.addProperty("manifestSha256", manifestHash);
    try {
      writePrivateFile(
          payload.resolve(MARKER_NAME), marker.toString().getBytes(StandardCharsets.UTF_8), false);
    } catch (IOException | SecurityException failure) {
      throw failure("INSTALL_IO_FAILED");
    }
  }

  private static void writePrivateFile(Path target, byte[] content, boolean executable)
      throws IOException {
    var permissions = executable ? PRIVATE_EXECUTABLE : PRIVATE_FILE;
    try (var output =
        FileChannel.open(
            target,
            Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(permissions))) {
      var buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        output.write(buffer);
      }
      output.force(true);
    }
  }

  private static void publish(Path payload, Path target) throws ManagedRuntimeInstallException {
    try {
      Files.move(payload, target, ATOMIC_MOVE);
      forceDirectoryBestEffort(target.getParent());
    } catch (AtomicMoveNotSupportedException failure) {
      throw failure("ATOMIC_PUBLISH_UNAVAILABLE");
    } catch (FileAlreadyExistsException failure) {
      throw failure("INSTALL_TARGET_EXISTS");
    } catch (IOException | SecurityException failure) {
      throw failure("PUBLISH_FAILED");
    }
  }

  private static void verifyInstalled(
      Path directory, UserPrincipal owner, ManagedRuntimeArtifact artifact, PackageContent expected)
      throws ManagedRuntimeInstallException {
    try {
      verifyDirectoryTree(directory, owner);
      var manifestPath = directory.resolve(MANIFEST_NAME);
      var markerPath = directory.resolve(MARKER_NAME);
      var manifestBytes = readBoundedRegularFile(manifestPath, owner, MAXIMUM_MANIFEST_BYTES);
      var markerBytes = readBoundedRegularFile(markerPath, owner, MAXIMUM_MANIFEST_BYTES);
      var manifestHash = sha256(manifestBytes);
      var installedManifest = parseManifest(manifestBytes);
      if (!installedManifest.equals(expected.manifest())
          || !manifestHash.equals(expected.manifestSha256())) {
        throw failure("INSTALLED_STATE_INVALID");
      }
      verifyMarker(markerBytes, artifact.sha256(), manifestHash);

      var expectedFiles = new HashMap<>(installedManifest.files());
      try (var paths = Files.walk(directory)) {
        for (var path : paths.toList()) {
          checkInterrupted();
          if (path.equals(directory) || Files.isDirectory(path, NOFOLLOW_LINKS)) {
            continue;
          }
          var relative =
              directory.relativize(path).toString().replace(java.io.File.separatorChar, '/');
          if (relative.equals(MANIFEST_NAME) || relative.equals(MARKER_NAME)) {
            continue;
          }
          var declared = expectedFiles.remove(relative);
          if (declared == null) {
            throw failure("INSTALLED_STATE_INVALID");
          }
          verifyInstalledFile(path, owner, declared);
        }
      }
      if (!expectedFiles.isEmpty()) {
        throw failure("INSTALLED_STATE_INVALID");
      }
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (IOException | SecurityException | UnsupportedOperationException failure) {
      throw failure("INSTALLED_STATE_INVALID");
    }
  }

  private static void verifyDirectoryTree(Path root, UserPrincipal owner)
      throws IOException, ManagedRuntimeInstallException {
    verifyDirectory(root, owner);
    try (var paths = Files.walk(root)) {
      for (var path : paths.toList()) {
        checkInterrupted();
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
          verifyDirectory(path, owner);
        }
      }
    }
  }

  private static byte[] readBoundedRegularFile(Path file, UserPrincipal owner, int maximum)
      throws IOException, ManagedRuntimeInstallException {
    var attributes = verifyFile(file, owner, PRIVATE_FILE, null);
    if (attributes.size() > maximum) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    try (var input = Files.newInputStream(file, READ, NOFOLLOW_LINKS)) {
      var bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum || bytes.length != attributes.size()) {
        throw failure("INSTALLED_STATE_INVALID");
      }
      return bytes;
    }
  }

  private static void verifyMarker(byte[] bytes, String artifactHash, String manifestHash)
      throws ManagedRuntimeInstallException {
    final JsonObject marker;
    try {
      marker =
          StrictJson.parseObject(
              decodeUtf8(bytes), "MANAGED_RUNTIME_MARKER_INVALID", "installation");
    } catch (RuntimeException | CharacterCodingException failure) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    requireExactFields(marker, MARKER_FIELDS, "INSTALLED_STATE_INVALID");
    if (integer(marker.get("schemaVersion"), 1, 1, "INSTALLED_STATE_INVALID") != 1
        || !artifactHash.equals(
            string(marker.get("artifactSha256"), SHA256, 64, "INSTALLED_STATE_INVALID"))
        || !manifestHash.equals(
            string(marker.get("manifestSha256"), SHA256, 64, "INSTALLED_STATE_INVALID"))) {
      throw failure("INSTALLED_STATE_INVALID");
    }
  }

  private static void verifyInstalledFile(Path path, UserPrincipal owner, ManifestFile expected)
      throws IOException, ManagedRuntimeInstallException {
    var permissions = expected.executable() ? PRIVATE_EXECUTABLE : PRIVATE_FILE;
    var attributes = verifyFile(path, owner, permissions, expected.size());
    var digest = sha256Digest();
    long read = 0;
    try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
      var buffer = new byte[BUFFER_BYTES];
      while (read <= expected.size()) {
        checkInterrupted();
        var maximumRead = (int) Math.min(buffer.length, expected.size() + 1 - read);
        var count = readProgress(input, buffer, maximumRead);
        if (count < 0) {
          break;
        }
        digest.update(buffer, 0, count);
        read += count;
      }
    }
    if (read != expected.size() || !hex(digest.digest()).equals(expected.sha256())) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    if (attributes.size() != read) {
      throw failure("INSTALLED_STATE_INVALID");
    }
  }

  private static void ensureParents(Path root, Path parent, UserPrincipal owner)
      throws IOException, ManagedRuntimeInstallException {
    var relative = root.relativize(parent);
    var current = root;
    for (var segment : relative) {
      current = current.resolve(segment.toString());
      createOrVerifyDirectory(current, owner);
    }
  }

  private static Path resolveOutput(Path root, String relative)
      throws ManagedRuntimeInstallException {
    var target = root.resolve(relative).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw failure("ARCHIVE_ENTRY_PATH_INVALID");
    }
    return target;
  }

  private static String validateEntryPath(String raw, boolean directory)
      throws ManagedRuntimeInstallException {
    if (raw == null || raw.isEmpty()) {
      throw failure("ARCHIVE_ENTRY_PATH_INVALID");
    }
    var path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    if ((!directory && raw.endsWith("/")) || !validRelativePath(path)) {
      throw failure("ARCHIVE_ENTRY_PATH_INVALID");
    }
    return path;
  }

  private static String validateManifestPath(String path) throws ManagedRuntimeInstallException {
    if (!validRelativePath(path) || path.equals(MANIFEST_NAME) || path.equals(MARKER_NAME)) {
      throw failure("MANIFEST_INVALID");
    }
    return path;
  }

  private static boolean validRelativePath(String path) {
    if (path.isEmpty()
        || path.length() > 240
        || path.startsWith("/")
        || path.endsWith("/")
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0
        || hasDrivePrefix(path)) {
      return false;
    }
    for (var segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasDrivePrefix(String path) {
    return path.length() >= 2
        && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z')
            || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'))
        && path.charAt(1) == ':';
  }

  private static Set<String> declaredParentPaths(Set<String> files) {
    var parents = new HashSet<String>();
    for (var file : files) {
      var separator = file.lastIndexOf('/');
      while (separator > 0) {
        parents.add(file.substring(0, separator));
        separator = file.lastIndexOf('/', separator - 1);
      }
    }
    return parents;
  }

  private static void rejectPathConflicts(Set<String> paths) throws ManagedRuntimeInstallException {
    for (var path : paths) {
      var separator = path.lastIndexOf('/');
      while (separator > 0) {
        if (paths.contains(path.substring(0, separator))) {
          throw failure("MANIFEST_INVALID");
        }
        separator = path.lastIndexOf('/', separator - 1);
      }
    }
  }

  private static void requireExactFields(JsonObject object, Set<String> fields, String code)
      throws ManagedRuntimeInstallException {
    if (object.size() != fields.size() || !object.keySet().equals(fields)) {
      throw failure(code);
    }
  }

  private static long integer(JsonElement element, long minimum, long maximum, String code)
      throws ManagedRuntimeInstallException {
    try {
      if (element == null
          || !element.isJsonPrimitive()
          || !element.getAsJsonPrimitive().isNumber()) {
        throw failure(code);
      }
      var value = element.getAsBigDecimal().longValueExact();
      if (value < minimum || value > maximum) {
        throw failure(code);
      }
      return value;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw failure(code);
    }
  }

  private static String string(JsonElement element, Pattern pattern, int maximum, String code)
      throws ManagedRuntimeInstallException {
    var value = stringValue(element, maximum, code);
    if (!pattern.matcher(value).matches()) {
      throw failure(code);
    }
    return value;
  }

  private static String stringValue(JsonElement element, int maximum, String code)
      throws ManagedRuntimeInstallException {
    if (element == null || !element.isJsonPrimitive()) {
      throw failure(code);
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isString()) {
      throw failure(code);
    }
    var value = primitive.getAsString();
    if (value.isEmpty() || value.length() > maximum) {
      throw failure(code);
    }
    return value;
  }

  private static boolean bool(JsonElement element, String code)
      throws ManagedRuntimeInstallException {
    if (element == null
        || !element.isJsonPrimitive()
        || !element.getAsJsonPrimitive().isBoolean()) {
      throw failure(code);
    }
    return element.getAsBoolean();
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static int readProgress(InputStream input, byte[] buffer, int maximumRead)
      throws IOException {
    var count = input.read(buffer, 0, maximumRead);
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

  private static DirectoryIdentity verifyDirectory(Path path, UserPrincipal expectedOwner)
      throws IOException, ManagedRuntimeInstallException {
    var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()
        || !attributes.isDirectory()
        || !attributes.permissions().equals(PRIVATE_DIRECTORY)
        || (expectedOwner != null && !attributes.owner().equals(expectedOwner))) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    return new DirectoryIdentity(attributes.owner());
  }

  private static PosixFileAttributes verifyFile(
      Path path, UserPrincipal owner, Set<PosixFilePermission> permissions, Long expectedSize)
      throws IOException, ManagedRuntimeInstallException {
    var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
    var links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()
        || !attributes.isRegularFile()
        || !attributes.permissions().equals(permissions)
        || !attributes.owner().equals(owner)
        || !(links instanceof Number number)
        || number.longValue() != 1L
        || (expectedSize != null && attributes.size() != expectedSize)) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    return attributes;
  }

  private static String sha256(byte[] bytes) {
    var digest = sha256Digest();
    digest.update(bytes);
    return hex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private static void forceDirectoryBestEffort(Path directory) {
    try (var channel = FileChannel.open(directory, READ)) {
      channel.force(true);
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // Publication is already atomic; directory fsync is not portable in Java.
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
          // Cleanup is limited to this invocation's random staging directory.
        }
      }
    } catch (IOException | SecurityException ignored) {
      // The installation diagnostic remains authoritative.
    }
  }

  private static ManagedRuntimeInstallException failure(String code) {
    return new ManagedRuntimeInstallException(code);
  }

  private record DirectoryIdentity(UserPrincipal owner) {}

  private record ManifestFile(String path, long size, String sha256, boolean executable) {}

  private record SidecarManifest(
      String runtimeVersion, String platform, Map<String, ManifestFile> files) {}

  private record PackageContent(
      SidecarManifest manifest,
      Map<String, ZipEntry> entries,
      byte[] manifestBytes,
      String manifestSha256) {}
}
