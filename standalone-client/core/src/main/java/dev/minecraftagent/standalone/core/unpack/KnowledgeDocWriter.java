package dev.minecraftagent.standalone.core.unpack;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes extracted knowledge documents into a client-owned directory using pure NIO. The write
 * is incremental: a content-hash marker file ({@value #MARKER_FILE}) short-circuits unchanged doc
 * sets, every file is written to a temporary sibling and moved into place atomically, and only
 * stale files carrying one of the two managed prefixes ({@code agma-modbook-}, {@code
 * agma-modadv-}) are deleted — anything else in the directory is never touched. The directory and
 * files get owner-only permissions where the file store supports POSIX attributes (best-effort
 * elsewhere). Failures surface as {@link IOException} for the caller to log and continue.
 */
public final class KnowledgeDocWriter {
  public static final String MARKER_FILE = "agma-knowledge.sha256";
  public static final int MAXIMUM_FILES = 240;
  private static final List<String> MANAGED_PREFIXES = List.of("agma-modbook-", "agma-modadv-");
  private static final int MAXIMUM_LISTED_ENTRIES = 4096;

  public record WriteReport(boolean changed, int written, int deletedStale) {}

  public WriteReport write(Path directory, List<KnowledgeDocument> documents) throws IOException {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(documents, "documents");
    var target = directory.toAbsolutePath().normalize();
    var selected = select(documents);
    var hash = hash(selected);
    var marker = target.resolve(MARKER_FILE);
    if (!Files.exists(target, NOFOLLOW_LINKS) && selected.isEmpty()) {
      return new WriteReport(false, 0, 0);
    }
    if (Files.isRegularFile(marker, NOFOLLOW_LINKS) && hash.equals(readMarker(marker))) {
      return new WriteReport(false, 0, 0);
    }
    prepareDirectory(target);

    var keep = new java.util.HashSet<String>();
    var written = 0;
    for (var document : selected) {
      atomicWrite(
          target, document.fileName(), document.markdown().getBytes(StandardCharsets.UTF_8));
      keep.add(document.fileName());
      written++;
    }
    var deleted = deleteStale(target, keep);
    atomicWrite(target, MARKER_FILE, (hash + "\n").getBytes(StandardCharsets.UTF_8));
    return new WriteReport(true, written, deleted);
  }

  /** Deterministic doc set: sorted by file name, first occurrence wins, bounded to the file cap. */
  private static List<KnowledgeDocument> select(List<KnowledgeDocument> documents) {
    var byName = new LinkedHashMap<String, KnowledgeDocument>();
    documents.stream()
        .sorted(Comparator.comparing(KnowledgeDocument::fileName))
        .forEach(document -> byName.putIfAbsent(document.fileName(), document));
    var selected = new ArrayList<>(byName.values());
    if (selected.size() > MAXIMUM_FILES) {
      selected = new ArrayList<>(selected.subList(0, MAXIMUM_FILES));
    }
    return selected;
  }

  private static String hash(List<KnowledgeDocument> documents) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var document : documents) {
        digest.update(document.fileName().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(document.markdown().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM does not provide SHA-256", error);
    }
  }

  private static String readMarker(Path marker) throws IOException {
    if (Files.size(marker) > 4096) {
      return "";
    }
    return Files.readString(marker, StandardCharsets.UTF_8).strip();
  }

  private static void prepareDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("knowledge directory is a symbolic link");
    }
    Files.createDirectories(directory);
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
      throw new IOException("knowledge directory is unavailable");
    }
    secure(directory, true);
  }

  private static void atomicWrite(Path directory, String fileName, byte[] bytes)
      throws IOException {
    var target = directory.resolve(fileName);
    if (Files.isSymbolicLink(target)
        || (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS))) {
      throw new IOException("knowledge document target is unsafe: " + fileName);
    }
    var temporary = directory.resolve("." + fileName + "." + UUID.randomUUID() + ".tmp");
    try {
      Files.write(temporary, bytes);
      secure(temporary, false);
      try {
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException fallback) {
        Files.move(temporary, target, REPLACE_EXISTING);
      }
      secure(target, false);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Deletes only stale files with a managed prefix; every other entry is left untouched. */
  private static int deleteStale(Path directory, java.util.Set<String> keep) throws IOException {
    var deleted = 0;
    try (var stream = Files.list(directory)) {
      var stale =
          stream
              .limit(MAXIMUM_LISTED_ENTRIES)
              .filter(path -> managed(path.getFileName().toString()))
              .filter(path -> !keep.contains(path.getFileName().toString()))
              .filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
              .filter(path -> !Files.isSymbolicLink(path))
              .toList();
      for (var path : stale) {
        Files.deleteIfExists(path);
        deleted++;
      }
    }
    return deleted;
  }

  private static boolean managed(String fileName) {
    if (!fileName.endsWith(".md")) {
      return false;
    }
    for (var prefix : MANAGED_PREFIXES) {
      if (fileName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Owner-only permissions where POSIX attributes exist; silently best-effort elsewhere. */
  private static void secure(Path path, boolean directory) {
    try {
      if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(
            path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
      }
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // Owner-only permissions are a hardening layer, never a reason to fail the write.
    }
  }
}
