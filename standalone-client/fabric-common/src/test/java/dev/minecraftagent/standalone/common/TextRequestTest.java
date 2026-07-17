package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TextRequestTest {
  @Test
  void legacyRequestKeepsTheMinimalPayload() {
    var request = new TextRequest(UUID.randomUUID(), "question", Duration.ofSeconds(30));
    assertEquals(java.util.Set.of("sessionId", "message"), request.applicationPayload().keySet());
    assertEquals(null, request.applicationPayload().get("sessionId"));
  }

  @Test
  void explicitContextsAreBoundedAndDeterministicallySerialized() {
    var authorizationId = UUID.randomUUID();
    var request =
        new TextRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "How do I craft this?",
            Duration.ofSeconds(60),
            new TextRequest.LocalContext(
                "1.21.11",
                "mc12111-2",
                new TextRequest.Target("minecraft:stone", "Stone", "minecraft", "1.21.11")),
            TextRequest.WebAuthorization.ONCE,
            new TextRequest.WebContext(
                "1.21.11",
                new TextRequest.Target("minecraft:stone", "Stone", "minecraft", "1.21.11"),
                null),
            new TextRequest.InventoryAuthorization(
                authorizationId, "mc12111-2", List.of("minecraft:stone", "minecraft:stick")));
    var payload = request.applicationPayload();
    assertEquals("once", payload.get("webAuthorization"));
    assertEquals("How do I craft this?", payload.get("message"));
    @SuppressWarnings("unchecked")
    var local = (java.util.Map<String, Object>) payload.get("localContext");
    assertEquals("mc12111-2", local.get("catalogGenerationId"));
    @SuppressWarnings("unchecked")
    var inventory = (java.util.Map<String, Object>) payload.get("inventoryAuthorization");
    assertEquals(authorizationId.toString(), inventory.get("authorizationId"));
    assertEquals(List.of("minecraft:stick", "minecraft:stone"), inventory.get("resourceIds"));
  }

  @Test
  void rejectsContextWithoutAnExplicitWebMode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextRequest(
                UUID.randomUUID(),
                null,
                "question",
                Duration.ofSeconds(30),
                null,
                null,
                new TextRequest.WebContext("1.18.2", null, null),
                null));
  }
}
