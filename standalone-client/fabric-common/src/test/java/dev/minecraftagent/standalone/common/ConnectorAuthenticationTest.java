package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConnectorAuthenticationTest {
  @Test
  void matchesThePublishedHandshakeGoldenVector() throws Exception {
    var fixture =
        JsonFields.object(
            StrictJson.parse(
                Files.readString(
                    Path.of("../contracts/fixtures/valid/standalone-contracts.json"),
                    StandardCharsets.UTF_8)),
            "/");
    var token = JsonFields.string(fixture.get("publicTestToken"), "/publicTestToken", 128);
    var request = ConnectorHelloCodec.decode(StrictJson.write(fixture.get("connectorRequest")));
    var response = ConnectorHelloCodec.decode(StrictJson.write(fixture.get("connectorResponse")));

    assertEquals(
        request.authentication().proof(),
        ConnectorAuthentication.proof(request, token.getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        response.authentication().proof(),
        ConnectorAuthentication.proof(response, token.getBytes(StandardCharsets.UTF_8)));
    assertTrue(ConnectorAuthentication.verify(request, token.getBytes(StandardCharsets.UTF_8)));
    assertTrue(ConnectorAuthentication.verify(response, token.getBytes(StandardCharsets.UTF_8)));
  }
}
