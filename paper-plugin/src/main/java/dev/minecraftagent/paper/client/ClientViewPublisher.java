package dev.minecraftagent.paper.client;

import dev.minecraftagent.paper.client.ClientTransferManager.Mode;
import dev.minecraftagent.paper.client.ClientTransferManager.TransferPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Converts a compatible completion into bounded transfer plans or an explicit text fallback. */
public final class ClientViewPublisher {
  private final ClientViewSelector selector;
  private final ClientTransferManager transfers;

  public ClientViewPublisher(ClientViewSelector selector, ClientTransferManager transfers) {
    this.selector = Objects.requireNonNull(selector);
    this.transfers = Objects.requireNonNull(transfers);
  }

  public Publication prepare(
      UUID playerUuid, String fallbackText, List<ClientStructuredView> views, Instant now) {
    var selection = selector.select(playerUuid, fallbackText, views);
    if (selection.usesFallbackOnly() || selection.connectionGeneration() == null) {
      return Publication.fallback(fallbackText, "CLIENT_VIEW_UNAVAILABLE");
    }

    var plans = new ArrayList<TransferPlan>();
    try {
      for (var view : selection.structuredViews()) {
        plans.add(
            transfers.prepare(
                playerUuid,
                selection.connectionGeneration(),
                view,
                Mode.SHOW,
                Objects.requireNonNull(now)));
      }
      return new Publication(fallbackText, List.copyOf(plans), null);
    } catch (ClientProtocolException failure) {
      for (var plan : plans) {
        transfers.cancel(playerUuid, plan.generation(), plan.transferId());
      }
      return Publication.fallback(fallbackText, failure.code());
    }
  }

  /** Releases transfer reservations when a prepared publication will not be sent. */
  public void discard(Publication publication) {
    Objects.requireNonNull(publication);
    for (var plan : publication.transfers()) {
      transfers.cancel(plan.playerUuid(), plan.generation(), plan.transferId());
    }
  }

  public record Publication(
      String fallbackText, List<TransferPlan> transfers, String fallbackReason) {
    public Publication {
      Objects.requireNonNull(fallbackText);
      transfers = List.copyOf(transfers);
      if (transfers.isEmpty() == (fallbackReason == null)) {
        throw new IllegalArgumentException("Publication must choose views or fallback");
      }
    }

    public static Publication fallback(String fallbackText, String reason) {
      return new Publication(fallbackText, List.of(), Objects.requireNonNull(reason));
    }

    public boolean useFallback() {
      return transfers.isEmpty();
    }
  }
}
