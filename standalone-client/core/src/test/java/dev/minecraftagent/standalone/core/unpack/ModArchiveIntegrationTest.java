package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.adapter.CatalogAssembler;
import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the full runtime path: mod jar bytes to an assembled catalog snapshot. */
final class ModArchiveIntegrationTest {
  private static final String GENERATION = "integration-generation-1";
  @TempDir Path gameDirectory;

  @Test
  void fixtureJarFlowsIntoTheCatalogSnapshot() throws IOException {
    var modsDirectory = Files.createDirectory(gameDirectory.resolve("mods"));
    writeJar(
        modsDirectory.resolve("create.jar"),
        Map.ofEntries(
            Map.entry(
                "fabric.mod.json", "{\"id\":\"create\",\"name\":\"Create\",\"version\":\"0.5\"}"),
            Map.entry(
                "data/create/recipes/crushing/aluminum_ore.json",
                fixture("create/recipes/crushing/aluminum_ore.json")),
            Map.entry(
                "data/create/recipes/filling/blaze_cake.json",
                fixture("create/recipes/filling/blaze_cake.json")),
            Map.entry(
                "data/c/tags/items/aluminum_ores.json", "{\"values\":[\"create:aluminum_ore\"]}"),
            Map.entry(
                "assets/create/lang/en_us.json",
                "{\"item.create.crushed_raw_aluminum\":\"Crushed Raw Aluminum\","
                    + "\"item.create.blaze_cake\":\"Blaze Cake\",\"block.create.spout\":\"Spout\"}")));

    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(1, modpack.mods().size());
    assertEquals(2, modpack.report().totalRecipes());

    var result =
        new UnpackCatalogMapper()
            .map(
                modpack,
                (kind, id) -> Optional.empty(),
                id -> true,
                Set.of(),
                "en_us",
                GENERATION,
                1000,
                1000);
    assertEquals(2, result.report().processesAdded());
    assertEquals(0, result.report().skippedTotal());

    var snapshot =
        new CatalogAssembler()
            .assemble(
                GENERATION,
                "4".repeat(64),
                Instant.parse("2026-09-13T00:00:00Z"),
                new CatalogAdapter.Contribution(
                    "minecraft_registry", GENERATION, List.of(), List.of(), List.of()),
                new CatalogAdapter.Contribution(
                    "vanilla_client", GENERATION, List.of(), List.of(), List.of()),
                result.contribution());

    var crushing = snapshot.process("create:crushing/aluminum_ore");
    assertTrue(crushing.isPresent());
    assertEquals("Crushed Raw Aluminum", crushing.get().outputs().get(0).resource().displayName());
    var resource =
        snapshot.resource(
            new ResourceKey(ResourceRef.Kind.ITEM, "create:crushed_raw_aluminum", null));
    assertTrue(resource.isPresent());
    assertEquals(ResourceRef.Trust.L2, resource.get().source().trust());

    var filling = snapshot.process("create:filling/blaze_cake");
    assertTrue(filling.isPresent());
    var lava =
        filling.get().inputs().stream()
            .flatMap(group -> group.alternatives().stream())
            .filter(candidate -> candidate.kind() == ResourceRef.Kind.FLUID)
            .findFirst()
            .orElseThrow();
    assertEquals("minecraft:lava", lava.id());
    assertTrue(
        snapshot
            .resource(new ResourceKey(ResourceRef.Kind.FLUID, "minecraft:lava", null))
            .isPresent());
  }

  private static String fixture(String path) {
    var resource = "/unpack-fixtures/data/" + path;
    try (var stream = ModArchiveIntegrationTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing fixture " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void writeJar(Path path, Map<String, String> entries) throws IOException {
    try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
  }
}
