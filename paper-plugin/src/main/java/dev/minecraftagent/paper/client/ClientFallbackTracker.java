package dev.minecraftagent.paper.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Correlates rich deliveries with their private text fallback without granting ACK authority. */
final class ClientFallbackTracker {
  private final Map<DeliveryKey, Delivery> deliveries = new LinkedHashMap<>();
  private final Map<TransferKey, DeliveryKey> transferIndex = new LinkedHashMap<>();

  synchronized Registration register(ClientViewPublisher.Publication publication) {
    Objects.requireNonNull(publication);
    if (publication.useFallback()) {
      throw new IllegalArgumentException("Cannot track a fallback-only publication");
    }
    var first = publication.transfers().getFirst();
    var key = new DeliveryKey(first.playerUuid(), first.generation(), first.requestId());
    var transferIds = new LinkedHashSet<UUID>();
    for (var plan : publication.transfers()) {
      if (!plan.playerUuid().equals(key.playerUuid)
          || plan.generation() != key.generation
          || !plan.requestId().equals(key.requestId)
          || !transferIds.add(plan.transferId())
          || transferIndex.containsKey(
              new TransferKey(plan.playerUuid(), plan.generation(), plan.transferId()))) {
        throw new ClientProtocolException("CLIENT_FALLBACK_CORRELATION_INVALID");
      }
    }
    if (deliveries.containsKey(key)) {
      throw new ClientProtocolException("CLIENT_FALLBACK_CORRELATION_INVALID");
    }
    deliveries.put(key, new Delivery(publication.fallbackText(), transferIds));
    for (var transferId : transferIds) {
      transferIndex.put(new TransferKey(key.playerUuid, key.generation, transferId), key);
    }
    return new Registration(key.playerUuid, key.generation, key.requestId);
  }

  synchronized void displayed(UUID playerUuid, long generation, UUID transferId) {
    var transferKey = new TransferKey(playerUuid, generation, transferId);
    var deliveryKey = transferIndex.remove(transferKey);
    if (deliveryKey == null) {
      return;
    }
    var delivery = deliveries.get(deliveryKey);
    if (delivery != null
        && delivery.transferIds.remove(transferId)
        && delivery.transferIds.isEmpty()) {
      deliveries.remove(deliveryKey);
    }
  }

  synchronized List<Fallback> rejectTransfer(UUID playerUuid, long generation, UUID transferId) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(transferId);
    var key = transferIndex.get(new TransferKey(playerUuid, generation, transferId));
    var fallback = key == null ? null : remove(key);
    return fallback == null ? List.of() : List.of(fallback);
  }

  synchronized List<Fallback> resolveGeneration(UUID playerUuid, long generation) {
    Objects.requireNonNull(playerUuid);
    var matches =
        deliveries.keySet().stream()
            .filter(key -> key.playerUuid.equals(playerUuid) && key.generation == generation)
            .toList();
    var fallbacks = new ArrayList<Fallback>(matches.size());
    for (var key : matches) {
      var fallback = remove(key);
      if (fallback != null) {
        fallbacks.add(fallback);
      }
    }
    return List.copyOf(fallbacks);
  }

  synchronized List<Fallback> expired(ClientTransferManager.ExpiredTransfer expired) {
    Objects.requireNonNull(expired);
    return rejectTransfer(expired.playerUuid(), expired.generation(), expired.transferId());
  }

  synchronized void discard(Registration registration) {
    Objects.requireNonNull(registration);
    remove(
        new DeliveryKey(registration.playerUuid, registration.generation, registration.requestId));
  }

  synchronized void discardPlayer(UUID playerUuid) {
    Objects.requireNonNull(playerUuid);
    var matches =
        deliveries.keySet().stream().filter(key -> key.playerUuid.equals(playerUuid)).toList();
    matches.forEach(this::remove);
  }

  synchronized void clear() {
    deliveries.clear();
    transferIndex.clear();
  }

  synchronized int pendingDeliveryCount() {
    return deliveries.size();
  }

  private Fallback remove(DeliveryKey key) {
    var delivery = deliveries.remove(key);
    if (delivery == null) {
      return null;
    }
    for (var transferId : delivery.transferIds) {
      transferIndex.remove(new TransferKey(key.playerUuid, key.generation, transferId));
    }
    return new Fallback(
        key.playerUuid, key.generation, delivery.fallbackText, Set.copyOf(delivery.transferIds));
  }

  record Registration(UUID playerUuid, long generation, UUID requestId) {}

  record Fallback(UUID playerUuid, long generation, String fallbackText, Set<UUID> transferIds) {
    Fallback {
      Objects.requireNonNull(playerUuid);
      Objects.requireNonNull(fallbackText);
      transferIds = Set.copyOf(transferIds);
    }
  }

  private record DeliveryKey(UUID playerUuid, long generation, UUID requestId) {}

  private record TransferKey(UUID playerUuid, long generation, UUID transferId) {}

  private static final class Delivery {
    private final String fallbackText;
    private final LinkedHashSet<UUID> transferIds;

    private Delivery(String fallbackText, Set<UUID> transferIds) {
      this.fallbackText = Objects.requireNonNull(fallbackText);
      this.transferIds = new LinkedHashSet<>(transferIds);
    }
  }
}
