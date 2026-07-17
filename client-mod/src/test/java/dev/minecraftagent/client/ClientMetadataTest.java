package dev.minecraftagent.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClientMetadataTest {
  @Test
  void litematicaIsNotARequiredDependency() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
      assertNotNull(stream);
      var metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      var descriptor = JsonParser.parseString(metadata).getAsJsonObject();
      var dependencies = descriptor.getAsJsonObject("depends");
      assertEquals("client", descriptor.get("environment").getAsString());
      assertEquals(">=0.19.3", dependencies.get("fabricloader").getAsString());
      assertEquals("0.141.4+1.21.11", dependencies.get("fabric-api").getAsString());
      assertEquals("1.21.11", dependencies.get("minecraft").getAsString());
      assertEquals(">=21", dependencies.get("java").getAsString());
      assertFalse(metadata.contains("litematica"));
      assertFalse(metadata.contains("malilib"));
    }
  }
}
