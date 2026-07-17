package dev.minecraftagent.paper.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minecraftagent.paper.client.ClientCapabilities;
import dev.minecraftagent.paper.client.ClientCapabilitySnapshot;
import dev.minecraftagent.paper.client.ClientFeature;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewType;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Bounds;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Cell;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Pattern;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Position;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Request;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentRequestServiceTest {
  private static final String SERVER_ID = "survival-main";

  @Test
  void requestUsesActualPlayerIdentityAndAllowsOnlyOneLiveRequestPerPlayer() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();

    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submit(playerId, "Where should I build?"));
    assertEquals(
        AgentRequestGateway.Submission.ALREADY_ACTIVE,
        fixture.service.submit(playerId, "A second request"));

    var envelope = object(fixture.connection.sent.getFirst());
    assertEquals("agent.request", envelope.get("type").getAsString());
    assertEquals(SERVER_ID, envelope.get("serverId").getAsString());
    assertEquals(
        playerId.toString(), envelope.getAsJsonObject("payload").get("playerUuid").getAsString());
    assertEquals("general", envelope.getAsJsonObject("payload").get("module").getAsString());
    assertTrue(envelope.getAsJsonObject("payload").get("sessionId").isJsonNull());
    assertEquals(1, fixture.service.activeRequestCount());
  }

  @Test
  void completionIsDeliveredOnlyThroughMainThreadAndLateDuplicateIsIgnored() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");
    var request = fixture.connection.sent.getFirst();
    var completion = completion(request, playerId, "Private answer", sessionId);

    fixture.connection.deliver(completion);
    assertTrue(fixture.replies.isEmpty());
    assertEquals(0, fixture.service.activeRequestCount());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);
    assertEquals(sessionId, fixture.service.currentSession(playerId));

    fixture.connection.deliver(completion(request, playerId, "Late duplicate", sessionId));
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Private answer"), fixture.replies);
  }

  @Test
  void negotiatedCapabilitiesReachRuntimeAndAValidatedViewReplacesChatFallback() {
    var richReplies = new ArrayList<String>();
    var capabilities = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    for (var feature : ClientFeature.values()) {
      capabilities.put(feature, feature == ClientFeature.OVERLAY ? 1 : 0);
    }
    var snapshot =
        new ClientCapabilitySnapshot(true, "1.1", new ClientCapabilities(capabilities), 7L);
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> snapshot,
            (playerId, fallbackText, views) -> {
              return () -> {
                richReplies.add(playerId + ":" + views.getFirst().viewType().wireName());
                return true;
              };
            });
    var playerId = UUID.randomUUID();

    fixture.service.submit(playerId, "Show this in the overlay");
    var request = object(fixture.connection.sent.getFirst());
    var advertised = request.getAsJsonObject("payload").getAsJsonObject("clientCapabilities");
    assertTrue(advertised.get("connected").getAsBoolean());
    assertEquals("1.1", advertised.get("clientProtocolVersion").getAsString());
    assertEquals(1, advertised.getAsJsonObject("features").get("overlay").getAsInt());
    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(),
            playerId,
            "Private rich answer",
            UUID.randomUUID()));

    assertTrue(richReplies.isEmpty());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":text"), richReplies);
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void aFailedClientPublicationFallsBackToTheSamePrivateText() {
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> () -> false);
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Fallback please");
    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(), playerId, "Safe fallback", UUID.randomUUID()));

    fixture.main.drain();
    assertEquals(List.of(playerId + ":Safe fallback"), fixture.replies);
  }

  @Test
  void structuredPreparationUsesCallbacksAndFinalSendUsesMainThread() {
    var callbacks = new QueuedExecutor();
    var phases = new ArrayList<String>();
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> {
              phases.add("prepare");
              return () -> {
                phases.add("send");
                return true;
              };
            },
            callbacks);
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Thread boundaries");

    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(), playerId, "Bound reply", UUID.randomUUID()));

    assertTrue(phases.isEmpty());
    assertTrue(fixture.replies.isEmpty());
    callbacks.drain();
    assertEquals(List.of("prepare"), phases);
    assertTrue(fixture.replies.isEmpty());
    fixture.main.drain();
    assertEquals(List.of("prepare", "send"), phases);
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void paperBuildPreviewOverridesRuntimeTextAndUsesCompletionFallback() {
    var published = new ArrayList<ClientStructuredView>();
    var publishedFallbacks = new ArrayList<String>();
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> {
              publishedFallbacks.add(fallbackText);
              published.addAll(views);
              return () -> true;
            });
    var playerId = UUID.randomUUID();
    fixture.service.submitModule(playerId, AgentModule.BUILD, "Preview one block");
    var requestEnvelope = fixture.connection.sent.getFirst();
    var requestId = UUID.fromString(object(requestEnvelope).get("requestId").getAsString());
    var preview = preview(requestId);
    fixture.service.setAuthoritativeViewSource(
        (boundRequestId, boundPlayerId) ->
            boundRequestId.equals(requestId) && boundPlayerId.equals(playerId)
                ? List.of(preview)
                : List.of());

    var completionFallback = "Preview ready. No blocks were changed.";
    assertFalse(completionFallback.equals(preview.fallbackText()));
    fixture.connection.deliver(
        completionWithTextView(requestEnvelope, playerId, completionFallback, UUID.randomUUID()));
    fixture.main.drain();

    assertEquals(List.of(completionFallback), publishedFallbacks);
    assertEquals(
        List.of(ClientViewType.BUILD_PREVIEW),
        published.stream().map(ClientStructuredView::viewType).toList());
    assertEquals(
        List.of(completionFallback),
        published.stream().map(ClientStructuredView::fallbackText).toList());
    assertEquals(preview.content(), published.getFirst().content());
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void runtimeSuppliedBuildPreviewIsDiscardedInsteadOfBecomingAuthority() {
    var prepared = new ArrayList<ClientStructuredView>();
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> {
              prepared.addAll(views);
              return () -> true;
            });
    var playerId = UUID.randomUUID();
    fixture.service.submitModule(playerId, AgentModule.BUILD, "Do not trust model previews");
    var requestEnvelope = fixture.connection.sent.getFirst();
    var requestId = UUID.fromString(object(requestEnvelope).get("requestId").getAsString());
    var forged = preview(requestId);
    var response = completion(requestEnvelope, playerId, forged.fallbackText(), UUID.randomUUID());
    response.getAsJsonObject("payload").getAsJsonArray("structuredViews").add(forged.toJson());

    fixture.connection.deliver(response);
    fixture.main.drain();

    assertTrue(prepared.isEmpty());
    assertEquals(List.of(playerId + ":" + forged.fallbackText()), fixture.replies);
  }

  @Test
  void structuredPreparationFailureFallsBackOnMainThread() {
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> {
              throw new IllegalStateException("prepare failed");
            });
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Prepare failure");

    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(), playerId, "Safe fallback", UUID.randomUUID()));

    assertTrue(fixture.replies.isEmpty());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Safe fallback"), fixture.replies);
    assertEquals(List.of("CLIENT_STRUCTURED_REPLY_FAILED"), fixture.events);
  }

  @Test
  void structuredSendFailureFallsBackOnMainThread() {
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) ->
                () -> {
                  throw new IllegalStateException("send failed");
                });
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Send failure");

    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(), playerId, "Safe fallback", UUID.randomUUID()));

    assertTrue(fixture.replies.isEmpty());
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Safe fallback"), fixture.replies);
    assertEquals(List.of("CLIENT_STRUCTURED_REPLY_FAILED"), fixture.events);
  }

  @Test
  void preparedStructuredReplyIsDiscardedWhenOfflineWinsBeforeMainThreadDelivery() {
    var callbacks = new QueuedExecutor();
    var prepared = new TrackingPreparedReply(true);
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> prepared,
            callbacks);
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Prepare before Offline");

    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(), playerId, "Stale rich reply", UUID.randomUUID()));
    callbacks.drain();
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.main.drain();

    assertEquals(0, prepared.sendCount);
    assertEquals(1, prepared.discardCount);
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void preparedStructuredReplyIsDiscardedWhenMainThreadSchedulingFails() {
    var prepared = new TrackingPreparedReply(true);
    var fixture =
        new Fixture(
            unavailableTools(),
            ignored -> ClientCapabilitySnapshot.disconnected(),
            (playerId, fallbackText, views) -> prepared);
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Reject main scheduling");
    fixture.main.rejectTasks();

    fixture.connection.deliver(
        completionWithTextView(
            fixture.connection.sent.getFirst(),
            playerId,
            "Unscheduled rich reply",
            UUID.randomUUID()));

    assertEquals(0, prepared.sendCount);
    assertEquals(1, prepared.discardCount);
    assertEquals(List.of("PLAYER_REPLY_SCHEDULE_FAILED"), fixture.events);
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void completionQueuedBeforeOfflineIsRejectedAtTheFinalReplyBoundary() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");

    fixture.connection.deliver(
        completion(
            fixture.connection.sent.getFirst(), playerId, "Stale answer", UUID.randomUUID()));
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.main.drain();

    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void wrongPlayerClosesConnectionWithoutReply() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Hello");

    fixture.connection.deliver(
        completion(
            fixture.connection.sent.getFirst(),
            UUID.randomUUID(),
            "Wrong binding",
            UUID.randomUUID()));
    fixture.main.drain();

    assertFalse(fixture.connection.isOpen());
    assertTrue(fixture.replies.isEmpty());
    assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
  }

  @Test
  void selectedSessionMustMatchContinuationCompletion() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var selectedSession = UUID.randomUUID();
    fixture.service.submit(playerId, "First");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "First answer", selectedSession));
    fixture.service.submit(playerId, "Second");

    fixture.connection.deliver(
        completion(fixture.connection.sent.get(1), playerId, "Wrong session", UUID.randomUUID()));
    fixture.main.drain();

    assertFalse(fixture.connection.isOpen());
    assertEquals(List.of("RUNTIME_APPLICATION_BINDING_REJECTED"), fixture.events);
    assertEquals(List.of(playerId + ":First answer"), fixture.replies);
  }

  @Test
  void nullableCompletionRemainsValidWhenConversationStorageIsDisabled() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Ephemeral question");

    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Ephemeral answer", null));
    fixture.main.drain();

    assertTrue(fixture.connection.isOpen());
    assertEquals(List.of(playerId + ":Ephemeral answer"), fixture.replies);
    assertEquals(null, fixture.service.currentSession(playerId));
  }

  @Test
  void explicitModuleIsOneShotAndKeepsTheSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));

    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submitModule(playerId, AgentModule.RECIPE, "Comparator recipe"));
    var moduleRequest = object(fixture.connection.sent.get(1)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), moduleRequest.get("sessionId").getAsString());
    assertEquals("recipe", moduleRequest.get("module").getAsString());
    fixture.connection.deliver(
        completion(fixture.connection.sent.get(1), playerId, "Recipe answer", sessionId));

    fixture.service.submit(playerId, "Continue normally");
    var nextRequest = object(fixture.connection.sent.get(2)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), nextRequest.get("sessionId").getAsString());
    assertEquals("general", nextRequest.get("module").getAsString());
  }

  @Test
  void resumeLatestIsBoundAndFeedsTheNextGeneralRequest() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();

    assertEquals(AgentRequestGateway.Submission.ACCEPTED, fixture.service.resume(playerId, null));
    assertEquals(
        AgentRequestGateway.Submission.ALREADY_ACTIVE,
        fixture.service.submit(playerId, "Cannot overlap"));
    var resume = object(fixture.connection.sent.getFirst());
    assertEquals("session.resume", resume.get("type").getAsString());
    assertTrue(resume.getAsJsonObject("payload").get("sessionId").isJsonNull());

    fixture.connection.deliver(
        sessionResumed(fixture.connection.sent.getFirst(), playerId, sessionId));
    fixture.main.drain();
    assertEquals(List.of(playerId + ":Resumed AI session " + sessionId + "."), fixture.replies);

    fixture.service.submit(playerId, "Continue");
    var query = object(fixture.connection.sent.get(1)).getAsJsonObject("payload");
    assertEquals(sessionId.toString(), query.get("sessionId").getAsString());
    assertEquals("general", query.get("module").getAsString());
  }

  @Test
  void storageDisabledClearsOnlyTheStaleSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));
    fixture.service.submit(playerId, "Continue");

    fixture.connection.deliver(
        error(
            fixture.connection.sent.get(1),
            playerId,
            "CONVERSATION_STORAGE_DISABLED",
            "Conversation storage is disabled."));
    fixture.service.submit(playerId, "Fresh ephemeral request");

    assertTrue(
        object(fixture.connection.sent.get(2))
            .getAsJsonObject("payload")
            .get("sessionId")
            .isJsonNull());
  }

  @Test
  void runtimeReattachmentRetainsTheSelectedSession() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Start");
    fixture.connection.deliver(
        completion(fixture.connection.sent.getFirst(), playerId, "Started", sessionId));

    fixture.service.detach(fixture.connection);
    var replacement = new FakeConnection();
    fixture.service.attach(replacement, SERVER_ID);
    fixture.service.submit(playerId, "After Runtime restart");

    assertEquals(
        sessionId.toString(),
        object(replacement.sent.getFirst())
            .getAsJsonObject("payload")
            .get("sessionId")
            .getAsString());
  }

  @Test
  void costsQueriesAreSingleFlightAndCallerCancellationDoesNotCancelTheWireRequest() {
    var fixture = new Fixture();

    var cancelledCaller = fixture.service.queryCosts().toCompletableFuture();
    var activeCaller = fixture.service.queryCosts().toCompletableFuture();

    assertEquals(1, fixture.connection.sent.size());
    var request = fixture.connection.sent.getFirst();
    var envelope = object(request);
    assertEquals("management.costs.request", envelope.get("type").getAsString());
    assertEquals(envelope.get("messageId"), envelope.get("requestId"));
    assertEquals(0, envelope.getAsJsonObject("payload").size());
    assertTrue(cancelledCaller.cancel(false));

    fixture.connection.deliver(costsResponse(request));

    var result = activeCaller.join();
    assertEquals(AgentRequestService.CostsQueryStatus.AVAILABLE, result.status());
    assertEquals(3, result.costs().currentDay().admittedRequests());
    assertEquals(18_000, result.costs().currentMonth().costMicroUsd());
    assertEquals(49_980_000, result.costs().budget().remainingMicroUsd());
    assertTrue(cancelledCaller.isCancelled());
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void costsQueryAndPlayerRequestRemainIndependent() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Keep player behavior unchanged");
    var playerRequest = fixture.connection.sent.getFirst();

    var costs = fixture.service.queryCosts().toCompletableFuture();
    var costsRequest = fixture.connection.sent.get(1);
    fixture.connection.deliver(costsResponse(costsRequest));

    assertEquals(AgentRequestService.CostsQueryStatus.AVAILABLE, costs.join().status());
    assertEquals(1, fixture.service.activeRequestCount());
    assertTrue(fixture.replies.isEmpty());

    fixture.connection.deliver(
        completion(playerRequest, playerId, "Unchanged private reply", UUID.randomUUID()));
    fixture.main.drain();

    assertEquals(List.of(playerId + ":Unchanged private reply"), fixture.replies);
  }

  @Test
  void costsTimeoutDropsLateResponseAndAllowsFreshQuery() {
    var fixture = new Fixture();
    var first = fixture.service.queryCosts().toCompletableFuture();
    var firstRequest = fixture.connection.sent.getFirst();

    fixture.timeouts.fire();

    assertEquals(AgentRequestService.CostsQueryStatus.TIMED_OUT, first.join().status());
    assertEquals(1, fixture.connection.sent.size());
    var second = fixture.service.queryCosts().toCompletableFuture();
    assertEquals(2, fixture.connection.sent.size());
    fixture.connection.deliver(costsResponse(firstRequest));
    assertFalse(second.isDone());
    fixture.connection.deliver(costsResponse(fixture.connection.sent.get(1)));

    assertEquals(AgentRequestService.CostsQueryStatus.AVAILABLE, second.join().status());
    assertTrue(fixture.connection.isOpen());
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void costsQueryIsClearedByDetachSocketCloseQuiesceAndServiceClose() {
    var detached = new Fixture();
    var detachedResult = detached.service.queryCosts().toCompletableFuture();
    detached.service.detach(detached.connection);
    assertEquals(AgentRequestService.CostsQueryStatus.UNAVAILABLE, detachedResult.join().status());

    var disconnected = new Fixture();
    var disconnectedResult = disconnected.service.queryCosts().toCompletableFuture();
    disconnected.connection.close();
    assertEquals(
        AgentRequestService.CostsQueryStatus.UNAVAILABLE, disconnectedResult.join().status());

    var quiesced = new Fixture();
    var quiescedResult = quiesced.service.queryCosts().toCompletableFuture();
    quiesced.gate.transitionTo(AgentState.STOPPING);
    quiesced.service.quiesce(quiesced.gate.epoch(), OfflineReason.MANUAL);
    assertEquals(AgentRequestService.CostsQueryStatus.UNAVAILABLE, quiescedResult.join().status());

    var closed = new Fixture();
    var closedResult = closed.service.queryCosts().toCompletableFuture();
    closed.service.close();
    assertEquals(AgentRequestService.CostsQueryStatus.UNAVAILABLE, closedResult.join().status());
    assertEquals(
        AgentRequestService.CostsQueryStatus.UNAVAILABLE,
        closed.service.queryCosts().toCompletableFuture().join().status());
    assertTrue(closed.replies.isEmpty());
  }

  @Test
  void costsSendFailuresAreContainedWithoutPlayerMessages() {
    var asynchronous = new Fixture();
    asynchronous.connection.nextSend =
        CompletableFuture.failedFuture(new IllegalStateException("send failed"));

    var asyncResult = asynchronous.service.queryCosts().toCompletableFuture().join();

    assertEquals(AgentRequestService.CostsQueryStatus.FAILED, asyncResult.status());
    assertEquals(List.of("RUNTIME_MANAGEMENT_COSTS_SEND_FAILED"), asynchronous.events);
    assertFalse(asynchronous.connection.isOpen());
    assertTrue(asynchronous.replies.isEmpty());

    var synchronous = new Fixture();
    synchronous.connection.throwOnSend = true;

    var syncResult = synchronous.service.queryCosts().toCompletableFuture().join();

    assertEquals(AgentRequestService.CostsQueryStatus.FAILED, syncResult.status());
    assertEquals(List.of("RUNTIME_MANAGEMENT_COSTS_SEND_FAILED"), synchronous.events);
    assertFalse(synchronous.connection.isOpen());
    assertTrue(synchronous.replies.isEmpty());
  }

  @Test
  void malformedCostsResponseFailsQueryAndRejectsProtocolConnection() {
    var fixture = new Fixture();
    var result = fixture.service.queryCosts().toCompletableFuture();
    var malformed = costsResponse(fixture.connection.sent.getFirst());
    malformed.getAsJsonObject("payload").addProperty("unknown", true);

    fixture.connection.deliver(malformed);

    assertEquals(AgentRequestService.CostsQueryStatus.FAILED, result.join().status());
    assertEquals(List.of("RUNTIME_APPLICATION_MESSAGE_REJECTED"), fixture.events);
    assertFalse(fixture.connection.isOpen());
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void timeoutCancelsRuntimeReleasesPlayerAndDropsLateCompletion() {
    var fixture = new Fixture();
    var playerId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow request");
    var request = fixture.connection.sent.getFirst();

    fixture.timeouts.fire();
    assertEquals(0, fixture.service.activeRequestCount());
    assertEquals("agent.cancel", object(fixture.connection.sent.get(1)).get("type").getAsString());
    assertEquals(
        "PAPER_TIMEOUT",
        object(fixture.connection.sent.get(1))
            .getAsJsonObject("payload")
            .get("reason")
            .getAsString());
    fixture.connection.deliver(completion(request, playerId, "Too late", null));
    fixture.main.drain();

    assertEquals(List.of(playerId + ":AI request timed out."), fixture.replies);
    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        fixture.service.submit(playerId, "A fresh request"));
  }

  @Test
  void offlineAndPlayerQuitCancelWithoutLeavingStaleDelivery() {
    var fixture = new Fixture();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    fixture.service.submit(first, "First");
    fixture.service.submit(second, "Second");

    fixture.service.cancelPlayer(first);
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.service.quiesce(fixture.gate.epoch(), OfflineReason.MANUAL);

    assertEquals(0, fixture.service.activeRequestCount());
    assertEquals(
        List.of("PLAYER_DISCONNECTED", "AGENT_OFFLINE"),
        fixture.connection.sent.stream()
            .skip(2)
            .map(AgentRequestServiceTest::object)
            .map(envelope -> envelope.getAsJsonObject("payload").get("reason").getAsString())
            .toList());
    fixture.main.drain();
    assertTrue(fixture.replies.isEmpty());
  }

  @Test
  void sendFailureHasOneSafeReplyAndSynchronousThrowIsLeftToCommandBoundary() {
    var asynchronous = new Fixture();
    var playerId = UUID.randomUUID();
    asynchronous.connection.nextSend = CompletableFuture.failedFuture(new IllegalStateException());
    assertEquals(
        AgentRequestGateway.Submission.ACCEPTED,
        asynchronous.service.submit(playerId, "Will fail asynchronously"));
    asynchronous.main.drain();
    assertEquals(List.of(playerId + ":AI unavailable. Try again later."), asynchronous.replies);
    assertEquals(0, asynchronous.service.activeRequestCount());

    var synchronous = new Fixture();
    synchronous.connection.throwOnSend = true;
    assertEquals(
        AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        synchronous.service.submit(playerId, "Will throw synchronously"));
    synchronous.main.drain();
    assertTrue(synchronous.replies.isEmpty());
    assertEquals(0, synchronous.service.activeRequestCount());
  }

  @Test
  void invalidTextAndGlobalCapacityAreBounded() {
    var fixture = new Fixture();
    assertEquals(
        AgentRequestGateway.Submission.INVALID_MESSAGE,
        fixture.service.submit(UUID.randomUUID(), "\ud800"));
    for (var index = 0; index < 64; index++) {
      assertEquals(
          AgentRequestGateway.Submission.ACCEPTED,
          fixture.service.submit(UUID.randomUUID(), "request " + index));
    }
    assertEquals(
        AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        fixture.service.submit(UUID.randomUUID(), "one too many"));
  }

  @Test
  void toolCallIsAnIntermediateBoundRoundAndCompletionMayFollowImmediately() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Where am I?");
    var request = fixture.connection.sent.getFirst();

    fixture.connection.deliver(
        toolCall(
            request, playerId, sessionId, "general", "player.context.read", new JsonObject(), 0));
    assertEquals(1, tools.calls.size());
    assertEquals(1, fixture.connection.sent.size());

    var result = new JsonObject();
    result.addProperty("online", true);
    tools.complete(ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, result));
    assertEquals("tool.result", object(fixture.connection.sent.get(1)).get("type").getAsString());

    fixture.connection.deliver(completion(request, playerId, "You are online.", sessionId));
    fixture.main.drain();
    assertTrue(fixture.connection.isOpen());
    assertEquals(List.of(playerId + ":You are online."), fixture.replies);
  }

  @Test
  void unknownToolIsRejectedWithoutExecutionAndConsumesTheRound() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Run something");
    var request = fixture.connection.sent.getFirst();

    fixture.connection.deliver(
        toolCall(
            request,
            playerId,
            sessionId,
            "general",
            "server.command.execute",
            new JsonObject(),
            0));

    assertTrue(tools.calls.isEmpty());
    var result = object(fixture.connection.sent.get(1));
    assertEquals("tool.result", result.get("type").getAsString());
    assertEquals("rejected", result.getAsJsonObject("payload").get("status").getAsString());
    assertEquals(
        "TOOL_UNKNOWN",
        result.getAsJsonObject("payload").getAsJsonObject("error").get("code").getAsString());
  }

  @Test
  void timeoutDropsAQueuedToolResultAndItsLateCompletion() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow tool");
    var request = fixture.connection.sent.getFirst();
    fixture.connection.deliver(
        toolCall(request, playerId, sessionId, "general", "server.info.read", new JsonObject(), 0));

    fixture.timeouts.fire();
    tools.complete(ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, new JsonObject()));

    assertEquals(
        List.of("agent.request", "agent.cancel"),
        fixture.connection.sent.stream()
            .map(AgentRequestServiceTest::object)
            .map(message -> message.get("type").getAsString())
            .toList());
  }

  @Test
  void offlineCancelsAnActiveToolExecutionAndDropsItsLateCompletion() {
    var tools = new ManualTools();
    var fixture = new Fixture(tools);
    var playerId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    fixture.service.submit(playerId, "Slow tool");
    var request = fixture.connection.sent.getFirst();
    fixture.connection.deliver(
        toolCall(request, playerId, sessionId, "general", "server.info.read", new JsonObject(), 0));

    assertFalse(tools.pending.isCancelled());
    fixture.gate.transitionTo(AgentState.STOPPING);
    fixture.service.quiesce(fixture.gate.epoch(), OfflineReason.MANUAL);

    assertTrue(tools.pending.isCancelled());
    assertEquals(
        List.of("agent.request", "agent.cancel"),
        fixture.connection.sent.stream()
            .map(AgentRequestServiceTest::object)
            .map(message -> message.get("type").getAsString())
            .toList());
    assertFalse(
        tools.pending.complete(
            ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, new JsonObject())));
  }

  private static JsonObject completion(
      String requestEnvelope, UUID playerId, String fallbackText, UUID sessionId) {
    var request = object(requestEnvelope);
    var payload = new JsonObject();
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("fallbackText", fallbackText);
    payload.add("structuredViews", new com.google.gson.JsonArray());

    var response = new JsonObject();
    response.addProperty("protocolVersion", "1.0");
    response.addProperty("messageId", UUID.randomUUID().toString());
    response.addProperty("requestId", request.get("requestId").getAsString());
    response.addProperty("serverId", SERVER_ID);
    response.addProperty("type", "agent.complete");
    response.addProperty("timestamp", Instant.now().toString());
    response.addProperty(
        "nonce",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    response.add("payload", payload);
    return response;
  }

  private static JsonObject costsResponse(String requestEnvelope) {
    var currentDay = new JsonObject();
    currentDay.addProperty("period", "2026-07-14");
    currentDay.addProperty("admittedRequests", 3);
    currentDay.addProperty("providerCalls", 5);
    currentDay.addProperty("reportedProviderCalls", 4);
    currentDay.addProperty("estimatedProviderCalls", 1);
    currentDay.addProperty("inputTokens", 1200);
    currentDay.addProperty("outputTokens", 300);
    currentDay.addProperty("costMicroUsd", 1800);

    var currentMonth = new JsonObject();
    currentMonth.addProperty("period", "2026-07");
    currentMonth.addProperty("admittedRequests", 30);
    currentMonth.addProperty("providerCalls", 42);
    currentMonth.addProperty("reportedProviderCalls", 40);
    currentMonth.addProperty("estimatedProviderCalls", 2);
    currentMonth.addProperty("inputTokens", 12_000);
    currentMonth.addProperty("outputTokens", 3000);
    currentMonth.addProperty("costMicroUsd", 18_000);

    var budget = new JsonObject();
    budget.addProperty("month", "2026-07");
    budget.addProperty("limitMicroUsd", 50_000_000);
    budget.addProperty("settledMicroUsd", 18_000);
    budget.addProperty("activeReservationsMicroUsd", 2000);
    budget.addProperty("remainingMicroUsd", 49_980_000);
    budget.addProperty("exhausted", false);

    var payload = new JsonObject();
    payload.add("currentDay", currentDay);
    payload.add("currentMonth", currentMonth);
    payload.add("budget", budget);
    return response(requestEnvelope, "management.costs.response", payload);
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

  private static JsonObject completionWithTextView(
      String requestEnvelope, UUID playerId, String fallbackText, UUID sessionId) {
    var completion = completion(requestEnvelope, playerId, fallbackText, sessionId);
    var requestId = object(requestEnvelope).get("requestId").getAsString();
    var view = new JsonObject();
    view.addProperty("viewSchemaVersion", "1.0");
    view.addProperty("viewId", UUID.randomUUID().toString());
    view.addProperty("requestId", requestId);
    view.addProperty("viewType", "text");
    view.addProperty("revision", 1);
    view.addProperty("title", "Agent response");
    view.addProperty("fallbackText", fallbackText);
    view.addProperty("pinnable", true);
    var content = new JsonObject();
    content.addProperty("text", fallbackText);
    view.add("content", content);
    completion.getAsJsonObject("payload").getAsJsonArray("structuredViews").add(view);
    return completion;
  }

  private static JsonObject toolCall(
      String requestEnvelope,
      UUID playerId,
      UUID sessionId,
      String module,
      String tool,
      JsonObject arguments,
      int sequence) {
    var payload = new JsonObject();
    payload.addProperty("toolCallId", UUID.randomUUID().toString());
    payload.addProperty("sessionId", sessionId.toString());
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("module", module);
    payload.addProperty("tool", tool);
    payload.add("arguments", arguments);
    payload.addProperty("sequence", sequence);
    return response(requestEnvelope, "tool.call", payload);
  }

  private static JsonObject sessionResumed(String requestEnvelope, UUID playerId, UUID sessionId) {
    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("sessionId", sessionId.toString());
    return response(requestEnvelope, "session.resumed", payload);
  }

  private static JsonObject error(
      String requestEnvelope, UUID playerId, String code, String fallbackText) {
    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerId.toString());
    payload.addProperty("code", code);
    payload.addProperty("fallbackText", fallbackText);
    payload.addProperty("retryable", false);
    return response(requestEnvelope, "agent.error", payload);
  }

  private static JsonObject response(String requestEnvelope, String type, JsonObject payload) {
    var request = object(requestEnvelope);
    var response = new JsonObject();
    response.addProperty("protocolVersion", "1.0");
    response.addProperty("messageId", UUID.randomUUID().toString());
    response.addProperty("requestId", request.get("requestId").getAsString());
    response.addProperty("serverId", SERVER_ID);
    response.addProperty("type", type);
    response.addProperty("timestamp", Instant.now().toString());
    response.addProperty(
        "nonce",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    response.add("payload", payload);
    return response;
  }

  private static JsonObject object(String source) {
    return JsonParser.parseString(source).getAsJsonObject();
  }

  private static final class Fixture {
    private final OperationalGate gate = new OperationalGate(AgentState.ONLINE);
    private final ManualTimeouts timeouts = new ManualTimeouts();
    private final QueuedMain main = new QueuedMain();
    private final List<String> replies = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final FakeConnection connection = new FakeConnection();
    private final AgentRequestService service;

    private Fixture() {
      this(unavailableTools());
    }

    private Fixture(ReadToolExecutor tools) {
      this(
          tools,
          ignored -> ClientCapabilitySnapshot.disconnected(),
          (playerId, fallbackText, views) -> () -> false);
    }

    private Fixture(
        ReadToolExecutor tools,
        AgentRequestService.ClientCapabilitySource capabilities,
        AgentRequestService.StructuredReplySink structuredReplies) {
      this(tools, capabilities, structuredReplies, Runnable::run);
    }

    private Fixture(
        ReadToolExecutor tools,
        AgentRequestService.ClientCapabilitySource capabilities,
        AgentRequestService.StructuredReplySink structuredReplies,
        Executor callbacks) {
      service =
          new AgentRequestService(
              gate,
              Duration.ofSeconds(30),
              timeouts,
              main::execute,
              (playerId, message) -> replies.add(playerId + ":" + message),
              events::add,
              new ReadToolRegistry(),
              tools,
              callbacks,
              capabilities,
              structuredReplies);
      service.attach(connection, SERVER_ID);
    }
  }

  private static ReadToolExecutor unavailableTools() {
    return call ->
        CompletableFuture.completedFuture(
            ReadToolResult.failed(
                ReadToolResult.Source.PAPER_POLICY,
                "TOOL_EXECUTION_UNAVAILABLE",
                "Unavailable.",
                true));
  }

  private static final class ManualTools implements ReadToolExecutor {
    private final List<ReadToolCall> calls = new ArrayList<>();
    private CompletableFuture<ReadToolResult> pending;

    @Override
    public CompletionStage<ReadToolResult> execute(ReadToolCall call) {
      calls.add(call);
      pending = new CompletableFuture<>();
      return pending;
    }

    private void complete(ReadToolResult result) {
      pending.complete(result);
    }
  }

  private static final class ManualTimeouts implements AgentRequestService.TimeoutScheduler {
    private final List<ScheduledTask> tasks = new ArrayList<>();

    @Override
    public AgentRequestService.Cancellable schedule(Duration delay, Runnable task) {
      var scheduled = new ScheduledTask(task);
      tasks.add(scheduled);
      return scheduled::cancel;
    }

    private void fire() {
      for (var task : List.copyOf(tasks)) {
        task.run();
      }
    }
  }

  private static final class ScheduledTask {
    private final Runnable task;
    private boolean cancelled;

    private ScheduledTask(Runnable task) {
      this.task = task;
    }

    private void cancel() {
      cancelled = true;
    }

    private void run() {
      if (!cancelled) {
        task.run();
      }
    }
  }

  private static final class QueuedMain {
    private final Queue<Runnable> tasks = new java.util.ArrayDeque<>();
    private boolean reject;

    private void execute(Runnable task) {
      if (reject) {
        throw new IllegalStateException("main thread rejected task");
      }
      tasks.add(task);
    }

    private void rejectTasks() {
      reject = true;
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
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

  private static final class QueuedExecutor implements Executor {
    private final Queue<Runnable> tasks = new java.util.ArrayDeque<>();

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
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final List<String> sent = new ArrayList<>();
    private boolean open = true;
    private boolean throwOnSend;
    private CompletionStage<Void> nextSend = CompletableFuture.completedFuture(null);
    private Consumer<String> handler = ignored -> {};

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return closed;
    }

    @Override
    public CompletionStage<Void> sendApplication(String message) {
      if (throwOnSend) {
        throw new IllegalStateException("send failed");
      }
      sent.add(message);
      var result = nextSend;
      nextSend = CompletableFuture.completedFuture(null);
      return result;
    }

    @Override
    public void setApplicationHandler(Consumer<String> handler) {
      this.handler = handler;
    }

    @Override
    public void close() {
      open = false;
      closed.complete(null);
    }

    private void deliver(JsonObject message) {
      handler.accept(message.toString());
    }
  }
}
