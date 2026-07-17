package dev.minecraftagent.paper.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRuntimeSetupProbeTest {
  @TempDir Path temporaryDirectory;

  private final ManagedRuntimeSetupProbe probe = new ManagedRuntimeSetupProbe();

  @Test
  void acceptsAConfiguredProviderWithoutExposingItsValues() throws Exception {
    var config = write("openai", "private-test-api-key", "model-name");

    assertDoesNotThrow(() -> probe.verify(config));
  }

  @Test
  void acceptsOnlyTheManagedEnvironmentReferencesOutsideProviderCredentials() throws Exception {
    var config =
        writeSource(
            """
            server:
              id: ${AGMA_MANAGED_SERVER_ID}
            transport:
              host: 127.0.0.1
              port: ${AGMA_MANAGED_RUNTIME_PORT}
              serverToken: ${AGMA_MANAGED_SERVER_TOKEN}
            model:
              provider: openai
              apiKey: private-test-api-key
              model: model-name
            """);

    assertDoesNotThrow(() -> probe.verify(config));
    assertDoesNotThrow(
        () ->
            probe.verify(
                writeSource(
                    modelWithBaseUrl("openai-compatible", "https://provider.example.test/v1"))));
  }

  @Test
  void rejectsMissingPlaceholderEnvironmentAndMalformedProviderConfiguration() throws Exception {
    for (var source :
        new String[] {
          "model: {}\n",
          model("openai", "replace-with-provider-api-key", "model-name"),
          model("openai", "${OPENAI_API_KEY}", "model-name"),
          model("openai", "private-test-api-key", "model-name")
              .replace("${AGMA_MANAGED_SERVER_ID}", "literal-server"),
          model("openai", "private-test-api-key", "model-name")
              .replace("${AGMA_MANAGED_RUNTIME_PORT}", "38127"),
          model("openai", "private-test-api-key", "model-name")
              .replace("${AGMA_MANAGED_SERVER_TOKEN}", "literal-token"),
          modelWithBaseUrl("openai", "${OPENAI_BASE_URL}"),
          modelWithBaseUrl("openai-compatible", null),
          modelWithBaseUrl("openai-compatible", "replace-with-provider-base-url"),
          model("unknown", "private-test-api-key", "model-name"),
          model("openai", "private-test-api-key", "configured-model-name"),
          model("openai", "private-test-api-key", "${AGMA_MANAGED_SERVER_TOKEN}"),
          model("openai", "private-test-api-key", "model-name")
              + "storage:\n  sqlitePath: ${RUNTIME_DATABASE}\n",
          model("openai", "private-test-api-key", "model-name")
              + "storage:\n  sqlitePath: ${AGMA_MANAGED_SERVER_TOKEN}\n",
          "model: [invalid]\n"
        }) {
      var path = temporaryDirectory.resolve("runtime-" + Math.abs(source.hashCode()) + ".yml");
      Files.writeString(path, source);
      privateFile(path);

      var failure = assertThrows(StartupFailure.class, () -> probe.verify(path));

      assertEquals(StartupFailure.Code.MANAGED_RUNTIME_SETUP_REQUIRED, failure.code());
    }
  }

  @Test
  void rejectsAProviderFileThatIsReadableByOtherUsers() throws Exception {
    var config = write("openai", "private-test-api-key", "model-name");
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));

    var failure = assertThrows(StartupFailure.class, () -> probe.verify(config));

    assertEquals(StartupFailure.Code.MANAGED_RUNTIME_SETUP_REQUIRED, failure.code());
  }

  @Test
  void rejectsAProviderFileBelowAnUntrustedDirectory() throws Exception {
    var config = write("openai", "private-test-api-key", "model-name");
    Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
    try {
      var failure = assertThrows(StartupFailure.class, () -> probe.verify(config));
      assertEquals(StartupFailure.Code.MANAGED_RUNTIME_SETUP_REQUIRED, failure.code());
    } finally {
      Files.setPosixFilePermissions(
          temporaryDirectory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private Path write(String provider, String apiKey, String model) throws Exception {
    return writeSource(model(provider, apiKey, model));
  }

  private Path writeSource(String source) throws Exception {
    var path = temporaryDirectory.resolve("runtime.yml");
    Files.writeString(path, source);
    privateFile(path);
    return path;
  }

  private static String model(String provider, String apiKey, String model) {
    return "server:\n"
        + "  id: ${AGMA_MANAGED_SERVER_ID}\n"
        + "transport:\n"
        + "  host: 127.0.0.1\n"
        + "  port: ${AGMA_MANAGED_RUNTIME_PORT}\n"
        + "  serverToken: ${AGMA_MANAGED_SERVER_TOKEN}\n"
        + "model:\n"
        + "  provider: "
        + provider
        + "\n  apiKey: "
        + apiKey
        + "\n  model: "
        + model
        + "\n";
  }

  private static String modelWithBaseUrl(String provider, String baseUrl) {
    return model(provider, "private-test-api-key", "model-name")
        + (baseUrl == null ? "" : "  baseUrl: " + baseUrl + "\n");
  }

  private static void privateFile(Path path) throws Exception {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
  }
}
