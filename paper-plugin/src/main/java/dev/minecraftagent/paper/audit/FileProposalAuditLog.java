package dev.minecraftagent.paper.audit;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.proposal.ProposalAuditEvent;
import dev.minecraftagent.paper.proposal.ProposalAuditSink;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Private append-only JSONL storage for Paper-authoritative proposal audit events. */
public final class FileProposalAuditLog implements ProposalAuditSink {
  static final String AUDIT_DIRECTORY_NAME = "audit";
  static final String AUDIT_FILE_NAME = "security-audit-v1.jsonl";
  static final long MAXIMUM_BYTES = 64L * 1024L * 1024L;

  private static final int AUDIT_VERSION = 1;
  private static final int MAXIMUM_RECORD_BYTES = 4096;
  private static final Pattern TOOL_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
  private static final Pattern OUTCOME_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final Path stateDirectory;
  private final Path auditDirectory;
  private final Path auditFile;
  private final UserPrincipal owner;
  private final Object stateDirectoryKey;
  private final Object auditDirectoryKey;
  private final Object auditFileKey;

  private FileProposalAuditLog(
      Path stateDirectory,
      Path auditDirectory,
      Path auditFile,
      UserPrincipal owner,
      Object stateDirectoryKey,
      Object auditDirectoryKey,
      Object auditFileKey) {
    this.stateDirectory = stateDirectory;
    this.auditDirectory = auditDirectory;
    this.auditFile = auditFile;
    this.owner = owner;
    this.stateDirectoryKey = stateDirectoryKey;
    this.auditDirectoryKey = auditDirectoryKey;
    this.auditFileKey = auditFileKey;
  }

  public static ProposalAuditSink open(Path stateDirectory) throws StartupFailure {
    Objects.requireNonNull(stateDirectory);
    var normalizedState = stateDirectory.toAbsolutePath().normalize();
    var auditDirectory = normalizedState.resolve(AUDIT_DIRECTORY_NAME);
    var auditFile = auditDirectory.resolve(AUDIT_FILE_NAME);
    try {
      var state = verifyDirectory(normalizedState, null, null);
      createDirectoryIfMissing(auditDirectory);
      var audit = verifyDirectory(auditDirectory, state.owner(), null);
      createFileIfMissing(auditFile);
      var file = verifyFile(auditFile, state.owner(), null);
      if (file.size() > MAXIMUM_BYTES) {
        throw unavailable();
      }
      verifyJsonLinesTail(auditFile, file, state.owner());
      return new FileProposalAuditLog(
          normalizedState,
          auditDirectory,
          auditFile,
          state.owner(),
          state.fileKey(),
          audit.fileKey(),
          file.fileKey());
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException error) {
      throw unavailable();
    } catch (SecurityException | UnsupportedOperationException error) {
      throw unsafe();
    }
  }

  @Override
  public synchronized void append(ProposalAuditEvent event) {
    final byte[] record;
    try {
      record = serialize(event);
    } catch (RuntimeException error) {
      throw new AuditStorageException(StartupFailure.Code.AUDIT_STORAGE_UNSAFE);
    }

    try {
      verifyTopology();
      Set<OpenOption> options = Set.of(WRITE, APPEND, NOFOLLOW_LINKS);
      try (var channel = FileChannel.open(auditFile, options)) {
        var opened = verifyTopology();
        var before = channel.size();
        if (before != opened.size() || before > MAXIMUM_BYTES - record.length) {
          throw new AuditStorageException(StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE);
        }
        var expectedSize = before + record.length;
        var buffer = ByteBuffer.wrap(record);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
        var after = verifyTopology();
        if (channel.size() != expectedSize || after.size() != expectedSize) {
          throw new AuditStorageException(StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE);
        }
      }
    } catch (AuditStorageException error) {
      throw error;
    } catch (StartupFailure failure) {
      throw new AuditStorageException(failure.code());
    } catch (IOException error) {
      throw new AuditStorageException(StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE);
    } catch (SecurityException | UnsupportedOperationException error) {
      throw new AuditStorageException(StartupFailure.Code.AUDIT_STORAGE_UNSAFE);
    }
  }

  private FileIdentity verifyTopology() throws IOException, StartupFailure {
    verifyDirectory(stateDirectory, owner, stateDirectoryKey);
    verifyDirectory(auditDirectory, owner, auditDirectoryKey);
    var file = verifyFile(auditFile, owner, auditFileKey);
    if (file.size() > MAXIMUM_BYTES) {
      throw unavailable();
    }
    return file;
  }

