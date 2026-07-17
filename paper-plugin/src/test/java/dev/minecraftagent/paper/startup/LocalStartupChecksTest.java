package dev.minecraftagent.paper.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStartupChecksTest {
  @TempDir Path temporaryDirectory;

  @BeforeEach
  void requirePosixPermissions() {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  @Test
  void returnsReadyWhenEveryCoreCheckAndOptionalDirectoryPasses() throws Exception {
    Files.createDirectory(temporaryDirectory.resolve("capabilities"));
    var config =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());

    var result = new LocalStartupChecks().run(request(config));

    assertEquals(StartupHealth.READY, result.health());
    assertTrue(result.warnings().isEmpty());
    assertTrue(result.coreTools().ready());
    assertTrue(Files.isDirectory(result.config().stateDirectory()));
  }

  @Test
  void missingOptionalCapabilitiesOnlyProducesDegradedWarning() throws Exception {
    var config =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());

    var result = new LocalStartupChecks().run(request(config));

    assertEquals(StartupHealth.DEGRADED, result.health());
    assertEquals(
        java.util.List.of(
            new StartupWarning(
                StartupWarning.Code.OPTIONAL_CAPABILITY_UNAVAILABLE,
                StartupWarning.Stage.OPTIONAL_CAPABILITIES)),
        result.warnings());
    assertTrue(result.coreTools().ready());
  }

  @Test
  void coreCompatibilityFailurePreventsAnyStateMutation() throws Exception {
    var config =
        StartupTestFixture.writeConfig(temporaryDirectory, StartupTestFixture.validConfig());
    var request =
        new LocalStartupChecks.Request(
            config, temporaryDirectory, StartupTestFixture.environment(), 21, "1.21.10");

    var failure = assertThrows(StartupFailure.class, () -> new LocalStartupChecks().run(request));

    assertEquals(StartupFailure.Code.PAPER_VERSION_UNSUPPORTED, failure.code());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(temporaryDirectory.resolve("state")));
  }

  private LocalStartupChecks.Request request(Path config) {
    return new LocalStartupChecks.Request(
        config,
        temporaryDirectory,
        StartupTestFixture.environment(),
        Runtime.version().feature(),
        "1.21.11");
  }
}
