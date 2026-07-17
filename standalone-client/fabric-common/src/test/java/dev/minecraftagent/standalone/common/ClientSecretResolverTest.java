package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
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
    makeOwnerOnly(token);
    makeOwnerOnly(api);
    var profile = privateFiles("connector.token", "provider.key");

    try (var secrets = resolver.resolve(profile, root, Map.of())) {
      assertArrayEquals(
          CONNECTOR.getBytes(StandardCharsets.UTF_8), secrets.connectorToken().copyBytes());
    }

    makeReadableByAnotherPrincipal(token);
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
    makeOwnerOnly(token);
    createSymbolicLinkOrSkip(root.resolve("linked"), target);
    var api = root.resolve("provider.key");
    Files.writeString(api, API_KEY, StandardCharsets.UTF_8);
    makeOwnerOnly(api);

    assertEquals(
        "SECRET_FILE_INVALID",
        assertThrows(
                ClientConfigurationException.class,
                () ->
                    resolver.resolve(
                        privateFiles("linked/connector.token", "provider.key"), root, Map.of()))
            .code());
  }

  private static void makeOwnerOnly(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
      return;
    }
    var view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("Owner-only file permissions are unsupported");
    }
    view.setAcl(List.of(ownerEntry(view)));
  }

  private static void makeReadableByAnotherPrincipal(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
      return;
    }
    var view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    var parentView =
        Files.getFileAttributeView(
            path.getParent(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null || parentView == null) {
      throw new IOException("ACL file permissions are unsupported");
    }
    var owner = view.getOwner();
    var other =
        parentView.getAcl().stream()
            .map(AclEntry::principal)
            .filter(principal -> !principal.equals(owner))
            .findFirst();
    assumeTrue(other.isPresent(), "No non-owner ACL principal is available for the fixture");
    var readable =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(other.orElseThrow())
            .setPermissions(AclEntryPermission.READ_DATA)
            .build();
    view.setAcl(List.of(ownerEntry(view), readable));
  }

  private static AclEntry ownerEntry(AclFileAttributeView view) throws IOException {
    return AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(view.getOwner())
        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
        .build();
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException failure) {
      assumeTrue(false, "Symbolic links are unavailable: " + failure.getMessage());
    }
  }

  private static RuntimeClientProfile privateFiles(String connector, String model) {
    return TestProfiles.profile(
        38_127,
        new RuntimeClientProfile.SecretReference("private_file", connector),
        new RuntimeClientProfile.SecretReference("private_file", model));
  }
}
