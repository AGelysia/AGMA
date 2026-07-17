package dev.minecraftagent.standalone.common;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Owner-only directory and atomic-file primitives shared by client state and process logs. */
final class PrivateFilePermissions {
  private PrivateFilePermissions() {}

  static Path prepareDirectory(Path requested) throws IOException {
    var directory = absolute(requested);
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("Private directory is a symbolic link");
    }
    if (!Files.exists(directory, NOFOLLOW_LINKS)) {
      var parent = directory.getParent();
      if (parent == null || !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
        throw new IOException("Private directory parent is unavailable");
      }
      Files.createDirectory(directory);
    }
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
      throw new IOException("Private directory is unavailable");
    }
    secure(directory, true);
    return directory;
  }

  static Path prepareChildDirectory(Path root, String name) throws IOException {
    var candidate = contained(root, root.resolve(name));
    return prepareDirectory(candidate);
  }

  static void atomicWrite(Path root, Path requested, byte[] bytes) throws IOException {
    var target = contained(root, requested);
    var parent = target.getParent();
    if (parent == null
        || !Files.isDirectory(parent, NOFOLLOW_LINKS)
        || Files.isSymbolicLink(parent)) {
      throw new IOException("Private file parent is unsafe");
    }
    if (Files.isSymbolicLink(target)
        || (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS))) {
      throw new IOException("Private file is unsafe");
    }
    verifySingleLink(target);
    var temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      try (var output =
          FileChannel.open(
              temporary,
              Set.of(
                  java.nio.file.StandardOpenOption.CREATE_NEW,
                  java.nio.file.StandardOpenOption.WRITE,
                  NOFOLLOW_LINKS))) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      secure(temporary, false);
      try {
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("Atomic private-file replacement is unsupported", exception);
      }
      secure(target, false);
      if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
        throw new IOException("Private file publication failed");
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void verifyFile(Path root, Path requested) throws IOException {
    var target = contained(root, requested);
    if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
      throw new IOException("Private file is unavailable");
    }
    secure(target, false);
  }

  static void deleteFileIfPresent(Path root, Path requested) throws IOException {
    var target = contained(root, requested);
    if (!Files.exists(target, NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
      throw new IOException("Private file is unsafe");
    }
    verifySingleLink(target);
    Files.delete(target);
  }

  static void deleteTreeIfPresent(Path root, String childName) throws IOException {
    if (childName == null || !childName.matches("^[a-z][a-z0-9-]{0,31}$")) {
      throw new IOException("Private tree name is invalid");
    }
    var target = contained(root, root.resolve(childName));
    if (!Files.exists(target, NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(target) || !Files.isDirectory(target, NOFOLLOW_LINKS)) {
      throw new IOException("Private tree is unsafe");
    }
    try (var paths = Files.walk(target)) {
      var entries = paths.toList();
      for (var path : entries) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("Private tree contains a symbolic link");
        }
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
          secure(path, true);
        } else if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
          verifySingleLink(path);
        } else {
          throw new IOException("Private tree contains an unsupported entry");
        }
      }
      for (var path : entries.stream().sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  static Path contained(Path root, Path requested) throws IOException {
    var canonicalRoot = absolute(root);
    var candidate = absolute(requested);
    if (candidate.equals(canonicalRoot) || !candidate.startsWith(canonicalRoot)) {
      throw new IOException("Private path escaped its root");
    }
    var current = canonicalRoot;
    for (var component : canonicalRoot.relativize(candidate)) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current)) {
        throw new IOException("Private path contains a symbolic link");
      }
    }
    return candidate;
  }

  private static void secure(Path path, boolean directory) throws IOException {
    FileStore store = Files.getFileStore(path);
    if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
      return;
    }
    var view = Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("Owner-only file permissions are unsupported");
    }
    var owner = view.getOwner();
    var entry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setAcl(java.util.List.of(entry));
  }

  private static void verifySingleLink(Path path) throws IOException {
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
      return;
    }
    try {
      var links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
      if (!(links instanceof Number number) || number.longValue() != 1L) {
        throw new IOException("Private file has unsafe links");
      }
    } catch (UnsupportedOperationException ignored) {
      // ACL-backed platforms rely on owner-only directories and no-follow operations.
    }
  }

  private static Path absolute(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("Path is required");
    }
    var normalized = path.toAbsolutePath().normalize();
    if (!normalized.equals(path)) {
      throw new IllegalArgumentException("Path must be absolute and normalized");
    }
    return normalized;
  }
}
