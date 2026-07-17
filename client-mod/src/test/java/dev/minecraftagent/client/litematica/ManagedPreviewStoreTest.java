package dev.minecraftagent.client.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.client.view.BuildPreviewView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedPreviewStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void stagesCommitsVerifiesUpdatesAndRemovesOnlyRegisteredFiles() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var store = new ManagedPreviewStore(root, 1024 * 1024, new NativeLitematicaWriter(4321));
    BuildPreviewView first = NativeLitematicaWriterTest.preview();

    ManagedPreviewStore.Artifact artifact = store.stage(first);
    assertEquals(first.previewId(), artifact.previewId());
    assertEquals(first.baseRegionHash(), artifact.baseRegionHash());
    assertEquals(first.changeSetHash(), artifact.changeSetHash());
    assertEquals(first.contentHash(), artifact.previewContentHash());
    assertEquals(first.origin(), artifact.origin());
    assertTrue(Files.isRegularFile(artifact.path()));
    assertTrue(store.verified(first.previewId()).isEmpty());
    assertEquals(1, store.stagedSize());
    try (var files = Files.list(root)) {
      assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
    store.commit(first, Set.of(first.previewId()), Set.of());
    assertEquals(artifact, store.verified(first.previewId()).orElseThrow());

    BuildPreviewView second = withRevision(first, 2, "3".repeat(64));
    ManagedPreviewStore.Artifact staged = store.stage(second);
    assertEquals(1, store.verified(first.previewId()).orElseThrow().revision());
    store.discard(second);
    assertFalse(Files.exists(staged.path()));
    assertEquals(1, store.verified(first.previewId()).orElseThrow().revision());

    staged = store.stage(second);
    assertThrows(IOException.class, () -> store.stage(withRevision(first, 2, "4".repeat(64))));
    store.commit(second, Set.of(first.previewId()), Set.of());
    assertEquals(2, store.publish(first).revision());
    assertFalse(Files.exists(artifact.path()));

    Files.write(staged.path(), new byte[] {1, 2, 3});
    assertThrows(IOException.class, () -> store.verified(first.previewId()));
    store.remove(first.previewId());
    assertFalse(Files.exists(staged.path()));
    assertEquals(0, store.size());
  }

  @Test
  void leavesUnregisteredFilesAloneAndRejectsSymlinkedOrOversizedTargets() throws Exception {
    BuildPreviewView preview = NativeLitematicaWriterTest.preview();
    Path root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    Path target = Files.write(root.resolve(preview.previewId() + ".litematica"), new byte[] {1});
    var store = new ManagedPreviewStore(root, 1024 * 1024, new NativeLitematicaWriter(4321));
    store.stage(preview);
    store.discard(preview);
    assertEquals(1, Files.size(target));

    Path smallRoot = Files.createDirectory(temporaryDirectory.resolve("small"));
    var tooSmall = new ManagedPreviewStore(smallRoot, 1, new NativeLitematicaWriter(4321));
    assertThrows(IOException.class, () -> tooSmall.publish(preview));
    try (var files = Files.list(smallRoot)) {
      assertEquals(0, files.count());
    }

    Path realRoot = Files.createDirectory(temporaryDirectory.resolve("real"));
    Path link = temporaryDirectory.resolve("link");
    try {
      Files.createSymbolicLink(link, realRoot);
      assertThrows(
          IOException.class,
          () -> new ManagedPreviewStore(link, 1024, new NativeLitematicaWriter(4321)));
    } catch (UnsupportedOperationException exception) {
      assertFalse(Files.exists(link));
    }
  }

  @Test
  void boundsStagingAndSequentialTransientReplacementNeverExhaustsActiveCapacity()
      throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    Path unrelated = Files.writeString(root.resolve("keep.txt"), "local");
    var store = new ManagedPreviewStore(root, 1024 * 1024, new NativeLitematicaWriter(4321));
    BuildPreviewView source = NativeLitematicaWriterTest.preview();
    for (int index = 1; index <= 8; index++) {
      store.stage(
          withId(source, UUID.fromString("40000000-0000-4000-8000-%012d".formatted(index))));
    }
    assertEquals(0, store.size());
    assertEquals(8, store.stagedSize());
    assertThrows(
        IOException.class,
        () -> store.stage(withId(source, UUID.fromString("40000000-0000-4000-8000-000000000009"))));
    store.discard(withId(source, UUID.fromString("40000000-0000-4000-8000-000000000001")));
    assertEquals(7, store.stagedSize());
    store.clear();

    Path previous = null;
    for (int index = 1; index <= 16; index++) {
      BuildPreviewView next =
          withId(source, UUID.fromString("50000000-0000-4000-8000-%012d".formatted(index)));
      store.stage(next);
      store.commit(next, Set.of(next.previewId()), Set.of());
      Path current = store.verified(next.previewId()).orElseThrow().path();
      if (previous != null) {
        assertFalse(Files.exists(previous));
      }
      previous = current;
      assertEquals(1, store.size());
      assertEquals(0, store.stagedSize());
    }

    store.clear();
    assertEquals(0, store.size());
    assertTrue(Files.isRegularFile(unrelated));
  }

  @Test
  void rollbackDoesNotDeleteTheActiveArtifact() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var store = new ManagedPreviewStore(root, 1024 * 1024, new NativeLitematicaWriter(4321));
    BuildPreviewView active = NativeLitematicaWriterTest.preview();
    store.publish(active);
    Path activePath = store.verified(active.previewId()).orElseThrow().path();
    BuildPreviewView rejected =
        withId(active, UUID.fromString("60000000-0000-4000-8000-000000000001"));

    Path stagedPath = store.stage(rejected).path();
    store.discard(rejected);

    assertTrue(Files.isRegularFile(activePath));
    assertFalse(Files.exists(stagedPath));
    assertEquals(active, store.verified(active.previewId()).orElseThrow().source());
  }

  private static BuildPreviewView withRevision(
      BuildPreviewView value, int revision, String contentHash) {
    return new BuildPreviewView(
        value.schemaVersion(),
        value.previewId(),
        value.projectId(),
        revision,
        value.operation(),
        value.dimension(),
        value.bounds(),
        value.origin(),
        value.transform(),
        value.baseRegionHash(),
        value.changeSetHash(),
        contentHash,
        value.paletteHash(),
        value.difference(),
        value.palette(),
        value.blocks());
  }

  private static BuildPreviewView withId(BuildPreviewView value, UUID previewId) {
    return new BuildPreviewView(
        value.schemaVersion(),
        previewId,
        value.projectId(),
        value.revision(),
        value.operation(),
        value.dimension(),
        value.bounds(),
        value.origin(),
        value.transform(),
        value.baseRegionHash(),
        value.changeSetHash(),
        value.contentHash(),
        value.paletteHash(),
        value.difference(),
        value.palette(),
        value.blocks());
  }
}
