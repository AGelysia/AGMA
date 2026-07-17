package dev.minecraftagent.standalone.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandaloneModMetadataTest {
  @Test
  void descriptorLocksTheMinecraft1182ClientOnlyTuple() throws Exception {
    var resource = getClass().getClassLoader().getResourceAsStream("fabric.mod.json");
    assertNotNull(resource);
    JsonObject descriptor;
    try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
      descriptor = JsonParser.parseReader(reader).getAsJsonObject();
    }

    assertEquals("agma_standalone", descriptor.get("id").getAsString());
    assertEquals("AGMA Standalone Client", descriptor.get("name").getAsString());
    assertEquals("client", descriptor.get("environment").getAsString());
    assertEquals(
        System.getProperty("agma.expectedVersion"), descriptor.get("version").getAsString());
    assertFalse(descriptor.has("mixins"));

    var entrypoints = descriptor.getAsJsonObject("entrypoints");
    assertEquals(2, entrypoints.size());
    assertEquals(
        StandaloneClientEntrypoint.class.getName(),
        entrypoints.getAsJsonArray("client").get(0).getAsString());
    assertEquals(
        "dev.minecraftagent.standalone.fabric.viewer.emi.EmiCatalogPlugin",
        entrypoints.getAsJsonArray("emi").get(0).getAsString());

    var dependencies = descriptor.getAsJsonObject("depends");
    assertEquals(">=0.19.3", dependencies.get("fabricloader").getAsString());
    assertEquals("0.77.0+1.18.2", dependencies.get("fabric-api").getAsString());
    assertEquals("1.18.2", dependencies.get("minecraft").getAsString());
    assertEquals(">=17", dependencies.get("java").getAsString());
    assertFalse(descriptor.toString().contains("paper"));
    assertFalse(descriptor.toString().contains("server"));
  }

  @Test
  void primaryClassesLoadWithoutViewerApisAndDoNotLinkThem() throws Exception {
    assertNotNull(StandaloneClientEntrypoint.class.getDeclaredConstructor());
    assertNotNull(StandaloneCatalogService.class.getDeclaredConstructor());
    for (var type : List.of(StandaloneClientEntrypoint.class, StandaloneCatalogService.class)) {
      var path = "/" + type.getName().replace('.', '/') + ".class";
      try (var input = type.getResourceAsStream(path)) {
        assertNotNull(input);
        var constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("mezz/jei"));
        assertFalse(constants.contains("dev/emi"));
        assertTrue(constants.contains("agma"));
      }
    }
  }
}
