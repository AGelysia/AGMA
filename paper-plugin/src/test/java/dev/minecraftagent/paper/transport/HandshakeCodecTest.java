package dev.minecraftagent.paper.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HandshakeCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void generatedPaperHelloMatchesBothProtocolSchemas() throws Exception {
    var now = Instant.parse("2026-07-12T00:00:00Z");
    var codec = new HandshakeCodec(new SecureRandom(), () -> now, new ReplayCache());
    var hello = codec.createPaperHello(settings());
    var document = JSON.readTree(hello.text());

    assertSchema("envelope.schema.json", document);
    assertSchema("handshake.schema.json", document.path("payload"));
    assertEquals(document.path("messageId"), document.path("requestId"));
    assertTrue(document.path("payload").path("selectedProtocolVersion").isNull());
    assertTrue(
        HandshakeProof.verify(
            document.path("payload").path("authentication").path("proof").asText(),
            settings().serverToken(),
            document.path("serverId").asText(),
            document.path("type").asText(),
            document.path("timestamp").asText(),
            document.path("nonce").asText(),
            document.path("payload").path("component").asText(),
            document.path("payload").path("componentVersion").asText(),
            document.path("payload").path("authentication").path("challenge").asText()));
  }

  @Test
  void productionProofImplementationMatchesTheSharedGolden() {
    assertEquals(
        "E9cIP0SbGOezGS3wRi2FEVbbdmx4vSVwW3SYTTpyTOs",
        HandshakeProof.compute(
            "public-golden-token-never-use-in-production",
            "survival-main",
            "paper.hello",
            "2026-07-12T00:00:00Z",
            "AAECAwQFBgcICQoLDA0ODw",
            "paper",
            "0.1.0",
            "ICEiIyQlJicoKSorLC0uLw"));
  }

  private static RuntimeConnectionSettings settings() {
    return new RuntimeConnectionSettings(
        URI.create("ws://127.0.0.1:38127/agent"),
        "survival-main",
        "public-test-token-at-least-32-characters",
        "0.1.0-SNAPSHOT",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private static void assertSchema(String name, com.fasterxml.jackson.databind.JsonNode document)
      throws Exception {
    var protocolRoot =
        Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
    var schemaNode = JSON.readTree(Files.newInputStream(protocolRoot.resolve("schemas/" + name)));
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                schemaNode, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    var errors = schema.validate(document);
    assertTrue(errors.isEmpty(), () -> name + " errors: " + errors);
  }
}
