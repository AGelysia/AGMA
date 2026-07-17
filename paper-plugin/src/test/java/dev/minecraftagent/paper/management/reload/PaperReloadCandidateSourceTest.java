package dev.minecraftagent.paper.management.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperReloadCandidateSourceTest {
  private static final String TOKEN = "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @TempDir Path temporaryDirectory;

  @Test
  void reusesStrictLoaderWithoutSideEffectsAndCapturesEnvironment() throws Exception {
    var configPath = temporaryDirectory.resolve("config.yml");
    Files.writeString(configPath, validConfig());
    if (configPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"));
    }
    var environment = new HashMap<>(Map.of("MINECRAFT_AGENT_SERVER_TOKEN", TOKEN));
    var source = new PaperReloadCandidateSource(configPath, temporaryDirectory, environment);
    environment.clear();

    var candidate = source.load();

    assertEquals(Set.of(OWNER), candidate.owners());
    assertEquals(temporaryDirectory.resolve("state"), candidate.stateDirectory());
    assertFalse(Files.exists(temporaryDirectory.resolve("state")));
    assertFalse(Files.exists(temporaryDirectory.resolve("capabilities")));
    assertFalse(candidate.toString().contains(TOKEN));
    assertFalse(candidate.toString().contains(OWNER.toString()));
    assertFalse(candidate.toString().contains(temporaryDirectory.toString()));
  }

  private static String validConfig() {
    return """
    config-version: 1
    server:
      id: survival-main
    owners:
      - 11111111-1111-4111-8111-111111111111
    runtime:
      url: ws://127.0.0.1:38127/agent
      server-token: ${MINECRAFT_AGENT_SERVER_TOKEN}
      connect-timeout-millis: 2000
      handshake-timeout-millis: 2000
    state:
      directory: state
    security:
      policy-version: 1
      world-write: OP
      player-write: OP
      server-admin: OWNER
      allow-op-toggle: false
    capabilities:
      directory: capabilities
      approvals: []
    """;
  }
}
