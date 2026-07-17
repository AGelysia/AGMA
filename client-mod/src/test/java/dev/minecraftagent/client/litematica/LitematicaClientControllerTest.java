package dev.minecraftagent.client.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.client.view.BuildPreviewView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicaClientControllerTest {
  private static final UUID VIEW_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

  @TempDir Path temporaryDirectory;

  @Test
  void onlyLoadsClientGeneratedArtifactsAtTheirDeclaredOrigin() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    BuildPreviewView preview = NativeLitematicaWriterTest.preview();

    assertTrue(controller.stagePreview(preview));
    assertEquals(0, adapter.loadCalls);
    assertTrue(controller.commitPreview(preview, Set.of(VIEW_ID)));
    var report = controller.load(controller.prepareLoad(VIEW_ID, "Preview"));

    assertEquals(LitematicaDisplayReport.State.LOADED, report.state());
    assertTrue(adapter.loaded.managedFile().startsWith(root));
    assertEquals(preview.contentHash(), adapter.loaded.contentSha256());
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

    BuildPreviewView preview = NativeLitematicaWriterTest.preview();
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
    BuildPreviewView preview = NativeLitematicaWriterTest.preview();
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
    BuildPreviewView preview = NativeLitematicaWriterTest.preview();
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
    BuildPreviewView first = NativeLitematicaWriterTest.preview();
    assertTrue(controller.stagePreview(first));
    assertTrue(controller.commitPreview(first, Set.of(VIEW_ID)));
    assertEquals(
        LitematicaDisplayReport.State.LOADED,
        controller.load(controller.prepareLoad(VIEW_ID, "Preview")).state());

    BuildPreviewView second = withRevision(first, 2, first.contentHash());
    assertTrue(controller.stagePreview(second));
    assertTrue(controller.commitPreview(second, Set.of(VIEW_ID)));
    assertEquals(
        LitematicaDisplayReport.State.LOADED,
        controller.load(controller.prepareLoad(VIEW_ID, "Preview")).state());
    assertEquals(2, adapter.loadCalls);
    assertEquals(1, adapter.removeCalls);
    assertEquals(second.contentHash(), adapter.loaded.contentSha256());

    assertEquals(LitematicaDisplayReport.State.REMOVED, controller.remove(VIEW_ID).state());
    try (var files = Files.list(root)) {
      assertEquals(0, files.count());
    }
    assertEquals(2, adapter.removeCalls);
  }

  @Test
  void disconnectClosesAdapterAndDeletesConnectionScopedArtifacts() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = controller(Optional.of(adapter), root, 1024 * 1024);
    BuildPreviewView preview = NativeLitematicaWriterTest.preview();
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

  private static LitematicaClientController controller(
      Optional<LitematicaAdapter> adapter, Path root, long maxBytes) throws IOException {
    return new LitematicaClientController(
        adapter, root, maxBytes, new NativeLitematicaWriter(4321));
  }

  private static Path onlySchematic(Path root, Path excluded) throws IOException {
    try (var files = Files.list(root)) {
      return files
          .filter(path -> !path.equals(excluded))
          .filter(path -> path.getFileName().toString().endsWith(".litematica"))
          .findFirst()
          .orElseThrow();
    }
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
