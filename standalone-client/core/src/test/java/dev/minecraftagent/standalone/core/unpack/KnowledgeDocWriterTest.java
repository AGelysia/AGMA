package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KnowledgeDocWriterTest {
  @TempDir Path directory;

  private final KnowledgeDocWriter writer = new KnowledgeDocWriter();

  @Test
  void writesDocumentsAndMarkerThenSkipsUnchanged() throws IOException {
    var target = directory.resolve("knowledge").resolve("local-docs");
    var documents = List.of(document("agma-modbook-testmod-guide.md", "Guide", "# Guide\n"));
    var first = writer.write(target, documents);
    assertTrue(first.changed());
    assertEquals(1, first.written());
    assertEquals(0, first.deletedStale());
    assertEquals("# Guide\n", Files.readString(target.resolve("agma-modbook-testmod-guide.md")));
    assertTrue(Files.isRegularFile(target.resolve(KnowledgeDocWriter.MARKER_FILE)));

    var second = writer.write(target, documents);
    assertFalse(second.changed());
    assertEquals(0, second.written());

    var changed =
        writer.write(
            target, List.of(document("agma-modbook-testmod-guide.md", "Guide", "# Guide v2\n")));
    assertTrue(changed.changed());
    assertEquals("# Guide v2\n", Files.readString(target.resolve("agma-modbook-testmod-guide.md")));
  }

  @Test
  void deletesOnlyStaleFilesWithManagedPrefixes() throws IOException {
    var target = directory.resolve("local-docs");
    Files.createDirectories(target);
    Files.writeString(target.resolve("agma-modbook-old-one.md"), "stale");
    Files.writeString(target.resolve("agma-modadv-old.md"), "stale");
    Files.writeString(target.resolve("notes.txt"), "keep me");
    Files.writeString(target.resolve("agma-other.md"), "keep me too");
    var report =
        writer.write(target, List.of(document("agma-modadv-testmod.md", "Adv", "# Adv\n")));
    assertTrue(report.changed());
    assertEquals(2, report.deletedStale());
    assertFalse(Files.exists(target.resolve("agma-modbook-old-one.md")));
    assertFalse(Files.exists(target.resolve("agma-modadv-old.md")));
    assertEquals("keep me", Files.readString(target.resolve("notes.txt")));
    assertEquals("keep me too", Files.readString(target.resolve("agma-other.md")));
  }

  @Test
  void emptyDocSetDoesNotCreateTheDirectory() throws IOException {
    var target = directory.resolve("nowhere").resolve("local-docs");
    var report = writer.write(target, List.of());
    assertFalse(report.changed());
    assertFalse(Files.exists(target));
  }

  @Test
  void boundsTheNumberOfFiles() throws IOException {
    var target = directory.resolve("local-docs");
    var documents = new ArrayList<KnowledgeDocument>();
    for (var index = 0; index < KnowledgeDocWriter.MAXIMUM_FILES + 5; index++) {
      documents.add(
          document(
              "agma-modbook-mod-" + String.format("%03d", index) + ".md", "T" + index, "# T\n"));
    }
    var report = writer.write(target, documents);
    assertTrue(report.changed());
    assertEquals(KnowledgeDocWriter.MAXIMUM_FILES, report.written());
    try (var stream = Files.list(target)) {
      var markdownFiles =
          stream.filter(path -> path.getFileName().toString().endsWith(".md")).count();
      assertEquals(KnowledgeDocWriter.MAXIMUM_FILES, markdownFiles);
    }
  }

  @Test
  void appliesOwnerOnlyPermissionsWherePosixExists() throws IOException {
    var target = directory.resolve("local-docs");
    var report =
        writer.write(target, List.of(document("agma-modadv-testmod.md", "Adv", "# Adv\n")));
    assertTrue(report.changed());
    if (!target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(target));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(target.resolve("agma-modadv-testmod.md")));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(target.resolve(KnowledgeDocWriter.MARKER_FILE)));
  }

  private static KnowledgeDocument document(String fileName, String title, String markdown) {
    return new KnowledgeDocument(fileName, title, markdown);
  }
}
