package dev.minecraftagent.standalone.core.unpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Flattens the guide books found in scanned mod archives into bounded markdown knowledge documents,
 * one document per book. The document header is the translated book title; entries are sorted by
 * their (translated) category and name and keep only human text: page {@code text} and {@code
 * title} fields (themselves resolved through the mod's lang maps, because real books store
 * translation keys there) plus compact {@code [item: ...]} and {@code [recipe: ...]} reference
 * lines. Locale selection is per entry and per category: the preferred locale (the game locale)
 * wins when the book ships that entry in it, with {@code en_us} as the fallback and any other
 * scanned locale last, so partially translated books still come out complete. Markup codes are
 * stripped ({@link KnowledgeText}) and every document stays within entry, size, and total budgets;
 * anything beyond the budgets is counted in the result and skipped, never thrown.
 */
public final class PatchouliBookExtractor {
  public static final int MAXIMUM_BOOKS = 24;
  public static final int MAXIMUM_ENTRIES_PER_BOOK = 128;
  public static final int MAXIMUM_ENTRY_CHARACTERS = 512;
  public static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
  public static final int MAXIMUM_TOTAL_BYTES = 1536 * 1024;
  private static final int MAXIMUM_PAGES_PER_ENTRY = 64;

  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final String FALLBACK_LOCALE = "en_us";
  private static final List<String> SCAN_LOCALES = List.of("en_us", "zh_cn");

  public record Result(List<KnowledgeDocument> documents, int booksSeen, int booksSkippedBudget) {
    public Result {
      documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }
  }

  /** Extracts with the default preferred locale zh_cn. */
  public Result extract(UnpackedModpack modpack) {
    return extract(modpack, "zh_cn");
  }

