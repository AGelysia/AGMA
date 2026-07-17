package dev.minecraftagent.paper.client;

import dev.minecraftagent.paper.client.ClientInboundMessage.Ack;
import dev.minecraftagent.paper.client.ClientInboundMessage.Error;
import dev.minecraftagent.paper.client.ClientInboundMessage.Hello;
import dev.minecraftagent.paper.client.ClientTransferManager.TransferPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit Messenger adapter for the raw JSON client channel. It performs no world mutation. */
public final class PaperClientChannel implements PluginMessageListener, Listener, AutoCloseable {
  private final Plugin plugin;
  private final ClientPayloadCodec codec;
  private final ClientStateCoordinator state;
  private final ClientTransferManager transfers;
  private final ClientFallbackTracker fallbacks = new ClientFallbackTracker();
  private final AtomicBoolean started = new AtomicBoolean();
  private final Map<UUID, Instant> lastRejectionWarnings = new HashMap<>();
  private BukkitTask expiryTask;

  public PaperClientChannel(
      Plugin plugin,
      ClientPayloadCodec codec,
      ClientStateCoordinator state,
      ClientTransferManager transfers) {
    this.plugin = Objects.requireNonNull(plugin);
    this.codec = Objects.requireNonNull(codec);
    this.state = Objects.requireNonNull(state);
    this.transfers = Objects.requireNonNull(transfers);
  }

