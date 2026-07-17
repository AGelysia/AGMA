package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientConnectionRegistryTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void bindsHelloToJoinedPlayerAndReplacesImmutableGeneration() {
    var registry = new ClientConnectionRegistry();
    var joined = registry.join(PLAYER);
    assertFalse(joined.negotiated());

    var first = registry.replace(PLAYER, handshake(1, 1, 1));
    var second = registry.replace(PLAYER, handshake(1, 0, 0));

    assertNotEquals(joined.generation(), first.generation());
    assertNotEquals(first.generation(), second.generation());
    assertEquals(1, first.handshake().capabilities().version(ClientFeature.RECIPE_VIEW));
    assertEquals(0, second.handshake().capabilities().version(ClientFeature.RECIPE_VIEW));
    assertEquals(second, registry.lookup(PLAYER).orElseThrow());
  }

  @Test
  void rejectsHelloWithoutServerObservedJoinAndRemovesQuitState() {
    var registry = new ClientConnectionRegistry();
    assertEquals(
        "CLIENT_CONNECTION_UNKNOWN",
        assertThrows(
                ClientProtocolException.class, () -> registry.replace(PLAYER, handshake(1, 0, 0)))
            .code());

    registry.join(PLAYER);
    registry.replace(PLAYER, handshake(1, 0, 0));
    assertTrue(registry.quit(PLAYER).isPresent());
    assertTrue(registry.lookup(PLAYER).isEmpty());
  }

  static ClientHandshake handshake(int overlay, int recipe, int preview) {
    var versions = new EnumMap<ClientFeature, Integer>(ClientFeature.class);
    versions.put(ClientFeature.OVERLAY, overlay);
    versions.put(ClientFeature.ITEM_ICONS, recipe > 0 ? 1 : 0);
    versions.put(ClientFeature.RECIPE_VIEW, recipe);
    versions.put(ClientFeature.LITEMATICA_PREVIEW, preview);
    versions.put(ClientFeature.LITEMATICA_MATERIAL_LIST, preview);
    var dependencies = new LinkedHashMap<String, String>();
    dependencies.put("litematica", preview == 1 ? "0.20.0" : null);
    dependencies.put("malilib", preview == 1 ? "0.21.0" : null);
    var diagnostic =
        preview == 1
            ? new ClientLitematicaDiagnostic(
                ClientLitematicaDiagnostic.Status.READY,
                "1.21.11",
                "0.19.3",
                Optional.of("0.20.0"),
                Optional.of("0.21.0"),
                Optional.of("litematica-reflection-1"))
            : new ClientLitematicaDiagnostic(
                ClientLitematicaDiagnostic.Status.NOT_INSTALLED,
                "1.21.11",
                "0.19.3",
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    return new ClientHandshake(
        "1.1", "1.2.3", new ClientCapabilities(versions), dependencies, diagnostic);
  }
}
