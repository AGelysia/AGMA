package dev.minecraftagent.paper.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.client.ClientViewSchemaRegistry;
import dev.minecraftagent.paper.client.ClientViewType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildPreviewArtifactRepositoryTest {
  @Test
  void bindsOneShotViewsToRequestAndPlayerAndExpiresThem() {
    var now = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
    var repository = new BuildPreviewArtifactRepository(now);
    var request = UUID.randomUUID();
    var player = UUID.randomUUID();
    repository.put(request, player, textView(request));

    assertTrue(repository.consume(request, UUID.randomUUID()).isEmpty());
    assertTrue(repository.consume(request, player).isEmpty());

    repository.put(request, player, textView(request));
    assertEquals(1, repository.consume(request, player).size());
    assertTrue(repository.consume(request, player).isEmpty());

    repository.put(request, player, textView(request));
    now.advanceSeconds(121);
    assertEquals(0, repository.size());
  }

  private static ClientStructuredView textView(UUID request) {
    var content = new JsonObject();
    content.addProperty("text", "preview");
    return new ClientStructuredView(
        ClientViewSchemaRegistry.VIEW_SCHEMA_V1,
        UUID.randomUUID(),
        request,
        ClientViewType.TEXT,
        1,
        "Preview",
        "Preview fallback",
        false,
        content);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
