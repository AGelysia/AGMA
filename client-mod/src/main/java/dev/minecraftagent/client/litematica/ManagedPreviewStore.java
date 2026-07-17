package dev.minecraftagent.client.litematica;

import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.BuildPreviewView.Position;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Connection-scoped registry of client-generated schematics. */
final class ManagedPreviewStore {
  private static final int MAX_ARTIFACTS = 8;

  private final Path root;
  private final long maxBytes;
  private final NativeLitematicaWriter writer;
  private final Map<UUID, Artifact> artifacts = new HashMap<>();
  private final Map<StageKey, Artifact> stagedArtifacts = new HashMap<>();
  private final Map<UUID, List<Artifact>> retiredArtifacts = new HashMap<>();

  ManagedPreviewStore(Path root, long maxBytes, NativeLitematicaWriter writer) throws IOException {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    this.writer = Objects.requireNonNull(writer, "writer");
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (Files.exists(this.root, LinkOption.NOFOLLOW_LINKS)) {
      requireRoot();
    }
  }

  synchronized Artifact stage(BuildPreviewView preview) throws IOException {
    Objects.requireNonNull(preview, "preview");
    Path realRoot = requireRoot();
    Artifact existing = artifacts.get(preview.previewId());
    if (existing != null && preview.revision() < existing.revision()) {
      return existing;
    }
    if (existing != null && preview.revision() == existing.revision()) {
      if (!preview.equals(existing.source())) {
        throw new IOException("preview revision was reused with different content");
      }
      return existing;
    }
    var stageKey = StageKey.from(preview);
    Artifact alreadyStaged = stagedArtifacts.get(stageKey);
    if (alreadyStaged != null) {
      if (!preview.equals(alreadyStaged.source())) {
        throw new IOException("preview revision was reused with different content");
      }
      return alreadyStaged;
    }
    if (stagedArtifacts.size() >= MAX_ARTIFACTS) {
      throw new IOException("managed preview staging limit reached");
    }

    Path target =
        realRoot.resolve(
            preview.previewId()
                + "."
                + preview.revision()
                + "."
                + UUID.randomUUID()
                + ".litematica");
    byte[] encoded = writer.write(preview);
    if (encoded.length < 1 || encoded.length > maxBytes) {
      throw new IOException("generated schematic exceeds its byte limit");
    }

    Path temporary = realRoot.resolve("." + preview.previewId() + "." + UUID.randomUUID() + ".tmp");
    boolean published = false;
    boolean registered = false;
    try {
      writeNewFile(temporary, encoded);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("managed root does not support atomic publication", exception);
      }
      published = true;
      syncDirectory(realRoot);
      BasicFileAttributes attributes = attributes(target);
      if (!attributes.isRegularFile() || attributes.size() != encoded.length) {
        throw new IOException("published schematic is not a regular file");
      }
      String encodedHash = hash(encoded);
      byte[] publishedBytes = readBounded(target);
      BasicFileAttributes verifiedAttributes = attributes(target);
      if (!sameIdentity(attributes, verifiedAttributes)
          || !hash(publishedBytes).equals(encodedHash)) {
        throw new IOException("published schematic changed during verification");
      }
      attributes = verifiedAttributes;
      Artifact artifact =
          new Artifact(
              preview,
              preview.previewId(),
              preview.revision(),
              preview.baseRegionHash(),
              preview.changeSetHash(),
              preview.contentHash(),
              preview.origin(),
              target,
              encoded.length,
              attributes.lastModifiedTime(),
              fileKey(attributes),
              encodedHash);
      stagedArtifacts.put(stageKey, artifact);
      registered = true;
      return artifact;
    } finally {
      Files.deleteIfExists(temporary);
      if (published && !registered) {
        Files.deleteIfExists(target);
      }
    }
  }

  synchronized Artifact commit(
      BuildPreviewView preview, Set<UUID> displayedViewIds, Set<String> loadedSchematicHashes)
      throws IOException {
    Objects.requireNonNull(preview, "preview");
    Objects.requireNonNull(displayedViewIds, "displayedViewIds");
    Objects.requireNonNull(loadedSchematicHashes, "loadedSchematicHashes");
    if (!displayedViewIds.contains(preview.previewId())) {
      throw new IOException("committed preview is not displayed");
    }
    if (displayedViewIds.size() > MAX_ARTIFACTS) {
      throw new IOException("managed preview limit reached");
    }

    Artifact staged = stagedArtifacts.remove(StageKey.from(preview));
    Artifact current = artifacts.get(preview.previewId());
    if (staged == null) {
      if (current != null
          && (preview.equals(current.source()) || preview.revision() < current.revision())) {
        cleanup(displayedViewIds, loadedSchematicHashes);
        return current;
      }
      throw new IOException("preview was not staged");
    }

    cleanup(displayedViewIds, loadedSchematicHashes);
    Artifact replaced = artifacts.put(preview.previewId(), staged);
    if (replaced != null && replaced != staged) {
      retireOrDelete(replaced, loadedSchematicHashes);
    }
    discardStagedAtOrBelow(preview.previewId(), preview.revision());
    cleanupRetired(loadedSchematicHashes);
    return staged;
  }

  synchronized void discard(BuildPreviewView preview) {
    Objects.requireNonNull(preview, "preview");
    Artifact staged = stagedArtifacts.remove(StageKey.from(preview));
    deleteOwned(staged);
  }

  synchronized void reconcile(Set<UUID> displayedViewIds, Set<String> loadedSchematicHashes)
      throws IOException {
    Objects.requireNonNull(displayedViewIds, "displayedViewIds");
    Objects.requireNonNull(loadedSchematicHashes, "loadedSchematicHashes");
    if (displayedViewIds.size() > MAX_ARTIFACTS) {
      throw new IOException("managed preview limit reached");
    }
    cleanup(displayedViewIds, loadedSchematicHashes);
  }

  synchronized Artifact publish(BuildPreviewView preview) throws IOException {
    stage(preview);
    return commit(preview, Set.of(preview.previewId()), Set.of());
  }

  synchronized Optional<Artifact> verified(UUID previewId) throws IOException {
    Artifact expected = artifacts.get(Objects.requireNonNull(previewId, "previewId"));
    if (expected == null) {
      return Optional.empty();
    }
    BasicFileAttributes before = attributes(expected.path());
    if (!before.isRegularFile()
        || before.size() != expected.byteLength()
        || !before.lastModifiedTime().equals(expected.lastModifiedTime())
        || !fileKey(before).equals(expected.fileKey())
        || !expected.path().toRealPath().startsWith(requireRoot())) {
      throw new IOException("managed preview identity changed");
    }
    byte[] bytes = readBounded(expected.path());
    BasicFileAttributes after = attributes(expected.path());
    if (bytes.length != expected.byteLength()
        || !sameIdentity(before, after)
        || !hash(bytes).equals(expected.schematicSha256())) {
      throw new IOException("managed preview changed during verification");
    }
    return Optional.of(expected);
  }

  synchronized void remove(UUID previewId) {
    Objects.requireNonNull(previewId, "previewId");
    deleteOwned(artifacts.remove(previewId));
    var stagedIterator = stagedArtifacts.entrySet().iterator();
    while (stagedIterator.hasNext()) {
      var entry = stagedIterator.next();
      if (entry.getKey().previewId().equals(previewId)) {
        stagedIterator.remove();
        deleteOwned(entry.getValue());
      }
    }
    for (Artifact retired : retiredArtifacts.getOrDefault(previewId, List.of())) {
      deleteOwned(retired);
    }
    retiredArtifacts.remove(previewId);
  }

  synchronized void releaseRetired(UUID previewId) {
    Objects.requireNonNull(previewId, "previewId");
    for (Artifact retired : retiredArtifacts.getOrDefault(previewId, List.of())) {
      deleteOwned(retired);
    }
    retiredArtifacts.remove(previewId);
  }

  synchronized void clear() {
    var previewIds = new HashSet<>(artifacts.keySet());
    for (StageKey stageKey : stagedArtifacts.keySet()) {
      previewIds.add(stageKey.previewId());
    }
    previewIds.addAll(retiredArtifacts.keySet());
    for (UUID previewId : previewIds) {
      remove(previewId);
    }
  }

  synchronized int size() {
    return artifacts.size();
  }

  synchronized int stagedSize() {
    return stagedArtifacts.size();
  }

  private void cleanup(Set<UUID> displayedViewIds, Set<String> loadedSchematicHashes) {
    var iterator = artifacts.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (!displayedViewIds.contains(entry.getKey())) {
        iterator.remove();
        retireOrDelete(entry.getValue(), loadedSchematicHashes);
      }
    }
    cleanupRetired(loadedSchematicHashes);
  }

  private void retireOrDelete(Artifact artifact, Set<String> loadedSchematicHashes) {
    if (loadedSchematicHashes.contains(artifact.schematicSha256())) {
      retiredArtifacts
          .computeIfAbsent(artifact.previewId(), ignored -> new ArrayList<>())
          .add(artifact);
    } else {
      deleteOwned(artifact);
    }
  }

  private void cleanupRetired(Set<String> loadedSchematicHashes) {
    var iterator = retiredArtifacts.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      entry
          .getValue()
          .removeIf(
              artifact -> {
                if (loadedSchematicHashes.contains(artifact.schematicSha256())) {
                  return false;
                }
                deleteOwned(artifact);
                return true;
              });
      if (entry.getValue().isEmpty()) {
        iterator.remove();
      }
    }
  }

  private void discardStagedAtOrBelow(UUID previewId, int revision) {
    var iterator = stagedArtifacts.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (entry.getKey().previewId().equals(previewId) && entry.getKey().revision() <= revision) {
        iterator.remove();
        deleteOwned(entry.getValue());
      }
    }
  }

  private static void deleteOwned(Artifact artifact) {
    if (artifact == null) {
      return;
    }
    try {
      Files.deleteIfExists(artifact.path());
    } catch (IOException ignored) {
      // The artifact is unregistered first and cannot be loaded again by this connection.
    }
  }

  private Path requireRoot() throws IOException {
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("managed preview root is unavailable");
    }
    return root.toRealPath();
  }

  private byte[] readBounded(Path path) throws IOException {
    var output = new ByteArrayOutputStream(Math.toIntExact(Math.min(maxBytes, 8192)));
    try (var input =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if ((long) output.size() + count > maxBytes) {
          throw new IOException("managed preview exceeds its byte limit");
        }
        output.write(buffer, 0, count);
      }
    }
    return output.toByteArray();
  }

  private static void writeNewFile(Path path, byte[] bytes) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
  }

  private static void syncDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException exception) {
      throw new IOException("managed root cannot be synchronized", exception);
    }
  }

  private static BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
    return first.size() == second.size()
        && first.lastModifiedTime().equals(second.lastModifiedTime())
        && fileKey(first).equals(fileKey(second));
  }

  private static Optional<String> fileKey(BasicFileAttributes attributes) {
    return Optional.ofNullable(attributes.fileKey()).map(Object::toString);
  }

  private static String hash(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  record Artifact(
      BuildPreviewView source,
      UUID previewId,
      int revision,
      String baseRegionHash,
      String changeSetHash,
      String previewContentHash,
      Position origin,
      Path path,
      long byteLength,
      java.nio.file.attribute.FileTime lastModifiedTime,
      Optional<String> fileKey,
      String schematicSha256) {
    Artifact {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(previewId, "previewId");
      Objects.requireNonNull(baseRegionHash, "baseRegionHash");
      Objects.requireNonNull(changeSetHash, "changeSetHash");
      Objects.requireNonNull(previewContentHash, "previewContentHash");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
      Objects.requireNonNull(fileKey, "fileKey");
      Objects.requireNonNull(schematicSha256, "schematicSha256");
    }
  }

  private record StageKey(UUID previewId, int revision) {
    private static StageKey from(BuildPreviewView preview) {
      return new StageKey(preview.previewId(), preview.revision());
    }
  }
}