  private static byte[] serialize(ProposalAuditEvent event) {
    Objects.requireNonNull(event);
    if (event.catalogGeneration() < 0
        || event.tool().length() < 3
        || event.tool().length() > 128
        || !TOOL_ID.matcher(event.tool()).matches()
        || !OUTCOME_CODE.matcher(event.outcomeCode()).matches()) {
      throw new IllegalArgumentException("Invalid proposal audit event");
    }
    var document = new JsonObject();
    document.addProperty("auditVersion", AUDIT_VERSION);
    document.addProperty("eventType", event.type().name());
    document.addProperty("timestamp", event.timestamp().toString());
    document.addProperty("proposalId", event.proposalId().toString());
    document.addProperty("requestId", event.requestId().toString());
    document.addProperty("playerUuid", event.playerUuid().toString());
    document.addProperty("tool", event.tool());
    document.addProperty("risk", event.risk().name());
    document.addProperty("catalogGeneration", event.catalogGeneration());
    document.addProperty("outcomeCode", event.outcomeCode());
    var bytes = (document + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAXIMUM_RECORD_BYTES) {
      throw new IllegalArgumentException("Invalid proposal audit event");
    }
    return bytes;
  }

  private static void createDirectoryIfMissing(Path directory) throws IOException {
    try {
      Files.createDirectory(
          directory, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
      forceDirectory(directory.getParent());
    } catch (FileAlreadyExistsException ignored) {
      // The object that won the creation race is validated by the caller.
    }
  }

  private static void createFileIfMissing(Path file) throws IOException {
    Set<OpenOption> options = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
    try (var channel =
        FileChannel.open(
            file, options, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))) {
      channel.force(true);
      forceDirectory(file.getParent());
    } catch (FileAlreadyExistsException ignored) {
      // The object that won the creation race is validated by the caller.
    }
  }

  private static DirectoryIdentity verifyDirectory(
      Path directory, UserPrincipal expectedOwner, Object expectedFileKey)
      throws IOException, StartupFailure {
    var attributes = Files.readAttributes(directory, PosixFileAttributes.class, NOFOLLOW_LINKS);
    var fileKey = attributes.fileKey();
    if (attributes.isSymbolicLink()
        || !attributes.isDirectory()
        || !attributes.permissions().equals(PRIVATE_DIRECTORY_PERMISSIONS)
        || (expectedOwner != null && !attributes.owner().equals(expectedOwner))
        || fileKey == null
        || (expectedFileKey != null && !fileKey.equals(expectedFileKey))) {
      throw unsafe();
    }
    return new DirectoryIdentity(attributes.owner(), fileKey);
  }

  private static FileIdentity verifyFile(
      Path file, UserPrincipal expectedOwner, Object expectedFileKey)
      throws IOException, StartupFailure {
    var attributes = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
    var fileKey = attributes.fileKey();
    var links = Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()
        || !attributes.isRegularFile()
        || !attributes.permissions().equals(PRIVATE_FILE_PERMISSIONS)
        || !attributes.owner().equals(expectedOwner)
        || !(links instanceof Number number)
        || number.longValue() != 1L
        || fileKey == null
        || (expectedFileKey != null && !fileKey.equals(expectedFileKey))) {
      throw unsafe();
    }
    return new FileIdentity(fileKey, attributes.size());
  }

  private static void verifyJsonLinesTail(
      Path file, FileIdentity identity, UserPrincipal expectedOwner)
      throws IOException, StartupFailure {
    if (identity.size() == 0) {
      return;
    }
    try (var channel = FileChannel.open(file, Set.of(READ, NOFOLLOW_LINKS))) {
      if (channel.size() != identity.size()) {
        throw unsafe();
      }
      channel.position(identity.size() - 1);
      var last = ByteBuffer.allocate(1);
      if (channel.read(last) != 1 || last.array()[0] != '\n') {
        throw unavailable();
      }
    }
    var after = verifyFile(file, expectedOwner, identity.fileKey());
    if (after.size() != identity.size()) {
      throw unsafe();
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (var channel = FileChannel.open(directory, READ)) {
      channel.force(true);
    }
  }

  private static StartupFailure unavailable() {
    return new StartupFailure(
        StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE, StartupFailure.Stage.STATE);
  }

  private static StartupFailure unsafe() {
    return new StartupFailure(StartupFailure.Code.AUDIT_STORAGE_UNSAFE, StartupFailure.Stage.STATE);
  }

  private record DirectoryIdentity(UserPrincipal owner, Object fileKey) {}

  private record FileIdentity(Object fileKey, long size) {}
}
