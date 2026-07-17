package dev.minecraftagent.paper.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperConfigLoaderTest {
  @TempDir Path temporaryDirectory;

  private final PaperConfigLoader loader = new PaperConfigLoader();

  @Test
  void legacyRuntimeSettingsConstructorDefaultsToExternal() {
    var runtime =
        new PaperStartupConfig.RuntimeSettings(
            URI.create("ws://127.0.0.1:38127/agent"),
            StartupTestFixture.TOKEN,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2));

    assertEquals(RuntimeDeploymentMode.EXTERNAL, runtime.mode());
  }

  @Test
  void loadsStrictConfigurationAndExpandsOnlyWholeEnvironmentValues() throws Exception {
    var configPath =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());

    var config = loader.load(configPath, temporaryDirectory, StartupTestFixture.environment());

    assertEquals("survival-main", config.serverId());
    assertEquals(Set.of(), config.owners());
    assertEquals(RuntimeDeploymentMode.EXTERNAL, config.runtime().mode());
    assertEquals("ws://127.0.0.1:38127/agent", config.runtime().endpoint().toString());
    assertEquals(StartupTestFixture.TOKEN, config.runtime().serverToken());
    assertEquals(Duration.ofSeconds(2), config.runtime().connectTimeout());
    assertEquals(temporaryDirectory.resolve("state"), config.stateDirectory());
    assertEquals(temporaryDirectory.resolve("capabilities"), config.optionalCapabilityDirectory());
    assertEquals(Set.of(), config.capabilityApprovals());
    assertFalse(config.toString().contains(StartupTestFixture.TOKEN));
    assertFalse(config.runtime().toString().contains(StartupTestFixture.TOKEN));
    assertFalse(config.toString().contains(temporaryDirectory.toString()));
  }

  @Test
  void loadsBothStrictVersionTwoRuntimeModes() throws Exception {
    for (var mode : RuntimeDeploymentMode.values()) {
      var source =
          StartupTestFixture.validConfig()
              .replace("config-version: 1", "config-version: 2")
              .replace("runtime:\n", "runtime:\n  mode: " + mode + "\n");
      var configPath = StartupTestFixture.writeConfig(temporaryDirectory, source);

      var config = loader.load(configPath, temporaryDirectory, StartupTestFixture.environment());

      assertEquals(mode, config.runtime().mode());
      assertEquals("ws://127.0.0.1:38127/agent", config.runtime().endpoint().toString());
      assertEquals(StartupTestFixture.TOKEN, config.runtime().serverToken());
    }
  }

  @Test
  void rejectsRuntimeModeOutsideItsStrictVersionTwoSchema() throws Exception {
    var versionTwo =
        StartupTestFixture.validConfig().replace("config-version: 1", "config-version: 2");
    for (var source :
        new String[] {
          versionTwo,
          versionTwo.replace("runtime:\n", "runtime:\n  mode: external\n"),
          versionTwo.replace("runtime:\n", "runtime:\n  mode: UNKNOWN\n"),
          versionTwo.replace("runtime:\n", "runtime:\n  mode: EXTERNAL\n  unknown: true\n"),
          StartupTestFixture.validConfig().replace("runtime:\n", "runtime:\n  mode: EXTERNAL\n")
        }) {
      var configPath = StartupTestFixture.writeConfig(temporaryDirectory, source);

      assertFailure(
          configPath, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
    }
  }

  @Test
  void loadsCanonicalOwnerUuidsWithoutExposingThem() throws Exception {
    var first = UUID.fromString("11111111-1111-4111-8111-111111111111");
    var second = UUID.fromString("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
    var source =
        StartupTestFixture.validConfig()
            .replace(
                "owners: []",
                "owners:\n"
                    + "  - 11111111-1111-4111-8111-111111111111\n"
                    + "  - AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
    var configPath = StartupTestFixture.writeConfig(temporaryDirectory, source);

    var config = loader.load(configPath, temporaryDirectory, StartupTestFixture.environment());

    assertEquals(Set.of(first, second), config.owners());
    assertThrows(UnsupportedOperationException.class, () -> config.owners().add(UUID.randomUUID()));
    assertTrue(config.toString().contains("ownersCount=2"));
    assertFalse(config.toString().contains(first.toString()));
    assertFalse(config.toString().contains(second.toString()));
  }

  @Test
  void safelyLoadsAPhaseThreeConfigWithoutOwnersAsConsoleOnly() throws Exception {
    var source = StartupTestFixture.validConfig().replace("owners: []\n\n", "");
    var configPath = StartupTestFixture.writeConfig(temporaryDirectory, source);

    var config = loader.load(configPath, temporaryDirectory, StartupTestFixture.environment());

    assertEquals(Set.of(), config.owners());
  }

  @Test
  void loadsOnlyExactCapabilityApprovalTriples() throws Exception {
    var hash = "0123456789abcdef".repeat(4);
    var source =
        StartupTestFixture.validConfig()
            .replace(
                "  directory: capabilities",
                "  directory: capabilities\n"
                    + "  approvals:\n"
                    + "    - id: example.server_version\n"
                    + "      version: 2\n"
                    + "      sha256: \""
                    + hash
                    + "\"");
    var configPath = StartupTestFixture.writeConfig(temporaryDirectory, source);

    var config = loader.load(configPath, temporaryDirectory, StartupTestFixture.environment());

    assertEquals(
        Set.of(
            new dev.minecraftagent.paper.capability.model.CapabilityApproval(
                "example.server_version", 2, hash)),
        config.capabilityApprovals());
    assertThrows(UnsupportedOperationException.class, () -> config.capabilityApprovals().clear());
    assertTrue(config.toString().contains("capabilityApprovalsCount=1"));
    assertFalse(config.toString().contains(hash));
  }

  @Test
  void rejectsMalformedAndDuplicateCapabilityApprovals() throws Exception {
    var hash = "0123456789abcdef".repeat(4);
    var valid =
        "  approvals:\n"
            + "    - id: example.server_version\n"
            + "      version: 1\n"
            + "      sha256: \""
            + hash
            + "\"";
    for (var approvals :
        new String[] {
          "  approvals: invalid",
          valid.replace("version: 1", "version: 0"),
          valid.replace("example.server_version", "Invalid"),
          valid.replace(hash, "A".repeat(64)),
          valid
              + "\n    - id: example.server_version\n      version: 1\n      sha256: \""
              + hash
              + "\"",
          valid + "\n      unknown: true"
        }) {
      var source =
          StartupTestFixture.validConfig()
              .replace("  directory: capabilities", "  directory: capabilities\n" + approvals);
      var path = StartupTestFixture.writeConfig(temporaryDirectory, source);
      assertFailure(
          path, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
    }
  }

  @Test
  void rejectsMalformedDuplicateOversizedAndNonSequenceOwners() throws Exception {
    for (var owners :
        new String[] {
          "owners: [not-a-uuid]",
          "owners: [11111111-1111-4111-8111-111111111111, 11111111-1111-4111-8111-111111111111]",
          "owners: 11111111-1111-4111-8111-111111111111",
          "owners: [42]"
        }) {
      var path =
          StartupTestFixture.writeConfig(
              temporaryDirectory, StartupTestFixture.validConfig().replace("owners: []", owners));
      assertFailure(
          path, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
    }

    var entries = new StringBuilder("owners:\n");
    for (var index = 0; index < 129; index++) {
      entries
          .append("  - 00000000-0000-4000-8000-")
          .append(String.format("%012d", index))
          .append('\n');
    }
    var oversizedPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig().replace("owners: []\n", entries.toString()));
    assertFailure(
        oversizedPath, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
  }

  @Test
  void rejectsMissingPartialPlaceholderAndUnsafeTokensWithoutLeakingThem() throws Exception {
    var partialPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig()
                .replace(
                    "${MINECRAFT_AGENT_SERVER_TOKEN}", "prefix-${MINECRAFT_AGENT_SERVER_TOKEN}"));
    var partial = assertFailure(partialPath, Map.of(), StartupFailure.Code.SERVER_TOKEN_UNSAFE);
    assertFalse(partial.getMessage().contains("prefix"));

    var missingPath =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());
    assertFailure(missingPath, Map.of(), StartupFailure.Code.SERVER_TOKEN_MISSING);

    var unsafe = "secret-with-control\ncharacter-01234567890123456789";
    var unsafeFailure =
        assertFailure(
            missingPath,
            Map.of("MINECRAFT_AGENT_SERVER_TOKEN", unsafe),
            StartupFailure.Code.SERVER_TOKEN_UNSAFE);
    assertFalse(unsafeFailure.getMessage().contains(unsafe));
  }

  @Test
  void rejectsUnknownDuplicateAliasAndOversizedYaml() throws Exception {
    var unknown =
        StartupTestFixture.writeConfig(
            temporaryDirectory, StartupTestFixture.validConfig() + "untrusted-secret-name: true\n");
    var unknownFailure =
        assertFailure(
            unknown, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
    assertFalse(unknownFailure.getMessage().contains("untrusted-secret-name"));

    var duplicate =
        StartupTestFixture.writeConfig(
            temporaryDirectory, StartupTestFixture.validConfig() + "server:\n  id: duplicate\n");
    assertFailure(
        duplicate, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);

    var alias =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig().replace("server:\n", "server: &server\n")
                + "copied: *server\n");
    assertFailure(
        alias, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);

    var oversized =
        StartupTestFixture.writeConfig(temporaryDirectory, "#" + "x".repeat(64 * 1024) + "\n");
    assertFailure(
        oversized, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_TOO_LARGE);
  }

  @Test
  void acceptsOnlyThePinnedLoopbackWebSocketEndpoint() throws Exception {
    for (var endpoint :
        new String[] {
          "ws://localhost:38127/agent",
          "wss://127.0.0.1:38127/agent",
          "ws://127.0.0.1:38127/",
          "ws://127.0.0.1:38127/agent?token=secret",
          "ws://user@127.0.0.1:38127/agent",
          "ws://127.0.0.1:80/agent"
        }) {
      var path =
          StartupTestFixture.writeConfig(
              temporaryDirectory,
              StartupTestFixture.validConfig().replace("ws://127.0.0.1:38127/agent", endpoint));
      assertFailure(
          path, StartupTestFixture.environment(), StartupFailure.Code.RUNTIME_ENDPOINT_INVALID);
    }
  }

  @Test
  void rejectsEscapingStateAndCapabilityPaths() throws Exception {
    var statePath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig().replace("directory: state", "directory: ../state"));
    assertFailure(
        statePath, StartupTestFixture.environment(), StartupFailure.Code.STATE_DIRECTORY_UNSAFE);

    var capabilityPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig()
                .replace("directory: capabilities", "directory: /tmp/capabilities"));
    assertFailure(
        capabilityPath, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);

    var unusualPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig()
                .replace("directory: capabilities", "directory: capability:pack"));
    assertFailure(
        unusualPath, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
  }

  @Test
  void validatesPolicyAndTimeoutsWithStableCodes() throws Exception {
    var policyPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig()
                .replace("allow-op-toggle: false", "allow-op-toggle: true"));
    var policy = loader.load(policyPath, temporaryDirectory, StartupTestFixture.environment());
    assertTrue(policy.securityPolicy().allowOpToggle());

    var timeoutPath =
        StartupTestFixture.writeConfig(
            temporaryDirectory,
            StartupTestFixture.validConfig()
                .replace("connect-timeout-millis: 2000", "connect-timeout-millis: 999999"));
    assertFailure(
        timeoutPath, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
  }

  @Test
  void rejectsSymlinkedAndMalformedUtf8Configuration() throws Exception {
    var target =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());
    var link = temporaryDirectory.resolve("linked-config.yml");
    Files.createSymbolicLink(link, target.getFileName());
    assertFailure(link, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);

    var malformed = temporaryDirectory.resolve("malformed.yml");
    Files.write(malformed, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertFailure(
        malformed, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
  }

  @Test
  void rejectsGroupOrWorldWritableConfiguration() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    var path = StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-r--"));

    assertFailure(path, StartupTestFixture.environment(), StartupFailure.Code.PAPER_CONFIG_INVALID);
  }

  private StartupFailure assertFailure(
      Path configPath, Map<String, String> environment, StartupFailure.Code expectedCode) {
    var failure =
        assertThrows(
            StartupFailure.class, () -> loader.load(configPath, temporaryDirectory, environment));
    assertEquals(expectedCode, failure.code());
    assertTrue(failure.getMessage().startsWith("Paper startup check failed:"));
    return failure;
  }
}
