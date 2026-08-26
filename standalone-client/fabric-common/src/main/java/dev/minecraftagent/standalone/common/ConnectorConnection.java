package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One authenticated WebSocket session against the Runtime's loopback connector.
 *
 * <p>The connection owns the session state machine, the transport, and the connection monitor.
 * Every mutation of session state, pending requests, or message identities happens inside {@code
 * synchronized (this)}; the collaborators it delegates to are passive and rely on that contract:
 *
 * <ul>
 *   <li>{@link ConnectorPendingTable} — request and tool correlation bookkeeping
 *   <li>{@link ConnectorIdentityAllocator} — replay-protected outbound message identities
 *   <li>{@link ConnectorHandshakeVerifier} — pure handshake and clock-skew rules
 *   <li>{@link ConnectorResponseParser} — pure inbound payload decoding
 *   <li>{@link ConnectorOutboundCodec} — pure outbound envelope encoding
 * </ul>
 */
final class ConnectorConnection implements WebSocket.Listener, LocalConnector.Session {
  static final Duration REPLAY_TTL = Duration.ofSeconds(60);
  static final int HANDSHAKE_MAXIMUM_BYTES = 16 * 1024;
  static final int REPLAY_MAXIMUM_ENTRIES = 4096;

  private final JavaHttpLocalConnector owner;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final JavaHttpLocalConnector.EntropySource entropy;
  private final ConnectorIdentityAllocator identities;
  private final ConnectorPendingTable table =
      new ConnectorPendingTable(REPLAY_TTL, REPLAY_MAXIMUM_ENTRIES);
  private final UUID scopeId;
  private final int maximumTextRequests;
  private final String componentVersion;
  private final List<ConnectorHello.Capability> requestedCapabilities;
  private final byte[] connectorToken;
  private final CompletableFuture<LocalConnector.Session> ready = new CompletableFuture<>();
  private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
  private final StringBuilder fragments = new StringBuilder();

  private volatile ConnectorSessionState state = ConnectorSessionState.CONNECTING;
  private CompletableFuture<WebSocket> opening;
  private WebSocket webSocket;
  private CompletableFuture<WebSocket> sendTail;
  private ConnectorHello requestHello;
  private ScheduledFuture<?> handshakeTimeout;
  private Set<String> activeTools = Set.of();
  private ClientToolHandler toolHandler =
      call ->
          CompletableFuture.completedFuture(
              new ClientToolError(
                  ClientToolError.Status.REJECTED,
                  "CLIENT_TOOL_HANDLER_UNAVAILABLE",
                  "The client tool handler is unavailable",
                  false));

  ConnectorConnection(
      JavaHttpLocalConnector owner,
      HttpClient httpClient,
      ScheduledExecutorService scheduler,
      Clock clock,
      JavaHttpLocalConnector.EntropySource entropy,
      Supplier<UUID> identifiers,
      String componentVersion,
      RuntimeClientProfile profile,
      byte[] connectorToken) {
    this.owner = owner;
    this.httpClient = httpClient;
    this.scheduler = scheduler;
    this.clock = clock;
    this.entropy = entropy;
    this.identities =
        new ConnectorIdentityAllocator(
            entropy, identifiers, clock, REPLAY_TTL, REPLAY_MAXIMUM_ENTRIES);
    this.scopeId = profile.identity().installationId();
    this.maximumTextRequests =
        Math.addExact(
            profile.limits().maxConcurrentRequests(), profile.limits().maxQueuedRequests());
    this.componentVersion = componentVersion;
    var configuredTools = Set.copyOf(profile.toolPolicy().allowed());
    var capabilities = new ArrayList<>(ConnectorAuthentication.C1_CAPABILITIES);
    for (var tool : configuredTools) {
      capabilities.add(new ConnectorHello.Capability(tool, 1));
    }
    capabilities.sort(Comparator.comparing(ConnectorHello.Capability::id));
    this.requestedCapabilities = List.copyOf(capabilities);
    this.connectorToken = connectorToken;
  }

  CompletableFuture<LocalConnector.Session> ready() {
    return ready;
  }

