package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientDiagnosticExporterTest {
  @TempDir Path temporary;

  @Test
  void exportsOnlyTheClosedRedactedSupportShape() throws Exception {
    var root = temporary.resolve("state").toAbsolutePath().normalize();
    Files.createDirectory(root);
    var profile =
        new ClientProfileSnapshot(
            ClientLifecycleState.ERROR,
            2,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "openai",
            "test-model",
            "RUNTIME_START_FAILED");
    var runtime =
        new ClientRuntimeView(profile, Set.of("game.resource.search"), "RUNTIME_START_FAILED");
    var exporter =
        new ClientDiagnosticExporter(
            Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC));
    var output =
        exporter.write(
            root,
            runtime,
            new ClientDiagnosticExporter.CatalogDiagnostic(
                "1.21.11", "READY", "SINGLEPLAYER", "PARTIAL", 10, 3, "jei"));
    var text = Files.readString(output);
    assertTrue(text.contains("RUNTIME_START_FAILED"));
    assertTrue(text.contains("game.resource.search"));
    assertFalse(text.contains("11111111"));
    assertFalse(text.contains(root.toString()));
    assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("apikey"));
    assertFalse(text.contains("question"));
  }
}
