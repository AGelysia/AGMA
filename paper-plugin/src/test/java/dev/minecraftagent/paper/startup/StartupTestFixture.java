package dev.minecraftagent.paper.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class StartupTestFixture {
  static final String TOKEN = "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private StartupTestFixture() {}

  static Map<String, String> environment() {
    return Map.of("MINECRAFT_AGENT_SERVER_TOKEN", TOKEN);
  }

  static String validConfig() {
    return """
        config-version: 1
        server:
          id: survival-main
        owners: []
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
        """;
  }

  static Path writeConfig(Path directory, String source) throws IOException {
    var path = directory.resolve("config.yml");
    Files.writeString(path, source);
    if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    return path;
  }
}
