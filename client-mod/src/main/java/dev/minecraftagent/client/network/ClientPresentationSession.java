package dev.minecraftagent.client.network;

import dev.minecraftagent.client.transfer.ViewTransferAcceptance;
import dev.minecraftagent.client.transfer.ViewTransferAccumulator;
import dev.minecraftagent.client.transfer.ViewTransferChunk;
import dev.minecraftagent.client.transfer.ViewTransferDescriptor;
import dev.minecraftagent.client.transfer.ViewTransferEncoding;
import dev.minecraftagent.client.transfer.ViewTransferFailure;
import dev.minecraftagent.client.transfer.ViewTransferMode;
import dev.minecraftagent.client.ui.OverlayController;
import dev.minecraftagent.client.ui.OverlayController.ViewUpdateResult;
import dev.minecraftagent.client.view.BuildPreviewView;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.StructuredViewDecoder;
import dev.minecraftagent.client.view.ViewDecodeException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-connection presentation state. All receive calls must be serialized off the render thread.
 */
public final class ClientPresentationSession {
  private static final int MAX_REPLAY_IDS = 2048;

  private final ClientPayloadCodec codec;
  private final ViewTransferAccumulator transfers;
  private final StructuredViewDecoder viewDecoder;
  private final OverlayController overlay;
  private final ClientThreadDispatcher clientThread;
  private final Consumer<byte[]> outbound;
  private final PresentationActionSink actions;
  private final LinkedHashSet<UUID> messageIds = new LinkedHashSet<>();

  private long generation;
  private boolean accepted;

  public ClientPresentationSession(
      ClientPayloadCodec codec,
      ViewTransferAccumulator transfers,
      StructuredViewDecoder viewDecoder,
      OverlayController overlay,
      ClientThreadDispatcher clientThread,
      Consumer<byte[]> outbound,
      PresentationActionSink actions) {
    this.codec = Objects.requireNonNull(codec);
    this.transfers = Objects.requireNonNull(transfers);
    this.viewDecoder = Objects.requireNonNull(viewDecoder);
    this.overlay = Objects.requireNonNull(overlay);
    this.clientThread = Objects.requireNonNull(clientThread);
    this.outbound = Objects.requireNonNull(outbound);
    this.actions = Objects.requireNonNull(actions);
  }

  public synchronized void connect(ClientHandshakeAdvertisement advertisement) {
    Objects.requireNonNull(advertisement);
    resetConnectionState();
    outbound.accept(codec.encodeHello(UUID.randomUUID(), advertisement));
  }

  public void receive(byte[] frame) {
    ClientServerMessage.UiControl controlToPrepare = null;
    synchronized (this) {
      ClientServerMessage message;
      try {
        message = codec.decodeServer(frame);
      } catch (ClientPayloadException failure) {
        sendError(null, failure.code());
        return;
      }
      if (!remember(message.messageId())) {
        sendError(transferId(message), "CLIENT_MESSAGE_REPLAYED");
        return;
      }

      if (message instanceof ClientServerMessage.ServerHello hello) {
        receiveHello(hello);
        return;
      }
      if (!accepted || messageGeneration(message) != generation) {
        sendError(transferId(message), "CLIENT_GENERATION_STALE");
        return;
      }

      switch (message) {
        case ClientServerMessage.ViewBegin begin -> receiveBegin(begin);
        case ClientServerMessage.ViewChunk chunk -> receiveChunk(chunk);
        case ClientServerMessage.ViewClear clear -> receiveClear(clear);
        case ClientServerMessage.UiControl control -> {
          if (requiresExplicitView(control) && control.viewId() == null) {
            sendError(null, "CLIENT_UI_TARGET_REQUIRED");
            return;
          }
          controlToPrepare = control;
        }
        case ClientServerMessage.ServerHello ignored -> {
          // Handled before the generation gate.
        }
      }
    }
    if (controlToPrepare != null) {
      receiveControl(controlToPrepare);
    }
  }