  /** Must be called once from the Paper primary thread during plugin enable. */
  public void start() {
    requirePrimaryThread();
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("Client channel already started");
    }
    var messenger = Bukkit.getMessenger();
    messenger.registerOutgoingPluginChannel(plugin, ClientPayloadCodec.CHANNEL);
    messenger.registerIncomingPluginChannel(plugin, ClientPayloadCodec.CHANNEL, this);
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var player : Bukkit.getOnlinePlayers()) {
      state.join(player.getUniqueId());
    }
    expiryTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  for (var expired : transfers.expire(Instant.now())) {
                    deliverFallbacks(fallbacks.expired(expired));
                  }
                },
                20L,
                20L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    if (started.get()) {
      fallbacks.discardPlayer(event.getPlayer().getUniqueId());
      state.join(event.getPlayer().getUniqueId());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    lastRejectionWarnings.remove(event.getPlayer().getUniqueId());
    fallbacks.discardPlayer(event.getPlayer().getUniqueId());
    state.quit(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onWorldChanged(PlayerChangedWorldEvent event) {
    var player = event.getPlayer();
    var connection = state.connection(player.getUniqueId());
    if (connection.isEmpty()) {
      return;
    }
    try {
      sendClear(player, null);
    } catch (RuntimeException failure) {
      warnRejected(player.getUniqueId(), "CLIENT_CHANNEL_SEND_FAILED");
    } finally {
      transfers.clearPending(player.getUniqueId(), connection.orElseThrow().generation());
      fallbacks.discardPlayer(player.getUniqueId());
    }
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] message) {
    if (!started.get() || !ClientPayloadCodec.CHANNEL.equals(channel)) {
      return;
    }
    requirePrimaryThread();
    var playerUuid = player.getUniqueId();
    try {
      switch (codec.decodeInbound(message)) {
        case Hello hello -> acceptHello(player, hello);
        case Ack ack -> handleAck(playerUuid, ack);
        case Error error -> handleError(playerUuid, error);
      }
    } catch (ClientProtocolException failure) {
      rejectConnection(player, failure.code());
    } catch (RuntimeException failure) {
      rejectConnection(player, "CLIENT_CHANNEL_FAILURE");
    }
  }

  /** Sends plans prepared by {@link ClientViewPublisher}; false means the caller must use chat. */
  public boolean sendPublication(Player player, ClientViewPublisher.Publication publication) {
    requirePrimaryThread();
    Objects.requireNonNull(player);
    Objects.requireNonNull(publication);
    if (publication.useFallback() || !player.isOnline()) {
      return false;
    }
    ClientFallbackTracker.Registration registration = null;
    var sent = false;
    try {
      registration = fallbacks.register(publication);
      for (var plan : publication.transfers()) {
        if (!player.getUniqueId().equals(plan.playerUuid()) || !sendPlan(player, plan)) {
          return false;
        }
      }
      sent = true;
      return true;
    } finally {
      if (!sent) {
        if (registration != null) {
          fallbacks.discard(registration);
        }
        for (var plan : publication.transfers()) {
          transfers.cancel(plan.playerUuid(), plan.generation(), plan.transferId());
        }
      }
    }
  }

  public boolean sendClear(Player player, UUID viewId) {
    requirePrimaryThread();
    var connection = state.connection(player.getUniqueId());
    if (connection.isEmpty() || !connection.orElseThrow().negotiated()) {
      return false;
    }
    send(
        player,
        codec.encodeViewClear(UUID.randomUUID(), connection.orElseThrow().generation(), viewId));
    return true;
  }

  public ClientUiCommandGateway.ControlSink uiControlSink() {
    return this::sendUiControl;
  }

  public void sendUiControl(UUID playerUuid, ClientUiCommandGateway.Control control) {
    requirePrimaryThread();
    var player = Bukkit.getPlayer(Objects.requireNonNull(playerUuid));
    var connection = state.connection(playerUuid);
    if (player == null
        || !player.isOnline()
        || connection.isEmpty()
        || connection.orElseThrow().generation() != control.generation()) {
      return;
    }
    send(player, codec.encodeUiControl(UUID.randomUUID(), control));
  }

  /** Sends clear-all before cancelling pending transfers during an Offline transition. */
  public void clearTransientViews() {
    requirePrimaryThread();
    try {
      for (var player : Bukkit.getOnlinePlayers()) {
        try {
          sendClear(player, null);
        } catch (RuntimeException failure) {
          warnRejected(player.getUniqueId(), "CLIENT_CHANNEL_SEND_FAILED");
        }
      }
    } finally {
      state.clearTransientTransfers();
      fallbacks.clear();
    }
  }

  void acceptHello(Player player, Hello hello) {
    resolvePendingBeforeGenerationChange(player.getUniqueId());
    var connection = state.negotiate(player.getUniqueId(), hello.handshake());
    send(player, codec.encodeServerHello(UUID.randomUUID(), connection.generation(), true));
  }

  void rejectConnection(Player player, String code) {
    var playerUuid = player.getUniqueId();
    warnRejected(playerUuid, code);
    resolvePendingBeforeGenerationChange(playerUuid);
    state
        .reject(playerUuid)
        .ifPresent(
            connection -> {
              try {
                send(
                    player,
                    codec.encodeServerHello(UUID.randomUUID(), connection.generation(), false));
              } catch (RuntimeException failure) {
                warnRejected(playerUuid, "CLIENT_CHANNEL_SEND_FAILED");
              }
            });
  }

  private void resolvePendingBeforeGenerationChange(UUID playerUuid) {
    var connection = state.connection(playerUuid);
    if (connection.isPresent() && connection.orElseThrow().negotiated()) {
      deliverFallbacks(
          fallbacks.resolveGeneration(playerUuid, connection.orElseThrow().generation()));
    } else {
      fallbacks.discardPlayer(playerUuid);
    }
  }

  private boolean sendPlan(Player player, TransferPlan plan) {
    var connection = state.connection(player.getUniqueId());
    if (connection.isEmpty()
        || !connection.orElseThrow().negotiated()
        || connection.orElseThrow().generation() != plan.generation()
        || !transfers.isPending(player.getUniqueId(), plan.generation(), plan.transferId())) {
      transfers.cancel(player.getUniqueId(), plan.generation(), plan.transferId());
      return false;
    }
    send(player, codec.encodeViewBegin(UUID.randomUUID(), plan));
    for (var chunk : plan.chunks()) {
      send(player, codec.encodeViewChunk(UUID.randomUUID(), plan.generation(), chunk));
    }
    return true;
  }

  private void handleAck(UUID playerUuid, Ack ack) {
    var result = transfers.acknowledge(playerUuid, ack);
    if (result == ClientTransferManager.AcknowledgementResult.DISPLAY_REPORTED) {
      fallbacks.displayed(playerUuid, ack.generation(), ack.transferId());
    } else if (result == ClientTransferManager.AcknowledgementResult.REJECTION_REPORTED) {
      deliverFallbacks(fallbacks.rejectTransfer(playerUuid, ack.generation(), ack.transferId()));
    }
  }

  void handleError(UUID playerUuid, Error error) {
    var result = transfers.clientError(playerUuid, error);
    if (result == ClientTransferManager.AcknowledgementResult.REJECTION_REPORTED) {
      deliverFallbacks(
          fallbacks.rejectTransfer(
              playerUuid, error.generation(), Objects.requireNonNull(error.transferId())));
    }
  }

  private void deliverFallbacks(java.util.List<ClientFallbackTracker.Fallback> resolved) {
    for (var fallback : resolved) {
      for (var transferId : fallback.transferIds()) {
        transfers.cancel(fallback.playerUuid(), fallback.generation(), transferId);
      }
      var connection = state.connection(fallback.playerUuid());
      var player = Bukkit.getPlayer(fallback.playerUuid());
      if (connection.isEmpty()
          || !connection.orElseThrow().negotiated()
          || connection.orElseThrow().generation() != fallback.generation()
          || player == null
          || !player.isOnline()) {
        continue;
      }
      try {
        player.sendMessage(Component.text(fallback.fallbackText()));
      } catch (RuntimeException failure) {
        warnRejected(fallback.playerUuid(), "CLIENT_FALLBACK_SEND_FAILED");
      }
    }
  }

  private void send(Player player, byte[] message) {
    player.sendPluginMessage(plugin, ClientPayloadCodec.CHANNEL, message);
  }

  private void warnRejected(UUID playerUuid, String code) {
    var now = Instant.now();
    var previous = lastRejectionWarnings.put(playerUuid, now);
    if (previous == null
        || Duration.between(previous, now).compareTo(Duration.ofSeconds(30)) >= 0) {
      plugin.getLogger().warning("event=client_payload_rejected code=" + code);
    }
  }

  @Override
  public void close() {
    requirePrimaryThread();
    if (!started.compareAndSet(true, false)) {
      return;
    }
    if (expiryTask != null) {
      expiryTask.cancel();
      expiryTask = null;
    }
    HandlerList.unregisterAll(this);
    var messenger = Bukkit.getMessenger();
    messenger.unregisterIncomingPluginChannel(plugin, ClientPayloadCodec.CHANNEL, this);
    messenger.unregisterOutgoingPluginChannel(plugin, ClientPayloadCodec.CHANNEL);
    state.close();
    fallbacks.clear();
    lastRejectionWarnings.clear();
  }

  private static void requirePrimaryThread() {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("Client channel access must use the Paper primary thread");
    }
  }
}
