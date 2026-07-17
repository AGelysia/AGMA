package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.net.URI;
import java.util.List;
import java.util.UUID;

final class TestProfiles {
  static final UUID INSTALLATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private TestProfiles() {}

  static RuntimeClientProfile environment(int port) {
    return profile(
        port,
        new RuntimeClientProfile.SecretReference("environment", "AGMA_CLIENT_CONNECTOR_TOKEN"),
        new RuntimeClientProfile.SecretReference("environment", "OPENAI_API_KEY"));
  }

  static RuntimeClientProfile withTools(int port, List<String> tools) {
    var base = environment(port);
    return new RuntimeClientProfile(
        base.configVersion(),
        base.profile(),
        base.identity(),
        base.transport(),
        base.model(),
        base.storage(),
        base.logging(),
        base.limits(),
        base.privacy(),
        new RuntimeClientProfile.ToolPolicy(
            tools, base.toolPolicy().denied(), base.toolPolicy().inventoryDefaultEnabled()),
        base.networkPolicy(),
        base.storagePolicy());
  }

  static RuntimeClientProfile profile(
      int port,
      RuntimeClientProfile.SecretReference connector,
      RuntimeClientProfile.SecretReference model) {
    return new RuntimeClientProfile(
        3,
        "client",
        new RuntimeClientProfile.Identity(INSTALLATION_ID, "installation"),
        new RuntimeClientProfile.Transport(
            "127.0.0.1", port, connector, "agma-connector-handshake-v1"),
        new RuntimeClientProfile.Model(
            "openai", URI.create("https://api.openai.com/v1"), model, "test-model", 30, 1, 1),
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
