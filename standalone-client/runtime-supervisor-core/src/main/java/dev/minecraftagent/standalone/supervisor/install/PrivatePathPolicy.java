package dev.minecraftagent.standalone.supervisor.install;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class PrivatePathPolicy {
  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");

  private final Mode mode;
  private final UserPrincipal owner;

  private PrivatePathPolicy(Mode mode, UserPrincipal owner) {
    this.mode = mode;
    this.owner = owner;
  }

  static PrivatePathPolicy prepare(Path root) throws IOException, ManagedRuntimeInstallException {
    var mode = detectMode(root);
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
      try {
        createDirectoryRaw(root, mode, null);
      } catch (FileAlreadyExistsException ignored) {
        // A concurrent installer may create the shared private root before locking it.
      }
    }
    var basic = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!basic.isDirectory() || basic.isSymbolicLink()) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    var owner = Files.getOwner(root, NOFOLLOW_LINKS);
    var policy = new PrivatePathPolicy(mode, owner);
    policy.secureDirectory(root);
    policy.verifyDirectory(root);
    return policy;
  }

  Path createOrVerifyDirectory(Path path) throws IOException, ManagedRuntimeInstallException {
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
      try {
        createDirectoryRaw(path, mode, owner);
      } catch (FileAlreadyExistsException ignored) {
        // The lock root itself is created before locking and can race across processes.
      }
    }
    verifyDirectoryIdentity(path);
    secureDirectory(path);
    verifyDirectory(path);
    return path;
  }

  FileChannel openLock(Path path) throws IOException, ManagedRuntimeInstallException {
    var options = Set.<OpenOption>of(CREATE, WRITE, NOFOLLOW_LINKS);
    var channel = FileChannel.open(path, options, creationAttributes(false));
    try {
      secureFile(path, false);
      verifyFile(path, false, null);
      return channel;
    } catch (IOException | ManagedRuntimeInstallException failure) {
      channel.close();
      throw failure;
    }
  }

  FileChannel createFile(Path path, boolean executable)
      throws IOException, ManagedRuntimeInstallException {
    var channel =
        FileChannel.open(
            path,
            Set.<OpenOption>of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            creationAttributes(executable));
    try {
      secureFile(path, executable);
      verifyFile(path, executable, 0L);
      return channel;
    } catch (IOException | ManagedRuntimeInstallException failure) {
      channel.close();
      throw failure;
    }
  }

  void verifyDirectory(Path path) throws IOException, ManagedRuntimeInstallException {
    var basic = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!basic.isDirectory()
        || basic.isSymbolicLink()
        || !Files.getOwner(path, NOFOLLOW_LINKS).equals(owner)) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    if (mode == Mode.POSIX) {
      var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
      if (!attributes.permissions().equals(DIRECTORY_PERMISSIONS)) {
        throw failure("INSTALL_ROOT_UNSAFE");
      }
    } else {
      verifyAcl(path);
    }
  }

  void verifyFile(Path path, boolean executable, Long expectedSize)
      throws IOException, ManagedRuntimeInstallException {
    var basic = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!basic.isRegularFile()
        || basic.isSymbolicLink()
        || !Files.getOwner(path, NOFOLLOW_LINKS).equals(owner)
        || (expectedSize != null && basic.size() != expectedSize)) {
      throw failure("INSTALLED_STATE_INVALID");
    }
    if (mode == Mode.POSIX) {
      var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
      var expected = executable ? EXECUTABLE_PERMISSIONS : FILE_PERMISSIONS;
      var links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
      if (!attributes.permissions().equals(expected)
          || !(links instanceof Number number)
          || number.longValue() != 1L) {
        throw failure("INSTALLED_STATE_INVALID");
      }
    } else {
      verifyAcl(path);
    }
  }

  void finishFile(Path path, boolean executable, long expectedSize)
      throws IOException, ManagedRuntimeInstallException {
    secureFile(path, executable);
    verifyFile(path, executable, expectedSize);
  }

  void verifyManagedFile(Path path) throws IOException, ManagedRuntimeInstallException {
    var basic = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!basic.isRegularFile()
        || basic.isSymbolicLink()
        || !Files.getOwner(path, NOFOLLOW_LINKS).equals(owner)) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    if (mode == Mode.POSIX) {
      var attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
      var permissions = attributes.permissions();
      var links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
      if ((!permissions.equals(FILE_PERMISSIONS) && !permissions.equals(EXECUTABLE_PERMISSIONS))
          || !(links instanceof Number number)
          || number.longValue() != 1L) {
        throw failure("INSTALL_ROOT_UNSAFE");
      }
    } else {
      verifyAcl(path);
    }
  }

  private void secureDirectory(Path path) throws IOException, ManagedRuntimeInstallException {
    if (mode == Mode.POSIX) {
      var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
      if (view == null) {
        throw failure("PRIVATE_FILES_UNSUPPORTED");
      }
      view.setPermissions(DIRECTORY_PERMISSIONS);
    } else {
      setOwnerOnlyAcl(path);
    }
  }

  private void secureFile(Path path, boolean executable)
      throws IOException, ManagedRuntimeInstallException {
    if (mode == Mode.POSIX) {
      var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
      if (view == null) {
        throw failure("PRIVATE_FILES_UNSUPPORTED");
      }
      view.setPermissions(executable ? EXECUTABLE_PERMISSIONS : FILE_PERMISSIONS);
    } else {
      setOwnerOnlyAcl(path);
    }
  }

  private FileAttribute<?>[] creationAttributes(boolean executable) {
    if (mode == Mode.POSIX) {
      return new FileAttribute<?>[] {
        PosixFilePermissions.asFileAttribute(executable ? EXECUTABLE_PERMISSIONS : FILE_PERMISSIONS)
      };
    }
    return new FileAttribute<?>[0];
  }

  private void setOwnerOnlyAcl(Path path) throws IOException, ManagedRuntimeInstallException {
    var view = Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS);
    if (view == null) {
      throw failure("PRIVATE_FILES_UNSUPPORTED");
    }
    var entry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setOwner(owner);
    view.setAcl(List.of(entry));
  }

  private void verifyAcl(Path path) throws IOException, ManagedRuntimeInstallException {
    var view = Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW_LINKS);
    if (view == null || !view.getOwner().equals(owner)) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    var entries = view.getAcl();
    if (entries.size() != 1
        || entries.get(0).type() != AclEntryType.ALLOW
        || !entries.get(0).principal().equals(owner)
        || !entries.get(0).permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
  }

  private void verifyDirectoryIdentity(Path path)
      throws IOException, ManagedRuntimeInstallException {
    var basic = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!basic.isDirectory()
        || basic.isSymbolicLink()
        || !Files.getOwner(path, NOFOLLOW_LINKS).equals(owner)) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
  }

  private static void createDirectoryRaw(Path path, Mode mode, UserPrincipal owner)
      throws IOException, ManagedRuntimeInstallException {
    if (path.getParent() == null || !Files.isDirectory(path.getParent(), NOFOLLOW_LINKS)) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    if (mode == Mode.POSIX) {
      Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
      return;
    }
    Files.createDirectory(path);
    if (owner != null) {
      Files.setOwner(path, owner);
    }
  }

  private static Mode detectMode(Path root) throws IOException, ManagedRuntimeInstallException {
    var anchor = root;
    while (anchor != null && !Files.exists(anchor, NOFOLLOW_LINKS)) {
      anchor = anchor.getParent();
    }
    if (anchor == null) {
      throw failure("INSTALL_ROOT_UNSAFE");
    }
    FileStore store = Files.getFileStore(anchor);
    if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
      return Mode.POSIX;
    }
    if (store.supportsFileAttributeView(AclFileAttributeView.class)) {
      return Mode.ACL;
    }
    throw failure("PRIVATE_FILES_UNSUPPORTED");
  }

  private static ManagedRuntimeInstallException failure(String code) {
    return new ManagedRuntimeInstallException(code);
  }

  private enum Mode {
    POSIX,
    ACL
  }
}
