package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Authenticated Java 17 WebSocket client for the Runtime's loopback-only connector.
 *
 * <p>This type is the lifecycle facade: it owns the shared HTTP client, timeout scheduler, and the
 * live connection registry, and admits or rejects connection attempts. All per-session protocol
 * work lives in {@link ConnectorConnection} and its collaborators.
 */
public final class JavaHttpLocalConnector implements LocalConnector {
  static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final EntropySource entropy;
  private final Supplier<UUID> identifiers;
  private final String componentVersion;
  private final boolean ownsScheduler;
  private final Set<ConnectorConnection> connections = ConcurrentHashMap.newKeySet();
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
    var connection =
        new ConnectorConnection(
            this,
            httpClient,
            scheduler,
            clock,
            entropy,
            identifiers,
            componentVersion,
            profile,
            token);
    connections.add(connection);
    if (closed) {
      connection.close();
      return connection.ready();
    }
    connection.start(uri);
    return connection.ready();
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

  boolean isClosed() {
    return closed;
  }

  void forgetConnection(ConnectorConnection connection) {
    connections.remove(connection);
  }

  @FunctionalInterface
  interface EntropySource {
    void nextBytes(byte[] target);
  }

  static <T> CompletionStage<T> failed(Throwable error) {
    return CompletableFuture.failedFuture(error);
  }

  private static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          var thread = new Thread(task, "agma-connector-timeouts");
          thread.setDaemon(true);
          return thread;
        });
  }
}
