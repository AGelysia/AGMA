package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.client.ClientCapabilitySnapshot;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.CancelReason;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

  @FunctionalInterface
  interface TimeoutScheduler {
    Cancellable schedule(Duration delay, Runnable task);
  }

  @FunctionalInterface
  interface Cancellable {
    void cancel();
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
  private static final Duration MANAGEMENT_QUERY_TIMEOUT = Duration.ofSeconds(5);

  private final Object lock = new Object();
  private final OperationalGate operationalGate;
  private final Duration requestTimeout;
  private final TimeoutScheduler timeoutScheduler;
  private final MainThreadExecutor mainThread;
  private final PlayerReplySink replies;
  private final EventSink events;
  private final ReadToolRegistry toolRegistry;
  private final ReadToolExecutor toolExecutor;
  private final Executor callbacks;
  private final ClientCapabilitySource clientCapabilities;
  private final StructuredReplySink structuredReplies;
  private final AtomicReference<AuthoritativeViewSource> authoritativeViews =
      new AtomicReference<>((requestId, playerId) -> List.of());
  private final AtomicReference<ConnectionBinding> connection = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Map<UUID, LiveRequest> requestsById = new HashMap<>();
  private final Map<UUID, LiveRequest> requestsByPlayer = new HashMap<>();
  private final Map<UUID, UUID> currentSessionsByPlayer = new HashMap<>();
  private CostsQuery costsQuery;

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
    this.mainThread = Objects.requireNonNull(mainThread);
    this.replies = Objects.requireNonNull(replies);
    this.events = Objects.requireNonNull(events);
    this.toolRegistry = Objects.requireNonNull(toolRegistry);
    this.toolExecutor = Objects.requireNonNull(toolExecutor);
    this.callbacks = Objects.requireNonNull(callbacks);
    this.clientCapabilities = Objects.requireNonNull(clientCapabilities);
    this.structuredReplies = Objects.requireNonNull(structuredReplies);
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("Request timeout must be positive");
    }
  }

  @Override
  public Submission submit(UUID playerId, String message) {
    return submitModule(playerId, AgentModule.GENERAL, message);
  }

  public void setAuthoritativeViewSource(AuthoritativeViewSource source) {
    authoritativeViews.set(Objects.requireNonNull(source));
  }

  @Override
  public Submission submitModule(UUID playerId, AgentModule module, String message) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(module);
    if (!validMessage(message)) {
      return Submission.INVALID_MESSAGE;
    }
    return submitOperation(playerId, Operation.QUERY, module, message, null);
  }

  @Override
  public Submission resume(UUID playerId, UUID sessionId) {
    Objects.requireNonNull(playerId);
    return submitOperation(playerId, Operation.RESUME, null, null, sessionId);
  }

  private Submission submitOperation(
      UUID playerId,
      Operation operation,
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
          operation == Operation.QUERY ? currentSessionsByPlayer.get(playerId) : requestedSessionId;
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
          operation == Operation.QUERY
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
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
    }
    var binding = connection.get();
    if (closed.get() || binding == null || !binding.connection().isOpen()) {
      return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
    }

    CostsQuery query;
    synchronized (lock) {
      if (closed.get()
          || connection.get() != binding
          || !binding.connection().isOpen()
          || !operationalGate.revalidate(permit.orElseThrow())) {
        return CompletableFuture.completedFuture(CostsQueryResult.unavailable());
      }
      if (costsQuery != null) {
        return costsQuery.completion().copy();
      }
      query = new CostsQuery(UUID.randomUUID(), permit.orElseThrow(), binding);
      costsQuery = query;
      try {
        query.timeout(
            timeoutScheduler.schedule(MANAGEMENT_QUERY_TIMEOUT, () -> timeoutCosts(query)));
      } catch (RuntimeException error) {
        costsQuery = null;
        query.completion().complete(CostsQueryResult.failed());
        return query.completion().copy();
      }
    }

    try {
      var encoded = binding.codec().encodeCostsRequest(query.requestId());
      if (!costsCurrent(query)) {
        finishCosts(query, CostsQueryResult.unavailable());
        return query.completion().copy();
      }
      var sent = Objects.requireNonNull(binding.connection().sendApplication(encoded));
      sent.whenComplete(
          (ignored, error) -> {
            if (error != null) {
              costsSendFailed(query);
            }
          });
    } catch (RuntimeException error) {
      costsSendFailed(query);
    }
    return query.completion().copy();
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
      runtimeConnection.whenClosed().whenComplete((ignored, error) -> disconnectCosts(binding));
    } catch (RuntimeException error) {
      connection.compareAndSet(binding, null);
      disconnectCosts(binding);
      throw error;
    }
  }

  @Override
  public void detach(AuthenticatedRuntimeConnection runtimeConnection) {
    var binding = connection.get();
    if (binding != null && binding.connection() == runtimeConnection) {
      if (connection.compareAndSet(binding, null)) {
        disconnectCosts(binding);
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
      pendingCosts = takeCosts(null);
    }
    for (var request : pending) {
      request.cancelTimeout();
      request.cancelToolExecution();
      sendCancellation(request, cancellationReason);
    }
    completeCosts(pendingCosts, CostsQueryResult.unavailable());
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
      disconnectCosts(source, CostsQueryResult.failed());
      events.event("RUNTIME_APPLICATION_MESSAGE_REJECTED");
      source.connection().close();
      return;
    }

    if (message instanceof AgentProtocolCodec.ManagementCosts costs) {
      receiveCosts(source, costs);
      return;
    }
    var playerMessage = (AgentProtocolCodec.PlayerMessage) message;
    if (message instanceof AgentProtocolCodec.ToolCall toolCall) {
      receiveToolCall(source, toolCall);
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
    dispatchBoundReply(
        request,
        reply,
        playerMessage instanceof AgentProtocolCodec.Completion completion ? completion : null);
  }

  private void receiveCosts(ConnectionBinding source, AgentProtocolCodec.ManagementCosts response) {
    CostsQuery query;
    CostsQueryResult result;
    synchronized (lock) {
      query = costsQuery;
      if (query == null || !query.requestId().equals(response.requestId())) {
        return;
      }
      if (query.binding() != source) {
        events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
        source.connection().close();
        return;
      }
      costsQuery = null;
      result =
          operationalGate.revalidate(query.permit())
              ? CostsQueryResult.available(response)
              : CostsQueryResult.unavailable();
    }
    completeCosts(query, result);
  }

  private void receiveToolCall(ConnectionBinding source, AgentProtocolCodec.ToolCall message) {
    LiveRequest request;
    ReadToolCall call = message.call();
    ReadToolResult immediate = null;
    synchronized (lock) {
      request = requestsById.get(message.requestId());
      if (request == null) {
        return;
      }
      if (request.binding() != source || !request.playerId().equals(message.playerUuid())) {
        rejectBinding(source);
        return;
      }
      if (!validToolBinding(request, call)) {
        rejectBinding(source);
        return;
      }
      var validation = toolRegistry.validate(call.tool(), call.module(), call.arguments());
      request.acceptToolCall(call);
      if (!validation.accepted()) {
        immediate = validation.rejection();
      }
    }

    if (immediate != null) {
      finishTool(request, call, immediate, null);
      return;
    }
    try {
      var execution = toolExecutor.execute(call).toCompletableFuture();
      synchronized (lock) {
        if (!requestOwnsActiveTool(request, call)) {
          execution.cancel(false);
          return;
        }
        request.toolExecution(call, execution);
      }
      execution.whenCompleteAsync(
          (result, error) -> finishTool(request, call, result, error), callbacks);
    } catch (RuntimeException error) {
      finishTool(request, call, null, error);
    }
  }

  private boolean validToolBinding(LiveRequest request, ReadToolCall call) {
    return request.operation() == Operation.QUERY
        && request.module() == call.module()
        && request.activeToolCall() == null
        && request.nextToolSequence() == call.sequence()
        && !request.hasToolCallId(call.toolCallId())
        && request.matchesToolSession(call.sessionId())
        && operationalGate.revalidate(request.permit());
  }

  private void finishTool(
      LiveRequest request, ReadToolCall call, ReadToolResult result, Throwable error) {
    var safeResult =
        error == null && result != null
            ? result
            : ReadToolResult.failed(
                toolRegistry
                    .find(call.tool())
                    .map(ReadToolRegistry.Descriptor::source)
                    .orElse(ReadToolResult.Source.PAPER_POLICY),
                "TOOL_EXECUTION_FAILED",
                "The server could not execute this read tool.",
                true);
    synchronized (lock) {
      if (!requestOwnsActiveTool(request, call)) {
        return;
      }
    }
    java.util.concurrent.CompletionStage<Void> sent;
    try {
      var encoded =
          request.binding().codec().encodeToolResult(request.requestId(), call, safeResult);
      synchronized (lock) {
        if (!requestOwnsActiveTool(request, call)) {
          return;
        }
        sent = request.binding().connection().sendApplication(encoded);
        request.completeToolCall(call);
      }
      sent.whenCompleteAsync(
          (ignored, sendError) -> {
            if (sendError != null) {
              sendFailed(request);
            }
          },
          callbacks);
    } catch (RuntimeException sendError) {
      sendFailed(request);
    }
  }

  private boolean requestOwnsActiveTool(LiveRequest request, ReadToolCall call) {
    return requestsById.get(request.requestId()) == request
        && request.activeToolCall() == call
        && connection.get() == request.binding()
        && request.binding().connection().isOpen()
        && operationalGate.revalidate(request.permit());
  }

  private void rejectBinding(ConnectionBinding source) {
    disconnectCosts(source, CostsQueryResult.failed());
    events.event("RUNTIME_APPLICATION_BINDING_REJECTED");
    source.connection().close();
  }

  private void timeoutCosts(CostsQuery query) {
    finishCosts(query, CostsQueryResult.timedOut());
  }

  private void costsSendFailed(CostsQuery query) {
    if (!finishCosts(query, CostsQueryResult.failed())) {
      return;
    }
    events.event("RUNTIME_MANAGEMENT_COSTS_SEND_FAILED");
    query.binding().connection().close();
  }

  private boolean costsCurrent(CostsQuery query) {
    synchronized (lock) {
      return costsQuery == query
          && !closed.get()
          && connection.get() == query.binding()
          && query.binding().connection().isOpen()
          && operationalGate.revalidate(query.permit());
    }
  }

  private boolean finishCosts(CostsQuery query, CostsQueryResult result) {
    synchronized (lock) {
      if (costsQuery != query) {
        return false;
      }
      costsQuery = null;
    }
    completeCosts(query, result);
    return true;
  }

  private void disconnectCosts(ConnectionBinding binding) {
    disconnectCosts(binding, CostsQueryResult.unavailable());
  }

  private void disconnectCosts(ConnectionBinding binding, CostsQueryResult result) {
    CostsQuery query;
    synchronized (lock) {
      query = takeCosts(binding);
    }
    completeCosts(query, result);
  }

  private CostsQuery takeCosts(ConnectionBinding binding) {
    if (costsQuery == null || binding != null && costsQuery.binding() != binding) {
      return null;
    }
    var query = costsQuery;
    costsQuery = null;
    return query;
  }

  private static void completeCosts(CostsQuery query, CostsQueryResult result) {
    if (query == null) {
      return;
    }
    query.cancelTimeout();
    query.completion().complete(result);
  }

  private void timeout(LiveRequest request) {
    synchronized (lock) {
      if (!remove(request)) {
        return;
      }
    }
    sendCancellation(request, CancelReason.PAPER_TIMEOUT);
    dispatchBoundReply(request, TIMEOUT_MESSAGE);
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
      dispatchBoundReply(request, UNAVAILABLE_MESSAGE);
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

  private void dispatchBoundReply(LiveRequest request, String message) {
    dispatchBoundReply(request, message, null);
  }

  private void dispatchBoundReply(
      LiveRequest request, String message, AgentProtocolCodec.Completion completion) {
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
            .filter(
                view ->
                    view.viewType() != dev.minecraftagent.paper.client.ClientViewType.BUILD_PREVIEW)
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
    if (view.viewType() != dev.minecraftagent.paper.client.ClientViewType.BUILD_PREVIEW
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
    if (request.operation() == Operation.QUERY
        && message instanceof AgentProtocolCodec.Completion completion) {
      return request.expectedSessionId() == null
          ? request.matchesCompletionSession(completion.sessionId())
          : request.expectedSessionId().equals(completion.sessionId());
    }
    if (request.operation() == Operation.RESUME
        && message instanceof AgentProtocolCodec.SessionResumed resumed) {
      return request.expectedSessionId() == null
          || request.expectedSessionId().equals(resumed.sessionId());
    }
    return false;
  }

  private void clearStaleSession(LiveRequest request) {
    if (request.operation() == Operation.QUERY && request.expectedSessionId() != null) {
      currentSessionsByPlayer.remove(request.playerId(), request.expectedSessionId());
    } else if (request.operation() == Operation.RESUME && request.expectedSessionId() == null) {
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

  private record ConnectionBinding(
      AuthenticatedRuntimeConnection connection, AgentProtocolCodec codec) {}

  private static final class CostsQuery {
    private final UUID requestId;
    private final OperationalGate.Permit permit;
    private final ConnectionBinding binding;
    private final CompletableFuture<CostsQueryResult> completion = new CompletableFuture<>();
    private final AtomicReference<Cancellable> timeout = new AtomicReference<>();

    private CostsQuery(UUID requestId, OperationalGate.Permit permit, ConnectionBinding binding) {
      this.requestId = Objects.requireNonNull(requestId);
      this.permit = Objects.requireNonNull(permit);
      this.binding = Objects.requireNonNull(binding);
    }

    UUID requestId() {
      return requestId;
    }

    OperationalGate.Permit permit() {
      return permit;
    }

    ConnectionBinding binding() {
      return binding;
    }

    CompletableFuture<CostsQueryResult> completion() {
      return completion;
    }

    void timeout(Cancellable scheduled) {
      if (!timeout.compareAndSet(null, scheduled) || completion.isDone()) {
        var current = timeout.getAndSet(null);
        if (current != null) {
          current.cancel();
        } else {
          scheduled.cancel();
        }
      }
    }

    void cancelTimeout() {
      var scheduled = timeout.getAndSet(null);
      if (scheduled != null) {
        scheduled.cancel();
      }
    }
  }

  private enum Operation {
    QUERY,
    RESUME
  }

  private static final class LiveRequest {
    private final UUID requestId;
    private final UUID playerId;
    private final OperationalGate.Permit permit;
    private final ConnectionBinding binding;
    private final Operation operation;
    private final UUID expectedSessionId;
    private final AgentModule module;
    private final AtomicReference<Cancellable> timeout = new AtomicReference<>();
    private final HashSet<UUID> toolCallIds = new HashSet<>();
    private ReadToolCall activeToolCall;
    private UUID toolSessionId;
    private int nextToolSequence;
    private CompletableFuture<ReadToolResult> toolExecution;

    private LiveRequest(
        UUID requestId,
        UUID playerId,
        OperationalGate.Permit permit,
        ConnectionBinding binding,
        Operation operation,
        UUID expectedSessionId,
        AgentModule module) {
      this.requestId = requestId;
      this.playerId = playerId;
      this.permit = permit;
      this.binding = binding;
      this.operation = operation;
      this.expectedSessionId = expectedSessionId;
      this.module = module;
    }

    UUID requestId() {
      return requestId;
    }

    UUID playerId() {
      return playerId;
    }

    OperationalGate.Permit permit() {
      return permit;
    }

    ConnectionBinding binding() {
      return binding;
    }

    Operation operation() {
      return operation;
    }

    UUID expectedSessionId() {
      return expectedSessionId;
    }

    AgentModule module() {
      return module;
    }

    ReadToolCall activeToolCall() {
      return activeToolCall;
    }

    int nextToolSequence() {
      return nextToolSequence;
    }

    boolean hasToolCallId(UUID toolCallId) {
      return toolCallIds.contains(toolCallId);
    }

    boolean matchesToolSession(UUID sessionId) {
      if (expectedSessionId != null) {
        return expectedSessionId.equals(sessionId);
      }
      return toolSessionId == null || toolSessionId.equals(sessionId);
    }

    boolean matchesCompletionSession(UUID sessionId) {
      return sessionId == null || toolSessionId == null || toolSessionId.equals(sessionId);
    }

    void acceptToolCall(ReadToolCall call) {
      activeToolCall = call;
      toolSessionId = call.sessionId();
      toolCallIds.add(call.toolCallId());
    }

    void completeToolCall(ReadToolCall call) {
      if (activeToolCall == call) {
        activeToolCall = null;
        toolExecution = null;
        nextToolSequence++;
      }
    }

    void toolExecution(ReadToolCall call, CompletableFuture<ReadToolResult> execution) {
      if (activeToolCall != call || toolExecution != null) {
        execution.cancel(false);
        throw new IllegalStateException("Tool execution is no longer current");
      }
      toolExecution = execution;
    }

    void cancelToolExecution() {
      var execution = toolExecution;
      toolExecution = null;
      if (execution != null) {
        execution.cancel(false);
      }
    }

    void timeout(Cancellable scheduled) {
      if (!timeout.compareAndSet(null, scheduled)) {
        scheduled.cancel();
      }
    }

    void cancelTimeout() {
      var scheduled = timeout.getAndSet(null);
      if (scheduled != null) {
        scheduled.cancel();
      }
    }
  }
}
