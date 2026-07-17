package dev.minecraftagent.paper.audit;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.minecraftagent.paper.proposal.ProposalAuditEvent;
import dev.minecraftagent.paper.proposal.RiskLevel;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileProposalAuditLogTest {
  private static final Set<String> AUDIT_FIELDS =
      Set.of(
          "auditVersion",
          "eventType",
          "timestamp",
          "proposalId",
          "requestId",
          "playerUuid",
          "tool",
          "risk",
          "catalogGeneration",
          "outcomeCode");
  private static final Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");

  @TempDir Path temporaryDirectory;

  @Test
  void createsPrivateOwnerBoundAuditDirectoryAndFile() throws Exception {
    var state = privateDirectory("state");

    FileProposalAuditLog.open(state);

    var audit = state.resolve(FileProposalAuditLog.AUDIT_DIRECTORY_NAME);
    var file = audit.resolve(FileProposalAuditLog.AUDIT_FILE_NAME);
    var stateAttributes = attributes(state);
    var auditAttributes = attributes(audit);
    var fileAttributes = attributes(file);
    assertTrue(auditAttributes.isDirectory());
    assertEquals(PRIVATE_DIRECTORY, auditAttributes.permissions());
    assertEquals(stateAttributes.owner(), auditAttributes.owner());
    assertTrue(fileAttributes.isRegularFile());
    assertEquals(PRIVATE_FILE, fileAttributes.permissions());
    assertEquals(stateAttributes.owner(), fileAttributes.owner());
    assertEquals(1L, ((Number) Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS)).longValue());
  }

  @Test
  void appendsClosedUtf8JsonLinesWithOnlyStableMetadata() throws Exception {
    var state = privateDirectory("state-json");
    var sink = FileProposalAuditLog.open(state);
    var first = event(ProposalAuditEvent.Type.CREATED, "PROPOSAL_CREATED");
    var second = event(ProposalAuditEvent.Type.CLAIM_AUTHORIZED, "CONFIRMATION_AUTHORIZED");

    sink.append(first);
    sink.append(second);

    var file = state.resolve("audit/security-audit-v1.jsonl");
    var source = Files.readString(file);
    assertTrue(source.endsWith("\n"));
    var lines = Files.readAllLines(file);
    assertEquals(2, lines.size());
    var created = JsonParser.parseString(lines.get(0)).getAsJsonObject();
    assertEquals(AUDIT_FIELDS, created.keySet());
    assertEquals(1, created.get("auditVersion").getAsInt());
    assertEquals("CREATED", created.get("eventType").getAsString());
    assertEquals(first.timestamp().toString(), created.get("timestamp").getAsString());
    assertEquals(first.proposalId().toString(), created.get("proposalId").getAsString());
    assertEquals(first.requestId().toString(), created.get("requestId").getAsString());
    assertEquals(first.playerUuid().toString(), created.get("playerUuid").getAsString());
    assertEquals("build.change_set.apply", created.get("tool").getAsString());
    assertEquals("WRITE_WORLD", created.get("risk").getAsString());
    assertEquals(7, created.get("catalogGeneration").getAsLong());
    assertEquals("PROPOSAL_CREATED", created.get("outcomeCode").getAsString());
    assertEquals(
        "CLAIM_AUTHORIZED",
        JsonParser.parseString(lines.get(1)).getAsJsonObject().get("eventType").getAsString());
  }

  @Test
  void cannotSerializeArgumentsSummarySecretsOrUntrustedToolText() throws Exception {
    var state = privateDirectory("state-redaction");
    var sink = FileProposalAuditLog.open(state);
    var rawSecret = "raw-secret=do-not-log";
    var rawSummary = "summary containing private player text";
    var rawArguments = "{\"token\":\"private-argument\"}";

    sink.append(event(ProposalAuditEvent.Type.CREATED, "PROPOSAL_CREATED"));
    var unsafe =
        new ProposalAuditEvent(
            ProposalAuditEvent.Type.REJECTED,
            Instant.parse("2026-07-13T05:00:01Z"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "build." + rawSecret,
            RiskLevel.WRITE_WORLD,
            7,
            "TOOL_REJECTED");
    var failure = assertThrows(AuditStorageException.class, () -> sink.append(unsafe));

    assertEquals(StartupFailure.Code.AUDIT_STORAGE_UNSAFE, failure.code());
    var stored = Files.readString(state.resolve("audit/security-audit-v1.jsonl"));
    assertFalse(stored.contains(rawSecret));
    assertFalse(stored.contains(rawSummary));
    assertFalse(stored.contains(rawArguments));
    assertFalse(stored.contains("private-argument"));
  }

  @Test
  void rejectsSymlinkedAuditDirectoryAndFile() throws Exception {
    var directoryState = privateDirectory("state-linked-directory");
    var targetDirectory = privateDirectory("audit-target");
    Files.createSymbolicLink(directoryState.resolve("audit"), targetDirectory);
    assertOpenFailure(directoryState, StartupFailure.Code.AUDIT_STORAGE_UNSAFE);

    var fileState = privateDirectory("state-linked-file");
    var audit = privateDirectory(fileState.resolve("audit"));
    var targetFile = privateFile(temporaryDirectory.resolve("audit-target.jsonl"));
    Files.createSymbolicLink(audit.resolve("security-audit-v1.jsonl"), targetFile);
    assertOpenFailure(fileState, StartupFailure.Code.AUDIT_STORAGE_UNSAFE);
  }

  @Test
  void rejectsHardLinkedAndOverbroadAuditObjects() throws Exception {
    var linkedState = privateDirectory("state-hard-link");
    var linkedAudit = privateDirectory(linkedState.resolve("audit"));
    var target = privateFile(linkedState.resolve("audit-target.jsonl"));
    Files.createLink(linkedAudit.resolve("security-audit-v1.jsonl"), target);
    assertOpenFailure(linkedState, StartupFailure.Code.AUDIT_STORAGE_UNSAFE);

    var broadDirectoryState = privateDirectory("state-broad-directory");
    var broadDirectory = privateDirectory(broadDirectoryState.resolve("audit"));
    Files.setPosixFilePermissions(broadDirectory, PosixFilePermissions.fromString("rwxr-x---"));
    assertOpenFailure(broadDirectoryState, StartupFailure.Code.AUDIT_STORAGE_UNSAFE);

    var broadFileState = privateDirectory("state-broad-file");
    var broadFileAudit = privateDirectory(broadFileState.resolve("audit"));
    var broadFile = privateFile(broadFileAudit.resolve("security-audit-v1.jsonl"));
    Files.setPosixFilePermissions(broadFile, PosixFilePermissions.fromString("rw-r-----"));
    assertOpenFailure(broadFileState, StartupFailure.Code.AUDIT_STORAGE_UNSAFE);
  }

  @Test
  void rejectsOversizedExistingFileAndFailsClosedAtTheAppendLimit() throws Exception {
    var oversizedState = privateDirectory("state-oversized");
    var oversizedAudit = privateDirectory(oversizedState.resolve("audit"));
    var oversized = privateFile(oversizedAudit.resolve("security-audit-v1.jsonl"));
    setSparseSize(oversized, FileProposalAuditLog.MAXIMUM_BYTES + 1, (byte) '\n');
    assertOpenFailure(oversizedState, StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE);

    var fullState = privateDirectory("state-full");
    var fullAudit = privateDirectory(fullState.resolve("audit"));
    var full = privateFile(fullAudit.resolve("security-audit-v1.jsonl"));
    setSparseSize(full, FileProposalAuditLog.MAXIMUM_BYTES, (byte) '\n');
    var sink = FileProposalAuditLog.open(fullState);

    var failure =
        assertThrows(
            AuditStorageException.class,
            () -> sink.append(event(ProposalAuditEvent.Type.CREATED, "PROPOSAL_CREATED")));
    assertEquals(StartupFailure.Code.AUDIT_STORAGE_UNAVAILABLE, failure.code());
    assertEquals(FileProposalAuditLog.MAXIMUM_BYTES, Files.size(full));
  }

  @Test
  void appendRejectsAFileReplacementEvenWhenNewFileLooksPrivate() throws Exception {
    var state = privateDirectory("state-replaced");
    var sink = FileProposalAuditLog.open(state);
    var file = state.resolve("audit/security-audit-v1.jsonl");
    Files.move(file, file.resolveSibling("replaced-audit.jsonl"));
    privateFile(file);

    var failure =
        assertThrows(
            AuditStorageException.class,
            () -> sink.append(event(ProposalAuditEvent.Type.CREATED, "PROPOSAL_CREATED")));

    assertEquals(StartupFailure.Code.AUDIT_STORAGE_UNSAFE, failure.code());
    assertEquals(0L, Files.size(file));
  }

  private Path privateDirectory(String name) throws Exception {
    return privateDirectory(temporaryDirectory.resolve(name));
  }

  private static Path privateDirectory(Path path) throws Exception {
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
    return path;
  }

  private static Path privateFile(Path path) throws Exception {
    Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
    return path;
  }

  private static PosixFileAttributes attributes(Path path) throws Exception {
    return Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
  }

  private static ProposalAuditEvent event(ProposalAuditEvent.Type type, String outcomeCode) {
    return new ProposalAuditEvent(
        type,
        Instant.parse("2026-07-13T05:00:00Z"),
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "build.change_set.apply",
        RiskLevel.WRITE_WORLD,
        7,
        outcomeCode);
  }

  private static void assertOpenFailure(Path state, StartupFailure.Code code) {
    var failure = assertThrows(StartupFailure.class, () -> FileProposalAuditLog.open(state));
    assertEquals(code, failure.code());
  }

  private static void setSparseSize(Path file, long size, byte lastByte) throws Exception {
    try (var channel = FileChannel.open(file, WRITE)) {
      channel.position(size - 1);
      channel.write(ByteBuffer.wrap(new byte[] {lastByte}));
      channel.force(true);
    }
  }
}
