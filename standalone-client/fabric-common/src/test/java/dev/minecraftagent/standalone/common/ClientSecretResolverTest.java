package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSecretResolverTest {
  private static final String CONNECTOR =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String API_KEY = "provider-key-for-tests";
  private final ClientSecretResolver resolver = new ClientSecretResolver();

  @TempDir Path root;

  @Test
  void resolvesEnvironmentReferencesIntoClearableRedactedMaterial() {
    var secrets =
        resolver.resolve(
            TestProfiles.environment(38_127),
            root,
            Map.of("AGMA_CLIENT_CONNECTOR_TOKEN", CONNECTOR, "OPENAI_API_KEY", API_KEY));

    assertArrayEquals(
        CONNECTOR.getBytes(StandardCharsets.UTF_8), secrets.connectorToken().copyBytes());
    assertArrayEquals(API_KEY.getBytes(StandardCharsets.UTF_8), secrets.modelApiKey().copyBytes());
    assertFalse(secrets.toString().contains(CONNECTOR));
    secrets.close();
    assertTrue(secrets.connectorToken().isClosed());
    assertTrue(secrets.modelApiKey().isClosed());
  }

  @Test
  void rejectsSecretReuseAndCredentialStoreReferences() {
    var reused =
        assertThrows(
            ClientConfigurationException.class,
            () ->
                resolver.resolve(
                    TestProfiles.environment(38_127),
                    root,
                    Map.of(
                        "AGMA_CLIENT_CONNECTOR_TOKEN", CONNECTOR,
                        "OPENAI_API_KEY", CONNECTOR)));
    assertEquals("SECRET_REUSE", reused.code());

    var credentialProfile =
        TestProfiles.profile(
            38_127,
            new RuntimeClientProfile.SecretReference("credential_store", "connector-token"),
            new RuntimeClientProfile.SecretReference("environment", "OPENAI_API_KEY"));
    assertEquals(
        "SECRET_REFERENCE_UNSUPPORTED",
        assertThrows(
                ClientConfigurationException.class,
                () -> resolver.resolve(credentialProfile, root, Map.of("OPENAI_API_KEY", API_KEY)))
            .code());
  }

  @Test
  void acceptsOwnerOnlyPrivateFilesAndRejectsLoosePermissions() throws Exception {
    var token = root.resolve("connector.token");
    var api = root.resolve("provider.key");
    Files.writeString(token, CONNECTOR + "\n", StandardCharsets.UTF_8);
    Files.writeString(api, API_KEY, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
    Files.setPosixFilePermissions(api, PosixFilePermissions.fromString("r--------"));
    var profile = privateFiles("connector.token", "provider.key");

    try (var secrets = resolver.resolve(profile, root, Map.of())) {
      assertArrayEquals(
          CONNECTOR.getBytes(StandardCharsets.UTF_8), secrets.connectorToken().copyBytes());
    }

    Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--"));
    assertEquals(
        "SECRET_FILE_INVALID",
        assertThrows(
                ClientConfigurationException.class, () -> resolver.resolve(profile, root, Map.of()))
            .code());
  }

  @Test
  void rejectsSymbolicLinksAnywhereInThePrivateFilePath() throws Exception {
    var target = Files.createDirectory(root.resolve("target"));
    var token = target.resolve("connector.token");
    Files.writeString(token, CONNECTOR, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
    Files.createSymbolicLink(root.resolve("linked"), target);
    var api = root.resolve("provider.key");
    Files.writeString(api, API_KEY, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(api, PosixFilePermissions.fromString("rw-------"));

    assertEquals(
        "SECRET_FILE_INVALID",
        assertThrows(
                ClientConfigurationException.class,
                () ->
                    resolver.resolve(
                        privateFiles("linked/connector.token", "provider.key"), root, Map.of()))
            .code());
  }

  private static RuntimeClientProfile privateFiles(String connector, String model) {
    return TestProfiles.profile(
        38_127,
        new RuntimeClientProfile.SecretReference("private_file", connector),
        new RuntimeClientProfile.SecretReference("private_file", model));
  }
}
