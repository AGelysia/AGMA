package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.ConnectorHello;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JavaHttpLocalConnectorTest {
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String TOKEN =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void authenticatesAndCorrelatesCompletionStatusCancellationAndLateTerminal() throws Exception {
    try (var server = new LocalWebSocketServer();
        var connector = connector()) {
      var session = authenticate(server, connector, true);

      var requestId = uuid(20);
      var completionFuture =
          session
              .request(
                  new TextRequest(requestId, "How do I craft a piston?", Duration.ofSeconds(5)))
              .toCompletableFuture();
      var request = object(server.takeText());
      assertEquals("client.request", request.get("type"));
      assertEquals(requestId.toString(), request.get("messageId"));
      assertEquals(
          "How do I craft a piston?",
          JsonFields.object(request.get("payload"), "/payload").get("message"));

      server.sendFragmentedText(
          response(
              "client.complete",
              uuid(120),
              requestId,
              120,
              ConnectorEnvelopeCodec.map(
                  "sessionId",
                  uuid(200).toString(),
                  "text",
                  "Use planks and redstone.",
                  "costMicroUsd",
                  19,
                  "costKind",
                  "reported",
                  "sources",
                  java.util.List.of(completionSource()))));
      var completion = completionFuture.get(3, TimeUnit.SECONDS);
      assertEquals(TextCompletion.Status.COMPLETED, completion.status());
      assertEquals("Use planks and redstone.", completion.text());
      assertEquals(19, completion.costMicroUsd());
      assertEquals(TextCompletion.CostKind.REPORTED, completion.costKind());
      assertEquals(1, completion.sources().size());
      assertEquals("claim.0123456789abcdef01234567", completion.sources().get(0).claimId());
      assertEquals("https://example.com/guide", completion.sources().get(0).url().toString());
      assertEquals(TextCompletion.Match.MATCH, completion.sources().get(0).applicability().match());

      var statusFuture = session.queryStatus(Duration.ofSeconds(2)).toCompletableFuture();
      var statusRequest = object(server.takeText());
      var statusRequestId = JsonFields.uuid(statusRequest.get("messageId"), "/messageId");
      server.sendText(
          response(
              "client.status",
              uuid(121),
              statusRequestId,
              121,
              ConnectorEnvelopeCodec.map(
                  "state", "READY", "activeRequests", 0, "queuedRequests", 0)));
      assertEquals(RuntimeStatus.State.READY, statusFuture.get(3, TimeUnit.SECONDS).state());

      var cancelledId = uuid(21);
      var cancelledFuture =
          session
              .request(new TextRequest(cancelledId, "Cancel me", Duration.ofSeconds(5)))
              .toCompletableFuture();
      assertEquals("client.request", object(server.takeText()).get("type"));
      assertTrue(session.cancel(cancelledId, CancelReason.CONTEXT_CHANGED));
      assertEquals(TextCompletion.Status.CANCELLED, cancelledFuture.get().status());
      var cancellation = object(server.takeText());
      assertEquals("client.cancel", cancellation.get("type"));
      assertEquals(
          "CONTEXT_CHANGED",
          JsonFields.object(cancellation.get("payload"), "/payload").get("reason"));

      server.sendText(
          response(
              "client.complete",
              uuid(122),
              cancelledId,
              122,
              ConnectorEnvelopeCodec.map(
                  "sessionId",
                  null,
                  "text",
                  "late",
                  "costMicroUsd",
                  50_006,
                  "costKind",
                  "mixed",
                  "sources",
                  java.util.List.of())));
      var afterLate = session.queryStatus(Duration.ofSeconds(2)).toCompletableFuture();
      var afterLateRequest = object(server.takeText());
      var afterLateId = JsonFields.uuid(afterLateRequest.get("messageId"), "/messageId");
      server.sendText(
          response(
              "client.status",
              uuid(123),
              afterLateId,
              123,
              ConnectorEnvelopeCodec.map(
                  "state", "READY", "activeRequests", 0, "queuedRequests", 0)));
      assertEquals(RuntimeStatus.State.READY, afterLate.get(3, TimeUnit.SECONDS).state());

      var futureCancelledId = uuid(22);
      var futureCancelled =
          session
              .request(new TextRequest(futureCancelledId, "Future cancel", Duration.ofSeconds(5)))
              .toCompletableFuture();
      server.takeText();
      assertTrue(futureCancelled.cancel(false));
      assertThrows(CancellationException.class, futureCancelled::join);
      assertEquals("client.cancel", object(server.takeText()).get("type"));

      session.close().toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertEquals(ConnectorSessionState.CLOSED, session.state());
      server.assertHealthy();
    }
  }

  @Test
  void rejectsAnInvalidRuntimeProof() throws Exception {
    try (var server = new LocalWebSocketServer();
        var connector = connector()) {
      var token = SecretMaterial.fromUtf8(TOKEN);
      var connected = connector.connect(TestProfiles.environment(server.port()), token);
      token.close();
      var request = ConnectorHelloCodec.decode(server.takeText());
      server.sendText(ConnectorHelloCodec.encode(runtimeHello(request, false)));

      var failure =
          assertThrows(
              ExecutionException.class,
              () -> connected.toCompletableFuture().get(3, TimeUnit.SECONDS));
      assertEquals(
          "AUTHENTICATION_FAILED",
          assertInstanceOf(ConnectorException.class, failure.getCause()).code());
    }
  }

  @Test
  void disconnectFailsEveryPendingRequestAndClearsTheSession() throws Exception {
    try (var server = new LocalWebSocketServer();
        var connector = connector()) {
      var session = authenticate(server, connector, true);
      var pending =
          session
              .request(new TextRequest(uuid(23), "Stay pending", Duration.ofSeconds(5)))
              .toCompletableFuture();
      server.takeText();
      server.disconnect();

      var failure = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
      assertInstanceOf(ConnectorException.class, failure.getCause());
      assertEquals(ConnectorSessionState.ERROR, session.state());
    }
  }

  @Test
  void routesNegotiatedToolResultsAndRuntimeCancellation() throws Exception {
    try (var server = new LocalWebSocketServer();
        var connector = connector()) {
      var profile =
          TestProfiles.withTools(server.port(), java.util.List.of("game.resource.search"));
      var session = authenticate(server, connector, profile, true);
      assertEquals(Set.of("game.resource.search"), session.activeTools());

      var slow = new CompletableFuture<ClientToolOutcome>();
      var cancelled = new AtomicReference<ClientToolCancellation>();
      var cancellationReceived = new CountDownLatch(1);
      session.setToolHandler(
          new ClientToolHandler() {
            @Override
            public java.util.concurrent.CompletionStage<? extends ClientToolOutcome> execute(
                ClientToolCall call) {
              if (call.arguments().get("query").equals("slow")) {
                return slow;
              }
              return CompletableFuture.completedFuture(
                  new ClientToolResult(
                      ConnectorEnvelopeCodec.map(
                          "generationId",
                          "generation-1",
                          "visibility",
                          "no_world",
                          "completeness",
                          "complete",
                          "candidates",
                          java.util.List.of(),
                          "ambiguous",
                          false,
                          "truncated",
                          false,
                          "warnings",
                          java.util.List.of())));
            }

            @Override
            public void cancel(ClientToolCancellation cancellation) {
              cancelled.set(cancellation);
              cancellationReceived.countDown();
            }
          });

      var requestId = uuid(30);
      var text =
          session
              .request(new TextRequest(requestId, "Find iron", Duration.ofSeconds(5)))
              .toCompletableFuture();
      server.takeText();
      var toolCallId = uuid(230);
      server.sendText(
          response(
              "client.tool.call",
              uuid(130),
              requestId,
              130,
              toolCallPayload(requestId, toolCallId, "iron")));
      var result = object(server.takeText());
      assertEquals("client.tool.result", result.get("type"));
      assertEquals(requestId.toString(), result.get("requestId"));
      var resultPayload = JsonFields.object(result.get("payload"), "/payload");
      assertEquals(toolCallId.toString(), resultPayload.get("toolCallId"));
      assertEquals("game.resource.search", resultPayload.get("tool"));
      server.sendText(
          response(
              "client.complete",
              uuid(131),
              requestId,
              131,
              ConnectorEnvelopeCodec.map(
                  "sessionId",
                  null,
                  "text",
                  "Found iron.",
                  "costMicroUsd",
                  100_000,
                  "costKind",
                  "estimated",
                  "sources",
                  java.util.List.of())));
      var completed = text.get(3, TimeUnit.SECONDS);
      assertEquals(TextCompletion.Status.COMPLETED, completed.status());
      assertEquals(100_000, completed.costMicroUsd());
      assertEquals(TextCompletion.CostKind.ESTIMATED, completed.costKind());

      var slowRequestId = uuid(31);
      var slowText =
          session
              .request(new TextRequest(slowRequestId, "Slow search", Duration.ofSeconds(5)))
              .toCompletableFuture();
      server.takeText();
      var slowToolCallId = uuid(231);
      server.sendText(
          response(
              "client.tool.call",
              uuid(132),
              slowRequestId,
              132,
              toolCallPayload(slowRequestId, slowToolCallId, "slow")));
      server.sendText(
          response(
              "client.tool.cancel",
              uuid(133),
              slowRequestId,
              133,
              ConnectorEnvelopeCodec.map(
                  "requestId",
                  slowRequestId.toString(),
                  "toolCallId",
                  slowToolCallId.toString(),
                  "subjectId",
                  TestProfiles.INSTALLATION_ID.toString(),
                  "tool",
                  "game.resource.search",
                  "sequence",
                  0,
                  "reason",
                  "TOOL_TIMEOUT")));
      assertTrue(cancellationReceived.await(3, TimeUnit.SECONDS));
      assertEquals(ClientToolCancellation.Reason.TOOL_TIMEOUT, cancelled.get().reason());
      assertTrue(slow.isCancelled());
      server.sendText(
          response(
              "client.error",
              uuid(134),
              slowRequestId,
              134,
              ConnectorEnvelopeCodec.map(
                  "code",
                  "MODEL_TIMEOUT",
                  "message",
                  "The request timed out.",
                  "retryable",
                  true)));
      assertEquals(TextCompletion.Status.TIMED_OUT, slowText.get(3, TimeUnit.SECONDS).status());
    }
  }

  private static JavaHttpLocalConnector connector() {
    var scheduler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "connector-test-timeouts");
              thread.setDaemon(true);
              return thread;
            });
    var entropyCounter = new AtomicInteger(1);
    var identifierCounter = new AtomicLong(1);
    return new JavaHttpLocalConnector(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        scheduler,
        CLOCK,
        bytes -> Arrays.fill(bytes, (byte) entropyCounter.getAndIncrement()),
        () -> uuid(identifierCounter.getAndIncrement()),
        "0.2.0",
        true);
  }

  private static LocalConnector.Session authenticate(
      LocalWebSocketServer server, JavaHttpLocalConnector connector, boolean validProof)
      throws Exception {
    return authenticate(server, connector, TestProfiles.environment(server.port()), validProof);
  }

  private static LocalConnector.Session authenticate(
      LocalWebSocketServer server,
      JavaHttpLocalConnector connector,
      dev.minecraftagent.standalone.core.contract.RuntimeClientProfile profile,
      boolean validProof)
      throws Exception {
    var token = SecretMaterial.fromUtf8(TOKEN);
    var connected = connector.connect(profile, token);
    token.close();
    var request = ConnectorHelloCodec.decode(server.takeText());
    server.sendText(ConnectorHelloCodec.encode(runtimeHello(request, validProof)));
    return connected.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  private static ConnectorHello runtimeHello(ConnectorHello request, boolean validProof) {
    var unsigned =
        new ConnectorHello(
            "1.0",
            ConnectorHello.CONNECTOR_KIND,
            ConnectorHello.TYPE,
            uuid(100),
            request.messageId(),
            NOW.toString(),
            nonce(100),
            "runtime",
            "0.2.0",
            request.scopeId(),
            java.util.List.of(ConnectorHello.PROTOCOL_VERSION),
            ConnectorHello.PROTOCOL_VERSION,
            request.capabilities(),
            new ConnectorHello.Authentication(
                ConnectorHello.Authentication.SCHEME,
                ConnectorAuthentication.KEY_ID,
                request.authentication().challenge(),
                "A".repeat(43)));
    var signed =
        ConnectorAuthentication.withProof(unsigned, TOKEN.getBytes(StandardCharsets.UTF_8));
    if (validProof) {
      return signed;
    }
    return new ConnectorHello(
        signed.schemaVersion(),
        signed.connectorKind(),
        signed.type(),
        signed.messageId(),
        signed.requestId(),
        signed.timestamp(),
        signed.nonce(),
        signed.component(),
        signed.componentVersion(),
        signed.scopeId(),
        signed.supportedProtocolVersions(),
        signed.selectedProtocolVersion(),
        signed.capabilities(),
        new ConnectorHello.Authentication(
            signed.authentication().scheme(),
            signed.authentication().keyId(),
            signed.authentication().challenge(),
            "A".repeat(43)));
  }

  private static String response(
      String type, UUID messageId, UUID requestId, int nonce, Map<String, Object> payload) {
    return StrictJson.write(
        ConnectorEnvelopeCodec.map(
            "schemaVersion",
            "1.0",
            "connectorKind",
            ConnectorHello.CONNECTOR_KIND,
            "protocolVersion",
            ConnectorHello.PROTOCOL_VERSION,
            "messageId",
            messageId.toString(),
            "requestId",
            requestId.toString(),
            "scopeId",
            TestProfiles.INSTALLATION_ID.toString(),
            "type",
            type,
            "timestamp",
            NOW.toString(),
            "nonce",
            nonce(nonce),
            "payload",
            payload));
  }

  private static Map<String, Object> toolCallPayload(
      UUID requestId, UUID toolCallId, String query) {
    return ConnectorEnvelopeCodec.map(
        "requestId",
        requestId.toString(),
        "toolCallId",
        toolCallId.toString(),
        "subjectId",
        TestProfiles.INSTALLATION_ID.toString(),
        "tool",
        "game.resource.search",
        "sequence",
        0,
        "arguments",
        ConnectorEnvelopeCodec.map("query", query, "limit", 5));
  }

  private static Map<String, Object> completionSource() {
    return ConnectorEnvelopeCodec.map(
        "claimId", "claim.0123456789abcdef01234567",
        "title", "Example guide",
        "url", "https://example.com/guide",
        "publisher", "Example",
        "retrievedAt", NOW.toString(),
        "applicability",
            ConnectorEnvelopeCodec.map(
                "minecraftVersion",
                "1.21.11",
                "modVersions",
                Map.of("minecraft", "1.21.11"),
                "modpackVersion",
                null,
                "match",
                "match"),
        "warnings", java.util.List.of());
  }

  private static Map<String, Object> object(String source) {
    return JsonFields.object(StrictJson.parse(source), "/");
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }

  private static String nonce(int value) {
    var bytes = new byte[16];
    Arrays.fill(bytes, (byte) value);
    return ConnectorAuthentication.encodeNonce(bytes);
  }

  private static final class LocalWebSocketServer implements AutoCloseable {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(32);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread thread;
    private volatile Socket connection;
    private volatile DataOutputStream output;
    private volatile boolean closed;

    private LocalWebSocketServer() throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      thread = new Thread(this::serve, "local-websocket-test-server");
      thread.setDaemon(true);
      thread.start();
    }

    private int port() {
      return server.getLocalPort();
    }

    private String takeText() throws Exception {
      var message = messages.poll(3, TimeUnit.SECONDS);
      if (message == null) {
        assertHealthy();
        throw new AssertionError("Timed out waiting for a WebSocket text frame");
      }
      return message;
    }

    private void sendText(String text) throws Exception {
      sendFrame(true, 1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFragmentedText(String text) throws Exception {
      var bytes = text.getBytes(StandardCharsets.UTF_8);
      var split = Math.max(1, bytes.length / 2);
      synchronized (this) {
        writeFrame(false, 1, Arrays.copyOfRange(bytes, 0, split));
        writeFrame(true, 0, Arrays.copyOfRange(bytes, split, bytes.length));
      }
    }

    private void disconnect() throws IOException {
      var socket = connection;
      if (socket != null) {
        socket.close();
      }
    }

    private void assertHealthy() {
      var error = failure.get();
      if (error != null) {
        throw new AssertionError("Local WebSocket server failed", error);
      }
    }

    private void serve() {
      try (var socket = server.accept()) {
        connection = socket;
        var input = socket.getInputStream();
        var request = readHttpRequest(input);
        var key = header(request, "Sec-WebSocket-Key");
        var accept =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
        output = new DataOutputStream(socket.getOutputStream());
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: "
                    + accept
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        readFrames(new DataInputStream(input));
      } catch (EOFException | SocketException exception) {
        if (!closed && connection != null && !connection.isClosed()) {
          failure.compareAndSet(null, exception);
        }
      } catch (Throwable exception) {
        if (!closed) {
          failure.compareAndSet(null, exception);
        }
      }
    }

    private void readFrames(DataInputStream input) throws Exception {
      while (!closed) {
        var first = input.readUnsignedByte();
        var second = input.readUnsignedByte();
        var opcode = first & 0x0f;
        var length = second & 0x7f;
        if (length == 126) {
          length = input.readUnsignedShort();
        } else if (length == 127) {
          var longLength = input.readLong();
          if (longLength < 0 || longLength > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
            throw new IOException("Invalid client frame length");
          }
          length = (int) longLength;
        }
        if ((second & 0x80) == 0 || length > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
          throw new IOException("Invalid client frame");
        }
        var mask = input.readNBytes(4);
        var payload = input.readNBytes(length);
        if (mask.length != 4 || payload.length != length) {
          throw new EOFException();
        }
        for (var index = 0; index < payload.length; index++) {
          payload[index] ^= mask[index % 4];
        }
        if (opcode == 1) {
          messages.put(new String(payload, StandardCharsets.UTF_8));
        } else if (opcode == 8) {
          sendFrame(true, 8, payload);
          return;
        } else if (opcode == 9) {
          sendFrame(true, 10, payload);
        }
      }
    }

    private synchronized void sendFrame(boolean fin, int opcode, byte[] payload) throws Exception {
      writeFrame(fin, opcode, payload);
    }

    private void writeFrame(boolean fin, int opcode, byte[] payload) throws Exception {
      var target = output;
      if (target == null) {
        throw new IOException("WebSocket upgrade is incomplete");
      }
      target.writeByte((fin ? 0x80 : 0) | opcode);
      if (payload.length <= 125) {
        target.writeByte(payload.length);
      } else if (payload.length <= 65_535) {
        target.writeByte(126);
        target.writeShort(payload.length);
      } else {
        target.writeByte(127);
        target.writeLong(payload.length);
      }
      target.write(payload);
      target.flush();
    }

    private static String readHttpRequest(java.io.InputStream input) throws IOException {
      var bytes = new ByteArrayOutputStream();
      var matched = 0;
      while (bytes.size() < 8192 && matched < 4) {
        var value = input.read();
        if (value < 0) {
          throw new EOFException();
        }
        bytes.write(value);
        matched =
            switch (matched) {
              case 0 -> value == '\r' ? 1 : 0;
              case 1 -> value == '\n' ? 2 : 0;
              case 2 -> value == '\r' ? 3 : 0;
              case 3 -> value == '\n' ? 4 : 0;
              default -> matched;
            };
      }
      if (matched != 4) {
        throw new IOException("Invalid HTTP upgrade request");
      }
      return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static String header(String request, String name) throws IOException {
      for (var line : request.split("\\r\\n")) {
        var delimiter = line.indexOf(':');
        if (delimiter > 0 && line.substring(0, delimiter).equalsIgnoreCase(name)) {
          return line.substring(delimiter + 1).trim();
        }
      }
      throw new IOException("Missing WebSocket header");
    }

    @Override
    public void close() throws Exception {
      closed = true;
      var socket = connection;
      if (socket != null) {
        socket.close();
      }
      server.close();
      thread.join(1000);
    }
  }
}
