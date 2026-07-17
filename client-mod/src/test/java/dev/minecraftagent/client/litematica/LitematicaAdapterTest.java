package dev.minecraftagent.client.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicaAdapterTest {
  private static final UUID PREVIEW_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final UUID ARTIFACT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

  @TempDir Path temporaryDirectory;

  @BeforeEach
  void resetFakeApi() {
    FakeSchematicHolder.INSTANCE.schematics.clear();
    FakeDataManager.MANAGER.placements.clear();
    FakeDataManager.materialList = null;
    FakeInfoHud.INSTANCE.renderers.clear();
    FakeInfoHud.INSTANCE.enabled = false;
  }

  @Test
  void exactReflectionAdapterLoadsRemovesAndOpensNativeMaterialHud() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var file = Files.writeString(managedFile(root), "verified schematic");
    var adapter = fakeAdapter(root, () -> true);
    var request = request(file, sha256(file));

    var loaded = adapter.loadPreview(request);
    assertEquals(LitematicaDisplayReport.State.LOADED, loaded.state());
    assertEquals(1, adapter.loadedPreviewCount());
    assertEquals(1, FakeDataManager.MANAGER.placements.size());
    assertTrue(FakeDataManager.MANAGER.placements.getFirst().enabled);
    assertTrue(FakeDataManager.MANAGER.placements.getFirst().renderEnabled);

    var material = adapter.openMaterialList(PREVIEW_ID);
    assertEquals(LitematicaDisplayReport.State.MATERIAL_LIST_OPEN, material.state());
    assertTrue(FakeDataManager.materialList instanceof FakeMaterialListPlacement);
    assertTrue(((FakeMaterialListPlacement) FakeDataManager.materialList).recreated);
    assertTrue(FakeInfoHud.INSTANCE.enabled);
    assertEquals(1, FakeInfoHud.INSTANCE.renderers.size());
    assertTrue(((FakeHudRenderer) FakeInfoHud.INSTANCE.renderers.getFirst()).shouldRender);

    var removed = adapter.removePreview(PREVIEW_ID);
    assertEquals(LitematicaDisplayReport.State.REMOVED, removed.state());
    assertEquals(0, adapter.loadedPreviewCount());
    assertTrue(FakeDataManager.MANAGER.placements.isEmpty());
    assertTrue(FakeSchematicHolder.INSTANCE.schematics.isEmpty());
    assertTrue(FakeInfoHud.INSTANCE.renderers.isEmpty());
    assertNull(FakeDataManager.materialList);
  }

  @Test
  void rejectsWrongThreadOutsideRootChangedFilesAndDuplicateIds() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var file = Files.writeString(managedFile(root), "verified schematic");
    var wrongThread = fakeAdapter(root, () -> false);
    assertEquals(
        LitematicaDisplayReport.Failure.WRONG_THREAD,
        wrongThread.loadPreview(request(file, sha256(file))).failure().orElseThrow());

    var adapter = fakeAdapter(root, () -> true);
    var outside = Files.writeString(temporaryDirectory.resolve("outside.litematica"), "outside");
    assertEquals(
        LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE,
        adapter.loadPreview(request(outside, sha256(outside))).failure().orElseThrow());
    var legacyName = Files.writeString(root.resolve(PREVIEW_ID + ".litematica"), "legacy name");
    assertEquals(
        LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE,
        adapter.loadPreview(request(legacyName, sha256(legacyName))).failure().orElseThrow());
    var changed = request(file, sha256(file));
    Files.writeString(file, "changed after background verification");
    assertEquals(
        LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE,
        adapter.loadPreview(changed).failure().orElseThrow());

    Files.writeString(file, "verified schematic");
    var valid = request(file, sha256(file));
    assertEquals(LitematicaDisplayReport.State.LOADED, adapter.loadPreview(valid).state());
    assertEquals(
        LitematicaDisplayReport.Failure.PREVIEW_ALREADY_LOADED,
        adapter.loadPreview(valid).failure().orElseThrow());
  }

  @Test
  void controllerGeneratedVersionedArtifactLoadsThroughExactReflectionAdapter() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = fakeAdapter(root, () -> true);
    var controller =
        new LitematicaClientController(
            Optional.of(adapter), root, 1024 * 1024, new NativeLitematicaWriter(4321));
    var preview = NativeLitematicaWriterTest.preview();

    assertTrue(controller.stagePreview(preview));
    assertTrue(controller.commitPreview(preview, java.util.Set.of(preview.previewId())));
    var report = controller.load(controller.prepareLoad(preview.previewId(), "Agent preview"));

    assertEquals(LitematicaDisplayReport.State.LOADED, report.state());
    assertEquals(1, adapter.loadedPreviewCount());
    controller.close();
  }

  @Test
  void dependencyDetectionNeverLoadsOptionalClassesBeforeExactVersionMatch() throws IOException {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var attemptedLoads = new AtomicInteger();
    var trapLoader =
        new ClassLoader(null) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            attemptedLoads.incrementAndGet();
            throw new ClassNotFoundException(name);
          }
        };

    var absent =
        LitematicaAdapterResolver.resolve(
            "1.21.11", "0.19.3", id -> Optional.empty(), trapLoader, root, () -> true);
    assertEquals(LitematicaCompatibility.Status.NOT_INSTALLED, absent.status());
    assertEquals(0, attemptedLoads.get());
    assertEquals(
        LitematicaAdapterDiagnostic.Status.NOT_INSTALLED,
        LitematicaAdapterDiagnostic.from(absent).status());

    var orphanMalilib =
        LitematicaAdapterResolver.resolve(
            "1.21.11",
            "0.19.3",
            versions(Map.of("malilib", "0.27.16")),
            trapLoader,
            root,
            () -> true);
    assertEquals(LitematicaCompatibility.Status.NOT_INSTALLED, orphanMalilib.status());
    assertEquals(Optional.of("0.27.16"), orphanMalilib.detectedVersions().malilibVersion());
    assertEquals(0, attemptedLoads.get());

    var unsafeMetadata =
        LitematicaAdapterResolver.resolve(
            "1.21.11",
            "0.19.3",
            versions(Map.of("litematica", "0.26.12\n", "malilib", "0.27.16")),
            trapLoader,
            root,
            () -> true);
    assertEquals(LitematicaCompatibility.Status.NOT_INSTALLED, unsafeMetadata.status());
    assertTrue(unsafeMetadata.detectedVersions().litematicaVersion().isEmpty());
    assertEquals(0, attemptedLoads.get());

    var pathMetadata =
        LitematicaAdapterResolver.resolve(
            "1.21.11",
            "0.19.3",
            versions(Map.of("litematica", "/home/player/mod.jar", "malilib", "0.27.16")),
            trapLoader,
            root,
            () -> true);
    assertEquals(LitematicaCompatibility.Status.NOT_INSTALLED, pathMetadata.status());
    assertTrue(pathMetadata.detectedVersions().litematicaVersion().isEmpty());
    assertEquals(0, attemptedLoads.get());

    var dependencyMissingMods = versions(Map.of("litematica", "0.26.12"));
    var dependencyMissing =
        LitematicaAdapterResolver.resolve(
            "1.21.11", "0.19.3", dependencyMissingMods, trapLoader, root, () -> true);
    assertEquals(LitematicaCompatibility.Status.MISSING_DEPENDENCY, dependencyMissing.status());
    assertEquals(0, attemptedLoads.get());
    assertEquals(
        LitematicaAdapterDiagnostic.Status.MISSING_DEPENDENCY,
        LitematicaAdapterDiagnostic.from(dependencyMissing).status());

    var unsupportedCombinations =
        List.of(
            new VersionCombination("1.21.10", "0.19.3", "0.26.12", "0.27.16"),
            new VersionCombination("1.21.11", "0.19.2", "0.26.12", "0.27.16"),
            new VersionCombination("1.21.11", "0.19.3", "0.26.11", "0.27.16"),
            new VersionCombination("1.21.11", "0.19.3", "0.26.12", "0.27.15"));
    for (var combination : unsupportedCombinations) {
      var unsupported =
          LitematicaAdapterResolver.resolve(
              combination.minecraftVersion(),
              combination.fabricLoaderVersion(),
              versions(
                  Map.of(
                      "litematica",
                      combination.litematicaVersion(),
                      "malilib",
                      combination.malilibVersion())),
              trapLoader,
              root,
              () -> true);
      assertEquals(LitematicaCompatibility.Status.UNSUPPORTED_VERSION, unsupported.status());
      assertEquals(
          LitematicaAdapterDiagnostic.Status.UNSUPPORTED_VERSION,
          LitematicaAdapterDiagnostic.from(unsupported).status());
    }
    assertEquals(0, attemptedLoads.get());

    var exactMods = versions(Map.of("litematica", "0.26.12", "malilib", "0.27.16"));
    var linkageFailure =
        LitematicaAdapterResolver.resolve(
            "1.21.11", "0.19.3", exactMods, trapLoader, root, () -> true);
    assertEquals(LitematicaCompatibility.Status.ADAPTER_LINKAGE_FAILED, linkageFailure.status());
    assertTrue(attemptedLoads.get() > 0);
    assertTrue(linkageFailure.adapter().isEmpty());
    assertEquals(
        LitematicaSupportMatrix.supported().getFirst(),
        linkageFailure.supportedCombination().orElseThrow());
    assertEquals(
        LitematicaAdapterDiagnostic.Status.ADAPTER_LINKAGE_FAILED,
        LitematicaAdapterDiagnostic.from(linkageFailure).status());
  }

  @Test
  void diagnosticsClassifyEveryCompatibilityResultWithoutPathsOrAuthority() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var versions =
        new LitematicaCompatibility.DetectedVersions(
            "1.21.11", "0.19.3", Optional.of("0.26.12"), Optional.of("0.27.16"));
    var ready =
        new LitematicaCompatibility(
            LitematicaCompatibility.Status.READY,
            versions,
            Optional.of(LitematicaSupportMatrix.supported().getFirst()),
            Optional.of(fakeAdapter(root, () -> true)));

    var diagnostic = LitematicaAdapterDiagnostic.from(ready);

    assertEquals(LitematicaAdapterDiagnostic.Status.READY, diagnostic.status());
    assertEquals(Optional.of("litematica-reflection-1"), diagnostic.adapterId());
    assertEquals(
        List.of(
            "status",
            "minecraftversion",
            "fabricloaderversion",
            "litematicaversion",
            "malilibversion",
            "adapterid"),
        java.util.Arrays.stream(LitematicaAdapterDiagnostic.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
            .toList());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LitematicaAdapterDiagnostic(
                LitematicaAdapterDiagnostic.Status.READY,
                "1.21.11",
                "0.19.3",
                Optional.of("0.26.12"),
                Optional.of("0.27.16"),
                Optional.empty()));
    assertEquals(
        LitematicaAdapterDiagnostic.Status.PREVIEW_STORAGE_UNAVAILABLE,
        LitematicaAdapterDiagnostic.previewStorageUnavailable(
                "1.21.11", "0.19.3", Optional.of("0.26.12"), Optional.of("0.27.16"))
            .status());
  }

  @Test
  void supportMatrixAndDisplayReportsHaveNoAuthorizationSurface() {
    assertEquals(
        List.of(
            new LitematicaSupportMatrix.Entry(
                "1.21.11", "0.19.3", "0.26.12", "0.27.16", "litematica-reflection-1")),
        LitematicaSupportMatrix.supported());
    assertFalse(LitematicaSupportMatrix.LITEMATICA_SOURCE.isBlank());
    assertFalse(LitematicaSupportMatrix.MALILIB_SOURCE.isBlank());

    var componentNames =
        java.util.Arrays.stream(LitematicaDisplayReport.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
            .toList();
    assertEquals(List.of("previewid", "state", "contentsha256", "failure"), componentNames);
    assertTrue(
        componentNames.stream()
            .noneMatch(
                name ->
                    name.contains("permission")
                        || name.contains("authoriz")
                        || name.contains("approv")));
  }

  private LitematicaAdapter fakeAdapter(Path root, java.util.function.BooleanSupplier threadCheck)
      throws ReflectiveOperationException {
    return ReflectiveLitematicaAdapter.linkWithNames(
        LitematicaSupportMatrix.supported().getFirst(),
        getClass().getClassLoader(),
        root,
        threadCheck,
        new ReflectiveLitematicaAdapter.ApiClassNames(
            FakeDataManager.class.getName(),
            FakeSchematicHolder.class.getName(),
            FakeSchematic.class.getName(),
            FakePlacementManager.class.getName(),
            FakePlacement.class.getName(),
            FakeMaterialListBase.class.getName(),
            FakeMaterialListPlacement.class.getName(),
            FakeHudRenderer.class.getName(),
            FakeInfoHud.class.getName(),
            FakeInfoHudRenderer.class.getName()));
  }

  private static LitematicaPreviewRequest request(Path file, String hash) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return new LitematicaPreviewRequest(
        PREVIEW_ID,
        1,
        file,
        attributes.size(),
        attributes.lastModifiedTime(),
        Optional.ofNullable(attributes.fileKey()).map(Object::toString),
        hash,
        "Agent preview",
        1,
        64,
        2);
  }

  private static Path managedFile(Path root) {
    return root.resolve(PREVIEW_ID + ".1." + ARTIFACT_ID + ".litematica");
  }

  private static ModInventory versions(Map<String, String> versions) {
    var copy = new HashMap<>(versions);
    return id -> Optional.ofNullable(copy.get(id));
  }

  private record VersionCombination(
      String minecraftVersion,
      String fabricLoaderVersion,
      String litematicaVersion,
      String malilibVersion) {}

  private static String sha256(Path path) throws IOException {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  public static final class FakeDataManager {
    static final FakePlacementManager MANAGER = new FakePlacementManager();
    static FakeMaterialListBase materialList;

    public static FakePlacementManager getSchematicPlacementManager() {
      return MANAGER;
    }

    public static FakeMaterialListBase getMaterialList() {
      return materialList;
    }

    public static void setMaterialList(FakeMaterialListBase value) {
      materialList = value;
    }
  }

  public static final class FakeSchematicHolder {
    static final FakeSchematicHolder INSTANCE = new FakeSchematicHolder();
    final List<FakeSchematic> schematics = new ArrayList<>();

    public static FakeSchematicHolder getInstance() {
      return INSTANCE;
    }

    public FakeSchematic getOrLoad(Path file) {
      for (var schematic : schematics) {
        if (schematic.file.equals(file)) {
          return schematic;
        }
      }
      var schematic = new FakeSchematic(file);
      schematics.add(schematic);
      return schematic;
    }

    public Collection<FakeSchematic> getAllSchematics() {
      return schematics;
    }

    public boolean removeSchematic(FakeSchematic schematic) {
      return schematics.remove(schematic);
    }
  }

  public static final class FakeSchematic {
    final Path file;

    FakeSchematic(Path file) {
      this.file = file;
    }
  }

  public static final class FakeBlockPosition {
    final int x;
    final int y;
    final int z;

    public FakeBlockPosition(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  public static final class FakePlacement {
    final FakeSchematic schematic;
    final FakeBlockPosition origin;
    final String name;
    final boolean enabled;
    final boolean renderEnabled;

    FakePlacement(
        FakeSchematic schematic,
        FakeBlockPosition origin,
        String name,
        boolean enabled,
        boolean renderEnabled) {
      this.schematic = schematic;
      this.origin = origin;
      this.name = name;
      this.enabled = enabled;
      this.renderEnabled = renderEnabled;
    }

    public static FakePlacement createFor(
        FakeSchematic schematic,
        FakeBlockPosition origin,
        String name,
        boolean enabled,
        boolean renderEnabled) {
      return new FakePlacement(schematic, origin, name, enabled, renderEnabled);
    }
  }

  public static final class FakePlacementManager {
    final List<FakePlacement> placements = new ArrayList<>();

    public void addSchematicPlacement(FakePlacement placement, boolean notify) {
      placements.add(placement);
    }

    public boolean removeSchematicPlacement(FakePlacement placement) {
      return placements.remove(placement);
    }

    public List<FakePlacement> getAllPlacementsOfSchematic(FakeSchematic schematic) {
      return placements.stream().filter(placement -> placement.schematic == schematic).toList();
    }
  }

  public static class FakeMaterialListBase {
    final FakeHudRenderer renderer = new FakeHudRenderer();

    public FakeHudRenderer getHudRenderer() {
      return renderer;
    }
  }

  public static final class FakeMaterialListPlacement extends FakeMaterialListBase {
    final FakePlacement placement;
    boolean recreated;

    public FakeMaterialListPlacement(FakePlacement placement) {
      this.placement = placement;
    }

    public void reCreateMaterialList() {
      recreated = true;
    }
  }

  public interface FakeInfoHudRenderer {}

  public static final class FakeHudRenderer implements FakeInfoHudRenderer {
    boolean shouldRender;

    public boolean getShouldRenderCustom() {
      return shouldRender;
    }

    public void toggleShouldRender() {
      shouldRender = !shouldRender;
    }
  }

  public static final class FakeInfoHud {
    static final FakeInfoHud INSTANCE = new FakeInfoHud();
    final List<FakeInfoHudRenderer> renderers = new ArrayList<>();
    boolean enabled;

    public static FakeInfoHud getInstance() {
      return INSTANCE;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public void addInfoHudRenderer(FakeInfoHudRenderer renderer, boolean enable) {
      if (!renderers.contains(renderer)) {
        renderers.add(renderer);
      }
      enabled = enable;
    }

    public void removeInfoHudRenderer(FakeInfoHudRenderer renderer, boolean disable) {
      renderers.remove(renderer);
    }
  }
}
