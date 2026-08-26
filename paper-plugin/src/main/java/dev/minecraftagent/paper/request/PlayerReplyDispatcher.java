package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewType;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.request.AgentRequestService.AuthoritativeViewSource;
import dev.minecraftagent.paper.request.AgentRequestService.EventSink;
import dev.minecraftagent.paper.request.AgentRequestService.MainThreadExecutor;
import dev.minecraftagent.paper.request.AgentRequestService.PlayerReplySink;
import dev.minecraftagent.paper.request.AgentRequestService.PreparedStructuredReply;
import dev.minecraftagent.paper.request.AgentRequestService.StructuredReplySink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers terminal request replies to players.
 *
 * <p>Structured replies are prepared off-thread on the callbacks executor and then sent (or
 * discarded) on the main thread; private chat text is the fallback whenever a structured reply
 * cannot be prepared or sent. Delivery only proceeds while the request's connection binding and
 * admission permit are still current.
 */
final class PlayerReplyDispatcher {
  private final MainThreadExecutor mainThread;
  private final PlayerReplySink replies;
  private final Executor callbacks;
  private final StructuredReplySink structuredReplies;
  private final EventSink events;
  private final AtomicBoolean closed;
  private final AtomicReference<ConnectionBinding> connection;
  private final OperationalGate operationalGate;
  private final AtomicReference<AuthoritativeViewSource> authoritativeViews =
      new AtomicReference<>((requestId, playerId) -> List.of());

  PlayerReplyDispatcher(
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      Executor callbacks,
      StructuredReplySink structuredReplies,
      EventSink events,
      AtomicBoolean closed,
      AtomicReference<ConnectionBinding> connection,
      OperationalGate operationalGate) {
    this.mainThread = Objects.requireNonNull(mainThread);
    this.replies = Objects.requireNonNull(replies);
    this.callbacks = Objects.requireNonNull(callbacks);
    this.structuredReplies = Objects.requireNonNull(structuredReplies);
    this.events = Objects.requireNonNull(events);
    this.closed = Objects.requireNonNull(closed);
    this.connection = Objects.requireNonNull(connection);
    this.operationalGate = Objects.requireNonNull(operationalGate);
  }

  void setAuthoritativeViewSource(AuthoritativeViewSource source) {
    authoritativeViews.set(Objects.requireNonNull(source));
  }

  void dispatch(LiveRequest request, String message) {
    dispatch(request, message, null);
  }

  void dispatch(LiveRequest request, String message, AgentProtocolCodec.Completion completion) {
    if (completion != null) {
      try {
        callbacks.execute(() -> prepareStructuredReply(request, message, completion));
      } catch (RuntimeException error) {
        events.event("CLIENT_STRUCTURED_REPLY_FAILED");
        dispatchPreparedReply(request, message, null);
      }
      return;
    }
    dispatchPreparedReply(request, message, null);
  }

  private void prepareStructuredReply(
      LiveRequest request, String message, AgentProtocolCodec.Completion completion) {
    if (!canDeliver(request)) {
      return;
    }
    PreparedStructuredReply prepared = null;
    try {
      var views = new ArrayList<ClientStructuredView>();
      var authoritative = authoritativeViews.get().consume(request.requestId(), request.playerId());
      if (!authoritative.isEmpty()) {
        authoritative.stream()
            .map(view -> authoritativeBuildView(request, completion, view))
            .forEach(views::add);
      } else {
        completion.structuredViews().stream()
            .filter(view -> view.viewType() != ClientViewType.BUILD_PREVIEW)
            .forEach(views::add);
      }
      if (views.isEmpty()) {
        dispatchPreparedReply(request, message, null);
        return;
      }
      prepared =
          Objects.requireNonNull(
              structuredReplies.prepare(
                  request.playerId(), completion.fallbackText(), List.copyOf(views)));
    } catch (RuntimeException error) {
      events.event("CLIENT_STRUCTURED_REPLY_FAILED");
    }
    dispatchPreparedReply(request, message, prepared);
  }

  private static ClientStructuredView authoritativeBuildView(
      LiveRequest request, AgentProtocolCodec.Completion completion, ClientStructuredView view) {
    if (view.viewType() != ClientViewType.BUILD_PREVIEW
        || !request.requestId().equals(view.requestId())) {
      throw new IllegalArgumentException("Authoritative build preview binding mismatch");
    }
    return new ClientStructuredView(
        view.viewSchemaVersion(),
        view.viewId(),
        view.requestId(),
        view.viewType(),
        view.revision(),
        view.title(),
        completion.fallbackText(),
        view.pinnable(),
        view.content());
  }

  private void dispatchPreparedReply(
      LiveRequest request, String message, PreparedStructuredReply prepared) {
    try {
      mainThread.execute(
          () -> {
            if (!canDeliver(request)) {
              discardPrepared(prepared);
              return;
            }
            if (prepared != null) {
              boolean sent = false;
              try {
                sent = prepared.send();
              } catch (RuntimeException error) {
                events.event("CLIENT_STRUCTURED_REPLY_FAILED");
              }
              if (sent) {
                return;
              }
              discardPrepared(prepared);
            }
            if (canDeliver(request)) {
              replies.send(request.playerId(), message);
            }
          });
    } catch (RuntimeException error) {
      discardPrepared(prepared);
      events.event("PLAYER_REPLY_SCHEDULE_FAILED");
    }
  }

  private void discardPrepared(PreparedStructuredReply prepared) {
    if (prepared == null) {
      return;
    }
    try {
      prepared.discard();
    } catch (RuntimeException error) {
      events.event("CLIENT_STRUCTURED_REPLY_FAILED");
    }
  }

  private boolean canDeliver(LiveRequest request) {
    return !closed.get()
        && connection.get() == request.binding()
        && operationalGate.revalidate(request.permit());
  }
}