  public synchronized void expireTransfers() {
    int expired = transfers.expireTimedOut();
    if (expired > 0) {
      sendError(null, "TRANSFER_TIMED_OUT");
    }
  }

  public synchronized void disconnect() {
    resetConnectionState();
    dispatch(
        () -> {
          overlay.disconnect();
          actions.disconnect();
        },
        null);
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized boolean accepted() {
    return accepted;
  }

  private void receiveHello(ClientServerMessage.ServerHello hello) {
    if (hello.generation() < generation) {
      return;
    }
    transfers.disconnect();
    generation = hello.generation();
    accepted = hello.accepted() && "1.0".equals(hello.viewSchemaVersion());
    dispatch(
        () -> {
          overlay.disconnect();
          actions.disconnect();
        },
        null);
  }

  private void receiveClear(ClientServerMessage.ViewClear clear) {
    dispatchForGeneration(
        clear.generation(),
        () -> {
          if (clear.viewId() == null) {
            overlay.dismissTransient();
            actions.disconnect();
          } else {
            overlay.dismiss(clear.viewId());
            actions.clear(clear.viewId());
          }
        },
        null);
  }

  private void receiveBegin(ClientServerMessage.ViewBegin begin) {
    if (begin.mode() == ClientServerMessage.Mode.UPDATE && begin.revision() == 1) {
      sendRejected(begin.transferId(), "INVALID_DESCRIPTOR");
      return;
    }
    ViewTransferDescriptor descriptor =
        new ViewTransferDescriptor(
            begin.generation(),
            begin.transferId(),
            begin.viewId(),
            begin.requestId(),
            begin.revision(),
            begin.mode() == ClientServerMessage.Mode.SHOW
                ? ViewTransferMode.SHOW
                : ViewTransferMode.UPDATE,
            begin.encoding() == ClientServerMessage.Encoding.IDENTITY
                ? ViewTransferEncoding.IDENTITY
                : ViewTransferEncoding.GZIP,
            begin.compressedBytes(),
            begin.uncompressedBytes(),
            begin.chunkCount(),
            begin.contentSha256());
    var result = transfers.begin(descriptor);
    if (!result.accepted()) {
      sendRejected(
          begin.transferId(),
          result.failure().orElse(ViewTransferFailure.INVALID_DESCRIPTOR).name());
    }
  }

  private void receiveChunk(ClientServerMessage.ViewChunk chunk) {
    var result =
        transfers.accept(
            new ViewTransferChunk(
                chunk.generation(),
                chunk.transferId(),
                chunk.index(),
                chunk.byteLength(),
                chunk.sha256(),
                Base64.getEncoder().encodeToString(chunk.data())));
    if (result.status() == ViewTransferAcceptance.Status.REJECTED) {
      sendRejected(
          chunk.transferId(), result.failure().orElse(ViewTransferFailure.UNKNOWN_TRANSFER).name());
      return;
    }
    if (result.status() != ViewTransferAcceptance.Status.COMPLETE) {
      return;
    }

    var verified = result.completedPayload().orElseThrow();
    StructuredView view;
    try {
      view =
          viewDecoder.decode(
              verified.contentBytes(),
              verified.descriptor().viewId(),
              verified.descriptor().requestId(),
              verified.descriptor().revision());
    } catch (ViewDecodeException failure) {
      sendRejected(chunk.transferId(), "VIEW_" + failure.code().name());
      return;
    }
    try {
      if (view.content() instanceof BuildPreviewView && !actions.stage(view)) {
        sendRejected(chunk.transferId(), "VIEW_PREVIEW_PERSIST_FAILED");
        return;
      }
    } catch (RuntimeException failure) {
      sendRejected(chunk.transferId(), "VIEW_PREVIEW_PERSIST_FAILED");
      return;
    }
    var descriptor = verified.descriptor();
    dispatchForGeneration(
        descriptor.generation(),
        () -> show(descriptor.generation(), chunk.transferId(), descriptor.mode(), view),
        chunk.transferId(),
        () -> discardStaged(view));
  }

  private void show(
      long expectedGeneration, UUID transferId, ViewTransferMode mode, StructuredView view) {
    ViewUpdateResult result;
    try {
      result = mode == ViewTransferMode.SHOW ? overlay.show(view) : overlay.update(view);
    } catch (RuntimeException | LinkageError failure) {
      discardStaged(view);
      throw failure;
    }
    switch (result) {
      case ADDED, UPDATED -> {
        boolean committed;
        try {
          committed = actions.commit(view, overlay.openViewIds());
        } catch (RuntimeException | LinkageError failure) {
          committed = false;
        }
        if (!committed) {
          discardStaged(view);
          sendRejected(expectedGeneration, transferId, "VIEW_PREVIEW_PERSIST_FAILED");
        } else {
          sendAck(expectedGeneration, transferId, true, "VIEW_DISPLAYED");
        }
      }
      case IGNORED_STALE -> {
        discardStaged(view);
        sendRejected(expectedGeneration, transferId, "VIEW_STALE");
      }
      case UNKNOWN_VIEW -> {
        discardStaged(view);
        sendRejected(expectedGeneration, transferId, "VIEW_UNKNOWN");
      }
      case CAPACITY_REJECTED -> {
        discardStaged(view);
        sendRejected(expectedGeneration, transferId, "VIEW_CAPACITY_REJECTED");
      }
    }
  }

  private void discardStaged(StructuredView view) {
    try {
      actions.discard(view);
    } catch (RuntimeException | LinkageError ignored) {
      // The connection lifecycle still clears every registered artifact as a final boundary.
    }
  }

  private void receiveControl(ClientServerMessage.UiControl control) {
    PresentationAction preparedAction = null;
    if (requiresExplicitView(control)) {
      try {
        preparedAction =
            Objects.requireNonNull(actions.prepare(control.action(), control.viewId()));
      } catch (RuntimeException failure) {
        sendError(control.generation(), null, "CLIENT_PRESENTATION_FAILED");
        return;
      }
    }
    PresentationAction finalPreparedAction = preparedAction;
    dispatchForGeneration(
        control.generation(),
        () -> {
          switch (control.action()) {
            case PIN -> overlay.pin(control.viewId() == null ? activeViewId() : control.viewId());
            case UNPIN ->
                overlay.unpin(control.viewId() == null ? activeViewId() : control.viewId());
            case CLEAR -> overlay.clear();
            case LITEMATICA_PREVIEW_LOAD,
                LITEMATICA_PREVIEW_REMOVE,
                LITEMATICA_MATERIAL_LIST_OPEN ->
                finalPreparedAction.invoke();
          }
        },
        null);
  }

  private static boolean requiresExplicitView(ClientServerMessage.UiControl control) {
    return control.action() == ClientServerMessage.Action.LITEMATICA_PREVIEW_LOAD
        || control.action() == ClientServerMessage.Action.LITEMATICA_PREVIEW_REMOVE
        || control.action() == ClientServerMessage.Action.LITEMATICA_MATERIAL_LIST_OPEN;
  }

  private UUID activeViewId() {
    return overlay.snapshot().map(snapshot -> snapshot.view().viewId()).orElse(null);
  }

  private void dispatch(Runnable action, UUID transferId) {
    try {
      if (!clientThread.execute(
          () -> {
            try {
              action.run();
            } catch (RuntimeException failure) {
              sendError(transferId, "CLIENT_PRESENTATION_FAILED");
            }
          })) {
        sendError(transferId, "CLIENT_DISPATCH_FAILED");
      }
    } catch (RuntimeException failure) {
      sendError(transferId, "CLIENT_DISPATCH_FAILED");
    }
  }

  private void dispatchForGeneration(long expectedGeneration, Runnable action, UUID transferId) {
    dispatchForGeneration(expectedGeneration, action, transferId, () -> {});
  }

  private void dispatchForGeneration(
      long expectedGeneration, Runnable action, UUID transferId, Runnable rejected) {
    try {
      if (!clientThread.execute(
          () -> {
            synchronized (this) {
              if (!accepted || generation != expectedGeneration) {
                rejected.run();
                return;
              }
            }
            try {
              action.run();
            } catch (RuntimeException failure) {
              sendError(expectedGeneration, transferId, "CLIENT_PRESENTATION_FAILED");
            }
          })) {
        rejected.run();
        sendError(expectedGeneration, transferId, "CLIENT_DISPATCH_FAILED");
      }
    } catch (RuntimeException failure) {
      rejected.run();
      sendError(expectedGeneration, transferId, "CLIENT_DISPATCH_FAILED");
    }
  }

  private void sendRejected(UUID transferId, String code) {
    sendAck(generation, transferId, false, code);
  }

  private void sendRejected(long expectedGeneration, UUID transferId, String code) {
    sendAck(expectedGeneration, transferId, false, code);
  }

  private synchronized void sendAck(
      long expectedGeneration, UUID transferId, boolean displayed, String code) {
    if (!accepted || generation != expectedGeneration || expectedGeneration < 1) {
      return;
    }
    outbound.accept(
        codec.encodeAck(UUID.randomUUID(), expectedGeneration, transferId, displayed, code));
  }

  private synchronized void sendError(UUID transferId, String code) {
    sendError(generation, transferId, code);
  }

  private synchronized void sendError(long expectedGeneration, UUID transferId, String code) {
    if (!accepted || generation != expectedGeneration || expectedGeneration < 1) {
      return;
    }
    outbound.accept(codec.encodeError(UUID.randomUUID(), expectedGeneration, transferId, code));
  }

  private boolean remember(UUID messageId) {
    if (!messageIds.add(messageId)) {
      return false;
    }
    if (messageIds.size() > MAX_REPLAY_IDS) {
      Iterator<UUID> iterator = messageIds.iterator();
      iterator.next();
      iterator.remove();
    }
    return true;
  }

  private void resetConnectionState() {
    accepted = false;
    generation = 0;
    messageIds.clear();
    transfers.disconnect();
  }

  private static long messageGeneration(ClientServerMessage message) {
    return switch (message) {
      case ClientServerMessage.ServerHello hello -> hello.generation();
      case ClientServerMessage.ViewBegin begin -> begin.generation();
      case ClientServerMessage.ViewChunk chunk -> chunk.generation();
      case ClientServerMessage.ViewClear clear -> clear.generation();
      case ClientServerMessage.UiControl control -> control.generation();
    };
  }

  private static UUID transferId(ClientServerMessage message) {
    return switch (message) {
      case ClientServerMessage.ViewBegin begin -> begin.transferId();
      case ClientServerMessage.ViewChunk chunk -> chunk.transferId();
      case ClientServerMessage.ServerHello ignored -> null;
      case ClientServerMessage.ViewClear ignored -> null;
      case ClientServerMessage.UiControl ignored -> null;
    };
  }

  @FunctionalInterface
  public interface ClientThreadDispatcher {
    boolean execute(Runnable action);
  }

  public interface PresentationActionSink {
    PresentationAction prepare(ClientServerMessage.Action action, UUID viewId);

    /**
     * Runs on the protocol worker. It may persist a validated preview but must not load it; its
     * result and the later display ACK are availability signals, never world-write authorization.
     */
    default boolean stage(StructuredView view) {
      return true;
    }

    /** Commits a staged artifact only after the HUD accepted the corresponding view. */
    default boolean commit(StructuredView view, Set<UUID> displayedViewIds) {
      return true;
    }

    /** Rolls back an artifact staged for a view that was not displayed. */
    default void discard(StructuredView view) {}

    default void clear(UUID viewId) {}

    default void disconnect() {}
  }

  @FunctionalInterface
  public interface PresentationAction {
    void invoke();
  }
}
