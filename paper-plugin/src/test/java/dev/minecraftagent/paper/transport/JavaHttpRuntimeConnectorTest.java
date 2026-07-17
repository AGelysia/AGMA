package dev.minecraftagent.paper.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JavaHttpRuntimeConnectorTest {
  private static final String TOKEN = "phase-3-public-test-token-32-characters";
  private ExecutorService executor;
  private FakeRuntime runtime;

  @AfterEach
  void closeResources() throws InterruptedException {
    if (runtime != null) {
      runtime.stop(1000);
    }
    if (executor != null) {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void authenticatesAgainstARealWebSocketPeerAndRetainsTheConnection() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);

    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertTrue(connection.isOpen());
    assertFalse(connection.whenClosed().toCompletableFuture().isDone());
    connection.close();
    connection.whenClosed().toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertFalse(connection.isOpen());
  }

  @Test
  void exchangesMultipleApplicationMessagesAfterAuthentication() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);
    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);
    var received = java.util.Collections.synchronizedList(new ArrayList<String>());
    connection.setApplicationHandler(received::add);

    connection.sendApplication("paper-one").toCompletableFuture().get(1, TimeUnit.SECONDS);
    connection.sendApplication("paper-two").toCompletableFuture().get(1, TimeUnit.SECONDS);
    await(() -> runtime.applicationMessages().size() == 2);

    runtime.sendApplication("runtime-one");
    runtime.sendApplication("runtime-two");
    await(() -> received.size() == 2);

    assertEquals(List.of("paper-one", "paper-two"), runtime.applicationMessages());
    assertEquals(List.of("runtime-one", "runtime-two"), received);
    assertTrue(connection.isOpen());
  }

  @Test
  void closesWhenApplicationMessageArrivesBeforeHandlerBinding() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);
    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);

    runtime.sendApplication("unexpected");

    connection.whenClosed().toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertFalse(connection.isOpen());
  }

  @Test
  void closesWhenApplicationHandlerThrows() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);
    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);
    connection.setApplicationHandler(
        ignored -> {
          throw new IllegalArgumentException("invalid application envelope");
        });

    runtime.sendApplication("invalid");

    connection.whenClosed().toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertFalse(connection.isOpen());
  }

  @Test
  void rejectsOversizedOutboundApplicationMessageWithoutWritingIt() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);
    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);
    connection.setApplicationHandler(ignored -> {});

    var failure =
        assertFailure(
            connection.sendApplication(
                "x".repeat(JavaHttpRuntimeConnector.MAX_APPLICATION_BYTES + 1)),
            "APPLICATION_MESSAGE_TOO_LARGE");

    assertEquals("protocol", failure.stage());
    assertEquals(List.of(), runtime.applicationMessages());
    assertTrue(connection.isOpen());
  }

  @Test
  void closesOnOversizedInboundApplicationMessage() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, TOKEN);
    var connection = connect(runtime.port()).toCompletableFuture().get(3, TimeUnit.SECONDS);
    connection.setApplicationHandler(ignored -> {});

    runtime.sendApplication("x".repeat(JavaHttpRuntimeConnector.MAX_APPLICATION_BYTES + 1));

    connection.whenClosed().toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertFalse(connection.isOpen());
  }

  @Test
  void rejectsAProofCreatedWithTheWrongToken() throws Exception {
    runtime = FakeRuntime.start(Mode.ACCEPT, "different-public-test-token-32-characters");

    assertFailure(connect(runtime.port()), "TOKEN_AUTH_FAILED");
  }

  @Test
  void mapsOnlyWhitelistedRuntimeCloseReasonsToStableCodes() throws Exception {
    runtime = FakeRuntime.start(Mode.AUTHENTICATION_CLOSE, TOKEN);

    assertFailure(connect(runtime.port()), "TOKEN_AUTH_FAILED");
  }

  @Test
  void rejectsAnIncompatibleProtocolSelection() throws Exception {
    runtime = FakeRuntime.start(Mode.INCOMPATIBLE, TOKEN);

    assertFailure(connect(runtime.port()), "PROTOCOL_INCOMPATIBLE");
  }

  @Test
  void rejectsStaleRuntimeHello() throws Exception {
    runtime = FakeRuntime.start(Mode.STALE, TOKEN);

    assertFailure(connect(runtime.port()), "HANDSHAKE_STALE");
  }

  @Test
  void rejectsMismatchedServerIdentity() throws Exception {
    runtime = FakeRuntime.start(Mode.SERVER_MISMATCH, TOKEN);

    assertFailure(connect(runtime.port()), "SERVER_ID_MISMATCH");
  }

  @Test
  void treatsAChallengeMismatchAsAuthenticationFailure() throws Exception {
    runtime = FakeRuntime.start(Mode.WRONG_CHALLENGE, TOKEN);

    assertFailure(connect(runtime.port()), "TOKEN_AUTH_FAILED");
  }

  @Test
  void retainsReplayStateAcrossWebSocketConnections() throws Exception {
    runtime = FakeRuntime.start(Mode.FIXED_REPLAY_IDENTITY, TOKEN);
    executor = Executors.newSingleThreadExecutor();
    var connector = new JavaHttpRuntimeConnector(executor);

    var first = connector.connect(settings(runtime.port())).toCompletableFuture().get();
    first.close();

    assertFailure(connector.connect(settings(runtime.port())), "HANDSHAKE_REPLAYED");
  }

  @Test
  void rejectsDuplicateJsonObjectKeys() throws Exception {
    runtime = FakeRuntime.start(Mode.DUPLICATE_KEY, TOKEN);

    assertFailure(connect(runtime.port()), "HANDSHAKE_MESSAGE_INVALID");
  }

  @Test
  void boundsTheHandshakeBeforeJsonParsing() throws Exception {
    runtime = FakeRuntime.start(Mode.OVERSIZED, TOKEN);

    assertFailure(connect(runtime.port()), "HANDSHAKE_MESSAGE_TOO_LARGE");
  }

  @Test
  void timesOutWhenRuntimeDoesNotAnswerTheHello() throws Exception {
    runtime = FakeRuntime.start(Mode.SILENT, TOKEN);

    assertFailure(connect(runtime.port()), "HANDSHAKE_TIMEOUT");
  }

  @Test
  void cancellingASilentHandshakeClosesTheSocketAndCompletion() throws Exception {
    runtime = FakeRuntime.start(Mode.SILENT, TOKEN);
    executor = Executors.newSingleThreadExecutor();
    var connector = new JavaHttpRuntimeConnector(executor);
    var attempt = connector.begin(settings(runtime.port()));
    await(() -> !runtime.getConnections().isEmpty());

    attempt.cancel();

    await(() -> attempt.result().toCompletableFuture().isDone());
    await(() -> runtime.getConnections().isEmpty());
    assertTrue(attempt.result().toCompletableFuture().isCompletedExceptionally());
  }

  @Test
  void reportsAnUnavailableRuntimeWithoutLeakingTheSocketError() throws Exception {
    var port = unusedPort();

    var failure = assertFailure(connect(port), "RUNTIME_UNREACHABLE");

    assertEquals("RUNTIME_UNREACHABLE", failure.getMessage());
    assertEquals(0, failure.getStackTrace().length);
  }

  private java.util.concurrent.CompletionStage<AuthenticatedRuntimeConnection> connect(int port) {
    executor = Executors.newSingleThreadExecutor();
    var connector = new JavaHttpRuntimeConnector(executor);
    return connector.connect(settings(port));
  }

  private static RuntimeConnectionSettings settings(int port) {
    return new RuntimeConnectionSettings(
        URI.create("ws://127.0.0.1:" + port + "/agent"),
        "survival-main",
        TOKEN,
        "0.1.0-SNAPSHOT",
        Duration.ofMillis(500),
        Duration.ofMillis(250));
  }

  private static RuntimeConnectionFailure assertFailure(
      java.util.concurrent.CompletionStage<?> stage, String code) throws Exception {
    try {
      stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
      throw new AssertionError("connection unexpectedly succeeded");
    } catch (ExecutionException error) {
      var cause = error.getCause();
      while (cause instanceof java.util.concurrent.CompletionException
          && cause.getCause() != null) {
        cause = cause.getCause();
      }
      var failure = assertInstanceOf(RuntimeConnectionFailure.class, cause);
      assertEquals(code, failure.code());
      return failure;
    }
  }

  private static int unusedPort() throws IOException {
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  private enum Mode {
    ACCEPT,
    INCOMPATIBLE,
    STALE,
    SERVER_MISMATCH,
    WRONG_CHALLENGE,
    FIXED_REPLAY_IDENTITY,
    DUPLICATE_KEY,
    OVERSIZED,
    AUTHENTICATION_CLOSE,
    SILENT
  }

  private static final class FakeRuntime extends WebSocketServer {
    private static final Gson JSON = new Gson();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Mode mode;
    private final String token;
    private final Set<WebSocket> authenticated = ConcurrentHashMap.newKeySet();
    private final List<String> applicationMessages =
        java.util.Collections.synchronizedList(new ArrayList<>());

    private FakeRuntime(Mode mode, String token) {
      super(new InetSocketAddress("127.0.0.1", 0), 1);
      this.mode = mode;
      this.token = token;
      setReuseAddr(false);
      setConnectionLostTimeout(0);
    }

    static FakeRuntime start(Mode mode, String token) throws InterruptedException {
      var server = new FakeRuntime(mode, token);
      server.start();
      assertTrue(server.started.await(2, TimeUnit.SECONDS));
      return server;
    }

    int port() {
      return getPort();
    }

    List<String> applicationMessages() {
      synchronized (applicationMessages) {
        return List.copyOf(applicationMessages);
      }
    }

    void sendApplication(String message) {
      var connection = authenticated.stream().findFirst().orElseThrow();
      connection.send(message);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
      if (!"/agent".equals(handshake.getResourceDescriptor())) {
        connection.close(1008);
      }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
      authenticated.remove(connection);
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
      if (authenticated.contains(connection)) {
        applicationMessages.add(message);
        return;
      }
      if (mode == Mode.SILENT) {
        return;
      }
      if (mode == Mode.AUTHENTICATION_CLOSE) {
        connection.close(1008, "AUTHENTICATION_FAILED");
        return;
      }
      if (mode == Mode.OVERSIZED) {
        connection.send("x".repeat(JavaHttpRuntimeConnector.MAX_HANDSHAKE_BYTES + 1));
        return;
      }

      var request = JSON.fromJson(message, JsonObject.class);
      var response = response(request);
      var encoded = response.toString();
      if (mode == Mode.DUPLICATE_KEY) {
        encoded =
            encoded.replaceFirst(
                "\\{\\\"protocolVersion\\\":\\\"1.0\\\"",
                "{\\\"protocolVersion\\\":\\\"1.0\\\",\\\"protocolVersion\\\":\\\"1.0\\\"");
      }
      authenticated.add(connection);
      connection.send(encoded);
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer message) {
      connection.close(1003);
    }

    @Override
    public void onError(WebSocket connection, Exception error) {
      if (connection == null && started.getCount() > 0) {
        started.countDown();
      }
    }

    @Override
    public void onStart() {
      started.countDown();
    }

    private JsonObject response(JsonObject request) {
      var requestPayload = request.getAsJsonObject("payload");
      var requestAuthentication = requestPayload.getAsJsonObject("authentication");
      var timestamp =
          (mode == Mode.STALE ? Instant.now().minusSeconds(31) : Instant.now()).toString();
      var nonce =
          mode == Mode.FIXED_REPLAY_IDENTITY
              ? "AAECAwQFBgcICQoLDA0ODw"
              : Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      UUID.randomUUID()
                          .toString()
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var challenge =
          mode == Mode.WRONG_CHALLENGE
              ? "ICEiIyQlJicoKSorLC0uLw"
              : requestAuthentication.get("challenge").getAsString();
      var serverId =
          mode == Mode.SERVER_MISMATCH ? "different-server" : request.get("serverId").getAsString();
      var proof =
          HandshakeProof.compute(
              token, serverId, "runtime.hello", timestamp, nonce, "runtime", "0.1.0", challenge);

      var authentication = new JsonObject();
      authentication.addProperty("scheme", "hmac-sha256");
      authentication.addProperty("keyId", serverId);
      authentication.addProperty("challenge", challenge);
      authentication.addProperty("proof", proof);

      var payload = new JsonObject();
      payload.addProperty("component", "runtime");
      payload.addProperty("componentVersion", "0.1.0");
      var versions = new JsonArray();
      versions.add("1.0");
      payload.add("supportedProtocolVersions", versions);
      if (mode == Mode.INCOMPATIBLE) {
        payload.add("selectedProtocolVersion", JsonNull.INSTANCE);
      } else {
        payload.addProperty("selectedProtocolVersion", "1.0");
      }
      payload.add("authentication", authentication);

      var envelope = new JsonObject();
      envelope.addProperty("protocolVersion", "1.0");
      envelope.addProperty(
          "messageId",
          mode == Mode.FIXED_REPLAY_IDENTITY
              ? "33333333-3333-4333-8333-333333333333"
              : UUID.randomUUID().toString());
      envelope.addProperty("requestId", request.get("requestId").getAsString());
      envelope.addProperty("serverId", serverId);
      envelope.addProperty("type", "runtime.hello");
      envelope.addProperty("timestamp", timestamp);
      envelope.addProperty("nonce", nonce);
      envelope.add("payload", payload);
      return envelope;
    }
  }
}
