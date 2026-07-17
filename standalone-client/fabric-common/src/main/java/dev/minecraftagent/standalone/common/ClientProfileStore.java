package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Installation-scoped v3 profile store. Secret values live only in owner-only referenced files. */
public final class ClientProfileStore {
  public static final long PROVIDER_RESERVATION_MICRO_USD = 10_000L;
  private static final String PROFILE_FILE = "client-profile.json";
  private static final String CONNECTOR_SECRET = "secrets/connector-token";
  private static final String MODEL_SECRET = "secrets/model-api-key";
  private static final String SEARCH_SECRET = "secrets/search-api-key";
  private static final List<String> ALLOWED_TOOLS =
      List.of(
          "game.resource.search",
          "game.process.lookup",
          "game.process.uses",
          "game.process.plan",
          "game.inventory.snapshot");
  private static final List<String> DENIED_CAPABILITIES =
      List.of(
          "paper.command",
          "paper.permission",
          "server.payload",
          "world.write",
          "arbitrary.web.fetch");

  private final Path root;
  private final ClientProfileCodec codec = new ClientProfileCodec();
  private final SecureRandom random;

  public ClientProfileStore(Path root) {
    this(root, new SecureRandom());
  }

  ClientProfileStore(Path root, SecureRandom random) {
    this.root = root.toAbsolutePath().normalize();
    if (!this.root.equals(root)) {
      throw new IllegalArgumentException("Profile root must be absolute and normalized");
    }
    this.random = random;
  }

  public Path root() {
    return root;
  }

  public Path profilePath() {
    return root.resolve(PROFILE_FILE);
  }

