package dev.minecraftagent.standalone.ui.preview.litematica;

import dev.minecraftagent.standalone.common.preview.StandalonePreview;
import dev.minecraftagent.standalone.ui.preview.hologram.PreviewHologramBridge;
import dev.minecraftagent.standalone.ui.preview.hologram.PreviewHologramBridge.LoadListener;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Presentation-only facade for the Litematica hologram and the standalone hologram bridge.
 * Schematic file IO (stage, commit, and load preparation) runs on a single background executor;
 * every adapter call runs on the Minecraft client thread. It derives local paths from preview IDs
 * and never accepts an external path or returns an authorization signal.
 */
public final class StandaloneLitematicaController implements PreviewHologramBridge {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger("agma-standalone-litematica");
  public static final long MAX_SCHEMATIC_BYTES = 16L * 1024L * 1024L;

  private final Optional<LitematicaAdapter> adapter;
  private final ManagedPreviewStore store;
  private final Map<UUID, LoadedArtifact> loadedArtifacts = new HashMap<>();
  private final BooleanSupplier clientThreadCheck;
  private final Consumer<Runnable> clientScheduler;
  private final ExecutorService ioExecutor;
  private final boolean ownsIoExecutor;
  private volatile LoadListener loadListener;

  private UUID loadedPreviewId;
  private UUID pendingPreviewId;
  private boolean closed;

  public StandaloneLitematicaController(
      LitematicaCompatibility compatibility,
      Path managedRoot,
      BooleanSupplier clientThreadCheck,
      Consumer<Runnable> clientScheduler)
      throws IOException {
    this(
        Objects.requireNonNull(compatibility).adapter(),
        managedRoot,
        clientThreadCheck,
        clientScheduler);
  }

  public StandaloneLitematicaController(
      Optional<LitematicaAdapter> adapter,
      Path managedRoot,
      BooleanSupplier clientThreadCheck,
      Consumer<Runnable> clientScheduler)
      throws IOException {
    this(
        adapter,
        managedRoot,
        MAX_SCHEMATIC_BYTES,
        new NativeLitematicaWriter(),
        clientThreadCheck,
        clientScheduler,
        newIoExecutor(),
        true);
  }

