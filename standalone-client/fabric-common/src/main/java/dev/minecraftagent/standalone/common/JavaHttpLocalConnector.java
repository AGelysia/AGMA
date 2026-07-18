package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Authenticated Java 17 WebSocket client for the Runtime's loopback-only connector. */
public final class JavaHttpLocalConnector implements LocalConnector {
  static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
  static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
  static final Duration REPLAY_TTL = Duration.ofSeconds(60);
  static final int HANDSHAKE_MAXIMUM_BYTES = 16 * 1024;
  static final int REPLAY_MAXIMUM_ENTRIES = 4096;

  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final EntropySource entropy;
  private final Supplier<UUID> identifiers;
  private final String componentVersion;
  private final boolean ownsScheduler;
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public JavaHttpLocalConnector() {
    this("0.3.2");
  }

  public JavaHttpLocalConnector(String componentVersion) {
    this(
        HttpClient.newBuilder().connectTimeout(HANDSHAKE_TIMEOUT).build(),
        newScheduler(),
        Clock.systemUTC(),
        new SecureRandom()::nextBytes,
        UUID::randomUUID,
        componentVersion,
        true);
  }

  JavaHttpLocalConnector(
      HttpClient httpClient,
      ScheduledExecutorService scheduler,
      Clock clock,
      EntropySource entropy,
      Supplier<UUID> identifiers,
      String componentVersion,
      boolean ownsScheduler) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.entropy = Objects.requireNonNull(entropy, "entropy");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    this.componentVersion = Objects.requireNonNull(componentVersion, "componentVersion");
    this.ownsScheduler = ownsScheduler;
  }

  @Override
  public CompletionStage<Session> connect(
      RuntimeClientProfile profile, SecretMaterial connectorToken) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(connectorToken, "connectorToken");
    if (closed) {
      return failed(new ConnectorException("CONNECTOR_CLOSED", "Connector client is closed"));
    }
    if (!"127.0.0.1".equals(profile.transport().host())) {
      return failed(
          new ConnectorException("CONFIG_SCHEMA_INVALID", "Connector profile is not C1-safe"));
    }

    final byte[] token;
    try {
      token = connectorToken.copyBytes();
    } catch (RuntimeException exception) {
      return failed(
          new ConnectorException("CONNECTOR_TOKEN_MISSING", "Connector token is unavailable"));
    }
    if (token.length < 32) {
      Arrays.fill(token, (byte) 0);
      return failed(
          new ConnectorException("CONNECTOR_TOKEN_MISSING", "Connector token is unavailable"));
    }

    var uri = URI.create("ws://127.0.0.1:" + profile.transport().port() + "/connector");
    var connection = new Connection(profile, token);
    connections.add(connection);
    if (closed) {
      connection.close();
      return connection.ready;
    }
    connection.start(uri);
    return connection.ready;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (var connection : List.copyOf(connections)) {
      connection.close();
    }
    if (ownsScheduler) {
      scheduler.shutdown();
    }
  }

  @FunctionalInterface
  interface EntropySource {
    void nextBytes(byte[] target);
  }

  private final class Connection implements WebSocket.Listener, Session {
    private final UUID scopeId;
    private final int maximumTextRequests;
    private final Set<String> configuredTools;
    private final List<ConnectorHello.Capability> requestedCapabilities;
    private final byte[] connectorToken;
    private final ReplayWindow replayWindow = new ReplayWindow(REPLAY_TTL, REPLAY_MAXIMUM_ENTRIES);
    private final SettledRequestWindow settledRequests =
        new SettledRequestWindow(REPLAY_TTL, REPLAY_MAXIMUM_ENTRIES);
    private final SettledRequestWindow settledToolCalls =
        new SettledRequestWindow(REPLAY_TTL, REPLAY_MAXIMUM_ENTRIES);
    private final Map<UUID, Pending<?>> pending = new java.util.LinkedHashMap<>();
    private final Map<UUID, PendingTool> pendingTools = new java.util.LinkedHashMap<>();
    private final CompletableFuture<Session> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();

    private volatile ConnectorSessionState state = ConnectorSessionState.CONNECTING;
    private CompletableFuture<WebSocket> opening;
    private WebSocket webSocket;
    private CompletableFuture<WebSocket> sendTail;
    private ConnectorHello requestHello;
    private ScheduledFuture<?> handshakeTimeout;
    private int pendingTextRequests;
    private int pendingStatusRequests;
    private Set<String> activeTools = Set.of();
    private ClientToolHandler toolHandler =
        call ->
            CompletableFuture.completedFuture(
                new ClientToolError(
                    ClientToolError.Status.REJECTED,
                    "CLIENT_TOOL_HANDLER_UNAVAILABLE",
                    "The client tool handler is unavailable",
                    false));

    private Connection(RuntimeClientProfile profile, byte[] connectorToken) {
      scopeId = profile.identity().installationId();
      maximumTextRequests =
          Math.addExact(
              profile.limits().maxConcurrentRequests(), profile.limits().maxQueuedRequests());
      configuredTools = Set.copyOf(profile.toolPolicy().allowed());
      var capabilities = new ArrayList<>(ConnectorAuthentication.C1_CAPABILITIES);
      for (var tool : configuredTools) {
        capabilities.add(new ConnectorHello.Capability(tool, 1));
      }
      capabilities.sort(java.util.Comparator.comparing(ConnectorHello.Capability::id));
      requestedCapabilities = List.copyOf(capabilities);
      this.connectorToken = connectorToken;
    }

    private void start(URI uri) {
      final CompletableFuture<WebSocket> future;
      try {
        future =
            httpClient
                .newWebSocketBuilder()
                .connectTimeout(HANDSHAKE_TIMEOUT)
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
      final PendingText entry;
      final String envelope;
      try {
        synchronized (this) {
          requireAuthenticated();
          var now = clock.millis();
          if (pendingTextRequests >= maximumTextRequests) {
            return failed(
                new ConnectorException(
                    "REQUEST_LIMITED", "Connector request capacity is exhausted"));
          }
          if (pending.containsKey(request.requestId())
              || settledRequests.contains(request.requestId(), now)) {
            return failed(
                new ConnectorException(
                    "REQUEST_ID_REUSED", "Connector request identifier was already used"));
          }
          envelope =
              createEnvelope(request.requestId(), "client.request", request.applicationPayload());
          var future = new CancellableFuture<TextCompletion>();
          entry = new PendingText(request, future);
          future.onCancel(() -> cancelFromFuture(entry));
          pending.put(request.requestId(), entry);
          pendingTextRequests++;
          try {
            entry.timeout =
                scheduler.schedule(
                    () -> timeout(entry), request.timeout().toMillis(), TimeUnit.MILLISECONDS);
          } catch (RejectedExecutionException exception) {
            pending.remove(request.requestId());
            pendingTextRequests--;
            settledRequests.remember(request.requestId(), now);
            return failed(new ConnectorException("CONNECTOR_CLOSED", "Connector client is closed"));
          }
        }
      } catch (ConnectorException exception) {
        return failed(exception);
      } catch (RuntimeException exception) {
        return failed(
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
      final PendingText entry;
      final String cancellation;
      final List<PendingTool> tools;
      synchronized (this) {
        if (state != ConnectorSessionState.AUTHENTICATED
            || !(pending.get(requestId) instanceof PendingText text)) {
          return false;
        }
        entry = text;
        pending.remove(requestId);
        pendingTextRequests--;
        cancelTimeout(entry);
        settledRequests.remember(requestId, clock.millis());
        tools = drainTools(requestId);
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
        return failed(
            new ConnectorException("STATUS_TIMEOUT_INVALID", "Status timeout is invalid"));
      }
      final PendingStatus entry;
      final String envelope;
      try {
        synchronized (this) {
          requireAuthenticated();
          if (pendingStatusRequests >= 8) {
            return failed(
                new ConnectorException(
                    "REQUEST_LIMITED", "Connector status capacity is exhausted"));
          }
          var identity = allocateIdentity();
          envelope = encodeClaimedEnvelope(identity, "client.status.request", Map.of());
          entry = new PendingStatus(identity.messageId(), new CancellableFuture<>());
          entry.future.onCancel(() -> abandonStatus(entry));
          pending.put(identity.messageId(), entry);
          pendingStatusRequests++;
          try {
            entry.timeout =
                scheduler.schedule(() -> timeout(entry), timeout.toMillis(), TimeUnit.MILLISECONDS);
          } catch (RejectedExecutionException exception) {
            pending.remove(identity.messageId());
            pendingStatusRequests--;
            settledRequests.remember(identity.messageId(), clock.millis());
            return failed(new ConnectorException("CONNECTOR_CLOSED", "Connector client is closed"));
          }
        }
      } catch (ConnectorException exception) {
        return failed(exception);
      } catch (RuntimeException exception) {
        return failed(
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
      final List<Pending<?>> abandoned;
      final List<PendingTool> abandonedTools;
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
        abandoned = drainPending();
        abandonedTools = drainTools(null);
        socket = webSocket;
        openingFuture = opening;
      }
      var error = new ConnectorException("CONNECTOR_CLOSED", "Connector session is closed");
      ready.completeExceptionally(error);
      failPending(abandoned, error);
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
        if (state != ConnectorSessionState.CONNECTING || closed) {
          socket.abort();
          finishClosed();
          return;
        }
        webSocket = socket;
        sendTail = CompletableFuture.completedFuture(socket);
        try {
          var identity = allocateIdentity();
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
                  HANDSHAKE_TIMEOUT.toMillis(),
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
      if (state == ConnectorSessionState.CONNECTING
          || state == ConnectorSessionState.AUTHENTICATED) {
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
          if (!"runtime".equals(response.component())
              || !requestHello.messageId().equals(response.requestId())
              || requestHello.messageId().equals(response.messageId())
              || requestHello.nonce().equals(response.nonce())
              || !requestHello
                  .authentication()
                  .challenge()
                  .equals(response.authentication().challenge())
              || !ConnectorAuthentication.KEY_ID.equals(response.authentication().keyId())
              || !validNegotiatedCapabilities(response.capabilities())) {
            throw new ConnectorException("HANDSHAKE_INVALID", "Runtime handshake is invalid");
          }
          if (!scopeId.equals(response.scopeId())
              || !ConnectorAuthentication.verify(response, connectorToken)) {
            throw new ConnectorException(
                "AUTHENTICATION_FAILED", "Runtime connector authentication failed");
          }
          if (!withinClockSkew(response.timestamp(), now)) {
            throw new ConnectorException("HANDSHAKE_STALE", "Runtime handshake is stale");
          }
          if (!replayWindow.claim(response.messageId(), response.nonce(), now)) {
            throw new ConnectorException("HANDSHAKE_REPLAYED", "Runtime handshake was replayed");
          }
          cancelTimeout(handshakeTimeout);
          clearToken();
          activeTools =
              response.capabilities().stream()
                  .map(ConnectorHello.Capability::id)
                  .filter(ClientToolPayloads.TOOLS::contains)
                  .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
        final ParsedResponse response = parseResponse(envelope);
        synchronized (this) {
          if (state != ConnectorSessionState.AUTHENTICATED) {
            return;
          }
          var now = clock.millis();
          if (!scopeId.equals(envelope.scopeId())) {
            throw new ConnectorException("SCOPE_ID_MISMATCH", "Runtime scope is invalid");
          }
          if (!withinClockSkew(envelope.timestamp(), now)) {
            throw new ConnectorException("APPLICATION_MESSAGE_STALE", "Runtime response is stale");
          }
          if (!replayWindow.claim(envelope.messageId(), envelope.nonce(), now)) {
            throw new ConnectorException(
                "APPLICATION_MESSAGE_REPLAYED", "Runtime response was replayed");
          }
          if (envelope.messageId().equals(envelope.requestId())) {
            throw new ConnectorException(
                "APPLICATION_MESSAGE_INVALID", "Runtime response is invalid");
          }
        }
        if (response instanceof ParsedToolCall toolCall) {
          acceptToolCall(envelope, toolCall.call());
        } else if (response instanceof ParsedToolCancellation cancellation) {
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

    private ParsedResponse parseResponse(ConnectorEnvelope envelope) {
      return switch (envelope.type()) {
        case "client.complete" -> {
          var payload =
              JsonFields.exactObject(
                  envelope.payload(),
                  "/payload",
                  "sessionId",
                  "text",
                  "costMicroUsd",
                  "costKind",
                  "sources");
          var costMicroUsd =
              JsonFields.longInteger(payload.get("costMicroUsd"), "/payload/costMicroUsd");
          if (costMicroUsd < 0 || costMicroUsd > TextCompletion.MAXIMUM_COST_MICRO_USD) {
            throw JsonFields.invalid("/payload/costMicroUsd");
          }
          yield new ParsedComplete(
              JsonFields.nullableUuid(payload.get("sessionId"), "/payload/sessionId"),
              JsonFields.string(
                  payload.get("text"), "/payload/text", TextCompletion.MAXIMUM_TEXT_LENGTH),
              costMicroUsd,
              TextCompletion.CostKind.fromWire(
                  JsonFields.string(payload.get("costKind"), "/payload/costKind", 16)),
              parseSources(payload.get("sources")));
        }
        case "client.error" -> {
          var payload =
              JsonFields.exactObject(
                  envelope.payload(), "/payload", "code", "message", "retryable");
          var code = JsonFields.string(payload.get("code"), "/payload/code", 64);
          if (!ERROR_CODES.contains(code)) {
            throw JsonFields.invalid("/payload/code");
          }
          yield new ParsedError(
              code,
              JsonFields.string(payload.get("message"), "/payload/message", 512),
              JsonFields.bool(payload.get("retryable"), "/payload/retryable"));
        }
        case "client.status" -> {
          var payload =
              JsonFields.exactObject(
                  envelope.payload(), "/payload", "state", "activeRequests", "queuedRequests");
          yield new ParsedStatus(
              RuntimeStatus.State.valueOf(
                  JsonFields.string(payload.get("state"), "/payload/state", 16)),
              JsonFields.integer(payload.get("activeRequests"), "/payload/activeRequests"),
              JsonFields.integer(payload.get("queuedRequests"), "/payload/queuedRequests"));
        }
        case "client.tool.call" -> {
          var payload =
              JsonFields.exactObject(
                  envelope.payload(),
                  "/payload",
                  "requestId",
                  "toolCallId",
                  "subjectId",
                  "tool",
                  "sequence",
                  "arguments");
          var requestId = JsonFields.uuid(payload.get("requestId"), "/payload/requestId");
          if (!requestId.equals(envelope.requestId())) {
            throw JsonFields.invalid("/payload/requestId");
          }
          yield new ParsedToolCall(
              new ClientToolCall(
                  requestId,
                  JsonFields.uuid(payload.get("toolCallId"), "/payload/toolCallId"),
                  JsonFields.uuid(payload.get("subjectId"), "/payload/subjectId"),
                  JsonFields.string(payload.get("tool"), "/payload/tool", 64),
                  JsonFields.integer(payload.get("sequence"), "/payload/sequence"),
                  JsonFields.object(payload.get("arguments"), "/payload/arguments")));
        }
        case "client.tool.cancel" -> {
          var payload =
              JsonFields.exactObject(
                  envelope.payload(),
                  "/payload",
                  "requestId",
                  "toolCallId",
                  "subjectId",
                  "tool",
                  "sequence",
                  "reason");
          var requestId = JsonFields.uuid(payload.get("requestId"), "/payload/requestId");
          if (!requestId.equals(envelope.requestId())) {
            throw JsonFields.invalid("/payload/requestId");
          }
          yield new ParsedToolCancellation(
              new ClientToolCancellation(
                  requestId,
                  JsonFields.uuid(payload.get("toolCallId"), "/payload/toolCallId"),
                  JsonFields.uuid(payload.get("subjectId"), "/payload/subjectId"),
                  JsonFields.string(payload.get("tool"), "/payload/tool", 64),
                  JsonFields.integer(payload.get("sequence"), "/payload/sequence"),
                  ClientToolCancellation.Reason.valueOf(
                      JsonFields.string(payload.get("reason"), "/payload/reason", 32))));
        }
        default -> throw JsonFields.invalid("/type");
      };
    }

    private List<TextCompletion.Source> parseSources(Object value) {
      var parsed = new ArrayList<TextCompletion.Source>();
      var entries = JsonFields.array(value, "/payload/sources", 5);
      for (var index = 0; index < entries.size(); index++) {
        var field = "/payload/sources/" + index;
        var source =
            JsonFields.exactObject(
                entries.get(index),
                field,
                "claimId",
                "title",
                "url",
                "publisher",
                "retrievedAt",
                "applicability",
                "warnings");
        var applicability =
            JsonFields.exactObject(
                source.get("applicability"),
                field + "/applicability",
                "minecraftVersion",
                "modVersions",
                "modpackVersion",
                "match");
        var modVersions = new java.util.TreeMap<String, String>();
        JsonFields.object(applicability.get("modVersions"), field + "/applicability/modVersions")
            .forEach(
                (modId, version) ->
                    modVersions.put(
                        modId,
                        JsonFields.string(
                            version, field + "/applicability/modVersions/" + modId, 128)));
        parsed.add(
            new TextCompletion.Source(
                JsonFields.string(source.get("claimId"), field + "/claimId", 30),
                JsonFields.string(source.get("title"), field + "/title", 256),
                URI.create(JsonFields.string(source.get("url"), field + "/url", 2048)),
                JsonFields.string(source.get("publisher"), field + "/publisher", 256),
                JsonFields.instant(source.get("retrievedAt"), field + "/retrievedAt"),
                new TextCompletion.Applicability(
                    JsonFields.string(
                        applicability.get("minecraftVersion"),
                        field + "/applicability/minecraftVersion",
                        128),
                    modVersions,
                    JsonFields.nullableString(
                        applicability.get("modpackVersion"),
                        field + "/applicability/modpackVersion",
                        128),
                    TextCompletion.Match.fromWire(
                        JsonFields.string(
                            applicability.get("match"), field + "/applicability/match", 16))),
                JsonFields.stringArray(source.get("warnings"), field + "/warnings", 4)));
      }
      return List.copyOf(parsed);
    }

    private void settleResponse(ConnectorEnvelope envelope, ParsedResponse response) {
      final Pending<?> entry;
      synchronized (this) {
        var now = clock.millis();
        entry = pending.get(envelope.requestId());
        if (entry == null) {
          if (settledRequests.contains(envelope.requestId(), now)) {
            return;
          }
          throw new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Runtime response correlation is invalid");
        }
        if (pendingTools.values().stream()
            .anyMatch(tool -> tool.call.requestId().equals(entry.requestId))) {
          throw new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Runtime completed a request with an active tool");
        }
        validateCorrelation(entry, response);
        pending.remove(entry.requestId);
        if (entry instanceof PendingText) {
          pendingTextRequests--;
        } else {
          pendingStatusRequests--;
        }
        cancelTimeout(entry);
        settledRequests.remember(entry.requestId, now);
      }
      complete(entry, response);
    }

    private void acceptToolCall(ConnectorEnvelope envelope, ClientToolCall call) {
      final PendingTool entry;
      synchronized (this) {
        var now = clock.millis();
        if (!scopeId.equals(call.subjectId())
            || !envelope.requestId().equals(call.requestId())
            || envelope.messageId().equals(call.toolCallId())
            || !activeTools.contains(call.tool())
            || !(pending.get(call.requestId()) instanceof PendingText)
            || pendingTools.containsKey(call.toolCallId())
            || settledToolCalls.contains(call.toolCallId(), now)
            || pendingTools.values().stream()
                .anyMatch(tool -> tool.call.requestId().equals(call.requestId()))) {
          throw new ConnectorException(
              "APPLICATION_MESSAGE_INVALID", "Runtime tool call correlation is invalid");
        }
        entry = new PendingTool(call, toolHandler);
        pendingTools.put(call.toolCallId(), entry);
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
        if (pendingTools.get(call.toolCallId()) == entry) {
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
      final PendingTool entry;
      synchronized (this) {
        var now = clock.millis();
        entry = pendingTools.get(cancellation.toolCallId());
        if (entry == null) {
          if (settledToolCalls.contains(cancellation.toolCallId(), now)) {
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
        pendingTools.remove(cancellation.toolCallId());
        settledToolCalls.remember(cancellation.toolCallId(), now);
      }
      cancelTool(entry, cancellation.reason());
    }

    private void finishTool(PendingTool entry, ClientToolOutcome supplied) {
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
          if (pendingTools.get(entry.call.toolCallId()) != entry
              || state != ConnectorSessionState.AUTHENTICATED) {
            return;
          }
          pendingTools.remove(entry.call.toolCallId());
          settledToolCalls.remember(entry.call.toolCallId(), clock.millis());
          response = toolOutcomeEnvelope(entry.call, outcome);
        }
      } catch (RuntimeException exception) {
        failProtocol(
            "APPLICATION_MESSAGE_INVALID", "Client tool response could not be encoded", exception);
        return;
      }
      enqueueText(response);
    }

    private void validateCorrelation(Pending<?> entry, ParsedResponse response) {
      if (entry instanceof PendingText text) {
        if (response instanceof ParsedStatus) {
          throw JsonFields.invalid("/type");
        }
        if (response instanceof ParsedComplete complete
            && text.request.sessionId() != null
            && !text.request.sessionId().equals(complete.sessionId())) {
          throw JsonFields.invalid("/payload/sessionId");
        }
      } else if (!(response instanceof ParsedStatus)) {
        throw JsonFields.invalid("/type");
      }
    }

    private void complete(Pending<?> entry, ParsedResponse response) {
      if (entry instanceof PendingText text) {
        if (response instanceof ParsedComplete complete) {
          text.future.complete(
              TextCompletion.completed(
                  text.requestId,
                  complete.sessionId(),
                  complete.text(),
                  complete.costMicroUsd(),
                  complete.costKind(),
                  complete.sources()));
        } else if (response instanceof ParsedError error) {
          var status =
              switch (error.code()) {
                case "MODEL_TIMEOUT" -> TextCompletion.Status.TIMED_OUT;
                case "REQUEST_CANCELLED" -> TextCompletion.Status.CANCELLED;
                default -> TextCompletion.Status.FAILED;
              };
          text.future.complete(
              TextCompletion.failed(
                  text.requestId, status, error.code(), error.message(), error.retryable()));
        }
      } else if (entry instanceof PendingStatus status && response instanceof ParsedStatus value) {
        status.future.complete(
            new RuntimeStatus(
                status.requestId, value.state(), value.activeRequests(), value.queuedRequests()));
      }
    }

    private synchronized String cancellationEnvelope(UUID requestId, CancelReason reason) {
      var identity = allocateIdentity();
      return encodeClaimedEnvelope(
          identity,
          "client.cancel",
          ConnectorEnvelopeCodec.map(
              "targetRequestId", requestId.toString(), "reason", reason.name()));
    }

    private synchronized String createEnvelope(
        UUID messageId, String type, Map<String, Object> payload) {
      var identity = allocateIdentity(messageId);
      return encodeClaimedEnvelope(identity, type, payload);
    }

    private String toolOutcomeEnvelope(ClientToolCall call, ClientToolOutcome outcome) {
      var identity = allocateIdentityDistinct(call.requestId(), call.toolCallId());
      final String type;
      final Map<String, Object> payload;
      if (outcome instanceof ClientToolResult result) {
        type = "client.tool.result";
        payload =
            ConnectorEnvelopeCodec.map(
                "requestId",
                call.requestId().toString(),
                "toolCallId",
                call.toolCallId().toString(),
                "subjectId",
                call.subjectId().toString(),
                "tool",
                call.tool(),
                "sequence",
                call.sequence(),
                "result",
                result.result());
      } else if (outcome instanceof ClientToolError error) {
        type = "client.tool.error";
        payload =
            ConnectorEnvelopeCodec.map(
                "requestId",
                call.requestId().toString(),
                "toolCallId",
                call.toolCallId().toString(),
                "subjectId",
                call.subjectId().toString(),
                "tool",
                call.tool(),
                "sequence",
                call.sequence(),
                "status",
                error.status().wireName(),
                "code",
                error.code(),
                "message",
                error.message(),
                "retryable",
                error.retryable());
      } else {
        throw new IllegalArgumentException("Client tool outcome is invalid");
      }
      var encoded =
          ConnectorEnvelopeCodec.outboundTool(
              identity.messageId(),
              call.requestId(),
              scopeId,
              type,
              clock.instant(),
              identity.nonce(),
              payload);
      if (encoded.getBytes(StandardCharsets.UTF_8).length > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
        throw new ConnectorException(
            "APPLICATION_MESSAGE_INVALID", "Client tool response exceeds the protocol limit");
      }
      return encoded;
    }

    private String encodeClaimedEnvelope(
        OutboundIdentity identity, String type, Map<String, Object> payload) {
      var result =
          ConnectorEnvelopeCodec.outbound(
              identity.messageId(), scopeId, type, clock.instant(), identity.nonce(), payload);
      if (result.getBytes(StandardCharsets.UTF_8).length > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
        throw new ConnectorException(
            "APPLICATION_MESSAGE_INVALID", "Connector request exceeds the protocol limit");
      }
      return result;
    }

    private OutboundIdentity allocateIdentity() {
      for (var attempt = 0; attempt < 8; attempt++) {
        var candidate = Objects.requireNonNull(identifiers.get(), "identifier");
        var identity = tryIdentity(candidate);
        if (identity != null) {
          return identity;
        }
      }
      throw new ConnectorException(
          "IDENTITY_EXHAUSTED", "Connector message identity could not be allocated");
    }

    private OutboundIdentity allocateIdentity(UUID messageId) {
      for (var attempt = 0; attempt < 8; attempt++) {
        var identity = tryIdentity(messageId);
        if (identity != null) {
          return identity;
        }
      }
      throw new ConnectorException(
          "REQUEST_ID_REUSED", "Connector request identifier was already used");
    }

    private OutboundIdentity allocateIdentityDistinct(UUID... excluded) {
      for (var attempt = 0; attempt < 8; attempt++) {
        var candidate = Objects.requireNonNull(identifiers.get(), "identifier");
        if (Arrays.asList(excluded).contains(candidate)) {
          continue;
        }
        var identity = tryIdentity(candidate);
        if (identity != null) {
          return identity;
        }
      }
      throw new ConnectorException(
          "IDENTITY_EXHAUSTED", "Connector message identity could not be allocated");
    }

    private OutboundIdentity tryIdentity(UUID messageId) {
      var bytes = new byte[16];
      try {
        entropy.nextBytes(bytes);
        var nonce = ConnectorAuthentication.encodeNonce(bytes);
        var now = clock.millis();
        return replayWindow.claim(messageId, nonce, now)
            ? new OutboundIdentity(messageId, nonce)
            : null;
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    }

    private void timeout(Pending<?> entry) {
      String cancellation = null;
      List<PendingTool> tools = List.of();
      synchronized (this) {
        if (pending.get(entry.requestId) != entry) {
          return;
        }
        pending.remove(entry.requestId);
        if (entry instanceof PendingText) {
          pendingTextRequests--;
          tools = drainTools(entry.requestId);
          if (state == ConnectorSessionState.AUTHENTICATED) {
            cancellation = cancellationEnvelope(entry.requestId, CancelReason.TIMEOUT);
          }
        } else {
          pendingStatusRequests--;
        }
        settledRequests.remember(entry.requestId, clock.millis());
      }
      if (entry instanceof PendingText text) {
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

    private void cancelFromFuture(PendingText entry) {
      String cancellation = null;
      List<PendingTool> tools = List.of();
      synchronized (this) {
        if (pending.get(entry.requestId) != entry) {
          return;
        }
        pending.remove(entry.requestId);
        pendingTextRequests--;
        cancelTimeout(entry);
        settledRequests.remember(entry.requestId, clock.millis());
        tools = drainTools(entry.requestId);
        if (state == ConnectorSessionState.AUTHENTICATED) {
          cancellation = cancellationEnvelope(entry.requestId, CancelReason.USER_REQUEST);
        }
      }
      cancelTools(tools, ClientToolCancellation.Reason.REQUEST_CANCELLED);
      if (cancellation != null) {
        enqueueText(cancellation);
      }
    }

    private void cancelTool(PendingTool entry, ClientToolCancellation.Reason reason) {
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

    private void abandonStatus(PendingStatus entry) {
      synchronized (this) {
        if (pending.get(entry.requestId) != entry) {
          return;
        }
        pending.remove(entry.requestId);
        pendingStatusRequests--;
        cancelTimeout(entry);
        settledRequests.remember(entry.requestId, clock.millis());
      }
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
      final List<Pending<?>> abandoned;
      final List<PendingTool> abandonedTools;
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
        abandoned = drainPending();
        abandonedTools = drainTools(null);
        socket = webSocket;
      }
      connections.remove(this);
      ready.completeExceptionally(error);
      failPending(abandoned, error);
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
      connections.remove(this);
      closedFuture.complete(null);
    }

    private List<Pending<?>> drainPending() {
      var result = new ArrayList<>(pending.values());
      var now = clock.millis();
      for (var entry : result) {
        cancelTimeout(entry);
        settledRequests.remember(entry.requestId, now);
      }
      pending.clear();
      pendingTextRequests = 0;
      pendingStatusRequests = 0;
      return result;
    }

    private List<PendingTool> drainTools(UUID requestId) {
      var result = new ArrayList<PendingTool>();
      var now = clock.millis();
      for (var iterator = pendingTools.entrySet().iterator(); iterator.hasNext(); ) {
        var entry = iterator.next();
        if (requestId == null || entry.getValue().call.requestId().equals(requestId)) {
          result.add(entry.getValue());
          settledToolCalls.remember(entry.getKey(), now);
          iterator.remove();
        }
      }
      return result;
    }

    private void cancelTools(List<PendingTool> tools, ClientToolCancellation.Reason reason) {
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

    private boolean validNegotiatedCapabilities(List<ConnectorHello.Capability> capabilities) {
      if (!capabilities.containsAll(ConnectorAuthentication.C1_CAPABILITIES)) {
        return false;
      }
      for (var capability : capabilities) {
        if (!requestedCapabilities.contains(capability)) {
          return false;
        }
      }
      return true;
    }

    private boolean withinClockSkew(String timestamp, long nowMillis) {
      try {
        return withinClockSkew(Instant.parse(timestamp), nowMillis);
      } catch (RuntimeException exception) {
        return false;
      }
    }

    private boolean withinClockSkew(Instant timestamp, long nowMillis) {
      try {
        var now = Instant.ofEpochMilli(nowMillis);
        return !timestamp.isBefore(now.minus(CLOCK_SKEW))
            && !timestamp.isAfter(now.plus(CLOCK_SKEW));
      } catch (RuntimeException exception) {
        return false;
      }
    }
  }

  private abstract static class Pending<T> {
    final UUID requestId;
    final CancellableFuture<T> future;
    ScheduledFuture<?> timeout;

    private Pending(UUID requestId, CancellableFuture<T> future) {
      this.requestId = requestId;
      this.future = future;
    }
  }

  private static final class PendingText extends Pending<TextCompletion> {
    final TextRequest request;

    private PendingText(TextRequest request, CancellableFuture<TextCompletion> future) {
      super(request.requestId(), future);
      this.request = request;
    }
  }

  private static final class PendingStatus extends Pending<RuntimeStatus> {
    private PendingStatus(UUID requestId, CancellableFuture<RuntimeStatus> future) {
      super(requestId, future);
    }
  }

  private static final class PendingTool {
    final ClientToolCall call;
    final ClientToolHandler handler;
    CompletionStage<? extends ClientToolOutcome> execution;

    private PendingTool(ClientToolCall call, ClientToolHandler handler) {
      this.call = call;
      this.handler = handler;
    }
  }

  private static final class CancellableFuture<T> extends CompletableFuture<T> {
    private Runnable cancellation = () -> {};

    private synchronized void onCancel(Runnable action) {
      cancellation = Objects.requireNonNull(action, "action");
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (!super.cancel(mayInterruptIfRunning)) {
        return false;
      }
      final Runnable action;
      synchronized (this) {
        action = cancellation;
      }
      action.run();
      return true;
    }
  }

  private sealed interface ParsedResponse
      permits ParsedComplete, ParsedError, ParsedStatus, ParsedToolCall, ParsedToolCancellation {}

  private record ParsedComplete(
      UUID sessionId,
      String text,
      long costMicroUsd,
      TextCompletion.CostKind costKind,
      List<TextCompletion.Source> sources)
      implements ParsedResponse {}

  private record ParsedError(String code, String message, boolean retryable)
      implements ParsedResponse {}

  private record ParsedToolCall(ClientToolCall call) implements ParsedResponse {}

  private record ParsedToolCancellation(ClientToolCancellation cancellation)
      implements ParsedResponse {}

  private record ParsedStatus(RuntimeStatus.State state, int activeRequests, int queuedRequests)
      implements ParsedResponse {
    private ParsedStatus {
      if (activeRequests < 0 || activeRequests > 8 || queuedRequests < 0 || queuedRequests > 128) {
        throw JsonFields.invalid("/payload");
      }
    }
  }

  private record OutboundIdentity(UUID messageId, String nonce) {}

  private static final Set<String> ERROR_CODES =
      Set.of(
          "MODEL_TIMEOUT",
          "MODEL_UNAVAILABLE",
          "MODEL_AUTHENTICATION_FAILED",
          "MODEL_RESPONSE_INVALID",
          "REQUEST_CANCELLED",
          "REQUEST_LIMITED",
          "BUDGET_EXCEEDED",
          "SESSION_NOT_FOUND",
          "CONVERSATION_STORAGE_DISABLED",
          "TOOL_REJECTED",
          "TOOL_ROUND_LIMIT",
          "RUNTIME_INTERNAL_ERROR");

  private static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          var thread = new Thread(task, "agma-connector-timeouts");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void cancelTimeout(Pending<?> pending) {
    if (pending.timeout != null) {
      pending.timeout.cancel(false);
    }
  }

  private static void cancelTimeout(ScheduledFuture<?> timeout) {
    if (timeout != null) {
      timeout.cancel(false);
    }
  }

  private static void failPending(List<Pending<?>> pending, ConnectorException error) {
    for (var entry : pending) {
      entry.future.completeExceptionally(error);
    }
  }

  private static <T> CompletionStage<T> failed(Throwable error) {
    return CompletableFuture.failedFuture(error);
  }
}
