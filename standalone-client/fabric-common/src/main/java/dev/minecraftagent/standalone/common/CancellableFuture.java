package dev.minecraftagent.standalone.common;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Completion future that runs a registered action when cancellation wins the race against a
 * terminal result. Used so a locally cancelled connector request still notifies the session.
 */
final class CancellableFuture<T> extends CompletableFuture<T> {
  private Runnable cancellation = () -> {};

  synchronized void onCancel(Runnable action) {
    cancellation = Objects.requireNonNull(action, "action");
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    if (!super.cancel(mayInterruptIfRunning)) {
      return false;
    }
    final Runnable action;
    synchronized (this) {
      action = cancellation;
    }
    action.run();
    return true;
  }
}
