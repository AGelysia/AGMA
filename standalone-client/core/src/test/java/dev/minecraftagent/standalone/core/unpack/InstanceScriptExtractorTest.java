package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstanceScriptExtractorTest {
  @TempDir Path gameDirectory;

  private final InstanceScriptExtractor extractor = new InstanceScriptExtractor();

  @Test
  void skipsMissingDirectoriesSilently() {
    var data = extractor.extract(gameDirectory);
    assertTrue(data.recipes().isEmpty());
    assertTrue(data.removals().isEmpty());
    assertEquals(0, data.stats().filesScanned());
    assertEquals(0, data.stats().skippedUnparsed());
  }

  @Test
  void extractsBothScriptKinds() throws IOException {
    var kubeJs = Files.createDirectories(gameDirectory.resolve("kubejs").resolve("server_scripts"));
    Files.writeString(kubeJs.resolve("a.js"), "event.shapeless('testmod:mix', ['minecraft:dirt'])");
    var nested = Files.createDirectories(kubeJs.resolve("nested"));
    Files.writeString(nested.resolve("b.js"), "event.smelting('testmod:glass', 'minecraft:sand')");
    var scripts = Files.createDirectories(gameDirectory.resolve("scripts"));
    Files.writeString(
        scripts.resolve("c.zs"),
        "craftingTable.addShapeless(\"mix2\", <item:testmod:mix2>, [<item:minecraft:sand>]);\n");
    // Files with other extensions are ignored.
    Files.writeString(scripts.resolve("notes.txt"), "craftingTable.removeAll();");

    var data = extractor.extract(gameDirectory);
    assertEquals(3, data.stats().filesScanned());
    assertEquals(3, data.recipes().size());
    assertEquals(3, data.stats().recipesExtracted());
    assertEquals("kubejs:shapeless", data.recipes().get(0).type());
    assertEquals("kubejs:smelting", data.recipes().get(1).type());
    assertEquals("crafttweaker:shapeless", data.recipes().get(2).type());
    // Synthetic ids are unique across files.
    assertEquals(
        data.recipes().size(), data.recipes().stream().map(UnpackedRecipe::id).distinct().count());
  }

  @Test
  void boundsFileCountAndFileSize() throws IOException {
    var kubeJs = Files.createDirectories(gameDirectory.resolve("kubejs").resolve("server_scripts"));
    for (var index = 0; index < InstanceScriptExtractor.MAXIMUM_FILES + 6; index++) {
      Files.writeString(
          kubeJs.resolve(String.format("%02d", index) + ".js"),
          "event.shapeless('testmod:m" + index + "', ['minecraft:dirt'])");
    }
    var data = extractor.extract(gameDirectory);
    assertEquals(InstanceScriptExtractor.MAXIMUM_FILES, data.stats().filesScanned());
    assertEquals(6, data.stats().filesSkipped());

    var scripts = Files.createDirectories(gameDirectory.resolve("scripts"));
    var big = "// " + "x".repeat(InstanceScriptExtractor.MAXIMUM_FILE_BYTES);
    Files.writeString(scripts.resolve("big.zs"), big);
    var second = extractor.extract(gameDirectory);
    assertTrue(second.stats().filesSkipped() >= 7);
  }
}