  /**
   * Extracts with {@code preferredLocale} (usually the game locale) winning per entry and per
   * category, {@code en_us} as the fallback. A blank preferred locale degrades to {@code en_us}.
   */
  public Result extract(UnpackedModpack modpack, String preferredLocale) {
    Objects.requireNonNull(modpack, "modpack");
    var preferred =
        preferredLocale == null || preferredLocale.isBlank()
            ? FALLBACK_LOCALE
            : preferredLocale.strip();
    var documents = new ArrayList<KnowledgeDocument>();
    var seen = 0;
    var skippedBudget = 0;
    var totalBytes = 0;
    for (var mod : modpack.mods()) {
      for (var book : mod.patchouliBooks()) {
        seen++;
        if (documents.size() >= MAXIMUM_BOOKS) {
          skippedBudget++;
          continue;
        }
        var document = bookDocument(mod, book, preferred);
        if (document == null) {
          continue;
        }
        var bytes = document.markdown().getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes + bytes > MAXIMUM_TOTAL_BYTES) {
          skippedBudget++;
          continue;
        }
        totalBytes += bytes;
        documents.add(document);
      }
    }
    return new Result(documents, seen, skippedBudget);
  }

  private static KnowledgeDocument bookDocument(
      UnpackedMod mod, UnpackedPatchouliBook book, String preferred) {
    var title =
        KnowledgeText.sanitized(
            ModLangText.resolve(mod.lang(), preferred, stringValue(book.definition().get("name"))),
            200);
    if (title.isEmpty()) {
      title = book.id();
    }
    var categoryNames = categoryNames(mod, book, preferred);
    var entriesByPath = new LinkedHashMap<String, Map<String, UnpackedPatchouliBook.BookFile>>();
    for (var file : book.files()) {
      if (!file.category()) {
        entriesByPath
            .computeIfAbsent(file.path(), path -> new LinkedHashMap<>())
            .putIfAbsent(file.locale(), file);
      }
    }
    var entries = new ArrayList<EntryText>();
    for (var byLocale : entriesByPath.values()) {
      var file = pickLocale(byLocale, preferred);
      if (file == null) {
        continue;
      }
      entries.add(entryText(mod, file, preferred, categoryNames));
      if (entries.size() >= MAXIMUM_ENTRIES_PER_BOOK) {
        break;
      }
    }
    if (entries.isEmpty() && book.definition().isEmpty()) {
      return null;
    }
    entries.sort(Comparator.comparing(EntryText::category).thenComparing(EntryText::name));

    var markdown = new StringBuilder();
    var bytes = append(markdown, "# " + title + "\n\n", 0);
    for (var entry : entries) {
      var chunk = new StringBuilder();
      chunk.append("## ").append(entry.name()).append("\n\n");
      if (!entry.body().isEmpty()) {
        chunk.append(entry.body()).append("\n\n");
      }
      var chunkBytes = chunk.toString().getBytes(StandardCharsets.UTF_8).length;
      if (bytes + chunkBytes > MAXIMUM_DOCUMENT_BYTES) {
        break;
      }
      markdown.append(chunk);
      bytes += chunkBytes;
    }
    var bookPath = book.id().substring(book.id().indexOf(':') + 1);
    return new KnowledgeDocument(
        KnowledgeDocument.fileName("modbook", mod.modId(), bookPath), title, markdown.toString());
  }

  /** Category display names by category path, resolved per category with locale fallback. */
  private static Map<String, String> categoryNames(
      UnpackedMod mod, UnpackedPatchouliBook book, String preferred) {
    var byPath = new LinkedHashMap<String, Map<String, UnpackedPatchouliBook.BookFile>>();
    for (var file : book.files()) {
      if (file.category()) {
        byPath
            .computeIfAbsent(file.path(), path -> new LinkedHashMap<>())
            .putIfAbsent(file.locale(), file);
      }
    }
    var names = new LinkedHashMap<String, String>();
    byPath.forEach(
        (path, byLocale) -> {
          var file = pickLocale(byLocale, preferred);
          if (file != null) {
            names.put(
                path,
                KnowledgeText.sanitized(
                    ModLangText.resolve(
                        mod.lang(), preferred, stringValue(file.json().get("name"))),
                    128));
          }
        });
    return names;
  }

  /** The preferred-locale file when present, then en_us, then any other scanned locale. */
  private static UnpackedPatchouliBook.BookFile pickLocale(
      Map<String, UnpackedPatchouliBook.BookFile> byLocale, String preferred) {
    var file = byLocale.get(preferred);
    if (file != null) {
      return file;
    }
    file = byLocale.get(FALLBACK_LOCALE);
    if (file != null) {
      return file;
    }
    for (var locale : SCAN_LOCALES) {
      file = byLocale.get(locale);
      if (file != null) {
        return file;
      }
    }
    return byLocale.values().stream().findFirst().orElse(null);
  }

  private record EntryText(String category, String name, String body) {}

  private static EntryText entryText(
      UnpackedMod mod,
      UnpackedPatchouliBook.BookFile file,
      String preferred,
      Map<String, String> categoryNames) {
    var name =
        KnowledgeText.sanitized(
            ModLangText.resolve(mod.lang(), preferred, stringValue(file.json().get("name"))), 200);
    if (name.isEmpty()) {
      name = file.path();
    }
    var category = "";
    if (file.json().get("category") instanceof String reference) {
      var path =
          reference.contains(":") ? reference.substring(reference.indexOf(':') + 1) : reference;
      category = categoryNames.getOrDefault(path, KnowledgeText.sanitized(reference, 128));
    }
    var body = new StringBuilder();
    if (file.json().get("pages") instanceof List<?> pages) {
      var rendered = 0;
      for (var page : pages) {
        if (rendered >= MAXIMUM_PAGES_PER_ENTRY
            || body.length() >= MAXIMUM_ENTRY_CHARACTERS
            || !(page instanceof Map<?, ?> pageMap)) {
          continue;
        }
        rendered++;
        appendPage(body, pageMap, mod, preferred);
      }
    }
    var text = body.toString().strip();
    if (text.length() > MAXIMUM_ENTRY_CHARACTERS) {
      text = text.substring(0, MAXIMUM_ENTRY_CHARACTERS).strip();
    }
    return new EntryText(category, name, text);
  }

  /**
   * Keeps only human text from one page: the {@code text} and {@code title} fields (each resolved
   * through the mod's lang maps, then markup-stripped), and compact reference lines for {@code
   * item} and {@code recipe} (including the second-slot {@code recipe2}) fields.
   */
  private static void appendPage(
      StringBuilder body, Map<?, ?> page, UnpackedMod mod, String preferred) {
    if (page.get("title") instanceof String title) {
      appendLine(
          body,
          KnowledgeText.sanitize(
                  KnowledgeText.stripMarkup(ModLangText.resolve(mod.lang(), preferred, title)))
              .strip());
    }
    if (page.get("text") instanceof String text) {
      appendLine(
          body,
          KnowledgeText.sanitize(
                  KnowledgeText.stripMarkup(ModLangText.resolve(mod.lang(), preferred, text)))
              .strip());
    }
    appendReferences(body, "item", page.get("item"));
    appendReferences(body, "recipe", page.get("recipe"));
    appendReferences(body, "recipe", page.get("recipe2"));
  }

  private static void appendReferences(StringBuilder body, String kind, Object raw) {
    for (var id : referenceIds(raw)) {
      appendLine(body, "[" + kind + ": " + id + "]");
    }
  }

  /** Reference fields are a single id, a comma-separated id list, or a JSON array of ids. */
  private static List<String> referenceIds(Object raw) {
    var tokens = new ArrayList<String>();
    if (raw instanceof String text) {
      for (var token : text.split(",")) {
        tokens.add(token.strip());
      }
    } else if (raw instanceof List<?> list) {
      for (var element : list) {
        if (element instanceof String text) {
          tokens.add(text.strip());
        }
      }
    }
    var ids = new ArrayList<String>(tokens.size());
    for (var token : tokens) {
      if (NAMESPACED_ID.matcher(token).matches() && !ids.contains(token)) {
        ids.add(token);
      }
    }
    return ids;
  }

  private static void appendLine(StringBuilder body, String line) {
    if (line.isEmpty()) {
      return;
    }
    if (body.length() > 0) {
      body.append("\n\n");
    }
    body.append(line);
  }

  private static int append(StringBuilder target, String chunk, int bytes) {
    target.append(chunk);
    return bytes + chunk.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : null;
  }
}
