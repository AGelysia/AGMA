package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
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

    assertOwnerOnly(root, "rwx------");
    assertOwnerOnly(store.profilePath(), "rw-------");
  }

  @Test
  void preservesInstallationIdentityAcrossConfigurationChanges() {
    var store = new ClientProfileStore(temporary.resolve("state").toAbsolutePath().normalize());
    var first = store.configure(setup("provider-secret-value-123456789"));
    var second = store.configure(setup("another-provider-secret-987654321"));
    assertEquals(first.identity().installationId(), second.identity().installationId());
  }

  @Test
  void priceOnlyUpdateRetainsTheExistingModelSecret() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    store.configure(setup("provider-secret-value-123456789"));
    var secret = root.resolve("secrets/model-api-key");
    var before = Files.readString(secret, StandardCharsets.UTF_8);

    var updated =
        store.configure(
            new ClientSetup(
                "openai",
                URI.create("https://api.openai.com/v1"),
                "gpt-4.1-mini",
                ClientSetup.SecretInput.keepExisting(),
                150_000,
                600_000,
                100,
                5_000_000,
                null,
                false,
                0));

    assertEquals(150_000, updated.model().inputMicroUsdPerMillionTokens());
    assertEquals(600_000, updated.model().outputMicroUsdPerMillionTokens());
    assertEquals(before, Files.readString(secret, StandardCharsets.UTF_8));
  }

  @Test
  void retainedSecretsRequireAnExistingPrivateKey() {
    var store = new ClientProfileStore(temporary.resolve("state").toAbsolutePath().normalize());
    var failure =
        assertThrows(
            ClientConfigurationException.class,
            () ->
                store.configure(
                    new ClientSetup(
                        "openai",
                        URI.create("https://api.openai.com/v1"),
                        "gpt-4.1-mini",
                        ClientSetup.SecretInput.keepExisting(),
                        150_000,
                        600_000,
                        100,
                        5_000_000,
                        null,
                        false,
                        0)));
    assertEquals("SECRET_REPLACEMENT_REQUIRED", failure.code());
    assertEquals("/model/apiKey", failure.field());
  }

  @Test
  void webSettingsUpdateRetainsAndCanRemoveTheExistingSearchSecret() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    store.configure(
        new ClientSetup(
            "openai",
            URI.create("https://api.openai.com/v1"),
            "gpt-4.1-mini",
            "provider-secret-value-123456789",
            150_000,
            600_000,
            100,
            5_000_000,
            new ClientSetup.WebSearchSetup(
                "search-secret-value-987654321", 1, 100_000, "CN", "zh-cn"),
            false,
            0));
    var searchSecret = root.resolve("secrets/search-api-key");
    var before = Files.readString(searchSecret, StandardCharsets.UTF_8);

    var updated =
        store.configure(
            new ClientSetup(
                "openai",
                URI.create("https://api.openai.com/v1"),
                "gpt-4.1-mini",
                ClientSetup.SecretInput.keepExisting(),
                200_000,
                800_000,
                100,
                5_000_000,
                new ClientSetup.WebSearchSetup(
                    ClientSetup.SecretInput.keepExisting(), 2, 200_000, "US", "en-us"),
                false,
                0));
    assertEquals(2, updated.webEvidence().requestCostMicroUsd());
    assertEquals(before, Files.readString(searchSecret, StandardCharsets.UTF_8));

    store.configure(
        new ClientSetup(
            "openai",
            URI.create("https://api.openai.com/v1"),
            "gpt-4.1-mini",
            ClientSetup.SecretInput.keepExisting(),
            200_000,
            800_000,
            100,
            5_000_000,
            null,
            false,
            0));
    assertFalse(Files.exists(searchSecret));
  }

  @Test
  void replacesAMissingSearchSecretWhileRetainingTheModelSecret() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    var store = new ClientProfileStore(root);
    store.configure(
        new ClientSetup(
            "openai",
            URI.create("https://api.openai.com/v1"),
            "gpt-4.1-mini",
            "provider-secret-value-123456789",
            150_000,
            600_000,
            100,
            5_000_000,
            new ClientSetup.WebSearchSetup(
                "search-secret-value-987654321", 1, 100_000, "CN", "zh-cn"),
            false,
            0));
    var modelSecret = root.resolve("secrets/model-api-key");
    var searchSecret = root.resolve("secrets/search-api-key");
    var modelBefore = Files.readString(modelSecret, StandardCharsets.UTF_8);
    Files.delete(searchSecret);

    var updated =
        store.configure(
            new ClientSetup(
                "openai",
                URI.create("https://api.openai.com/v1"),
                "gpt-4.1-mini",
                ClientSetup.SecretInput.keepExisting(),
                200_000,
                800_000,
                100,
                5_000_000,
                new ClientSetup.WebSearchSetup(
                    ClientSetup.SecretInput.replace("replacement-search-secret-246813579"),
                    2,
                    200_000,
                    "US",
                    "en-us"),
                false,
                0));

    assertEquals(modelBefore, Files.readString(modelSecret, StandardCharsets.UTF_8));
    try (var secrets = new ClientSecretResolver().resolve(updated, root, Map.of())) {
      assertEquals(
          "replacement-search-secret-246813579",
          new String(secrets.searchApiKey().copyBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void enablingWebSearchRequiresANewSearchKey() {
    var store = new ClientProfileStore(temporary.resolve("state").toAbsolutePath().normalize());
    store.configure(setup("provider-secret-value-123456789"));
    var failure =
        assertThrows(
            ClientConfigurationException.class,
            () ->
                store.configure(
                    new ClientSetup(
                        "openai",
                        URI.create("https://api.openai.com/v1"),
                        "gpt-4.1-mini",
                        ClientSetup.SecretInput.keepExisting(),
                        150_000,
                        600_000,
                        100,
                        5_000_000,
                        new ClientSetup.WebSearchSetup(
                            ClientSetup.SecretInput.keepExisting(), 1, 100_000, "CN", "zh-cn"),
                        false,
                        0)));
    assertEquals("SECRET_REPLACEMENT_REQUIRED", failure.code());
    assertEquals("/webEvidence/apiKey", failure.field());
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
    createSymbolicLinkOrSkip(link, target);
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
    var secret = root.resolve("secrets/model-api-key");
    assumeTrue(supportsLinkCount(secret), "File link counts are unavailable");
    createHardLinkOrSkip(temporary.resolve("linked-secret"), secret);
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

  private static boolean supportsLinkCount(Path path) {
    try {
      return Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Number;
    } catch (UnsupportedOperationException | IOException | SecurityException failure) {
      return false;
    }
  }

  private static void assertOwnerOnly(Path path, String posixPermissions) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
      assertEquals(
          PosixFilePermissions.fromString(posixPermissions), Files.getPosixFilePermissions(path));
      return;
    }
    var view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    assertNotNull(view);
    var entries = view.getAcl();
    assertEquals(1, entries.size());
    var entry = entries.get(0);
    assertEquals(AclEntryType.ALLOW, entry.type());
    assertEquals(view.getOwner(), entry.principal());
    assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)));
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException failure) {
      assumeTrue(false, "Symbolic links are unavailable: " + failure.getMessage());
    }
  }

  private static void createHardLinkOrSkip(Path link, Path target) {
    try {
      Files.createLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException failure) {
      assumeTrue(false, "Hard links are unavailable: " + failure.getMessage());
    }
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
