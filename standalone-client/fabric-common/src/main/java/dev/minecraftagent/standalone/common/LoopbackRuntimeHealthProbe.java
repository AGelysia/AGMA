package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.supervisor.OwnedRuntimeProcess;
import dev.minecraftagent.standalone.supervisor.RuntimeHealthProbe;
import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bounded loopback-only `/health` probe for a single configured Runtime port. */
public final class LoopbackRuntimeHealthProbe implements RuntimeHealthProbe {
  private static final int MAXIMUM_RESPONSE_BYTES = 4096;
  private final HttpClient client;
  private final URI endpoint;

  public LoopbackRuntimeHealthProbe(int port) {
    if (port < 1024 || port > 65_535) {
      throw new IllegalArgumentException("Runtime health port is out of range");
    }
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    endpoint = URI.create("http://127.0.0.1:" + port + "/health");
  }

  @Override
  public CompletionStage<Boolean> isReady(OwnedRuntimeProcess process, RuntimeLaunchSpec spec) {
    if (!process.isAlive()) {
      return CompletableFuture.completedFuture(false);
    }
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(2))
            .header("Accept", "application/json")
            .GET()
            .build();
    return client
        .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(
            response -> {
              try (var body = response.body()) {
                if (response.statusCode() != 200) {
                  return false;
                }
                var bytes = body.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                  return false;
                }
                var value =
                    StrictJson.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                if (!(value instanceof Map<?, ?> map)) {
                  return false;
                }
                return "READY".equals(map.get("status"));
              } catch (IOException | RuntimeException exception) {
                return false;
              }
            })
        .exceptionally(failure -> false);
  }
}
