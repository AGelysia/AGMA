package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoopbackRuntimeHealthProbeTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void acceptsOnlyTheBoundedExactReadyIdentity() throws Exception {
    var body =
        """
        {"status":"READY","runtimeVersion":"0.1.0","protocolVersion":"1.0",\
        "checkedAt":"2026-07-16T00:00:00Z","checks":[]}
        """;
    var settings = serve(200, body);

    assertTrue(new LoopbackRuntimeHealthProbe().isReady(settings));
  }

  @Test
  void rejectsStatusIdentitySchemaAndBodyLimitFailures() throws Exception {
    var probe = new LoopbackRuntimeHealthProbe();
    assertFalse(probe.isReady(serve(503, readyBody())));
    resetServer();
    assertFalse(probe.isReady(serve(200, readyBody().replace("READY", "STARTING"))));
    resetServer();
    assertFalse(probe.isReady(serve(200, readyBody().replace("0.1.0", "9.9.9"))));
    resetServer();
    assertFalse(probe.isReady(serve(200, readyBody().replace("}", ",\"extra\":true}"))));
    resetServer();
    assertFalse(probe.isReady(serve(200, "x".repeat(4097))));
  }

  @Test
  void boundsAResponseWhoseBodyStallsAfterHeaders() throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var releaseBody = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var response = exchange.getResponseBody()) {
            response.write('{');
            response.flush();
            bodyStarted.countDown();
            releaseBody.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
    var settings = settings(Duration.ofMillis(250));

    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> assertFalse(new LoopbackRuntimeHealthProbe().isReady(settings)));
      assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
    } finally {
      releaseBody.countDown();
    }
  }

  private RuntimeConnectionSettings serve(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var response = exchange.getResponseBody()) {
            response.write(bytes);
          }
        });
    server.start();
    return settings(Duration.ofSeconds(1));
  }

  private RuntimeConnectionSettings settings(Duration connectTimeout) {
    return new RuntimeConnectionSettings(
        RuntimeDeploymentMode.MANAGED,
        URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/agent"),
        "server",
        "server-token-that-is-long-enough-0123456789",
        "0.1.0",
        connectTimeout,
        Duration.ofSeconds(1));
  }

  private void resetServer() {
    server.stop(0);
    server = null;
  }

  private static String readyBody() {
    return """
        {"status":"READY","runtimeVersion":"0.1.0","protocolVersion":"1.0",\
        "checkedAt":"2026-07-16T00:00:00Z","checks":[]}
        """;
  }
}
