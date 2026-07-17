package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientProfileStoreTest {
  @TempDir Path temporary;

  @Test
  void storesOnlyReferencesAndRotatesTheConnectorToken() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    var configured = store.configure(setup("provider-secret-value-123456789"));

    var source = Files.readString(store.profilePath(), StandardCharsets.UTF_8);
    assertFalse(source.contains("provider-secret-value"));
    assertTrue(source.contains("secrets/model-api-key"));
    assertTrue(source.contains("secrets/connector-token"));
    assertEquals(5, configured.toolPolicy().allowed().size());
    assertFalse(configured.toolPolicy().inventoryDefaultEnabled());

    String first;
    try (var secrets = new ClientSecretResolver().resolve(configured, root, Map.of())) {
      first = new String(secrets.connectorToken().copyBytes(), StandardCharsets.UTF_8);
    }
    var prepared = store.prepareStart();
    String second;
    try (var secrets = new ClientSecretResolver().resolve(prepared, root, Map.of())) {
      second = new String(secrets.connectorToken().copyBytes(), StandardCharsets.UTF_8);
    }
    assertNotEquals(first, second);

    if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
      assertEquals(
          PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
      assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(store.profilePath()));
    }
  }

  @Test
  void preservesInstallationIdentityAcrossConfigurationChanges() {
    var store = new ClientProfileStore(temporary.resolve("state").toAbsolutePath().normalize());
    var first = store.configure(setup("provider-secret-value-123456789"));
    var second = store.configure(setup("another-provider-secret-987654321"));
    assertEquals(first.identity().installationId(), second.identity().installationId());
  }

  @Test
  void storesAnOptionalDistinctSearchSecretWithoutSerializingIt() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    var profile =
        store.configure(
            new ClientSetup(
                "openai",
                URI.create("https://api.openai.com/v1"),
                "gpt-4.1-mini",
                "provider-secret-value-123456789",
                0,
                0,
                100,
                5_000_000,
                new ClientSetup.WebSearchSetup(
                    "search-secret-value-987654321", 1, 100_000, "CN", "zh-cn")));
    assertEquals("brave", profile.webEvidence().provider());
    var document = Files.readString(store.profilePath());
    assertFalse(document.contains("search-secret-value"));
    assertTrue(document.contains("secrets/search-api-key"));
    try (var secrets = new ClientSecretResolver().resolve(profile, root, Map.of())) {
      assertEquals(
          "search-secret-value-987654321",
          new String(secrets.searchApiKey().copyBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejectsASymbolicLinkProfileRoot() throws Exception {
    var target = Files.createDirectory(temporary.resolve("target"));
    var link = temporary.resolve("state");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException exception) {
      return;
    }
    var store = new ClientProfileStore(link.toAbsolutePath().normalize());
    var failure =
        assertThrows(
            ClientConfigurationException.class,
            () -> store.configure(setup("provider-secret-value-123456789")));
    assertEquals("CONFIG_WRITE_FAILED", failure.code());
  }

  @Test
  void rejectsReplacingAHardLinkedSecret() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    store.configure(setup("provider-secret-value-123456789"));
    try {
      Files.createLink(temporary.resolve("linked-secret"), root.resolve("secrets/model-api-key"));
    } catch (UnsupportedOperationException exception) {
      return;
    }
    var failure =
        assertThrows(
            ClientConfigurationException.class,
            () -> store.configure(setup("another-provider-secret-987654321")));
    assertEquals("CONFIG_WRITE_FAILED", failure.code());
  }

  @Test
  void deletesPrivateConfigurationButLeavesManagedRuntimeForExplicitUninstall() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    store.configure(setup("provider-secret-value-123456789"));
    Files.writeString(root.resolve("data/client.sqlite"), "private conversation fixture");
    Files.writeString(root.resolve("logs/runtime.log"), "redacted fixture");
    var managed = Files.createDirectory(root.resolve("managed-runtime"));
    Files.writeString(managed.resolve("keep"), "managed runtime fixture");

    store.deleteConfiguration();

    assertFalse(Files.exists(store.profilePath()));
    assertFalse(Files.exists(root.resolve("secrets")));
    assertFalse(Files.exists(root.resolve("data")));
    assertFalse(Files.exists(root.resolve("logs")));
    assertTrue(Files.isRegularFile(managed.resolve("keep")));
  }

  private static ClientSetup setup(String key) {
    return new ClientSetup(
        "openai",
        URI.create("https://api.openai.com/v1"),
        "gpt-4.1-mini",
        key,
        0,
        0,
        100,
        5_000_000);
  }
}
