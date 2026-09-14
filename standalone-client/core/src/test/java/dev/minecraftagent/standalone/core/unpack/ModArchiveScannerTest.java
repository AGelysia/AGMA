package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModArchiveScannerTest {
  @TempDir Path modsDirectory;

  @Test
  void unpacksFabricModArchive() throws IOException {
    jar(
        "create-test.jar",
        Map.ofEntries(
            Map.entry(
                "fabric.mod.json",
                "{\"id\":\"testmod\",\"name\":\"Test Mod\",\"version\":\"1.2.3\"}"),
            Map.entry(
                "data/testmod/recipes/machines/foo.json",
                "{\"type\":\"testmod:machine\",\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}],"
                    + "\"result\":{\"item\":\"testmod:foo\"}}"),
            Map.entry(
                "data/testmod/tags/items/gems.json",
                "{\"replace\":false,\"values\":[\"testmod:ruby\",{\"id\":\"#testmod:shards\","
                    + "\"required\":true},{\"id\":\"testmod:topaz\",\"required\":false}]}"),
            Map.entry(
                "assets/testmod/lang/en_us.json",
                "{\"item.testmod.foo\":\"Foo Item\",\"block.testmod.machine\":\"Machine\"}"),
            Map.entry("assets/testmod/lang/zh_cn.json", "{\"item.testmod.foo\":\" foo zh\"}"),
            Map.entry("assets/testmod/lang/de_de.json", "{\"item.testmod.foo\":\"Foo DE\"}"),
            Map.entry("README.txt", "not json")));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(1, modpack.mods().size());
    var mod = modpack.mods().get(0);
    assertEquals("testmod", mod.modId());
    assertEquals("Test Mod", mod.modName());
    assertEquals("1.2.3", mod.modVersion());
    assertEquals("create-test.jar", mod.jarName());
    assertEquals(1, mod.recipes().size());
    var recipe = mod.recipes().get(0);
    assertEquals("testmod:machines/foo", recipe.id());
    assertEquals("testmod:machine", recipe.type());
    assertEquals(1, mod.tags().size());
    var tag = mod.tags().get(0);
    assertEquals(UnpackedTag.Kind.ITEM, tag.kind());
    assertEquals("testmod:gems", tag.id());
    assertEquals(
        List.of(
            new UnpackedTag.Entry("testmod:ruby", true),
            new UnpackedTag.Entry("#testmod:shards", true),
            new UnpackedTag.Entry("testmod:topaz", false)),
        tag.values());
    assertEquals("Foo Item", mod.lang().get("en_us").get("item.testmod.foo"));
    assertEquals(1, mod.lang().get("zh_cn").size());
    // Only en_us and zh_cn are collected.
    assertEquals(2, mod.lang().size());

    var report = modpack.report();
    assertEquals(1, report.jarsConsidered());
    assertEquals(1, report.jarsScanned());
    assertEquals(1, report.totalRecipes());
    assertEquals(1, report.totalTags());
    assertEquals(3, report.totalLangEntries());
    assertEquals(1, report.mods().size());
    assertEquals("testmod", report.mods().get(0).modId());
  }

  @Test
  void skipsLibrariesWithoutModMetadata() throws IOException {
    jar(
        "library.jar",
        Map.of(
            "data/library/recipes/hidden.json",
            "{\"type\":\"library:hidden\",\"result\":{\"item\":\"library:x\"}}"));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertTrue(modpack.mods().isEmpty());
    assertEquals(1, modpack.report().jarsSkippedNoMetadata());
    assertEquals(0, modpack.report().totalRecipes());
  }

  @Test
  void parsesModsTomlMetadata() throws IOException {
    jar(
        "forge-style.jar",
        Map.of(
            "META-INF/mods.toml",
            "modLoader=\"javafml\"\n"
                + "[[mods]]\n"
                + "modId=\"tomlmod\"\n"
                + "displayName=\"TOML Mod\"\n"
                + "version=\"${file.jarVersion}\"\n"
                + "[[dependencies.tomlmod]]\n"
                + "modId=\"other\"\n",
            "data/tomlmod/recipes/thing.json",
            "{\"type\":\"tomlmod:thing\",\"result\":{\"item\":\"tomlmod:thing\"}}"));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(1, modpack.mods().size());
    var mod = modpack.mods().get(0);
    assertEquals("tomlmod", mod.modId());
    assertEquals("TOML Mod", mod.modName());
    // Unresolved placeholders normalize to "unknown".
    assertEquals("unknown", mod.modVersion());
    assertEquals(1, mod.recipes().size());
  }

  @Test
  void skipsOversizeEntriesButKeepsTheMod() throws IOException {
    var bigRecipe = "{\"type\":\"testmod:machine\",\"padding\":\"" + "x".repeat(4096) + "\"}";
    jar(
        "mixed.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"testmod\"}",
            "data/testmod/recipes/big.json", bigRecipe,
            "data/testmod/recipes/small.json", "{\"type\":\"testmod:small\"}"));
    var limits = new ModArchiveScanner.Limits(512, 256L * 1024 * 1024, 1024, 20_000, 200_000);
    var modpack = new ModArchiveScanner(limits).scan(modsDirectory);
    var mod = modpack.mods().get(0);
    assertEquals(1, mod.recipes().size());
    assertEquals("testmod:small", mod.recipes().get(0).id());
    assertEquals(1, modpack.report().entriesSkippedOversize());
  }

  @Test
  void skipsOversizeJars() throws IOException {
    var limits = new ModArchiveScanner.Limits(512, 64, 1024 * 1024, 20_000, 200_000);
    jar(
        "fat.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"testmod\"}",
            "data/testmod/recipes/a.json", "{\"type\":\"testmod:a\"}"));
    var modpack = new ModArchiveScanner(limits).scan(modsDirectory);
    assertTrue(modpack.mods().isEmpty());
    assertEquals(1, modpack.report().jarsSkippedOversize());
  }

  @Test
  void countsMalformedJsonAndKeepsScanning() throws IOException {
    jar(
        "broken.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"testmod\"}",
            "data/testmod/recipes/broken.json", "{not json",
            "data/testmod/recipes/fine.json", "{\"type\":\"testmod:fine\"}"));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(1, modpack.mods().size());
    assertEquals(1, modpack.mods().get(0).recipes().size());
    assertEquals(1, modpack.report().entriesMalformed());
  }

  @Test
  void scansJarsInNameOrderAndIgnoresNonJars() throws IOException {
    jar("b-mod.jar", Map.of("fabric.mod.json", "{\"id\":\"bmod\"}"));
    jar("a-mod.jar", Map.of("fabric.mod.json", "{\"id\":\"amod\"}"));
    Files.writeString(modsDirectory.resolve("notes.txt"), "hello");
    Files.createDirectory(modsDirectory.resolve("notajar.jar"));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(List.of("amod", "bmod"), modpack.mods().stream().map(UnpackedMod::modId).toList());
    assertEquals(2, modpack.report().jarsConsidered());
  }

  @Test
  void missingDirectoryYieldsEmptyModpack() {
    var modpack = new ModArchiveScanner().scan(modsDirectory.resolve("does-not-exist"));
    assertTrue(modpack.mods().isEmpty());
    assertEquals(0, modpack.report().jarsConsidered());
  }

  @Test
  void boundsEntriesPerJar() throws IOException {
    var entries = new LinkedHashMap<String, String>();
    entries.put("fabric.mod.json", "{\"id\":\"testmod\"}");
    for (var index = 0; index < 40; index++) {
      entries.put(
          "data/testmod/recipes/r" + index + ".json", "{\"type\":\"testmod:r" + index + "\"}");
    }
    jar("many.jar", entries);
    var limits = new ModArchiveScanner.Limits(512, 256L * 1024 * 1024, 1024 * 1024, 8, 200_000);
    var modpack = new ModArchiveScanner(limits).scan(modsDirectory);
    var scanned = modpack.mods().get(0).recipes().size();
    assertTrue(scanned < 40, "entry cap must bound recipe reads, got " + scanned);
  }

  @Test
  void mergesTagsAcrossMods() throws IOException {
    jar(
        "one.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"one\"}",
            "data/c/tags/items/gems.json", "{\"values\":[\"one:ruby\"]}"));
    jar(
        "two.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"two\"}",
            "data/c/tags/items/gems.json", "{\"values\":[\"two:topaz\",\"one:ruby\"]}",
            "data/c/tags/items/reset.json", "{\"replace\":true,\"values\":[\"two:only\"]}"));
    jar(
        "three.jar",
        Map.of(
            "fabric.mod.json", "{\"id\":\"three\"}",
            "data/c/tags/items/reset.json", "{\"values\":[\"three:ignored\"]}"));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    var tags = modpack.mergedTags(UnpackedTag.Kind.ITEM);
    assertEquals(
        List.of(new UnpackedTag.Entry("one:ruby", true), new UnpackedTag.Entry("two:topaz", true)),
        tags.get("c:gems"));
    // Scan order is one, three, two (jar name order); two's replace file discards the values
    // accumulated from three's earlier non-replace file.
    assertEquals(List.of(new UnpackedTag.Entry("two:only", true)), tags.get("c:reset"));
  }

  @Test
  void collectsPatchouliBooksAndAdvancements() throws IOException {
    // The real guide book layout is hybrid: book.json on the data side, localized categories and
    // entries on the assets side next to the lang files.
    jar(
        "guide.jar",
        Map.ofEntries(
            Map.entry("fabric.mod.json", "{\"id\":\"testmod\"}"),
            Map.entry(
                "data/testmod/patchouli_books/guide/book.json",
                "{\"name\":\"book.testmod.guide.title\"}"),
            Map.entry(
                "assets/testmod/patchouli_books/guide/en_us/categories/machines.json",
                "{\"name\":\"Machines\"}"),
            Map.entry(
                "assets/testmod/patchouli_books/guide/en_us/entries/first.json",
                "{\"name\":\"First\",\"pages\":[{\"text\":\"hello\"}]}"),
            Map.entry(
                "assets/testmod/patchouli_books/guide/zh_cn/entries/first.json",
                "{\"name\":\"First ZH\",\"pages\":[{\"text\":\"你好\"}]}"),
            Map.entry(
                "assets/testmod/patchouli_books/guide/de_de/entries/first.json",
                "{\"name\":\"First DE\",\"pages\":[{\"text\":\"hallo\"}]}"),
            Map.entry("assets/testmod/lang/en_us.json", "{\"book.testmod.guide.title\":\"Guide\"}"),
            Map.entry(
                "data/testmod/advancements/root.json",
                "{\"display\":{\"title\":{\"translate\":\"t\"},\"description\":\"d\"}}"),
            Map.entry(
                "data/testmod/advancements/branch/child.json",
                "{\"parent\":\"testmod:root\",\"display\":{\"title\":\"c\",\"description\":\"d\"}}")));
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    var mod = modpack.mods().get(0);
    assertEquals(1, mod.patchouliBooks().size());
    var book = mod.patchouliBooks().get(0);
    assertEquals("testmod:guide", book.id());
    assertEquals("book.testmod.guide.title", book.definition().get("name"));
    // Lang collection still works alongside the book documents on the assets side.
    assertEquals("Guide", mod.lang().get("en_us").get("book.testmod.guide.title"));
    // de_de is not a scanned locale; the category flag and paths survive.
    assertEquals(3, book.files().size());
    var categories =
        book.files().stream().filter(UnpackedPatchouliBook.BookFile::category).toList();
    assertEquals(1, categories.size());
    assertEquals("en_us", categories.get(0).locale());
    assertEquals("machines", categories.get(0).path());
    var zhEntries = book.files().stream().filter(file -> file.locale().equals("zh_cn")).toList();
    assertEquals(1, zhEntries.size());
    assertEquals("first", zhEntries.get(0).path());
    assertEquals(2, mod.advancements().size());
    assertEquals(
        java.util.Set.of("testmod:root", "testmod:branch/child"),
        mod.advancements().stream()
            .map(UnpackedAdvancement::id)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(1, modpack.report().totalPatchouliBooks());
    assertEquals(2, modpack.report().totalAdvancements());
  }

  @Test
  void boundsAdvancementsPerJar() throws IOException {
    var entries = new LinkedHashMap<String, String>();
    entries.put("fabric.mod.json", "{\"id\":\"testmod\"}");
    for (var index = 0; index < ModArchiveScanner.MAXIMUM_ADVANCEMENTS_PER_JAR + 10; index++) {
      entries.put(
          "data/testmod/advancements/a" + index + ".json",
          "{\"display\":{\"title\":\"t\",\"description\":\"d\"}}");
    }
    jar("many-advancements.jar", entries);
    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(
        ModArchiveScanner.MAXIMUM_ADVANCEMENTS_PER_JAR,
        modpack.mods().get(0).advancements().size());
  }

  private Path jar(String name, Map<String, String> entries) throws IOException {
    var path = modsDirectory.resolve(name);
    try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return path;
  }
}
