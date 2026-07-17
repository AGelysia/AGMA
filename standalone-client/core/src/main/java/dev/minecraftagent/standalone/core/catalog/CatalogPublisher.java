package dev.minecraftagent.standalone.core.catalog;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CatalogPublisher implements AutoCloseable {
  private final Object lock = new Object();
  private final AtomicReference<CatalogSnapshot> published = new AtomicReference<>();
  private final AtomicReference<Progress> progress =
      new AtomicReference<>(new Progress(Phase.IDLE, 0, 0, null));

  private ActiveRebuild active;
  private boolean closed;

  public RebuildHandle rebuild(Executor executor, SnapshotBuilder builder) {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(builder, "builder");
    final ActiveRebuild rebuild;
    synchronized (lock) {
      requireOpen();
      if (active != null) {
        active.cancelled.set(true);
      }
      rebuild = new ActiveRebuild();
      active = rebuild;
      progress.set(new Progress(Phase.BUILDING, 0, 0, null));
    }

    rebuild.future =
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return builder.build(
                        rebuild.cancelled::get,
                        (completed, total) -> updateProgress(rebuild, completed, total));
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(error);
                  } catch (Exception error) {
                    throw new CompletionException(error);
                  }
                },
                executor)
            .whenComplete((snapshot, failure) -> complete(rebuild, snapshot, failure));
    return new RebuildHandle(rebuild);
  }

  public Optional<CatalogSnapshot> current() {
    return Optional.ofNullable(published.get());
  }

  public CatalogSnapshot current(String generationId) {
    var snapshot =
        current().orElseThrow(() -> new IllegalStateException("catalog is not currently ready"));
    snapshot.requireCurrentGeneration(generationId);
    return snapshot;
  }

  public Progress progress() {
    return progress.get();
  }

  public void invalidate() {
    synchronized (lock) {
      if (active != null) {
        active.cancelled.set(true);
        active = null;
      }
      published.set(null);
      if (!closed) {
        progress.set(new Progress(Phase.IDLE, 0, 0, null));
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      if (active != null) {
        active.cancelled.set(true);
        active = null;
      }
      published.set(null);
      progress.set(new Progress(Phase.CLOSED, 0, 0, null));
    }
  }

  private void updateProgress(ActiveRebuild rebuild, long completed, long total) {
    if (completed < 0 || total < 0 || completed > total) {
      throw new IllegalArgumentException("catalog progress is invalid");
    }
    synchronized (lock) {
      if (active == rebuild && !rebuild.cancelled.get() && !closed) {
        progress.set(new Progress(Phase.BUILDING, completed, total, null));
      }
    }
  }

  private void complete(ActiveRebuild rebuild, CatalogSnapshot snapshot, Throwable failure) {
    synchronized (lock) {
      if (active != rebuild) {
        return;
      }
      active = null;
      if (closed || rebuild.cancelled.get()) {
        progress.set(
            closed
                ? new Progress(Phase.CLOSED, 0, 0, null)
                : new Progress(Phase.CANCELLED, 0, 0, FailureCode.CANCELLED));
        return;
      }
      if (failure != null || snapshot == null) {
        progress.set(new Progress(Phase.FAILED, 0, 0, FailureCode.BUILD_FAILED));
        return;
      }
      published.set(snapshot);
      progress.set(new Progress(Phase.READY, 1, 1, null));
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("catalog publisher is closed");
    }
  }

  public interface SnapshotBuilder {
    CatalogSnapshot build(Cancellation cancellation, ProgressListener progress) throws Exception;
  }

  @FunctionalInterface
  public interface Cancellation {
    boolean cancelled();

    default void throwIfCancelled() throws InterruptedException {
      if (cancelled()) {
        throw new InterruptedException("catalog rebuild cancelled");
      }
    }
  }

  @FunctionalInterface
  public interface ProgressListener {
    void update(long completed, long total);
  }

  public enum Phase {
    IDLE,
    BUILDING,
    READY,
    FAILED,
    CANCELLED,
    CLOSED
  }

  public enum FailureCode {
    BUILD_FAILED,
    CANCELLED
  }

  public record Progress(Phase phase, long completed, long total, FailureCode failure) {
    public Progress {
      Objects.requireNonNull(phase, "phase");
      if (completed < 0 || total < 0 || completed > total) {
        throw new IllegalArgumentException("catalog progress is invalid");
      }
      if ((phase == Phase.FAILED || phase == Phase.CANCELLED) != (failure != null)) {
        throw new IllegalArgumentException("catalog progress failure is inconsistent");
      }
    }
  }

  public final class RebuildHandle {
    private final ActiveRebuild rebuild;

    private RebuildHandle(ActiveRebuild rebuild) {
      this.rebuild = rebuild;
    }

    public CompletableFuture<CatalogSnapshot> future() {
      return rebuild.future;
    }

    public boolean cancel() {
      var changed = rebuild.cancelled.compareAndSet(false, true);
      synchronized (lock) {
        if (active == rebuild) {
          active = null;
          progress.set(new Progress(Phase.CANCELLED, 0, 0, FailureCode.CANCELLED));
        }
      }
      return rebuild.future.cancel(true) || changed;
    }
  }

  private static final class ActiveRebuild {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private CompletableFuture<CatalogSnapshot> future;
  }
}
