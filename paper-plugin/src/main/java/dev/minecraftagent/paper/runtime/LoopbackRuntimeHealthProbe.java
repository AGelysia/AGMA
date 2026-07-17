package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import dev.minecraftagent.paper.transport.StrictJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A bounded readiness hint; the authenticated WebSocket handshake remains authoritative. */
public final class LoopbackRuntimeHealthProbe implements HealthProbe {
  private static final int MAX_HEALTH_BYTES = 4096;
  private static final Set<String> HEALTH_FIELDS =
      Set.of("status", "runtimeVersion", "protocolVersion", "checkedAt", "checks");

  private final HttpClient client;

  public LoopbackRuntimeHealthProbe() {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  LoopbackRuntimeHealthProbe(HttpClient client) {
    this.client = java.util.Objects.requireNonNull(client);
  }

  @Override
  public boolean isReady(RuntimeConnectionSettings settings)
      throws IOException, InterruptedException {
    var endpoint = settings.endpoint();
    var health = URI.create("http://127.0.0.1:" + endpoint.getPort() + "/health");
    var request =
        HttpRequest.newBuilder(health)
            .timeout(settings.connectTimeout())
            .header("Accept", "application/json")
            .GET()
            .build();
    var bodySubscriber = new BoundedBodySubscriber(MAX_HEALTH_BYTES);
    var responseFuture = client.sendAsync(request, responseInfo -> bodySubscriber);

    HttpResponse<byte[]> response;
    try {
      response = responseFuture.get(settings.connectTimeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException error) {
      responseFuture.cancel(true);
      bodySubscriber.cancel();
      return false;
    } catch (InterruptedException error) {
      responseFuture.cancel(true);
      bodySubscriber.cancel();
      throw error;
    } catch (ExecutionException error) {
      return false;
    }

    try {
      if (response.statusCode() != 200) {
        return false;
      }
      var json =
          StrictJson.parseObject(
              new String(response.body(), StandardCharsets.UTF_8),
              "MANAGED_RUNTIME_HEALTH_INVALID",
              "runtime-supervisor");
      if (!json.keySet().equals(HEALTH_FIELDS)) {
        return false;
      }
      var status = json.get("status");
      var runtimeVersion = json.get("runtimeVersion");
      var protocolVersion = json.get("protocolVersion");
      return status != null
          && status.isJsonPrimitive()
          && "READY".equals(status.getAsString())
          && runtimeVersion != null
          && runtimeVersion.isJsonPrimitive()
          && settings.componentVersion().equals(runtimeVersion.getAsString())
          && protocolVersion != null
          && protocolVersion.isJsonPrimitive()
          && "1.0".equals(protocolVersion.getAsString());
    } catch (RuntimeException error) {
      return false;
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final Object lock = new Object();
    private final int maxBytes;
    private final ByteArrayOutputStream bytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();

    private Flow.Subscription subscription;
    private boolean terminated;

    private BoundedBodySubscriber(int maxBytes) {
      this.maxBytes = maxBytes;
      bytes = new ByteArrayOutputStream(maxBytes);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription candidate) {
      var reject = false;
      synchronized (lock) {
        if (subscription != null || terminated) {
          reject = true;
        } else {
          subscription = candidate;
        }
      }
      if (reject) {
        candidate.cancel();
      } else {
        candidate.request(Long.MAX_VALUE);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      Flow.Subscription current = null;
      synchronized (lock) {
        if (terminated) {
          return;
        }
        var remaining = maxBytes - bytes.size();
        for (var buffer : buffers) {
          if (buffer.remaining() > remaining) {
            terminated = true;
            current = subscription;
            break;
          }
          remaining -= buffer.remaining();
        }
        if (!terminated) {
          for (var buffer : buffers) {
            var chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
          }
        }
      }
      if (current != null) {
        current.cancel();
        body.completeExceptionally(new BodyLimitExceededException());
      }
    }

    @Override
    public void onError(Throwable error) {
      synchronized (lock) {
        if (terminated) {
          return;
        }
        terminated = true;
      }
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      byte[] result;
      synchronized (lock) {
        if (terminated) {
          return;
        }
        terminated = true;
        result = bytes.toByteArray();
      }
      body.complete(result);
    }

    private void cancel() {
      Flow.Subscription current;
      boolean cancelBody;
      synchronized (lock) {
        cancelBody = !terminated;
        terminated = true;
        current = subscription;
      }
      if (current != null) {
        current.cancel();
      }
      if (cancelBody) {
        body.cancel(false);
      }
    }
  }

  private static final class BodyLimitExceededException extends RuntimeException {
    private BodyLimitExceededException() {
      super("Health response exceeded its byte limit");
    }
  }
}
