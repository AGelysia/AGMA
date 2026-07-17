package dev.minecraftagent.paper.transport;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class JavaHttpRuntimeConnector implements RuntimeConnector {
  static final int MAX_HANDSHAKE_BYTES = 16 * 1024;
  static final int MAX_APPLICATION_BYTES = 64 * 1024;
  static final int MAX_PENDING_APPLICATION_SENDS = 64;

  private final HttpClient httpClient;
  private final HandshakeCodec codec;

  public JavaHttpRuntimeConnector(Executor executor) {
    this(
        HttpClient.newBuilder()
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.systemUTC());
  }

  JavaHttpRuntimeConnector(HttpClient httpClient, Clock clock) {
    this.httpClient = Objects.requireNonNull(httpClient);
    this.codec = new HandshakeCodec(new SecureRandom(), () -> clock.instant(), new ReplayCache());
  }

  @Override
  public CompletionStage<AuthenticatedRuntimeConnection> connect(
      RuntimeConnectionSettings settings) {
    return begin(settings).result();
  }

  @Override
  public RuntimeConnectAttempt begin(RuntimeConnectionSettings settings) {
    var listener = new HandshakeListener();
    var hello = codec.createPaperHello(settings);
    var socketReference = new AtomicReference<WebSocket>();
    var cancelled = new AtomicBoolean();

    var rawSocketFuture =
        httpClient
            .newWebSocketBuilder()
            .connectTimeout(settings.connectTimeout())
            .buildAsync(settings.endpoint(), listener);
    rawSocketFuture.whenComplete(
        (socket, error) -> {
          if (socket != null && cancelled.get()) {
            socket.abort();
          }
        });
    var socketFuture =
        rawSocketFuture
            .orTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .handle(
                (socket, error) -> {
                  if (error == null) {
                    return socket;
                  }
                  if (unwrap(error) instanceof TimeoutException) {
                    throw new CompletionException(
                        new RuntimeConnectionFailure("RUNTIME_CONNECT_TIMEOUT", "runtime-connect"));
                  }
                  throw new CompletionException(
                      new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
                });

    var result =
        socketFuture
            .thenCompose(
                socket -> {
                  socketReference.set(socket);
                  listener.attach(socket);
                  var response =
                      listener
                          .firstMessage()
                          .orTimeout(settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                  return socket
                      .sendText(hello.text(), true)
                      .orTimeout(settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS)
                      .thenCompose(ignored -> response);
                })
            .thenApply(
                response -> {
                  codec.validateRuntimeHello(response, hello, settings);
                  var socket = socketReference.get();
                  if (socket == null || listener.closed().isDone()) {
                    throw new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
                  }
                  listener.markAuthenticated();
                  return (AuthenticatedRuntimeConnection)
                      new JavaHttpAuthenticatedConnection(socket, listener);
                });

    var completion =
        result
            .handle(
                (connection, error) -> {
                  if (error == null && !cancelled.get()) {
                    return connection;
                  }
                  if (connection != null) {
                    connection.close();
                  }
                  var socket = socketReference.get();
                  if (socket != null) {
                    socket.abort();
                  }
                  if (cancelled.get()) {
                    throw new CompletionException(
                        new RuntimeConnectionFailure("SELF_CHECK_CANCELLED", "lifecycle"));
                  }
                  throw mapFailure(error);
                })
            .toCompletableFuture();
    return new RuntimeConnectAttempt() {
      @Override
      public CompletionStage<AuthenticatedRuntimeConnection> result() {
        return completion;
      }

      @Override
      public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
          return;
        }
        rawSocketFuture.cancel(true);
        socketFuture.cancel(true);
        completion.cancel(true);
        listener.abort();
        var socket = socketReference.get();
        if (socket != null) {
          socket.abort();
        }
      }
    };
  }

  private static CompletionException mapFailure(Throwable error) {
    var cause = unwrap(error);
    if (cause instanceof RuntimeConnectionFailure failure) {
      return new CompletionException(failure);
    }
    if (cause instanceof TimeoutException) {
      return new CompletionException(
          new RuntimeConnectionFailure("HANDSHAKE_TIMEOUT", "runtime-connect"));
    }
    return new CompletionException(
        new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
  }

  private static Throwable unwrap(Throwable error) {
    var current = error;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String strictUtf8(
      CharSequence text, int maximumBytes, String tooLargeCode, String invalidCode, String stage) {
    if (text.length() > maximumBytes) {
      throw new RuntimeConnectionFailure(tooLargeCode, stage);
    }
    try {
      var encoder =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      var encoded = encoder.encode(CharBuffer.wrap(text));
      if (encoded.remaining() > maximumBytes) {
        throw new RuntimeConnectionFailure(tooLargeCode, stage);
      }
      var bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (CharacterCodingException error) {
      throw new RuntimeConnectionFailure(invalidCode, stage);
    }
  }

  private static final class HandshakeListener implements WebSocket.Listener {
    private final CompletableFuture<String> firstMessage = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();
    private final AtomicReference<Consumer<String>> applicationHandler = new AtomicReference<>();
    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private boolean receivedHandshake;

    void attach(WebSocket socket) {
      this.socket = socket;
      socket.request(1);
    }

    CompletableFuture<String> firstMessage() {
      return firstMessage;
    }

    CompletableFuture<Void> closed() {
      return closed;
    }

    synchronized void markAuthenticated() {
      if (!receivedHandshake || authenticated) {
        throw new IllegalStateException("Invalid WebSocket authentication transition");
      }
      fragments.setLength(0);
      authenticated = true;
      var current = socket;
      if (current != null) {
        current.request(1);
      }
    }

    void setApplicationHandler(Consumer<String> handler) {
      Objects.requireNonNull(handler);
      if (!applicationHandler.compareAndSet(null, handler)) {
        throw new IllegalStateException("Application handler is already bound");
      }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      socket = webSocket;
    }

    @Override
    public synchronized CompletionStage<?> onText(
        WebSocket webSocket, CharSequence data, boolean last) {
      if (authenticated) {
        return onApplicationText(webSocket, data, last);
      }
      if (receivedHandshake || fragments.length() + data.length() > MAX_HANDSHAKE_BYTES) {
        fail(
            new RuntimeConnectionFailure(
                receivedHandshake ? "HANDSHAKE_MESSAGE_INVALID" : "HANDSHAKE_MESSAGE_TOO_LARGE",
                "authentication"));
        abortSocket(webSocket);
        return null;
      }
      fragments.append(data);
      if (!last) {
        webSocket.request(1);
        return null;
      }
      receivedHandshake = true;
      try {
        firstMessage.complete(
            strictUtf8(
                fragments,
                MAX_HANDSHAKE_BYTES,
                "HANDSHAKE_MESSAGE_TOO_LARGE",
                "HANDSHAKE_MESSAGE_INVALID",
                "authentication"));
      } catch (RuntimeConnectionFailure failure) {
        fail(failure);
        abortSocket(webSocket);
      }
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      if (!authenticated) {
        fail(new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication"));
      }
      abortSocket(webSocket);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      if (!authenticated) {
        firstMessage.completeExceptionally(closeFailure(statusCode, reason));
      }
      closed.complete(null);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      fail(new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
      closed.complete(null);
    }

    private void fail(RuntimeConnectionFailure failure) {
      firstMessage.completeExceptionally(failure);
    }

    private CompletionStage<?> onApplicationText(
        WebSocket webSocket, CharSequence data, boolean last) {
      if (fragments.length() + data.length() > MAX_APPLICATION_BYTES) {
        abortSocket(webSocket);
        return null;
      }
      fragments.append(data);
      if (!last) {
        webSocket.request(1);
        return null;
      }

      String message;
      try {
        message =
            strictUtf8(
                fragments,
                MAX_APPLICATION_BYTES,
                "APPLICATION_MESSAGE_TOO_LARGE",
                "APPLICATION_MESSAGE_INVALID",
                "protocol");
      } catch (RuntimeConnectionFailure failure) {
        abortSocket(webSocket);
        return null;
      } finally {
        fragments.setLength(0);
      }
      var handler = applicationHandler.get();
      if (handler == null) {
        abortSocket(webSocket);
        return null;
      }
      try {
        handler.accept(message);
      } catch (RuntimeException error) {
        abortSocket(webSocket);
        return null;
      }
      webSocket.request(1);
      return null;
    }

    private void abortSocket(WebSocket webSocket) {
      webSocket.abort();
      closed.complete(null);
    }

    private static RuntimeConnectionFailure closeFailure(int statusCode, String reason) {
      if (statusCode != 1008) {
        return new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
      }
      return switch (reason) {
        case "AUTHENTICATION_FAILED" ->
            new RuntimeConnectionFailure("TOKEN_AUTH_FAILED", "authentication");
        case "PROTOCOL_INCOMPATIBLE" ->
            new RuntimeConnectionFailure("PROTOCOL_INCOMPATIBLE", "protocol");
        case "HANDSHAKE_REPLAYED" ->
            new RuntimeConnectionFailure("HANDSHAKE_REPLAYED", "authentication");
        case "HANDSHAKE_STALE" -> new RuntimeConnectionFailure("HANDSHAKE_STALE", "authentication");
        case "HANDSHAKE_TIMEOUT" ->
            new RuntimeConnectionFailure("HANDSHAKE_TIMEOUT", "runtime-connect");
        case "HANDSHAKE_INVALID", "UNSUPPORTED_MESSAGE_TYPE" ->
            new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication");
        default -> new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
      };
    }

    void abort() {
      var current = socket;
      if (current != null) {
        current.abort();
      }
      closed.complete(null);
    }
  }

  private static final class JavaHttpAuthenticatedConnection
      implements AuthenticatedRuntimeConnection {
    private final WebSocket socket;
    private final HandshakeListener listener;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger pendingSends = new AtomicInteger();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);

    private JavaHttpAuthenticatedConnection(WebSocket socket, HandshakeListener listener) {
      this.socket = socket;
      this.listener = listener;
      listener.closed().thenRun(() -> open.set(false));
    }

    @Override
    public boolean isOpen() {
      return open.get() && !listener.closed().isDone();
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return listener.closed();
    }

    @Override
    public CompletionStage<Void> sendApplication(String message) {
      Objects.requireNonNull(message);
      try {
        strictUtf8(
            message,
            MAX_APPLICATION_BYTES,
            "APPLICATION_MESSAGE_TOO_LARGE",
            "APPLICATION_MESSAGE_INVALID",
            "protocol");
      } catch (RuntimeConnectionFailure failure) {
        return CompletableFuture.failedFuture(failure);
      }
      if (!isOpen()) {
        return CompletableFuture.failedFuture(
            new RuntimeConnectionFailure("RUNTIME_CONNECTION_LOST", "runtime-connect"));
      }
      if (pendingSends.incrementAndGet() > MAX_PENDING_APPLICATION_SENDS) {
        pendingSends.decrementAndGet();
        return CompletableFuture.failedFuture(
            new RuntimeConnectionFailure("APPLICATION_SEND_QUEUE_FULL", "protocol"));
      }

      CompletableFuture<Void> result;
      synchronized (this) {
        result =
            sendTail
                .thenCompose(
                    ignored -> {
                      if (!isOpen()) {
                        return CompletableFuture.<Void>failedFuture(
                            new RuntimeConnectionFailure(
                                "RUNTIME_CONNECTION_LOST", "runtime-connect"));
                      }
                      try {
                        return socket.sendText(message, true).thenApply(sent -> (Void) null);
                      } catch (RuntimeException error) {
                        return CompletableFuture.<Void>failedFuture(
                            new RuntimeConnectionFailure(
                                "RUNTIME_CONNECTION_LOST", "runtime-connect"));
                      }
                    })
                .toCompletableFuture();
        sendTail = result;
      }
      result.whenComplete(
          (ignored, error) -> {
            pendingSends.decrementAndGet();
            if (error != null) {
              close();
            }
          });
      return result;
    }

    @Override
    public void setApplicationHandler(Consumer<String> handler) {
      if (!isOpen()) {
        throw new IllegalStateException("Runtime connection is closed");
      }
      listener.setApplicationHandler(handler);
    }

    @Override
    public void close() {
      if (open.compareAndSet(true, false)) {
        listener.abort();
        socket.abort();
      }
    }
  }
}
