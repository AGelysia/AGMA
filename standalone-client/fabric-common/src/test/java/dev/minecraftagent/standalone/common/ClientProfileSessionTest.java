package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientProfileSessionTest {
  @Test
  void profileLifecycleIsExplicitAndSecretFree() {
    var session = new ClientProfileSession();
    assertEquals(ClientLifecycleState.UNCONFIGURED, session.snapshot().state());

    var configured = session.configure(profile());
    assertEquals(ClientLifecycleState.STOPPED, configured.state());
    assertEquals("openai", configured.provider());
    assertEquals("test-model", configured.model());
    assertNull(configured.failureCode());

    session.transition(ClientLifecycleState.STARTING);
    session.transition(ClientLifecycleState.READY);
    assertThrows(IllegalStateException.class, () -> session.configure(profile()));
    session.transition(ClientLifecycleState.STOPPING);
    assertEquals(
        ClientLifecycleState.STOPPED, session.transition(ClientLifecycleState.STOPPED).state());
  }

  @Test
  void failureCanRecoverOnlyThroughAnExplicitStartTransition() {
    var session = new ClientProfileSession();
    session.configure(profile());
    session.transition(ClientLifecycleState.STARTING);

    assertEquals(ClientLifecycleState.ERROR, session.fail("RUNTIME_OFFLINE").state());
    assertEquals(
        ClientLifecycleState.STARTING, session.transition(ClientLifecycleState.STARTING).state());
    assertNull(session.snapshot().failureCode());
  }

  @Test
  void textMessagesEnforceTheC1Boundary() {
    var request =
        new TextRequest(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "How do I craft a piston?",
            Duration.ofSeconds(30));
    var completion =
        new TextCompletion(
            request.requestId(), TextCompletion.Status.COMPLETED, "Use planks.", null);

    assertEquals(request.requestId(), completion.requestId());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextRequest(UUID.randomUUID(), "seed\u0000value", Duration.ofSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextCompletion(
                request.requestId(), TextCompletion.Status.FAILED, "leaked body", "OFFLINE"));
  }

  private static RuntimeClientProfile profile() {
    return new RuntimeClientProfile(
        3,
        "client",
        new RuntimeClientProfile.Identity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "installation"),
        new RuntimeClientProfile.Transport(
            "127.0.0.1",
            38127,
            new RuntimeClientProfile.SecretReference("environment", "AGMA_CLIENT_CONNECTOR_TOKEN"),
            "agma-connector-handshake-v1"),
        new RuntimeClientProfile.Model(
            "openai",
            URI.create("https://api.openai.com/v1"),
            new RuntimeClientProfile.SecretReference("environment", "OPENAI_API_KEY"),
            "test-model",
            30,
            1,
            1),
        new RuntimeClientProfile.Storage("runtime/client.sqlite"),
        new RuntimeClientProfile.Logging("runtime/logs", "info"),
        new RuntimeClientProfile.Limits(1, 8, 4, 20, 16_384, 0, 100, 100_000, 1),
        new RuntimeClientProfile.Privacy(false, 0, false, false),
        new RuntimeClientProfile.ToolPolicy(
            List.of(),
            List.of(
                "paper.command",
                "paper.permission",
                "server.payload",
                "world.write",
                "arbitrary.web.fetch"),
            false),
        new RuntimeClientProfile.NetworkPolicy(false, true),
        new RuntimeClientProfile.StoragePolicy("installation", true));
  }
}
