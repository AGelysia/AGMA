package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.minecraftagent.standalone.supervisor.OwnedRuntimeProcess;
import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoopbackRuntimeHealthProbeTest {
  @TempDir Path temporary;

  @Test
  void usesHttp11SoTheWebSocketServerDoesNotRejectAnH2cUpgrade() throws Exception {
    var upgrade = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/health",
        exchange -> {
          upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
          var body =
              (upgrade.get() == null ? "{\"status\":\"READY\"}" : "Invalid Upgrade header")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(upgrade.get() == null ? 200 : 400, body.length);
          try (var response = exchange.getResponseBody()) {
            response.write(body);
          }
        });
    server.start();
    try {
      var probe = new LoopbackRuntimeHealthProbe(server.getAddress().getPort());
      var root = temporary.toAbsolutePath().normalize();
      var spec =
          new RuntimeLaunchSpec(
              UUID.randomUUID(), root, root.resolve("runtime"), List.of(), Map.of());
      assertTrue(probe.isReady(liveProcess(), spec).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertNull(upgrade.get());
    } finally {
      server.stop(0);
    }
  }

  private static OwnedRuntimeProcess liveProcess() {
    return new OwnedRuntimeProcess() {
      @Override
      public long pid() {
        return 1;
      }

      @Override
      public boolean isAlive() {
        return true;
      }

      @Override
      public CompletionStage<Integer> onExit() {
        return new CompletableFuture<>();
      }

      @Override
      public void requestStop() {}

      @Override
      public void forceStop() {}
    };
  }
}
