package dev.minecraftagent.client.litematica;

import dev.minecraftagent.client.view.BuildPreviewView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Presentation-only facade for the three Litematica UI controls. It derives local paths from view
 * IDs and never accepts a server-provided path or returns an authorization signal.
 */
public final class LitematicaClientController implements AutoCloseable {
  public static final long MAX_SCHEMATIC_BYTES = 16L * 1024L * 1024L;

  private final Optional<LitematicaAdapter> adapter;
  private final ManagedPreviewStore store;
  private final Map<UUID, LoadedArtifact> loadedArtifacts = new HashMap<>();

  public LitematicaClientController(LitematicaCompatibility compatibility, Path managedRoot)
      throws IOException {
    this(Objects.requireNonNull(compatibility).adapter(), managedRoot, MAX_SCHEMATIC_BYTES);
  }

  public LitematicaClientController(Optional<LitematicaAdapter> adapter, Path managedRoot)
      throws IOException {
    this(adapter, managedRoot, MAX_SCHEMATIC_BYTES);
  }

  LitematicaClientController(
      Optional<LitematicaAdapter> adapter, Path managedRoot, long maxSchematicBytes)
      throws IOException {
    this(adapter, managedRoot, maxSchematicBytes, new NativeLitematicaWriter());
  }

  LitematicaClientController(
      Optional<LitematicaAdapter> adapter,
      Path managedRoot,
      long maxSchematicBytes,
      NativeLitematicaWriter writer)
      throws IOException {
    this.adapter = Objects.requireNonNull(adapter);
    Path configuredRoot = Objects.requireNonNull(managedRoot).toAbsolutePath().normalize();
    Path normalizedRoot = adapter.isPresent() ? configuredRoot.toRealPath() : configuredRoot;
    if ((adapter.isPresent() && !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS))
        || maxSchematicBytes < 1) {
      throw new IOException("managed Litematica root is unavailable");
    }
    this.store = new ManagedPreviewStore(normalizedRoot, maxSchematicBytes, writer);
  }

  /** Generates and atomically stages a validated preview without loading it into Litematica. */
  public boolean stagePreview(BuildPreviewView preview) {
    Objects.requireNonNull(preview, "preview");
    try {
      store.stage(preview);
      return true;
    } catch (IOException | RuntimeException | LinkageError exception) {
      return false;
    }
  }

  /** Commits a staged preview only after the overlay has accepted the corresponding view. */
  public synchronized boolean commitPreview(BuildPreviewView preview, Set<UUID> displayedViewIds) {
    Objects.requireNonNull(preview, "preview");
    Objects.requireNonNull(displayedViewIds, "displayedViewIds");
    try {
      store.commit(preview, displayedViewIds, loadedSchematicHashes());
      return true;
    } catch (IOException | RuntimeException | LinkageError exception) {
      return false;
    }
  }

  /** Rolls back one staged preview after display or dispatch rejection. */
  public void discardPreview(BuildPreviewView preview) {
    store.discard(Objects.requireNonNull(preview, "preview"));
  }

  /** Drops transient artifacts that the overlay no longer displays, preserving loaded revisions. */
  public synchronized boolean reconcileDisplayedPreviews(Set<UUID> displayedViewIds) {
    Objects.requireNonNull(displayedViewIds, "displayedViewIds");
    try {
      store.reconcile(displayedViewIds, loadedSchematicHashes());
      return true;
    } catch (IOException | RuntimeException | LinkageError exception) {
      return false;
    }
  }

  /**
   * Performs all bounded file IO before the prepared action reaches the Minecraft client thread.
   */
  public PreparedLoad prepareLoad(UUID viewId, String displayName) {
    Objects.requireNonNull(viewId);
    Objects.requireNonNull(displayName);
    if (adapter.isEmpty()) {
      return PreparedLoad.failed(viewId, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
    }

    try {
      ManagedPreviewStore.Artifact verified = store.verified(viewId).orElseThrow();
      return PreparedLoad.ready(
          new LitematicaPreviewRequest(
              viewId,
              verified.revision(),
              verified.path(),
              verified.byteLength(),
              verified.lastModifiedTime(),
              verified.fileKey(),
              verified.previewContentHash(),
              displayName,
              verified.origin().x(),
              verified.origin().y(),
              verified.origin().z()),
          LoadedArtifact.from(verified));
    } catch (IOException | RuntimeException exception) {
      return PreparedLoad.failed(viewId, LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
    }
  }

  /** Commits one prepared load. The adapter remains responsible for enforcing its client thread. */
  public synchronized LitematicaDisplayReport load(PreparedLoad prepared) {
    Objects.requireNonNull(prepared);
    if (prepared.failure != null) {
      return LitematicaDisplayReport.failed(prepared.viewId, null, prepared.failure);
    }
    if (adapter.isEmpty()) {
      return unavailable(prepared.viewId);
    }
    try {
      ManagedPreviewStore.Artifact verified = store.verified(prepared.viewId).orElseThrow();
      if (!prepared.matches(verified)) {
        return LitematicaDisplayReport.failed(
            prepared.viewId,
            prepared.request.contentSha256(),
            LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
      }
      LoadedArtifact loaded = loadedArtifacts.get(prepared.viewId);
      if (loaded != null && !loaded.equals(prepared.artifact)) {
        LitematicaDisplayReport removed = adapter.orElseThrow().removePreview(prepared.viewId);
        if (removed.state() != LitematicaDisplayReport.State.REMOVED) {
          return removed;
        }
        loadedArtifacts.remove(prepared.viewId);
        store.releaseRetired(prepared.viewId);
      }
      LitematicaDisplayReport report = adapter.orElseThrow().loadPreview(prepared.request);
      if (report.state() == LitematicaDisplayReport.State.LOADED) {
        loadedArtifacts.put(prepared.viewId, prepared.artifact);
      }
      return report;
    } catch (IOException | RuntimeException | LinkageError exception) {
      if (exception instanceof IOException) {
        return LitematicaDisplayReport.failed(
            prepared.viewId,
            prepared.request.contentSha256(),
            LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
      }
      return adapterFailure(prepared.viewId, prepared.request.contentSha256());
    }
  }

  public synchronized LitematicaDisplayReport remove(UUID viewId) {
    Objects.requireNonNull(viewId);
    if (adapter.isEmpty()) {
      store.remove(viewId);
      return unavailable(viewId);
    }
    try {
      LitematicaDisplayReport report = adapter.orElseThrow().removePreview(viewId);
      loadedArtifacts.remove(viewId);
      store.remove(viewId);
      return report;
    } catch (RuntimeException | LinkageError exception) {
      loadedArtifacts.remove(viewId);
      store.remove(viewId);
      return adapterFailure(viewId, null);
    }
  }

  public LitematicaDisplayReport openMaterialList(UUID viewId) {
    Objects.requireNonNull(viewId);
    if (adapter.isEmpty()) {
      return unavailable(viewId);
    }
    try {
      return adapter.orElseThrow().openMaterialList(viewId);
    } catch (RuntimeException | LinkageError exception) {
      return adapterFailure(viewId, null);
    }
  }

  public boolean available() {
    return adapter.isPresent();
  }

  @Override
  public synchronized void close() {
    try {
      adapter.ifPresent(LitematicaAdapter::close);
    } catch (RuntimeException | LinkageError ignored) {
      // Connection-scoped files must still be unregistered if the optional adapter fails.
    } finally {
      loadedArtifacts.clear();
      store.clear();
    }
  }

  private static LitematicaDisplayReport unavailable(UUID viewId) {
    return LitematicaDisplayReport.failed(
        viewId, null, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
  }

  private static LitematicaDisplayReport adapterFailure(UUID viewId, String hash) {
    return LitematicaDisplayReport.failed(
        viewId, hash, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
  }

  private Set<String> loadedSchematicHashes() {
    return loadedArtifacts.values().stream()
        .map(LoadedArtifact::schematicHash)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public static final class PreparedLoad {
    private final UUID viewId;
    private final LitematicaPreviewRequest request;
    private final LoadedArtifact artifact;
    private final LitematicaDisplayReport.Failure failure;

    private PreparedLoad(
        UUID viewId,
        LitematicaPreviewRequest request,
        LoadedArtifact artifact,
        LitematicaDisplayReport.Failure failure) {
      this.viewId = Objects.requireNonNull(viewId);
      this.request = request;
      this.artifact = artifact;
      this.failure = failure;
    }

    private static PreparedLoad ready(LitematicaPreviewRequest request, LoadedArtifact artifact) {
      return new PreparedLoad(
          request.previewId(),
          Objects.requireNonNull(request),
          Objects.requireNonNull(artifact),
          null);
    }

    private static PreparedLoad failed(UUID viewId, LitematicaDisplayReport.Failure failure) {
      return new PreparedLoad(viewId, null, null, Objects.requireNonNull(failure));
    }

    private boolean matches(ManagedPreviewStore.Artifact verified) {
      return artifact.equals(LoadedArtifact.from(verified))
          && request.managedFile().equals(verified.path())
          && request.managedFileBytes() == verified.byteLength()
          && request.lastModifiedTime().equals(verified.lastModifiedTime())
          && request.fileKey().equals(verified.fileKey());
    }
  }

  private record LoadedArtifact(
      int revision,
      String baseRegionHash,
      String changeSetHash,
      String contentHash,
      String schematicHash) {
    private static LoadedArtifact from(ManagedPreviewStore.Artifact artifact) {
      return new LoadedArtifact(
          artifact.revision(),
          artifact.baseRegionHash(),
          artifact.changeSetHash(),
          artifact.previewContentHash(),
          artifact.schematicSha256());
    }
  }
}
