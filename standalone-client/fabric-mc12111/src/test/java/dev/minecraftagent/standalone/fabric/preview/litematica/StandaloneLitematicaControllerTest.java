package dev.minecraftagent.standalone.fabric.preview.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandaloneLitematicaControllerTest {
  private static final UUID VIEW_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

  @TempDir Path temporaryDirectory;

  @Test
  void onlyLoadsClientGeneratedArtifactsAtTheirDeclaredOrigin() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();

    assertTrue(controller.stagePreview(preview));
    assertEquals(0, adapter.loadCalls);
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));
    var report = controller.load(controller.prepareLoad(VIEW_ID, "Preview"));

    assertEquals(LitematicaDisplayReport.State.LOADED, report.state());
    assertTrue(adapter.loaded.managedFile().startsWith(root));
    assertEquals(preview.changeSetHash(), adapter.loaded.contentSha256());
    assertEquals(preview.origin().x(), adapter.loaded.originX());
    assertEquals(preview.origin().y(), adapter.loaded.originY());
    assertEquals(preview.origin().z(), adapter.loaded.originZ());
    assertEquals(1, adapter.loadCalls);
  }

  @Test
  void refusesUnregisteredPreexistingAndTamperedFiles() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    Path expected = Files.write(root.resolve(VIEW_ID + ".litematica"), new byte[] {1});

    assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview")));
    assertEquals(0, adapter.loadCalls);
    Files.delete(expected);

    StandalonePreview preview = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(preview));
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));
    Path managed = onlySchematic(root, expected);
    Files.write(managed, new byte[] {1, 2, 3});
    assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview")));
    assertEquals(0, adapter.loadCalls);

    Files.delete(managed);
    Path outside = Files.write(temporaryDirectory.resolve("outside.litematica"), new byte[] {1});
    try {
      Files.createSymbolicLink(managed, outside);
      assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview")));
    } catch (UnsupportedOperationException exception) {
      assertFalse(Files.exists(managed));
    }
    assertEquals(0, adapter.loadCalls);
  }

  @Test
  void rehashesTheManagedFileImmediatelyBeforeCallingTheAdapter() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(preview));
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));
    var prepared = controller.prepareLoad(VIEW_ID, "Preview");
    Path managed = onlySchematic(root, null);

    Files.write(managed, new byte[] {1, 2, 3});
    assertFileUnavailable(controller.load(prepared));
    assertEquals(0, adapter.loadCalls);
  }

  @Test
  void reconciliationDeletesAnUnloadedPreviewThatIsNoLongerDisplayed() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var controller = controller(Optional.of(new CapturingAdapter()), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(preview));
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));
    Path managed = onlySchematic(root, null);

    assertTrue(controller.reconcileDisplayedPreviews(Set.of()));

    assertFalse(Files.exists(managed));
    assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview")));
  }

  @Test
  void updateReplacesLoadedPlacementAndRemoveDeletesOwnedFile() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview first = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(first));
    assertTrue(controller.commitPreview(first, Set.of(VIEW_ID)));
    assertEquals(
        LitematicaDisplayReport.State.LOADED,
        controller.load(controller.prepareLoad(VIEW_ID, "Preview")).state());

    StandalonePreview second = withRevision(first, 2, first.changeSetHash());
    assertTrue(controller.stagePreview(second));
    assertTrue(controller.commitPreview(second, Set.of(VIEW_ID)));
    assertEquals(
        LitematicaDisplayReport.State.LOADED,
        controller.load(controller.prepareLoad(VIEW_ID, "Preview")).state());
    assertEquals(2, adapter.loadCalls);
    assertEquals(1, adapter.removeCalls);
    assertEquals(second.changeSetHash(), adapter.loaded.contentSha256());

    assertEquals(LitematicaDisplayReport.State.REMOVED, controller.remove(VIEW_ID).state());
    try (var files = Files.list(root)) {
      assertEquals(0, files.count());
    }
    assertEquals(2, adapter.removeCalls);
  }

  @Test
  void closeClosesAdapterAndDeletesSessionScopedArtifacts() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(preview));
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));

    controller.close();

    assertEquals(1, adapter.closeCalls);
    try (var files = Files.list(root)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void absentAdapterIsStableUnavailableAndCarriesNoAuthority() throws IOException {
    var root = temporaryDirectory.resolve("not-created-for-unavailable-adapter");
    var controller = controller(Optional.empty(), root, 8);

    assertFalse(controller.available());
    assertFalse(controller.stagePreview(NativeLitematicaWriterTest.preview()));
    assertUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview")));
    assertUnavailable(controller.remove(VIEW_ID));
    assertUnavailable(controller.openMaterialList(VIEW_ID));
  }

  @Test
  void bridgeLoadStagesCommitsLoadsAndIgnoresRepeatedLoadsOfTheSamePreview() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();

    controller.load(preview);

    assertEquals(1, adapter.loadCalls);
    assertEquals(Optional.of(preview.previewId()), controller.loadedPreviewId());
    assertTrue(adapter.loaded.managedFile().startsWith(root));
    assertEquals(preview.origin().x(), adapter.loaded.originX());

    controller.load(preview);

    assertEquals(1, adapter.loadCalls);
    assertEquals(Optional.of(preview.previewId()), controller.loadedPreviewId());
  }

  @Test
  void bridgeLoadReplacesTheCurrentlyLoadedPreview() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview first = NativeLitematicaWriterTest.preview();
    StandalonePreview second =
        withIdAndHash(
            first, UUID.fromString("30000000-0000-4000-8000-000000000009"), "3".repeat(64));

    controller.load(first);
    controller.load(second);

    assertEquals(2, adapter.loadCalls);
    assertEquals(1, adapter.removeCalls);
    assertEquals(Optional.of(second.previewId()), controller.loadedPreviewId());
    assertEquals(second.changeSetHash(), adapter.loaded.contentSha256());
  }

  @Test
  void removeCurrentUnloadsAndDeletesTheLoadedPreview() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    StandalonePreview preview = NativeLitematicaWriterTest.preview();

    controller.load(preview);
    assertEquals(1, adapter.loadCalls);

    controller.removeCurrent();

    assertEquals(1, adapter.removeCalls);
    assertEquals(Optional.empty(), controller.loadedPreviewId());
    try (var files = Files.list(root)) {
      assertEquals(0, files.count());
    }

    controller.removeCurrent();
    assertEquals(1, adapter.removeCalls);
  }

  private static StandaloneLitematicaController controller(
      Optional<LitematicaAdapter> adapter, Path root, long maxBytes) throws IOException {
    return new StandaloneLitematicaController(
        adapter,
        root,
        maxBytes,
        new NativeLitematicaWriter(4321),
        () -> true,
        Runnable::run,
        directExecutor(),
        false);
  }

  static ExecutorService directExecutor() {
    return new AbstractExecutorService() {
      private boolean shutdown;

      @Override
      public void shutdown() {
        shutdown = true;
      }

      @Override
      public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
      }

      @Override
      public boolean isShutdown() {
        return shutdown;
      }

      @Override
      public boolean isTerminated() {
        return shutdown;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
      }

      @Override
      public void execute(Runnable command) {
        command.run();
      }
    };
  }

  private static Path onlySchematic(Path root, Path excluded) throws IOException {
    try (var files = Files.list(root)) {
      return files
          .filter(path -> !path.equals(excluded))
          .filter(path -> path.getFileName().toString().endsWith(".litematic"))
          .findFirst()
          .orElseThrow();
    }
  }

  private static StandalonePreview withRevision(
      StandalonePreview value, int revision, String changeSetHash) {
    return new StandalonePreview(
        value.previewId(),
        value.projectId(),
        revision,
        value.operation(),
        value.dimension(),
        value.bounds(),
        value.origin(),
        value.rotation(),
        value.mirror(),
        value.baseRegionHash(),
        changeSetHash,
        value.targetBlockCount(),
        value.changeCount(),
        value.difference(),
        value.cells(),
        value.palette());
  }

  private static StandalonePreview withIdAndHash(
      StandalonePreview value, UUID previewId, String changeSetHash) {
    return new StandalonePreview(
        previewId,
        value.projectId(),
        value.revision(),
        value.operation(),
        value.dimension(),
        value.bounds(),
        value.origin(),
        value.rotation(),
        value.mirror(),
        value.baseRegionHash(),
        changeSetHash,
        value.targetBlockCount(),
        value.changeCount(),
        value.difference(),
        value.cells(),
        value.palette());
  }

  private static void assertUnavailable(LitematicaDisplayReport report) {
    assertEquals(LitematicaDisplayReport.State.FAILED, report.state());
    assertEquals(
        LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE, report.failure().orElseThrow());
  }

  private static void assertFileUnavailable(LitematicaDisplayReport report) {
    assertEquals(LitematicaDisplayReport.State.FAILED, report.state());
    assertEquals(
        LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE, report.failure().orElseThrow());
  }

  private static final class CapturingAdapter implements LitematicaAdapter {
    private LitematicaPreviewRequest loaded;
    private int loadCalls;
    private int removeCalls;
    private int materialCalls;
    private int closeCalls;

    @Override
    public LitematicaSupportMatrix.Entry supportedCombination() {
      return LitematicaSupportMatrix.supported().getFirst();
    }

    @Override
    public LitematicaDisplayReport loadPreview(LitematicaPreviewRequest request) {
      loaded = request;
      loadCalls++;
      return LitematicaDisplayReport.success(
          request.previewId(), request.contentSha256(), LitematicaDisplayReport.State.LOADED);
    }

    @Override
    public LitematicaDisplayReport removePreview(UUID previewId) {
      removeCalls++;
      return LitematicaDisplayReport.success(
          previewId, null, LitematicaDisplayReport.State.REMOVED);
    }

    @Override
    public LitematicaDisplayReport openMaterialList(UUID previewId) {
      materialCalls++;
      return LitematicaDisplayReport.success(
          previewId, null, LitematicaDisplayReport.State.MATERIAL_LIST_OPEN);
    }

    @Override
    public int loadedPreviewCount() {
      return loaded == null ? 0 : 1;
    }

    @Override
    public void close() {
      closeCalls++;
    }
  }
}
