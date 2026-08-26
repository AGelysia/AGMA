package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewSchemaRegistry;
import dev.minecraftagent.paper.client.ClientViewType;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Bounds;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Cell;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Pattern;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Position;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Request;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlayerReplyDispatcherTest {
  private static final String SERVER_ID = "survival-main";

  @Test
  void structuredCompletionsPrepareOnCallbacksAndSendOnMainThread() {
    var fixture = new Fixture(true);
    var completion =
        completion(
            fixture.request, "Bound reply", List.of(textView(fixture.request, "Bound reply")));

    fixture.dispatcher.dispatch(fixture.request, "Bound reply", completion);

    assertTrue(fixture.preparedViews.isEmpty());
    fixture.callbacks.drain();
    assertEquals(1, fixture.preparedViews.size());
    assertTrue(fixture.replies.isEmpty());
    fixture.main.drain();
    assertEquals(1, fixture.prepared.sendCount);
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void dispatchesWithoutCompletionBypassStructuredPreparation() {
    var fixture = new Fixture(true);

    fixture.dispatcher.dispatch(fixture.request, "Plain timeout text");
    fixture.callbacks.drain();

    assertTrue(fixture.preparedViews.isEmpty());
    fixture.main.drain();
    assertEquals(List.of(fixture.request.playerId() + ":Plain timeout text"), fixture.replies);
  }

  @Test
  void failedStructuredSendsFallBackToChatText() {
    var fixture = new Fixture(false);
    var completion =
        completion(fixture.request, "Falls back", List.of(textView(fixture.request, "Falls back")));

    fixture.dispatcher.dispatch(fixture.request, "Falls back", completion);
    fixture.callbacks.drain();
    fixture.main.drain();

    assertEquals(1, fixture.prepared.sendCount);
    assertEquals(1, fixture.prepared.discardCount);
    assertEquals(List.of(fixture.request.playerId() + ":Falls back"), fixture.replies);
  }

  @Test
  void authoritativeBuildPreviewsRebindFallbackTextAndRejectForeignRequests() {
    var fixture = new Fixture(true);
    var preview = preview(fixture.request.requestId());
    fixture.dispatcher.setAuthoritativeViewSource(
        (requestId, playerId) ->
            requestId.equals(fixture.request.requestId()) ? List.of(preview) : List.of());

    fixture.dispatcher.dispatch(
        fixture.request,
        "Completion fallback",
        completion(fixture.request, "Completion fallback", List.of()));
    fixture.callbacks.drain();

    assertEquals(List.of("Completion fallback"), fixture.preparedFallbacks);
    assertEquals(
        List.of("Completion fallback"),
        fixture.preparedViews.stream().map(ClientStructuredView::fallbackText).toList());
    assertEquals(
        List.of(ClientViewType.BUILD_PREVIEW),
        fixture.preparedViews.stream().map(ClientStructuredView::viewType).toList());

    var foreign = preview(UUID.randomUUID());
    fixture.dispatcher.setAuthoritativeViewSource((requestId, playerId) -> List.of(foreign));
    fixture.dispatcher.dispatch(
        fixture.request, "Second answer", completion(fixture.request, "Second answer", List.of()));
    fixture.callbacks.drain();
    fixture.main.drain();

    assertEquals(List.of("CLIENT_STRUCTURED_REPLY_FAILED"), fixture.events);
    assertEquals(List.of(fixture.request.playerId() + ":Second answer"), fixture.replies);
  }

  @Test
  void offlineBeforeMainThreadDiscardsThePreparedReply() {
    var fixture = new Fixture(true);
    var completion =
        completion(fixture.request, "Rich reply", List.of(textView(fixture.request, "Rich reply")));

    fixture.dispatcher.dispatch(fixture.request, "Rich reply", completion);
    fixture.callbacks.drain();
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.main.drain();

    assertEquals(0, fixture.prepared.sendCount);
    assertEquals(1, fixture.prepared.discardCount);
    assertTrue(fixture.replies.isEmpty());
  }

  private static AgentProtocolCodec.Completion completion(
      LiveRequest request, String fallbackText, List<ClientStructuredView> views) {
    return new AgentProtocolCodec.Completion(
        UUID.randomUUID(),
        request.requestId(),
        UUID.randomUUID(),
        request.playerId(),
        fallbackText,
        views);
  }

  private static ClientStructuredView textView(LiveRequest request, String fallbackText) {
    var content = new JsonObject();
    content.addProperty("text", fallbackText);
    return new ClientStructuredView(
        ClientViewSchemaRegistry.VIEW_SCHEMA_V1,
        UUID.randomUUID(),
        request.requestId(),
        ClientViewType.TEXT,
        1,
        "Agent response",
        fallbackText,
        true,
        content);
  }

  private static ClientStructuredView preview(UUID requestId) {
    var position = new Position(0, 64, 0);
    var bounds = new Bounds(position, position);
    var request =
        new Request(
            requestId,
            SERVER_ID,
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
            "minecraft:overworld",
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
            1,
            "create",
            bounds,
            position,
            Pattern.SOLID,
            "minecraft:stone",
            0,
            "NONE");
    return new BuildPreviewArtifactFactory()
        .create(request, List.of(new Cell(position, "minecraft:air")))
        .view();
  }

  private static final class Fixture {
    private final OperationalGate gate = new OperationalGate(AgentState.ONLINE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectionBinding binding =
        new ConnectionBinding(new FakeConnection(), new AgentProtocolCodec(SERVER_ID));
    private final AtomicReference<ConnectionBinding> connection = new AtomicReference<>(binding);
    private final QueuedMain main = new QueuedMain();
    private final QueuedExecutor callbacks = new QueuedExecutor();
    private final List<String> replies = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<ClientStructuredView> preparedViews = new ArrayList<>();
    private final List<String> preparedFallbacks = new ArrayList<>();
    private final PlayerReplyDispatcher dispatcher;
    private final LiveRequest request;
    private final TrackingPreparedReply prepared;

    private Fixture(boolean sendResult) {
      prepared = new TrackingPreparedReply(sendResult);
      dispatcher =
          new PlayerReplyDispatcher(
              main::execute,
              (playerId, message) -> replies.add(playerId + ":" + message),
              callbacks,
              (playerId, fallbackText, views) -> {
                preparedFallbacks.add(fallbackText);
                preparedViews.addAll(views);
                return prepared;
              },
              events::add,
              closed,
              connection,
              gate);
      request =
          new LiveRequest(
              UUID.randomUUID(),
              UUID.randomUUID(),
              gate.tryAcquire().orElseThrow(),
              binding,
              LiveRequest.Operation.QUERY,
              null,
              AgentModule.GENERAL);
    }
  }

  private static final class TrackingPreparedReply
      implements AgentRequestService.PreparedStructuredReply {
    private final boolean sendResult;
    private int sendCount;
    private int discardCount;

    private TrackingPreparedReply(boolean sendResult) {
      this.sendResult = sendResult;
    }

    @Override
    public boolean send() {
      sendCount++;
      return sendResult;
    }

    @Override
    public void discard() {
      discardCount++;
    }
  }

  private static final class QueuedMain {
    private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

    private void execute(Runnable task) {
      tasks.add(task);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
    }
  }

  private static final class QueuedExecutor implements java.util.concurrent.Executor {
    private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
    }
  }

  private static final class FakeConnection implements AuthenticatedRuntimeConnection {
    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return new CompletableFuture<>();
    }

    @Override
    public void close() {}
  }
}
