package dev.minecraftagent.paper.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Quiesces every Paper-owned source of transient Agent work during an Offline transition. */
public final class OfflineCleanup {
  @FunctionalInterface
  public interface Control {
    void quiesce(long epoch, OfflineReason reason);
  }

  private final Control requests;
  private final Control proposals;
  private final Control operations;
  private final Control clientState;

  public OfflineCleanup(
      Control requests, Control proposals, Control operations, Control clientState) {
    this.requests = Objects.requireNonNull(requests);
    this.proposals = Objects.requireNonNull(proposals);
    this.operations = Objects.requireNonNull(operations);
    this.clientState = Objects.requireNonNull(clientState);
  }

  public static OfflineCleanup empty() {
    Control noOp = (epoch, reason) -> {};
    return new OfflineCleanup(noOp, noOp, noOp, noOp);
  }

  public List<String> quiesce(long epoch, OfflineReason reason) {
    Objects.requireNonNull(reason);
    var failures = new ArrayList<String>();
    run("REQUEST_CANCELLATION_FAILED", requests, epoch, reason, failures);
    run("PROPOSAL_INVALIDATION_FAILED", proposals, epoch, reason, failures);
    run("OPERATION_CANCELLATION_FAILED", operations, epoch, reason, failures);
    run("CLIENT_TRANSIENT_CLEAR_FAILED", clientState, epoch, reason, failures);
    return List.copyOf(failures);
  }

  private static void run(
      String code, Control control, long epoch, OfflineReason reason, List<String> failures) {
    try {
      control.quiesce(epoch, reason);
    } catch (RuntimeException error) {
      failures.add(code);
    }
  }
}