  void start(URI uri) {
    final CompletableFuture<WebSocket> future;
    try {
      future =
          httpClient
              .newWebSocketBuilder()
              .connectTimeout(JavaHttpLocalConnector.HANDSHAKE_TIMEOUT)
              .buildAsync(uri, this);
    } catch (RuntimeException exception) {
      failTransport("CONNECTOR_UNAVAILABLE", "Runtime connector is unavailable");
      return;
    }
    synchronized (this) {
      opening = future;
    }
    future.whenComplete(
        (ignored, error) -> {
          if (error != null) {
            failTransport("CONNECTOR_UNAVAILABLE", "Runtime connector is unavailable");
          }
        });
  }

  @Override
  public ConnectorSessionState state() {
    return state;
  }

  @Override
  public CompletionStage<TextCompletion> request(TextRequest request) {
    Objects.requireNonNull(request, "request");
    final ConnectorPendingTable.PendingText entry;
    final String envelope;
    try {
      synchronized (this) {
        requireAuthenticated();
        var now = clock.millis();
        if (table.textCapacityExceeded(maximumTextRequests)) {
          return JavaHttpLocalConnector.failed(
              new ConnectorException("REQUEST_LIMITED", "Connector request capacity is exhausted"));
        }
        if (table.knownRequest(request.requestId(), now)) {
          return JavaHttpLocalConnector.failed(
              new ConnectorException(
                  "REQUEST_ID_REUSED", "Connector request identifier was already used"));
        }
        envelope =
            ConnectorOutboundCodec.envelope(
                identities.allocate(request.requestId()),
                scopeId,
                "client.request",
                clock.instant(),
                request.applicationPayload());
        var future = new CancellableFuture<TextCompletion>();
        entry = new ConnectorPendingTable.PendingText(request, future);
        future.onCancel(() -> cancelFromFuture(entry));
        table.addText(entry);
        try {
          entry.timeout =
              scheduler.schedule(
                  () -> timeout(entry), request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
          table.forgetText(entry.requestId, now);
          return JavaHttpLocalConnector.failed(
              new ConnectorException("CONNECTOR_CLOSED", "Connector client is closed"));
        }
      }
    } catch (ConnectorException exception) {
      return JavaHttpLocalConnector.failed(exception);
    } catch (RuntimeException exception) {
      return JavaHttpLocalConnector.failed(
          new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Connector request could not be encoded"));
    }
    enqueueText(envelope);
    return entry.future;
  }

  @Override
  public boolean cancel(UUID requestId) {
    return cancel(requestId, CancelReason.USER_REQUEST);
  }

  @Override
  public boolean cancel(UUID requestId, CancelReason reason) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(reason, "reason");
    final ConnectorPendingTable.PendingText entry;
    final String cancellation;
    final List<ConnectorPendingTable.PendingTool> tools;
    synchronized (this) {
      if (state != ConnectorSessionState.AUTHENTICATED
          || !(table.find(requestId) instanceof ConnectorPendingTable.PendingText text)) {
        return false;
      }
      entry = text;
      table.cancelText(entry, clock.millis());
      tools = table.drainTools(requestId, clock.millis());
      try {
        cancellation = cancellationEnvelope(requestId, reason);
      } catch (RuntimeException exception) {
        failTransport("APPLICATION_MESSAGE_INVALID", "Cancellation could not be encoded");
        return false;
      }
    }
    entry.future.complete(
        TextCompletion.failed(
            requestId,
            TextCompletion.Status.CANCELLED,
            "REQUEST_CANCELLED",
            "Request cancelled locally",
            false));
    cancelTools(tools, ClientToolCancellation.Reason.REQUEST_CANCELLED);
    enqueueText(cancellation);
    return true;
  }

  @Override
  public CompletionStage<RuntimeStatus> queryStatus(Duration timeout) {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
      return JavaHttpLocalConnector.failed(
          new ConnectorException("STATUS_TIMEOUT_INVALID", "Status timeout is invalid"));
    }
    final ConnectorPendingTable.PendingStatus entry;
    final String envelope;
    try {
      synchronized (this) {
        requireAuthenticated();
        if (table.statusCapacityExceeded()) {
          return JavaHttpLocalConnector.failed(
              new ConnectorException("REQUEST_LIMITED", "Connector status capacity is exhausted"));
        }
        var identity = identities.allocate();
        envelope =
            ConnectorOutboundCodec.envelope(
                identity, scopeId, "client.status.request", clock.instant(), Map.of());
        entry =
            new ConnectorPendingTable.PendingStatus(
                identity.messageId(), new CancellableFuture<>());
        entry.future.onCancel(() -> abandonStatus(entry));
        table.addStatus(entry);
        try {
          entry.timeout =
              scheduler.schedule(() -> timeout(entry), timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
          table.forgetStatus(identity.messageId(), clock.millis());
          return JavaHttpLocalConnector.failed(
              new ConnectorException("CONNECTOR_CLOSED", "Connector client is closed"));
        }
      }
    } catch (ConnectorException exception) {
      return JavaHttpLocalConnector.failed(exception);
    } catch (RuntimeException exception) {
      return JavaHttpLocalConnector.failed(
          new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Status request could not be encoded"));
    }
    enqueueText(envelope);
    return entry.future;
  }

  @Override
  public synchronized void setToolHandler(ClientToolHandler handler) {
    toolHandler = Objects.requireNonNull(handler, "handler");
  }

  @Override
  public synchronized Set<String> activeTools() {
    return activeTools;
  }

  @Override
  public CompletionStage<Void> close() {
    final List<ConnectorPendingTable.Pending<?>> abandoned;
    final List<ConnectorPendingTable.PendingTool> abandonedTools;
    final WebSocket socket;
    final CompletableFuture<WebSocket> openingFuture;
    synchronized (this) {
      if (state == ConnectorSessionState.CLOSED
          || state == ConnectorSessionState.CLOSING
          || state == ConnectorSessionState.ERROR) {
        return closedFuture;
      }
      state = ConnectorSessionState.CLOSING;
      clearToken();
      cancelTimeout(handshakeTimeout);
      abandoned = table.drainAll(clock.millis());
      abandonedTools = table.drainTools(null, clock.millis());
      socket = webSocket;
      openingFuture = opening;
    }
    var error = new ConnectorException("CONNECTOR_CLOSED", "Connector session is closed");
    ready.completeExceptionally(error);
    ConnectorPendingTable.failPending(abandoned, error);
    cancelTools(abandonedTools, ClientToolCancellation.Reason.RUNTIME_SHUTDOWN);
    if (socket == null) {
      if (openingFuture != null) {
        openingFuture.cancel(true);
      }
      finishClosed();
    } else {
      enqueueClose(socket);
    }
    return closedFuture;
  }

  @Override
  public void onOpen(WebSocket socket) {
    final String hello;
    synchronized (this) {
      if (state != ConnectorSessionState.CONNECTING || owner.isClosed()) {
        socket.abort();
        finishClosed();
        return;
      }
      webSocket = socket;
      sendTail = CompletableFuture.completedFuture(socket);
      try {
        var identity = identities.allocate();
        var challengeBytes = new byte[32];
        entropy.nextBytes(challengeBytes);
        var challenge = ConnectorAuthentication.encodeNonce(challengeBytes);
        Arrays.fill(challengeBytes, (byte) 0);
        requestHello =
            ConnectorAuthentication.createRequest(
                scopeId,
                componentVersion,
                clock,
                identity.messageId(),
                identity.nonce(),
                challenge,
                requestedCapabilities,
                connectorToken);
        hello = ConnectorHelloCodec.encode(requestHello);
        if (hello.getBytes(StandardCharsets.UTF_8).length > HANDSHAKE_MAXIMUM_BYTES) {
          throw new IllegalArgumentException("Handshake is too large");
        }
        handshakeTimeout =
            scheduler.schedule(
                () -> failProtocol("HANDSHAKE_TIMEOUT", "Runtime handshake timed out"),
                JavaHttpLocalConnector.HANDSHAKE_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
      } catch (RuntimeException exception) {
        failTransport("HANDSHAKE_INVALID", "Connector handshake could not be created");
        return;
      }
    }
    enqueueText(hello);
    socket.request(1);
  }

  @Override
  public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
    String message = null;
    String failureCode = null;
    String failureMessage = null;
    synchronized (this) {
      if (socket != webSocket
          || (state != ConnectorSessionState.CONNECTING
              && state != ConnectorSessionState.AUTHENTICATED)) {
        return CompletableFuture.completedFuture(null);
      }
      var maximum =
          state == ConnectorSessionState.CONNECTING
              ? HANDSHAKE_MAXIMUM_BYTES
              : ConnectorEnvelopeCodec.MAXIMUM_BYTES;
      if (data.length() > maximum - fragments.length()) {
        fragments.setLength(0);
        failureCode =
            state == ConnectorSessionState.CONNECTING
                ? "HANDSHAKE_INVALID"
                : "APPLICATION_MESSAGE_INVALID";
        failureMessage = "Runtime message exceeds the connector limit";
      } else {
        fragments.append(data);
      }
      if (failureCode == null && last) {
        message = fragments.toString();
        fragments.setLength(0);
        if (message.getBytes(StandardCharsets.UTF_8).length > maximum) {
          failureCode =
              state == ConnectorSessionState.CONNECTING
                  ? "HANDSHAKE_INVALID"
                  : "APPLICATION_MESSAGE_INVALID";
          failureMessage = "Runtime message exceeds the connector limit";
          message = null;
        }
      }
    }
    if (failureCode != null) {
      failProtocol(failureCode, failureMessage);
    } else if (message != null) {
      if (state == ConnectorSessionState.CONNECTING) {
        acceptHandshake(message);
      } else {
        acceptApplication(message);
      }
    }
    if (state == ConnectorSessionState.CONNECTING || state == ConnectorSessionState.AUTHENTICATED) {
      socket.request(1);
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
    failProtocol(
        state == ConnectorSessionState.CONNECTING
            ? "HANDSHAKE_INVALID"
            : "APPLICATION_MESSAGE_INVALID",
        "Binary Runtime messages are unsupported");
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
    var source = message.asReadOnlyBuffer();
    var bytes = new byte[source.remaining()];
    source.get(bytes);
    final CompletableFuture<WebSocket> queued;
    synchronized (this) {
      if (sendTail == null) {
        return CompletableFuture.completedFuture(null);
      }
      queued =
          sendTail
              .thenCompose(ignored -> socket.sendPong(ByteBuffer.wrap(bytes)))
              .toCompletableFuture();
      sendTail = queued;
    }
    queued.whenComplete(
        (ignored, error) -> {
          Arrays.fill(bytes, (byte) 0);
          if (error != null) {
            failTransport("CONNECTOR_UNAVAILABLE", "Runtime connector transport failed");
          }
        });
    socket.request(1);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
    socket.request(1);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
    if (state == ConnectorSessionState.CLOSING) {
      finishClosed();
    } else if (state != ConnectorSessionState.CLOSED && state != ConnectorSessionState.ERROR) {
      failTransport("CONNECTOR_CLOSED", "Runtime connector closed unexpectedly");
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void onError(WebSocket socket, Throwable error) {
    failTransport("CONNECTOR_UNAVAILABLE", "Runtime connector transport failed");
  }

  private void acceptHandshake(String source) {
    try {
      var response = ConnectorHelloCodec.decode(source);
      synchronized (this) {
        if (state != ConnectorSessionState.CONNECTING || requestHello == null) {
          return;
        }
        var now = clock.millis();
        ConnectorHandshakeVerifier.verify(
            requestHello, response, scopeId, requestedCapabilities, connectorToken);
        if (!ConnectorHandshakeVerifier.withinClockSkew(response.timestamp(), now)) {
          throw new ConnectorException("HANDSHAKE_STALE", "Runtime handshake is stale");
        }
        if (!identities.claimInbound(response.messageId(), response.nonce(), now)) {
          throw new ConnectorException("HANDSHAKE_REPLAYED", "Runtime handshake was replayed");
        }
        cancelTimeout(handshakeTimeout);
        clearToken();
        activeTools = ConnectorHandshakeVerifier.negotiatedTools(response.capabilities());
        state = ConnectorSessionState.AUTHENTICATED;
      }
      ready.complete(this);
    } catch (ConnectorException exception) {
      failProtocol(exception.code(), exception.getMessage(), exception);
    } catch (RuntimeException exception) {
      failProtocol("HANDSHAKE_INVALID", "Runtime handshake is invalid");
    }
  }

  private void acceptApplication(String source) {
    try {
      var envelope = ConnectorEnvelopeCodec.inbound(source);
      final ConnectorResponseParser.Response response = ConnectorResponseParser.parse(envelope);
      synchronized (this) {
        if (state != ConnectorSessionState.AUTHENTICATED) {
          return;
        }
        var now = clock.millis();
        if (!scopeId.equals(envelope.scopeId())) {
          throw new ConnectorException("SCOPE_ID_MISMATCH", "Runtime scope is invalid");
        }
        if (!ConnectorHandshakeVerifier.withinClockSkew(envelope.timestamp(), now)) {
          throw new ConnectorException("APPLICATION_MESSAGE_STALE", "Runtime response is stale");
        }
        if (!identities.claimInbound(envelope.messageId(), envelope.nonce(), now)) {
          throw new ConnectorException(
              "APPLICATION_MESSAGE_REPLAYED", "Runtime response was replayed");
        }
        if (envelope.messageId().equals(envelope.requestId())) {
          throw new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Runtime response is invalid");
        }
      }
      if (response instanceof ConnectorResponseParser.ToolCall toolCall) {
        acceptToolCall(envelope, toolCall.call());
      } else if (response instanceof ConnectorResponseParser.ToolCancellation cancellation) {
        acceptToolCancellation(envelope, cancellation.cancellation());
      } else {
        settleResponse(envelope, response);
      }
    } catch (ConnectorException exception) {
      failProtocol(exception.code(), exception.getMessage(), exception);
    } catch (RuntimeException exception) {
      failProtocol("APPLICATION_MESSAGE_INVALID", "Runtime response is invalid", exception);
    }
  }

  private void settleResponse(
      ConnectorEnvelope envelope, ConnectorResponseParser.Response response) {
    final ConnectorPendingTable.Pending<?> entry;
    synchronized (this) {
      var now = clock.millis();
      entry = table.settle(envelope.requestId(), now);
      if (entry == null) {
        return;
      }
      table.validateCorrelation(entry, response);
      table.remove(entry, now);
    }
    table.complete(entry, response);
  }

  private void acceptToolCall(ConnectorEnvelope envelope, ClientToolCall call) {
    final ConnectorPendingTable.PendingTool entry;
    synchronized (this) {
      var now = clock.millis();
      if (!scopeId.equals(call.subjectId())
          || !envelope.requestId().equals(call.requestId())
          || envelope.messageId().equals(call.toolCallId())
          || !activeTools.contains(call.tool())
          || !table.canRegisterTool(call, now)) {
        throw new ConnectorException(
            "APPLICATION_MESSAGE_INVALID", "Runtime tool call correlation is invalid");
      }
      entry = new ConnectorPendingTable.PendingTool(call, toolHandler);
      table.addTool(entry);
    }

    final CompletionStage<? extends ClientToolOutcome> execution;
    try {
      execution = Objects.requireNonNull(entry.handler.execute(call), "tool execution");
    } catch (RuntimeException exception) {
      finishTool(
          entry,
          new ClientToolError(
              ClientToolError.Status.FAILED,
              "CLIENT_TOOL_EXECUTION_FAILED",
              "The client tool could not be executed",
              true));
      return;
    }
    var abandoned = false;
    synchronized (this) {
      if (table.findTool(call.toolCallId()) == entry) {
        entry.execution = execution;
      } else {
        abandoned = true;
      }
    }
    if (abandoned) {
      execution.toCompletableFuture().cancel(false);
      return;
    }
    execution.whenComplete(
        (outcome, error) -> {
          if (error != null || outcome == null) {
            finishTool(
                entry,
                new ClientToolError(
                    ClientToolError.Status.FAILED,
                    "CLIENT_TOOL_EXECUTION_FAILED",
                    "The client tool could not be executed",
                    true));
          } else {
            finishTool(entry, outcome);
          }
        });
  }

  private void acceptToolCancellation(
      ConnectorEnvelope envelope, ClientToolCancellation cancellation) {
    final ConnectorPendingTable.PendingTool entry;
    synchronized (this) {
      var now = clock.millis();
      entry = table.findTool(cancellation.toolCallId());
      if (entry == null) {
        if (table.toolSettled(cancellation.toolCallId(), now)) {
          return;
        }
        throw new ConnectorException(
            "APPLICATION_MESSAGE_INVALID", "Runtime tool cancellation is unknown");
      }
      if (!envelope.requestId().equals(cancellation.requestId())
          || envelope.messageId().equals(cancellation.toolCallId())
          || !entry.call.requestId().equals(cancellation.requestId())
          || !entry.call.subjectId().equals(cancellation.subjectId())
          || !entry.call.tool().equals(cancellation.tool())
          || entry.call.sequence() != cancellation.sequence()) {
        throw new ConnectorException(
            "APPLICATION_MESSAGE_INVALID", "Runtime tool cancellation is invalid");
      }
      table.removeTool(entry, now);
    }
    cancelTool(entry, cancellation.reason());
  }

  private void finishTool(ConnectorPendingTable.PendingTool entry, ClientToolOutcome supplied) {
    ClientToolOutcome outcome = supplied;
    if (outcome instanceof ClientToolResult result) {
      try {
        ClientToolPayloads.validateResult(entry.call.tool(), result.result());
      } catch (RuntimeException exception) {
        outcome =
            new ClientToolError(
                ClientToolError.Status.FAILED,
                "CLIENT_TOOL_RESULT_INVALID",
                "The client tool returned an invalid result",
                false);
      }
    }
    final String response;
    try {
      synchronized (this) {
        if (table.findTool(entry.call.toolCallId()) != entry
            || state != ConnectorSessionState.AUTHENTICATED) {
          return;
        }
        table.removeTool(entry, clock.millis());
        response =
            ConnectorOutboundCodec.toolOutcome(
                identities.allocateDistinct(entry.call.requestId(), entry.call.toolCallId()),
                entry.call,
                scopeId,
                clock.instant(),
                outcome);
      }
    } catch (RuntimeException exception) {
      failProtocol(
          "APPLICATION_MESSAGE_INVALID", "Client tool response could not be encoded", exception);
      return;
    }
    enqueueText(response);
  }

  private void timeout(ConnectorPendingTable.Pending<?> entry) {
    String cancellation = null;
    List<ConnectorPendingTable.PendingTool> tools = List.of();
    synchronized (this) {
      if (!table.takeIfCurrent(entry)) {
        return;
      }
      if (entry instanceof ConnectorPendingTable.PendingText) {
        tools = table.drainTools(entry.requestId, clock.millis());
        if (state == ConnectorSessionState.AUTHENTICATED) {
          cancellation = cancellationEnvelope(entry.requestId, CancelReason.TIMEOUT);
        }
      }
      table.rememberRequest(entry.requestId, clock.millis());
    }
    if (entry instanceof ConnectorPendingTable.PendingText text) {
      text.future.complete(
          TextCompletion.failed(
              text.requestId,
              TextCompletion.Status.TIMED_OUT,
              "MODEL_TIMEOUT",
              "Request timed out locally",
              true));
    } else {
      entry.future.completeExceptionally(
          new ConnectorException("STATUS_TIMEOUT", "Runtime status request timed out"));
    }
    cancelTools(tools, ClientToolCancellation.Reason.MODEL_TIMEOUT);
    if (cancellation != null) {
      enqueueText(cancellation);
    }
  }

  private void cancelFromFuture(ConnectorPendingTable.PendingText entry) {
    String cancellation = null;
    List<ConnectorPendingTable.PendingTool> tools = List.of();
    synchronized (this) {
      if (!table.takeIfCurrent(entry)) {
        return;
      }
      ConnectorPendingTable.cancelTimeout(entry);
      table.rememberRequest(entry.requestId, clock.millis());
      tools = table.drainTools(entry.requestId, clock.millis());
      if (state == ConnectorSessionState.AUTHENTICATED) {
        cancellation = cancellationEnvelope(entry.requestId, CancelReason.USER_REQUEST);
      }
    }
    cancelTools(tools, ClientToolCancellation.Reason.REQUEST_CANCELLED);
    if (cancellation != null) {
      enqueueText(cancellation);
    }
  }

  private void cancelTool(
      ConnectorPendingTable.PendingTool entry, ClientToolCancellation.Reason reason) {
    var cancellation =
        new ClientToolCancellation(
            entry.call.requestId(),
            entry.call.toolCallId(),
            entry.call.subjectId(),
            entry.call.tool(),
            entry.call.sequence(),
            reason);
    try {
      entry.handler.cancel(cancellation);
    } catch (RuntimeException ignored) {
      // Cancellation is best-effort after the wire terminal has already been accepted.
    }
    var execution = entry.execution;
    if (execution != null) {
      execution.toCompletableFuture().cancel(false);
    }
  }

  private void abandonStatus(ConnectorPendingTable.PendingStatus entry) {
    synchronized (this) {
      if (!table.takeIfCurrent(entry)) {
        return;
      }
      ConnectorPendingTable.cancelTimeout(entry);
      table.rememberRequest(entry.requestId, clock.millis());
    }
  }

  private synchronized String cancellationEnvelope(UUID requestId, CancelReason reason) {
    return ConnectorOutboundCodec.cancellation(
        identities.allocate(), scopeId, clock.instant(), requestId, reason);
  }

  private void enqueueText(String text) {
    final CompletableFuture<WebSocket> queued;
    synchronized (this) {
      if (webSocket == null
          || sendTail == null
          || (state != ConnectorSessionState.CONNECTING
              && state != ConnectorSessionState.AUTHENTICATED)) {
        return;
      }
      var socket = webSocket;
      queued = sendTail.thenCompose(ignored -> socket.sendText(text, true)).toCompletableFuture();
      sendTail = queued;
    }
    queued.whenComplete(
        (ignored, error) -> {
          if (error != null) {
            failTransport("CONNECTOR_UNAVAILABLE", "Runtime connector transport failed");
          }
        });
  }

  private void enqueueClose(WebSocket socket) {
    final CompletableFuture<WebSocket> closeSend;
    synchronized (this) {
      var previous = sendTail == null ? CompletableFuture.completedFuture(socket) : sendTail;
      closeSend =
          previous
              .handle((ignored, error) -> socket)
              .thenCompose(ignored -> socket.sendClose(1000, "CLIENT_SHUTDOWN"))
              .toCompletableFuture();
    }
    closeSend.whenComplete(
        (ignored, error) -> {
          if (error != null) {
            socket.abort();
            finishClosed();
          }
        });
    try {
      scheduler.schedule(
          () -> {
            if (!closedFuture.isDone()) {
              socket.abort();
              finishClosed();
            }
          },
          2,
          TimeUnit.SECONDS);
    } catch (RejectedExecutionException exception) {
      socket.abort();
      finishClosed();
    }
  }

  private void failProtocol(String code, String message) {
    fail(new ConnectorException(code, message), true);
  }

  private void failProtocol(String code, String message, Throwable cause) {
    fail(new ConnectorException(code, message, cause), true);
  }

  private void failTransport(String code, String message) {
    fail(new ConnectorException(code, message), true);
  }

  private void fail(ConnectorException error, boolean abort) {
    final List<ConnectorPendingTable.Pending<?>> abandoned;
    final List<ConnectorPendingTable.PendingTool> abandonedTools;
    final WebSocket socket;
    synchronized (this) {
      if (state == ConnectorSessionState.ERROR || state == ConnectorSessionState.CLOSED) {
        return;
      }
      if (state == ConnectorSessionState.CLOSING) {
        finishClosed();
        return;
      }
      state = ConnectorSessionState.ERROR;
      cancelTimeout(handshakeTimeout);
      clearToken();
      abandoned = table.drainAll(clock.millis());
      abandonedTools = table.drainTools(null, clock.millis());
      socket = webSocket;
    }
    owner.forgetConnection(this);
    ready.completeExceptionally(error);
    ConnectorPendingTable.failPending(abandoned, error);
    cancelTools(abandonedTools, ClientToolCancellation.Reason.RUNTIME_SHUTDOWN);
    closedFuture.completeExceptionally(error);
    if (abort && socket != null) {
      socket.abort();
    }
  }

  private void finishClosed() {
    synchronized (this) {
      if (state == ConnectorSessionState.CLOSED) {
        return;
      }
      state = ConnectorSessionState.CLOSED;
      clearToken();
      cancelTimeout(handshakeTimeout);
    }
    owner.forgetConnection(this);
    closedFuture.complete(null);
  }

  private void cancelTools(
      List<ConnectorPendingTable.PendingTool> tools, ClientToolCancellation.Reason reason) {
    for (var tool : tools) {
      cancelTool(tool, reason);
    }
  }

  private void clearToken() {
    Arrays.fill(connectorToken, (byte) 0);
  }

  private void requireAuthenticated() {
    if (state != ConnectorSessionState.AUTHENTICATED) {
      throw new ConnectorException("CONNECTOR_NOT_READY", "Connector session is not ready");
    }
  }

  private static void cancelTimeout(ScheduledFuture<?> timeout) {
    if (timeout != null) {
      timeout.cancel(false);
    }
  }
}
