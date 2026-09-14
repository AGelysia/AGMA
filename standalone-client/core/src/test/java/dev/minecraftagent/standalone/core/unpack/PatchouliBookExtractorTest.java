package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PatchouliBookExtractorTest {
  private final PatchouliBookExtractor extractor = new PatchouliBookExtractor();

  @Test
  void rendersBookWithTranslatedTitleAndStrippedMarkup() {
    var modpack = modpack(book(true, true));
    var result = extractor.extract(modpack);
    assertEquals(1, result.documents().size());
    assertEquals(1, result.booksSeen());
    var document = result.documents().get(0);
    assertEquals("agma-modbook-testmod-guide.md", document.fileName());
    // zh_cn is preferred when the book ships zh_cn documents.
    assertEquals("指南", document.title());
    var markdown = document.markdown();
    assertTrue(markdown.startsWith("# 指南\n"), () -> markdown);
    assertTrue(markdown.contains("[recipe: testmod:crushing]"), () -> markdown);
    assertTrue(markdown.contains("[item: testmod:crushing_wheel]"), () -> markdown);
    // Markup codes are stripped to plain text and line breaks survive.
    assertFalse(markdown.contains("$("), () -> markdown);
    assertTrue(markdown.contains("Crush things\nnow"), () -> markdown);
  }

  @Test
  void fallsBackToEnUsLocale() {
    // The book and the lang maps only exist in en_us; under the zh_cn preference the document
    // still comes out complete through per-entry fallback.
    var modpack = modpackEnOnly(book(false, true));
    var document = extractor.extract(modpack).documents().get(0);
    assertEquals("Guide Book", document.title());
    assertTrue(document.markdown().contains("## First Entry"));
  }

  @Test
  void sortsEntriesByCategoryThenName() {
    var files =
        List.of(
            categoryFile("en_us", "tools", "{\"name\":\"Tools\"}"),
            categoryFile("en_us", "machines", "{\"name\":\"Machines\"}"),
            entryFile(
                "en_us",
                "zz",
                "{\"name\":\"Alpha\",\"category\":\"tools\",\"pages\":[{\"text\":\"t\"}]}"),
            entryFile(
                "en_us",
                "aa",
                "{\"name\":\"Beta\",\"category\":\"testmod:machines\",\"pages\":[{\"text\":\"m\"}]}"),
            entryFile("en_us", "misc", "{\"name\":\"Intro\",\"pages\":[{\"text\":\"i\"}]}"));
    var book = new UnpackedPatchouliBook("testmod:guide", Map.of("name", "Guide"), files);
    var document = extractor.extract(modpack(book)).documents().get(0);
    var markdown = document.markdown();
    var intro = markdown.indexOf("## Intro");
    var beta = markdown.indexOf("## Beta");
    var alpha = markdown.indexOf("## Alpha");
    assertTrue(intro >= 0 && beta > intro && alpha > beta, () -> markdown);
  }

  @Test
  void boundsEntriesPerBookAndCharactersPerEntry() {
    var files = new ArrayList<UnpackedPatchouliBook.BookFile>();
    for (var index = 0; index < 130; index++) {
      files.add(
          entryFile(
              "en_us",
              "e" + index,
              "{\"name\":\"Entry " + index + "\",\"pages\":[{\"text\":\"x\"}]}"));
    }
    var capped =
        extractor.extract(
            modpack(new UnpackedPatchouliBook("testmod:guide", Map.of("name", "Guide"), files)));
    var markdown = capped.documents().get(0).markdown();
    assertEquals(
        PatchouliBookExtractor.MAXIMUM_ENTRIES_PER_BOOK, countOccurrences(markdown, "\n## "));

    var longText = "y".repeat(1000);
    var longEntry =
        new UnpackedPatchouliBook(
            "testmod:guide",
            Map.of("name", "Guide"),
            List.of(
                entryFile(
                    "en_us",
                    "long",
                    "{\"name\":\"Long\",\"pages\":[{\"text\":\"" + longText + "\"}]}")));
    var document = extractor.extract(modpack(longEntry)).documents().get(0);
    assertFalse(document.markdown().contains("y".repeat(513)));
    assertTrue(document.markdown().contains("y".repeat(512)));
  }

  @Test
  void boundsBooksPerScanAndDocumentBytes() {
    var books = new ArrayList<UnpackedPatchouliBook>();
    for (var index = 0; index < 30; index++) {
      books.add(
          new UnpackedPatchouliBook(
              "testmod:book" + index,
              Map.of("name", "Book " + index),
              List.of(
                  entryFile("en_us", "one", "{\"name\":\"One\",\"pages\":[{\"text\":\"x\"}]}"))));
    }
    var mod = mod(books);
    var result = extractor.extract(new UnpackedModpack(List.of(mod), ScanReport.empty()));
    assertEquals(PatchouliBookExtractor.MAXIMUM_BOOKS, result.documents().size());
    assertEquals(30, result.booksSeen());
    assertEquals(30 - PatchouliBookExtractor.MAXIMUM_BOOKS, result.booksSkippedBudget());

    // A maximally filled book still respects the per-document byte cap.
    var files = new ArrayList<UnpackedPatchouliBook.BookFile>();
    for (var index = 0; index < PatchouliBookExtractor.MAXIMUM_ENTRIES_PER_BOOK; index++) {
      files.add(
          entryFile(
              "en_us",
              "e" + index,
              "{\"name\":\"Entry "
                  + index
                  + "\",\"pages\":[{\"text\":\""
                  + "z".repeat(512)
                  + "\"}]}"));
    }
    var big =
        extractor.extract(
            modpack(new UnpackedPatchouliBook("testmod:big", Map.of("name", "Big"), files)));
    var bytes = big.documents().get(0).markdown().getBytes(StandardCharsets.UTF_8).length;
    assertTrue(
        bytes <= PatchouliBookExtractor.MAXIMUM_DOCUMENT_BYTES, () -> "document bytes " + bytes);
  }

  private static int countOccurrences(String haystack, String needle) {
    var count = 0;
    var index = -1;
    while ((index = haystack.indexOf(needle, index + 1)) >= 0) {
      count++;
    }
    return count;
  }

  private static UnpackedModpack modpack(UnpackedPatchouliBook book) {
    return new UnpackedModpack(List.of(mod(List.of(book))), ScanReport.empty());
  }

  private static UnpackedModpack modpackEnOnly(UnpackedPatchouliBook book) {
    var lang =
        Map.of(
            "en_us",
            Map.of(
                "book.testmod.guide.title", "Guide Book",
                "entry.testmod.first", "First Entry"));
    var mod =
        new UnpackedMod(
            "testmod",
            "Test Mod",
            "1.0",
            "testmod.jar",
            List.of(),
            List.of(),
            lang,
            List.of(book),
            List.of());
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  private static UnpackedMod mod(List<UnpackedPatchouliBook> books) {
    var lang =
        Map.of(
            "en_us",
            Map.of(
                "book.testmod.guide.title", "Guide Book",
                "entry.testmod.first", "First Entry"),
            "zh_cn",
            Map.of(
                "book.testmod.guide.title", "指南",
                "entry.testmod.first", "条目一"));
    return new UnpackedMod(
        "testmod", "Test Mod", "1.0", "testmod.jar", List.of(), List.of(), lang, books, List.of());
  }

  /** A guide book with one category and one entry in each requested locale. */
  private static UnpackedPatchouliBook book(boolean zhCn, boolean enUs) {
    var files = new ArrayList<UnpackedPatchouliBook.BookFile>();
    if (enUs) {
      files.add(categoryFile("en_us", "machines", "{\"name\":\"Machines\"}"));
      files.add(entryFile("en_us", "first", entryJson("entry.testmod.first")));
    }
    if (zhCn) {
      files.add(categoryFile("zh_cn", "machines", "{\"name\":\"机器\"}"));
      files.add(entryFile("zh_cn", "first", entryJson("entry.testmod.first")));
    }
    return new UnpackedPatchouliBook(
        "testmod:guide", Map.of("name", "book.testmod.guide.title"), files);
  }

  private static String entryJson(String nameKey) {
    return "{\"name\":\""
        + nameKey
        + "\",\"category\":\"testmod:machines\",\"pages\":["
        + "{\"type\":\"patchouli:text\",\"text\":\"$(l)Crush$() things$(br)now\"},"
        + "{\"type\":\"patchouli:crafting\",\"title\":\"Make it\",\"recipe\":\"testmod:crushing\","
        + "\"item\":\"testmod:crushing_wheel, not-an-id\"}]}";
  }

  private static UnpackedPatchouliBook.BookFile categoryFile(
      String locale, String path, String json) {
    return new UnpackedPatchouliBook.BookFile(locale, true, path, BoundedJson.parseObject(json));
  }

  private static UnpackedPatchouliBook.BookFile entryFile(String locale, String path, String json) {
    return new UnpackedPatchouliBook.BookFile(locale, false, path, BoundedJson.parseObject(json));
  }
}
