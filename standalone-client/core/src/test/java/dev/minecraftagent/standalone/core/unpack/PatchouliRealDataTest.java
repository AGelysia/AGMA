package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test against real guide book content lifted from actual mod archives: Botania's
 * lexicon (en_us only, every string a translation key) and Ad Astra's astrodux (full en_us and
 * zh_cn, literal text). The fixtures under {@code /unpack-fixtures/} preserve the real archive
 * layout — {@code data/<ns>/patchouli_books/<book>/book.json} plus {@code
 * assets/<ns>/patchouli_books/<book>/<locale>/...} — and the botania lang file is trimmed to the
 * referenced keys with the real values. The whole path (scan then extract) must produce documents
 * with real entry bodies, not title-only stubs.
 */
final class PatchouliRealDataTest {
  @TempDir Path modsDirectory;

  @Test
  void realBooksProduceRichDocumentsInBothLocales() throws IOException {
    jar(
        "botania.jar",
        "{\"id\":\"botania\",\"name\":\"Botania\",\"version\":\"455\"}",
        "data/botania/patchouli_books/lexicon/book.json",
        "assets/botania/lang/en_us.json",
        "assets/botania/patchouli_books/lexicon/en_us/categories/basics.json",
        "assets/botania/patchouli_books/lexicon/en_us/entries/basics/lexicon.json",
        "assets/botania/patchouli_books/lexicon/en_us/entries/basics/welcome.json");
    jar(
        "ad_astra.jar",
        "{\"id\":\"ad_astra\",\"name\":\"Ad Astra\",\"version\":\"1.15.21\"}",
        "data/ad_astra/patchouli_books/astrodux/book.json",
        "assets/ad_astra/patchouli_books/astrodux/en_us/categories/the_moon.json",
        "assets/ad_astra/patchouli_books/astrodux/en_us/entries/the_moon/ti_69.json",
        "assets/ad_astra/patchouli_books/astrodux/zh_cn/categories/the_moon.json",
        "assets/ad_astra/patchouli_books/astrodux/zh_cn/entries/the_moon/ti_69.json");

    var modpack = new ModArchiveScanner().scan(modsDirectory);
    assertEquals(2, modpack.mods().size());
    assertEquals(2, modpack.report().totalPatchouliBooks());
    var botania = modpack.mods().get(1);
    assertEquals("botania", botania.modId());
    assertEquals(1, botania.patchouliBooks().size());
    var lexicon = botania.patchouliBooks().get(0);
    assertEquals("botania:lexicon", lexicon.id());
    assertEquals("item.botania.lexicon", lexicon.definition().get("name"));
    assertEquals(3, lexicon.files().size());

    // zh_cn preferred (the game locale of the smoke instance): the en_us-only Botania book must
    // still come out complete through per-entry fallback.
    var zh = new PatchouliBookExtractor().extract(modpack, "zh_cn");
    assertEquals(2, zh.documents().size());
    var lexiconDoc =
        zh.documents().stream()
            .filter(document -> document.fileName().equals("agma-modbook-botania-lexicon.md"))
            .findFirst()
            .orElseThrow();
    assertEquals("Lexica Botania", lexiconDoc.title());
    var markdown = lexiconDoc.markdown();
    assertEquals(2, countOccurrences(markdown, "\n## "));
    assertTrue(markdown.contains("## Welcome To Botania"), () -> markdown);
    // Page text was a lang key in the entry JSON and resolves to the real en_us text.
    assertTrue(
        markdown.contains(
            "Lexica Botania is the repository of all knowledge for all botanical matters"),
        () -> markdown);
    // No raw translation keys leak into the document.
    assertFalse(markdown.contains("botania.page.lexicon0"), () -> markdown);
    assertFalse(markdown.contains("$("), () -> markdown);

    // Ad Astra ships full zh_cn content, so zh_cn wins per entry under the zh_cn game locale.
    var astroduxDoc =
        zh.documents().stream()
            .filter(document -> document.fileName().equals("agma-modbook-ad-astra-astrodux.md"))
            .findFirst()
            .orElseThrow();
    var zhMarkdown = astroduxDoc.markdown();
    assertEquals(1, countOccurrences(zhMarkdown, "\n## "));
    assertTrue(zhMarkdown.contains("显示局部的氧气"), () -> zhMarkdown);
    assertTrue(zhMarkdown.contains("Ti-69 配方"), () -> zhMarkdown);
    assertTrue(zhMarkdown.contains("[recipe: ad_astra:ti_69]"), () -> zhMarkdown);

    // Under the en_us game locale the same book renders its en_us entry text.
    var en = new PatchouliBookExtractor().extract(modpack, "en_us");
    var enAstrodux =
        en.documents().stream()
            .filter(document -> document.fileName().equals("agma-modbook-ad-astra-astrodux.md"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        enAstrodux.markdown().contains("display the local oxygen"), () -> enAstrodux.markdown());
    // The en_us-only Botania book is identical under both locales.
    var enLexicon =
        en.documents().stream()
            .filter(document -> document.fileName().equals("agma-modbook-botania-lexicon.md"))
            .findFirst()
            .orElseThrow();
    assertEquals(markdown, enLexicon.markdown());
  }

  private static int countOccurrences(String haystack, String needle) {
    var count = 0;
    var index = -1;
    while ((index = haystack.indexOf(needle, index + 1)) >= 0) {
      count++;
    }
    return count;
  }

  /** Writes a synthetic mod jar whose content files come verbatim from the classpath fixtures. */
  private void jar(String name, String modMetadata, String... fixturePaths) throws IOException {
    var entries = new LinkedHashMap<String, String>();
    entries.put("fabric.mod.json", modMetadata);
    for (var path : fixturePaths) {
      entries.put(path, fixture("/unpack-fixtures/" + path));
    }
    var path = modsDirectory.resolve(name);
    try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
  }

  private static String fixture(String resource) {
    try (var stream = PatchouliRealDataTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("missing fixture " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