  StandaloneLitematicaController(
      Optional<LitematicaAdapter> adapter,
      Path managedRoot,
      long maxSchematicBytes,
      NativeLitematicaWriter writer,
      BooleanSupplier clientThreadCheck,
      Consumer<Runnable> clientScheduler,
      ExecutorService ioExecutor,
      boolean ownsIoExecutor)
      throws IOException {
    this.adapter = Objects.requireNonNull(adapter);
    Path configuredRoot = Objects.requireNonNull(managedRoot).toAbsolutePath().normalize();
    Path normalizedRoot = adapter.isPresent() ? configuredRoot.toRealPath() : configuredRoot;
    if ((adapter.isPresent() && !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS))
        || maxSchematicBytes < 1) {
      throw new IOException("managed Litematica root is unavailable");
    }
    this.store = new ManagedPreviewStore(normalizedRoot, maxSchematicBytes, writer);
    this.clientThreadCheck = Objects.requireNonNull(clientThreadCheck, "clientThreadCheck");
    this.clientScheduler = Objects.requireNonNull(clientScheduler, "clientScheduler");
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    this.ownsIoExecutor = ownsIoExecutor;
  }

  /**
   * Loads one preview as the current hologram, replacing any previously loaded one. Staging,
   * committing, and load preparation run off the caller thread; the adapter load is scheduled onto
   * the client thread. A repeated load of the currently loaded or already pending preview is a
   * no-op.
   */
  @Override
  public void load(StandalonePreview preview) {
    Objects.requireNonNull(preview, "preview");
    if (!available()) {
      notifyLoadFailed(LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE.name());
      return;
    }
    synchronized (this) {
      if (closed
          || preview.previewId().equals(loadedPreviewId)
          || preview.previewId().equals(pendingPreviewId)) {
        return;
      }
      pendingPreviewId = preview.previewId();
    }
    try {
      ioExecutor.execute(() -> prepareLoadOffThread(preview));
    } catch (RejectedExecutionException exception) {
      clearPending(preview.previewId());
    }
  }

  /** Unloads the currently loaded hologram and deletes its managed schematic file. */
  @Override
  public void removeCurrent() {
    final UUID loaded;
    final UUID pending;
    synchronized (this) {
      loaded = loadedPreviewId;
      pending = pendingPreviewId;
      loadedPreviewId = null;
      pendingPreviewId = null;
    }
    if (loaded == null && pending == null) {
      return;
    }
    runOnClientThread(
        () -> {
          synchronized (this) {
            if (loaded != null) {
              logReport(remove(loaded));
            }
            if (pending != null && !pending.equals(loaded)) {
              store.remove(pending);
            }
          }
        });
  }

  /** Generates and atomically stages a validated preview without loading it into Litematica. */
  public boolean stagePreview(StandalonePreview preview) {
    Objects.requireNonNull(preview, "preview");
    try {
      store.stage(preview);
      return true;
    } catch (IOException | RuntimeException | LinkageError exception) {
      LOGGER.warn(
          "AGMA standalone preview staging failed: {}: {}",
          exception.getClass().getSimpleName(),
          exception.getMessage());
      return false;
    }
  }

  /** Commits a staged preview only after the presentation has accepted the corresponding view. */
  public synchronized boolean commitPreview(StandalonePreview preview, Set<UUID> displayedViewIds) {
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
  public void discardPreview(StandalonePreview preview) {
    store.discard(Objects.requireNonNull(preview, "preview"));
  }

  /** Drops transient artifacts that the presentation no longer displays, preserving loaded ones. */
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
  public PreparedLoad prepareLoad(UUID previewId, String displayName) {
    Objects.requireNonNull(previewId);
    Objects.requireNonNull(displayName);
    if (adapter.isEmpty()) {
      return PreparedLoad.failed(previewId, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
    }

    try {
      ManagedPreviewStore.Artifact verified = store.verified(previewId).orElseThrow();
      return PreparedLoad.ready(
          new LitematicaPreviewRequest(
              previewId,
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
      return PreparedLoad.failed(
          previewId, LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
    }
  }

  /** Commits one prepared load. The adapter remains responsible for enforcing its client thread. */
  public synchronized LitematicaDisplayReport load(PreparedLoad prepared) {
    Objects.requireNonNull(prepared);
    if (prepared.failure != null) {
      return LitematicaDisplayReport.failed(prepared.previewId, null, prepared.failure);
    }
    if (adapter.isEmpty()) {
      return unavailable(prepared.previewId);
    }
    try {
      ManagedPreviewStore.Artifact verified = store.verified(prepared.previewId).orElseThrow();
      if (!prepared.matches(verified)) {
        return LitematicaDisplayReport.failed(
            prepared.previewId,
            prepared.request.contentSha256(),
            LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
      }
      LoadedArtifact loaded = loadedArtifacts.get(prepared.previewId);
      if (loaded != null && !loaded.equals(prepared.artifact)) {
        LitematicaDisplayReport removed = adapter.orElseThrow().removePreview(prepared.previewId);
        if (removed.state() != LitematicaDisplayReport.State.REMOVED) {
          return removed;
        }
        loadedArtifacts.remove(prepared.previewId);
        store.releaseRetired(prepared.previewId);
      }
      LitematicaDisplayReport report = adapter.orElseThrow().loadPreview(prepared.request);
      if (report.state() == LitematicaDisplayReport.State.LOADED) {
        loadedArtifacts.put(prepared.previewId, prepared.artifact);
      }
      return report;
    } catch (IOException | RuntimeException | LinkageError exception) {
      if (exception instanceof IOException) {
        return LitematicaDisplayReport.failed(
            prepared.previewId,
            prepared.request.contentSha256(),
            LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
      }
      LOGGER.warn("Litematica load preparation failed", exception);
      return adapterFailure(prepared.previewId, prepared.request.contentSha256());
    }
  }

  public synchronized LitematicaDisplayReport remove(UUID previewId) {
    Objects.requireNonNull(previewId);
    if (adapter.isEmpty()) {
      store.remove(previewId);
      return unavailable(previewId);
    }
    try {
      LitematicaDisplayReport report = adapter.orElseThrow().removePreview(previewId);
      loadedArtifacts.remove(previewId);
      store.remove(previewId);
      return report;
    } catch (RuntimeException | LinkageError exception) {
      loadedArtifacts.remove(previewId);
      store.remove(previewId);
      return adapterFailure(previewId, null);
    }
  }

  public LitematicaDisplayReport openMaterialList(UUID previewId) {
    Objects.requireNonNull(previewId);
    if (adapter.isEmpty()) {
      return unavailable(previewId);
    }
    try {
      return adapter.orElseThrow().openMaterialList(previewId);
    } catch (RuntimeException | LinkageError exception) {
      return adapterFailure(previewId, null);
    }
  }

  @Override
  public boolean available() {
    return adapter.isPresent();
  }

  /** Registers the listener that receives each asynchronous hologram load outcome. */
  @Override
  public void loadListener(LoadListener listener) {
    this.loadListener = listener;
  }

  /** The preview currently loaded as the hologram, empty when none is displayed. */
  public synchronized Optional<UUID> loadedPreviewId() {
    return Optional.ofNullable(loadedPreviewId);
  }

  /** Unloads every hologram on the client thread and deletes all managed schematic files. */
  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      loadedPreviewId = null;
      pendingPreviewId = null;
    }
    runOnClientThread(
        () -> {
          synchronized (this) {
            try {
              adapter.ifPresent(LitematicaAdapter::close);
            } catch (RuntimeException | LinkageError ignored) {
              // Session-scoped files must still be unregistered if the optional adapter fails.
            } finally {
              loadedArtifacts.clear();
              store.clear();
            }
          }
        });
    if (ownsIoExecutor) {
      ioExecutor.shutdownNow();
    }
  }

  private void prepareLoadOffThread(StandalonePreview preview) {
    if (!stagePreview(preview) || !commitPreview(preview, Set.of(preview.previewId()))) {
      clearPending(preview.previewId());
      LOGGER.warn("AGMA standalone preview could not be staged for Litematica");
      notifyLoadFailed("STAGE_FAILED");
      return;
    }
    var prepared = prepareLoad(preview.previewId(), displayName(preview.previewId()));
    runOnClientThread(() -> loadOnClient(preview.previewId(), prepared));
  }

  private void loadOnClient(UUID previewId, PreparedLoad prepared) {
    final LitematicaDisplayReport report;
    synchronized (this) {
      if (closed || !previewId.equals(pendingPreviewId)) {
        // The load was superseded or cancelled before it reached the client thread; the preview was
        // never loaded into Litematica, so only its managed file needs to be dropped.
        store.remove(previewId);
        return;
      }
      pendingPreviewId = null;
      var current = loadedPreviewId;
      if (current != null) {
        var removed = remove(current);
        loadedPreviewId = null;
        if (removed.state() != LitematicaDisplayReport.State.REMOVED) {
          report = removed;
          finishLoad(report);
          return;
        }
      }
      var loaded = load(prepared);
      if (loaded.state() == LitematicaDisplayReport.State.LOADED) {
        loadedPreviewId = previewId;
      }
      report = loaded;
    }
    finishLoad(report);
  }

  private synchronized void clearPending(UUID previewId) {
    if (previewId.equals(pendingPreviewId)) {
      pendingPreviewId = null;
    }
  }

  private void runOnClientThread(Runnable action) {
    final boolean inline;
    try {
      inline = clientThreadCheck.getAsBoolean();
    } catch (RuntimeException | LinkageError exception) {
      return;
    }
    if (inline) {
      action.run();
      return;
    }
    try {
      clientScheduler.accept(action);
    } catch (RuntimeException | LinkageError exception) {
      LOGGER.warn("AGMA standalone Litematica action could not reach the client thread");
    }
  }

  private static String displayName(UUID previewId) {
    return "Agent preview " + previewId.toString().substring(0, 8);
  }

  private static void logReport(LitematicaDisplayReport report) {
    if (report.state() == LitematicaDisplayReport.State.FAILED) {
      LOGGER.warn("AGMA standalone Litematica action failed: {}", report.failure().orElseThrow());
    }
  }

  private void finishLoad(LitematicaDisplayReport report) {
    logReport(report);
    if (report.state() == LitematicaDisplayReport.State.LOADED) {
      notifyLoaded();
    } else if (report.state() == LitematicaDisplayReport.State.FAILED) {
      notifyLoadFailed(report.failure().orElseThrow().name());
    }
  }

  private void notifyLoaded() {
    var listener = loadListener;
    if (listener != null) {
      runOnClientThread(() -> listener.onLoad(true, null));
    }
  }

  private void notifyLoadFailed(String reason) {
    var listener = loadListener;
    if (listener != null) {
      runOnClientThread(() -> listener.onLoad(false, reason));
    }
  }

  private static LitematicaDisplayReport unavailable(UUID previewId) {
    return LitematicaDisplayReport.failed(
        previewId, null, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
  }

  private static LitematicaDisplayReport adapterFailure(UUID previewId, String hash) {
    return LitematicaDisplayReport.failed(
        previewId, hash, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
  }

  private Set<String> loadedSchematicHashes() {
    return loadedArtifacts.values().stream()
        .map(LoadedArtifact::schematicHash)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static ExecutorService newIoExecutor() {
    return Executors.newSingleThreadExecutor(
        task -> {
          var thread = new Thread(task, "agma-standalone-litematica-io");
          thread.setDaemon(true);
          return thread;
        });
  }

  public static final class PreparedLoad {
    private final UUID previewId;
    private final LitematicaPreviewRequest request;
    private final LoadedArtifact artifact;
    private final LitematicaDisplayReport.Failure failure;

    private PreparedLoad(
        UUID previewId,
        LitematicaPreviewRequest request,
        LoadedArtifact artifact,
        LitematicaDisplayReport.Failure failure) {
      this.previewId = Objects.requireNonNull(previewId);
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

    private static PreparedLoad failed(UUID previewId, LitematicaDisplayReport.Failure failure) {
      return new PreparedLoad(previewId, null, null, Objects.requireNonNull(failure));
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
