package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.client.ClientCapabilitySnapshot;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.CancelReason;
import dev.minecraftagent.paper.tool.ReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admits player Agent requests, drives them across the attached Runtime application connection, and
 * cancels them on timeout, player quit, and Offline transitions.
 *
 * <p>This class owns request admission, inbound message routing, session bookkeeping, and
 * cancellation. Reply delivery, management costs queries, and read-tool rounds are delegated to
 * {@link PlayerReplyDispatcher}, {@link ManagementCostsCoordinator}, and {@link
 * ReadToolRoundCoordinator}, which share this service's lock and connection state so their behavior
 * stays mutually consistent.
 */
public final class AgentRequestService
    implements AgentRequestGateway,
        RuntimeApplicationLifecycle,
        OfflineCleanup.Control,
        AutoCloseable {
  @FunctionalInterface
  public interface MainThreadExecutor {
    void execute(Runnable task);
  }

  @FunctionalInterface
  public interface PlayerReplySink {
    void send(UUID playerId, String message);
  }

  @FunctionalInterface
  public interface EventSink {
    void event(String code);
  }

  @FunctionalInterface
  public interface ClientCapabilitySource {
    ClientCapabilitySnapshot snapshot(UUID playerId);
  }

  @FunctionalInterface
  public interface StructuredReplySink {
    PreparedStructuredReply prepare(
        UUID playerId, String fallbackText, List<ClientStructuredView> views);
  }

  @FunctionalInterface
  public interface AuthoritativeViewSource {
    List<ClientStructuredView> consume(UUID requestId, UUID playerId);
  }

  @FunctionalInterface
  public interface PreparedStructuredReply {
    boolean send();

    /** Releases unsent preparation. Implementations must be idempotent and safe off-thread. */
    default void discard() {}
  }

  public enum CostsQueryStatus {
    AVAILABLE,
    UNAVAILABLE,
    TIMED_OUT,
    FAILED
  }

  public record CostsQueryResult(
      CostsQueryStatus status, AgentProtocolCodec.ManagementCosts costs) {
    public CostsQueryResult {
      Objects.requireNonNull(status);
      if ((status == CostsQueryStatus.AVAILABLE) != (costs != null)) {
        throw new IllegalArgumentException("Invalid costs query result");
      }
    }

    public static CostsQueryResult available(AgentProtocolCodec.ManagementCosts costs) {
      return new CostsQueryResult(CostsQueryStatus.AVAILABLE, Objects.requireNonNull(costs));
    }

    public static CostsQueryResult unavailable() {
      return new CostsQueryResult(CostsQueryStatus.UNAVAILABLE, null);
    }

    public static CostsQueryResult timedOut() {
      return new CostsQueryResult(CostsQueryStatus.TIMED_OUT, null);
    }

    public static CostsQueryResult failed() {
      return new CostsQueryResult(CostsQueryStatus.FAILED, null);
    }
  }

  private static final String TIMEOUT_MESSAGE = "AI request timed out.";
  private static final String UNAVAILABLE_MESSAGE = "AI unavailable. Try again later.";
  private static final String SESSION_RESUMED_PREFIX = "Resumed AI session ";
  private static final int MAX_ACTIVE_REQUESTS = 64;

  private final Object lock = new Object();
  private final OperationalGate operationalGate;
  private final Duration requestTimeout;
  private final TimeoutScheduler timeoutScheduler;
  private final ClientCapabilitySource clientCapabilities;
  private final EventSink events;
  private final PlayerReplyDispatcher playerReplies;
  private final ManagementCostsCoordinator managementCosts;
  private final ReadToolRoundCoordinator toolRounds;
  private final AtomicReference<ConnectionBinding> connection = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Map<UUID, LiveRequest> requestsById = new HashMap<>();
  private final Map<UUID, LiveRequest> requestsByPlayer = new HashMap<>();
  private final Map<UUID, UUID> currentSessionsByPlayer = new HashMap<>();

  public AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      ScheduledExecutorService scheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events) {
    this(
        operationalGate,
        requestTimeout,
        scheduler,
        mainThread,
        replies,
        events,
        new ReadToolRegistry(),
        call ->
            CompletableFuture.completedFuture(
                ReadToolResult.failed(
                    ReadToolResult.Source.PAPER_POLICY,
                    "TOOL_EXECUTION_UNAVAILABLE",
                    "Read tools are unavailable.",
                    true)),
        scheduler);
  }

  public AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      ScheduledExecutorService scheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks) {
    this(
        operationalGate,
        requestTimeout,
        (delay, task) -> {
          var future = scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
          return () -> future.cancel(false);
        },
        mainThread,
        replies,
        events,
        toolRegistry,
        toolExecutor,
        callbacks,
        ignored -> ClientCapabilitySnapshot.disconnected(),
        (playerId, fallbackText, views) -> () -> false);
  }

  public AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      ScheduledExecutorService scheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks,
      ClientCapabilitySource clientCapabilities) {
    this(
        operationalGate,
        requestTimeout,
        (delay, task) -> {
          var future = scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
          return () -> future.cancel(false);
        },
        mainThread,
        replies,
        events,
        toolRegistry,
        toolExecutor,
        callbacks,
        clientCapabilities,
        (playerId, fallbackText, views) -> () -> false);
  }

  public AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      ScheduledExecutorService scheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks,
      ClientCapabilitySource clientCapabilities,
      StructuredReplySink structuredReplies) {
    this(
        operationalGate,
        requestTimeout,
        (delay, task) -> {
          var future = scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
          return () -> future.cancel(false);
        },
        mainThread,
        replies,
        events,
        toolRegistry,
        toolExecutor,
        callbacks,
        clientCapabilities,
        structuredReplies);
  }

  AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      TimeoutScheduler timeoutScheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events) {
    this(
        operationalGate,
        requestTimeout,
        timeoutScheduler,
        mainThread,
        replies,
        events,
        new ReadToolRegistry(),
        call ->
            CompletableFuture.completedFuture(
                ReadToolResult.failed(
                    ReadToolResult.Source.PAPER_POLICY,
                    "TOOL_EXECUTION_UNAVAILABLE",
                    "Read tools are unavailable.",
                    true)),
        Runnable::run,
        ignored -> ClientCapabilitySnapshot.disconnected(),
        (playerId, fallbackText, views) -> () -> false);
  }

  AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      TimeoutScheduler timeoutScheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks) {
    this(
        operationalGate,
        requestTimeout,
        timeoutScheduler,
        mainThread,
        replies,
        events,
        toolRegistry,
        toolExecutor,
        callbacks,
        ignored -> ClientCapabilitySnapshot.disconnected(),
        (playerId, fallbackText, views) -> () -> false);
  }

  AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      TimeoutScheduler timeoutScheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks,
      ClientCapabilitySource clientCapabilities) {
    this(
        operationalGate,
        requestTimeout,
        timeoutScheduler,
        mainThread,
        replies,
        events,
        toolRegistry,
        toolExecutor,
        callbacks,
        clientCapabilities,
        (playerId, fallbackText, views) -> () -> false);
  }

  AgentRequestService(
      OperationalGate operationalGate,
      Duration requestTimeout,
      TimeoutScheduler timeoutScheduler,
      MainThreadExecutor mainThread,
      PlayerReplySink replies,
      EventSink events,
      ReadToolRegistry toolRegistry,
      ReadToolExecutor toolExecutor,
      Executor callbacks,
      ClientCapabilitySource clientCapabilities,
      StructuredReplySink structuredReplies) {
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.requestTimeout = Objects.requireNonNull(requestTimeout);
    this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
    this.events = Objects.requireNonNull(events);
    this.playerReplies =
        new PlayerReplyDispatcher(
            mainThread,
            replies,
            callbacks,
            structuredReplies,
            events,
            closed,
            connection,
            operationalGate);
    this.managementCosts =
        new ManagementCostsCoordinator(
            lock, operationalGate, connection, closed, timeoutScheduler, events);
    this.toolRounds =
        new ReadToolRoundCoordinator(
            lock,
            requestsById,
            connection,
            operationalGate,
            toolRegistry,
            toolExecutor,
            callbacks,
            events,
            this::sendFailed,
            this::rejectBinding);
    this.clientCapabilities = Objects.requireNonNull(clientCapabilities);
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("Request timeout must be positive");
    }
  }

  @Override
  public Submission submit(UUID playerId, String message) {
    return submitModule(playerId, AgentModule.GENERAL, message);
  }

  public void setAuthoritativeViewSource(AuthoritativeViewSource source) {
    playerReplies.setAuthoritativeViewSource(source);
  }

  @Override
  public Submission submitModule(UUID playerId, AgentModule module, String message) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(module);
    if (!validMessage(message)) {
      return Submission.INVALID_MESSAGE;
    }
    return submitOperation(playerId, LiveRequest.Operation.QUERY, module, message, null);
  }

  @Override
  public Submission resume(UUID playerId, UUID sessionId) {
    Objects.requireNonNull(playerId);
    return submitOperation(playerId, LiveRequest.Operation.RESUME, null, null, sessionId);
  }

  private Submission submitOperation(
      UUID playerId,
      LiveRequest.Operation operation,
      AgentModule module,
      String message,
      UUID requestedSessionId) {
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return Submission.OFFLINE;
    }
    var binding = connection.get();
    if (binding == null || !binding.connection().isOpen() || closed.get()) {
      return Submission.RUNTIME_UNAVAILABLE;
    }

    LiveRequest request;
    synchronized (lock) {
      var sessionId =
          operation == LiveRequest.Operation.QUERY
              ? currentSessionsByPlayer.get(playerId)
              : requestedSessionId;
      request =
          new LiveRequest(
              UUID.randomUUID(),
              playerId,
              permit.orElseThrow(),
              binding,
              operation,
              sessionId,
              module);
      if (closed.get()
          || connection.get() != binding
          || !binding.connection().isOpen()
          || !operationalGate.revalidate(request.permit())) {
        return Submission.RUNTIME_UNAVAILABLE;
      }
      if (requestsByPlayer.containsKey(playerId)) {
        return Submission.ALREADY_ACTIVE;
      }
      if (requestsById.size() >= MAX_ACTIVE_REQUESTS) {
        return Submission.RUNTIME_UNAVAILABLE;
      }
      requestsById.put(request.requestId(), request);
      requestsByPlayer.put(playerId, request);
      request.timeout(timeoutScheduler.schedule(requestTimeout, () -> timeout(request)));
    }

    try {
      var encoded =
          operation == LiveRequest.Operation.QUERY
              ? binding
                  .codec()
                  .encodeRequest(
                      request.requestId(),
                      playerId,
                      request.expectedSessionId(),
                      Objects.requireNonNull(module),
                      Objects.requireNonNull(message),
                      clientCapabilities.snapshot(playerId))
              : binding
                  .codec()
                  .encodeResume(request.requestId(), playerId, request.expectedSessionId());
      binding
          .connection()
          .sendApplication(encoded)
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  sendFailed(request);
                }
              });
    } catch (RuntimeException error) {
      sendFailed(request, false);
      return Submission.RUNTIME_UNAVAILABLE;
    }
    return Submission.ACCEPTED;
  }

  public CompletionStage<CostsQueryResult> queryCosts() {
    return managementCosts.query();
  }

  @Override
  public void attach(AuthenticatedRuntimeConnection runtimeConnection, String serverId) {
    Objects.requireNonNull(runtimeConnection);
    if (closed.get() || !runtimeConnection.isOpen()) {
      throw new IllegalStateException("Cannot attach an unavailable Runtime connection");
    }
    var binding = new ConnectionBinding(runtimeConnection, new AgentProtocolCodec(serverId));
    if (!connection.compareAndSet(null, binding)) {
      throw new IllegalStateException("A Runtime application connection is already attached");
    }
    try {
      runtimeConnection.setApplicationHandler(message -> receive(binding, message));
      runtimeConnection
          .whenClosed()
          .whenComplete((ignored, error) -> managementCosts.disconnect(binding));
    } catch (RuntimeException error) {
      connection.compareAndSet(binding, null);
      managementCosts.disconnect(binding);
      throw error;
    }
  }

  @Override
  public void detach(AuthenticatedRuntimeConnection runtimeConnection) {
    var binding = connection.get();
    if (binding != null && binding.connection() == runtimeConnection) {
      if (connection.compareAndSet(binding, null)) {
        managementCosts.disconnect(binding);
      }
    }
  }

  public void cancelPlayer(UUID playerId) {
    Objects.requireNonNull(playerId);
    LiveRequest request;
    synchronized (lock) {
      currentSessionsByPlayer.remove(playerId);
      request = requestsByPlayer.get(playerId);
      if (request == null || !remove(request)) {
        return;
      }
    }
    request.cancelTimeout();
    sendCancellation(request, CancelReason.PLAYER_DISCONNECTED);
  }

  @Override
  public void quiesce(long epoch, OfflineReason reason) {
    Objects.requireNonNull(reason);
    var cancellationReason =
        reason == OfflineReason.RUNTIME_UNAVAILABLE
            ? CancelReason.RUNTIME_DISCONNECTED
            : CancelReason.AGENT_OFFLINE;
    ArrayList<LiveRequest> pending;
    CostsQuery pendingCosts;
    synchronized (lock) {
      pending = new ArrayList<>(requestsById.values());
      requestsById.clear();
      requestsByPlayer.clear();
      pendingCosts = managementCosts.take(null);
    }
    for (var request : pending) {
      request.cancelTimeout();
      request.cancelToolExecution();
      sendCancellation(request, cancellationReason);
    }
    managementCosts.completeCosts(pendingCosts, CostsQueryResult.unavailable());
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    quiesce(operationalGate.epoch(), OfflineReason.MANUAL);
    connection.set(null);
    synchronized (lock) {
      currentSessionsByPlayer.clear();
    }
  }

  public int activeRequestCount() {
    synchronized (lock) {
      return requestsById.size();
    }
  }

  public boolean runtimeConnected() {
    var binding = connection.get();
    return !closed.get() && binding != null && binding.connection().isOpen();
  }

  UUID currentSession(UUID playerId) {
    synchronized (lock) {
      return currentSessionsByPlayer.get(playerId);
    }
  }

  private void receive(ConnectionBinding source, String encoded) {
    if (closed.get() || connection.get() != source) {
      return;
    }

    AgentProtocolCodec.InboundMessage message;
    try {
      message = source.codec().decode(encoded);
    } catch (RuntimeException error) {
      managementCosts.disconnect(source, CostsQueryResult.failed());
      events.event("RUNTIME_APPLICATION_MESSAGE_REJECTED");
      source.connection().close();
      return;
    }

    if (message instanceof AgentProtocolCodec.ManagementCosts costs) {
      managementCosts.receive(source, costs);
      return;
    }
    var playerMessage = (AgentProtocolCodec.PlayerMessage) message;
    if (message instanceof AgentProtocolCodec.ToolCall toolCall) {
      toolRounds.receive(source, toolCall);
      return;
    }

    LiveRequest request;
    String reply;
    synchronized (lock) {
      request = requestsById.get(playerMessage.requestId());
      if (request == null) {
        return;
      }
      if (request.binding() != source || !request.playerId().equals(playerMessage.playerUuid())) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      if (!validResponse(request, playerMessage)) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      if (!operationalGate.revalidate(request.permit()) || !remove(request)) {
        return;
      }
      if (playerMessage instanceof AgentProtocolCodec.Completion completion) {
        if (completion.sessionId() != null) {
          currentSessionsByPlayer.put(request.playerId(), completion.sessionId());
        }
      } else if (playerMessage instanceof AgentProtocolCodec.SessionResumed resumed) {
        currentSessionsByPlayer.put(request.playerId(), resumed.sessionId());
      } else if (playerMessage instanceof AgentProtocolCodec.AgentError error
          && (error.code() == AgentProtocolCodec.AgentErrorCode.SESSION_NOT_FOUND
              || error.code() == AgentProtocolCodec.AgentErrorCode.CONVERSATION_STORAGE_DISABLED)) {
        clearStaleSession(request);
      }
      reply = responseText(playerMessage);
    }
    request.cancelTimeout();
    playerReplies.dispatch(
        request,
        reply,
        playerMessage instanceof AgentProtocolCodec.Completion completion ? completion : null);
  }

  private void rejectBinding(ConnectionBinding source) {
    managementCosts.disconnect(source, CostsQueryResult.failed());
    events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
    source.connection().close();
  }

  private void timeout(LiveRequest request) {
    synchronized (lock) {
      if (!remove(request)) {
        return;
      }
    }
    sendCancellation(request, CancelReason.PAPER_TIMEOUT);
    playerReplies.dispatch(request, TIMEOUT_MESSAGE);
  }

  private void sendFailed(LiveRequest request) {
    sendFailed(request, true);
  }

  private void sendFailed(LiveRequest request, boolean notifyPlayer) {
    synchronized (lock) {
      if (!remove(request)) {
        return;
      }
    }
    request.cancelTimeout();
    events.event("RUNTIME_APPLICATION_SEND_FAILED");
    if (notifyPlayer) {
      playerReplies.dispatch(request, UNAVAILABLE_MESSAGE);
    }
    request.binding().connection().close();
  }

  private boolean remove(LiveRequest request) {
    if (requestsById.get(request.requestId()) != request) {
      return false;
    }
    requestsById.remove(request.requestId());
    requestsByPlayer.remove(request.playerId(), request);
    request.cancelToolExecution();
    return true;
  }

  private void sendCancellation(LiveRequest request, CancelReason reason) {
    var binding = request.binding();
    if (connection.get() != binding || !binding.connection().isOpen()) {
      return;
    }
    try {
      binding
          .connection()
          .sendApplication(
              binding.codec().encodeCancel(request.requestId(), request.playerId(), reason))
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  events.event("RUNTIME_APPLICATION_CANCEL_FAILED");
                }
              });
    } catch (RuntimeException error) {
      events.event("RUNTIME_APPLICATION_CANCEL_FAILED");
    }
  }

  private static boolean validMessage(String message) {
    return message != null
        && !message.isBlank()
        && message.codePointCount(0, message.length()) <= 4096
        && message.codePoints().noneMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff);
  }

  private static boolean validResponse(
      LiveRequest request, AgentProtocolCodec.PlayerMessage message) {
    if (message instanceof AgentProtocolCodec.AgentError) {
      return request.activeToolCall() == null;
    }
    if (request.operation() == LiveRequest.Operation.QUERY
        && message instanceof AgentProtocolCodec.Completion completion) {
      return request.expectedSessionId() == null
          ? request.matchesCompletionSession(completion.sessionId())
          : request.expectedSessionId().equals(completion.sessionId());
    }
    if (request.operation() == LiveRequest.Operation.RESUME
        && message instanceof AgentProtocolCodec.SessionResumed resumed) {
      return request.expectedSessionId() == null
          || request.expectedSessionId().equals(resumed.sessionId());
    }
    return false;
  }

  private void clearStaleSession(LiveRequest request) {
    if (request.operation() == LiveRequest.Operation.QUERY && request.expectedSessionId() != null) {
      currentSessionsByPlayer.remove(request.playerId(), request.expectedSessionId());
    } else if (request.operation() == LiveRequest.Operation.RESUME
        && request.expectedSessionId() == null) {
      currentSessionsByPlayer.remove(request.playerId());
    }
  }

  private static String responseText(AgentProtocolCodec.PlayerMessage message) {
    return switch (message) {
      case AgentProtocolCodec.Completion completion -> completion.fallbackText();
      case AgentProtocolCodec.AgentError error -> error.fallbackText();
      case AgentProtocolCodec.SessionResumed resumed ->
          SESSION_RESUMED_PREFIX + resumed.sessionId() + ".";
      case AgentProtocolCodec.ToolCall ignored ->
          throw new IllegalStateException("Intermediate tool call");
    };
  }
}