  public boolean isConfigured() {
    return Files.isRegularFile(profilePath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        && !Files.isSymbolicLink(profilePath());
  }

  public RuntimeClientProfile load() {
    return codec.load(profilePath());
  }

  public RuntimeClientProfile configure(ClientSetup setup) {
    try {
      prepareRoots();
      var installationId = existingInstallationId();
      var profile = profile(setup, installationId, availablePort());
      if (!setup.storeConversations()) {
        deleteConversationData();
      }
      if (setup.webSearch() != null && setup.apiKey().equals(setup.webSearch().apiKey())) {
        throw new IllegalArgumentException("Model and Web Search API keys must be distinct");
      }
      writeSecret(MODEL_SECRET, setup.apiKey());
      if (setup.webSearch() == null) {
        PrivateFilePermissions.deleteFileIfPresent(root, root.resolve(SEARCH_SECRET));
      } else {
        writeSecret(SEARCH_SECRET, setup.webSearch().apiKey());
      }
      rotateConnectorToken();
      PrivateFilePermissions.atomicWrite(
          root, profilePath(), (codec.encode(profile) + "\n").getBytes(StandardCharsets.UTF_8));
      return codec.load(profilePath());
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw failure("CONFIG_WRITE_FAILED", "Client profile could not be stored", exception);
    }
  }

  /** Rotates the session authentication token before every managed Runtime start. */
  public RuntimeClientProfile prepareStart() {
    try {
      prepareRoots();
      var current = codec.load(profilePath());
      var profile =
          new RuntimeClientProfile(
              current.configVersion(),
              current.profile(),
              current.identity(),
              new RuntimeClientProfile.Transport(
                  current.transport().host(),
                  availablePort(),
                  current.transport().connectorToken(),
                  current.transport().authenticationDomain()),
              current.model(),
              current.storage(),
              current.logging(),
              current.limits(),
              current.privacy(),
              current.toolPolicy(),
              current.networkPolicy(),
              current.webEvidence(),
              current.storagePolicy());
      rotateConnectorToken();
      PrivateFilePermissions.atomicWrite(
          root, profilePath(), (codec.encode(profile) + "\n").getBytes(StandardCharsets.UTF_8));
      return profile;
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw failure(
          "CONFIG_WRITE_FAILED", "Client startup secrets could not be prepared", exception);
    }
  }

  public void deleteConfiguration() {
    try {
      if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      PrivateFilePermissions.prepareDirectory(root);
      PrivateFilePermissions.deleteFileIfPresent(root, profilePath());
      PrivateFilePermissions.deleteTreeIfPresent(root, "secrets");
      PrivateFilePermissions.deleteTreeIfPresent(root, "data");
      PrivateFilePermissions.deleteTreeIfPresent(root, "logs");
      PrivateFilePermissions.deleteTreeIfPresent(root, "diagnostics");
    } catch (IOException | RuntimeException failure) {
      throw failure("CONFIG_DELETE_FAILED", "Private client data could not be deleted", failure);
    }
  }

  private void prepareRoots() throws IOException {
    PrivateFilePermissions.prepareDirectory(root);
    PrivateFilePermissions.prepareChildDirectory(root, "secrets");
    PrivateFilePermissions.prepareChildDirectory(root, "data");
    PrivateFilePermissions.prepareChildDirectory(root, "logs");
  }

  private UUID existingInstallationId() {
    if (!isConfigured()) {
      return UUID.randomUUID();
    }
    try {
      return codec.load(profilePath()).identity().installationId();
    } catch (RuntimeException ignored) {
      return UUID.randomUUID();
    }
  }

  private RuntimeClientProfile profile(ClientSetup setup, UUID installationId, int port) {
    return new RuntimeClientProfile(
        RuntimeClientProfile.CONFIG_VERSION,
        RuntimeClientProfile.PROFILE,
        new RuntimeClientProfile.Identity(installationId, "installation"),
        new RuntimeClientProfile.Transport(
            "127.0.0.1",
            port,
            new RuntimeClientProfile.SecretReference("private_file", CONNECTOR_SECRET),
            "agma-connector-handshake-v1"),
        new RuntimeClientProfile.Model(
            setup.provider(),
            setup.baseUrl(),
            new RuntimeClientProfile.SecretReference("private_file", MODEL_SECRET),
            setup.model(),
            60,
            setup.inputMicroUsdPerMillionTokens(),
            setup.outputMicroUsdPerMillionTokens()),
        new RuntimeClientProfile.Storage("data/client.sqlite"),
        new RuntimeClientProfile.Logging("logs", "info"),
        new RuntimeClientProfile.Limits(
            1,
            8,
            4,
            30,
            32_768,
            2,
            setup.dailyRequests(),
            setup.monthlyBudgetMicroUsd(),
            PROVIDER_RESERVATION_MICRO_USD),
        new RuntimeClientProfile.Privacy(
            setup.storeConversations(), setup.retentionDays(), false, false),
        new RuntimeClientProfile.ToolPolicy(ALLOWED_TOOLS, DENIED_CAPABILITIES, false),
        new RuntimeClientProfile.NetworkPolicy(false, true),
        setup.webSearch() == null
            ? null
            : new RuntimeClientProfile.WebEvidence(
                "brave",
                new RuntimeClientProfile.SecretReference("private_file", SEARCH_SECRET),
                setup.webSearch().requestCostMicroUsd(),
                setup.webSearch().monthlyBudgetMicroUsd(),
                "off",
                false,
                setup.webSearch().country(),
                setup.webSearch().searchLanguage()),
        new RuntimeClientProfile.StoragePolicy("installation", true));
  }

  private void rotateConnectorToken() throws IOException {
    var bytes = new byte[48];
    random.nextBytes(bytes);
    var token = Base64.getUrlEncoder().withoutPadding().encode(bytes);
    java.util.Arrays.fill(bytes, (byte) 0);
    try {
      PrivateFilePermissions.atomicWrite(root, root.resolve(CONNECTOR_SECRET), token);
    } finally {
      java.util.Arrays.fill(token, (byte) 0);
    }
  }

  private void writeSecret(String relativePath, String value) throws IOException {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    try {
      PrivateFilePermissions.atomicWrite(root, root.resolve(relativePath), bytes);
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private void deleteConversationData() throws IOException {
    PrivateFilePermissions.deleteFileIfPresent(root, root.resolve("data/client.sqlite"));
    PrivateFilePermissions.deleteFileIfPresent(root, root.resolve("data/client.sqlite-wal"));
    PrivateFilePermissions.deleteFileIfPresent(root, root.resolve("data/client.sqlite-shm"));
  }

  private static int availablePort() throws IOException {
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      var port = socket.getLocalPort();
      if (port < 1024 || port > 65_535) {
        throw new IOException("No bounded loopback port is available");
      }
      return port;
    }
  }

  private static ClientConfigurationException failure(
      String code, String message, Throwable cause) {
    return new ClientConfigurationException(code, "/", message, cause);
  }
}
