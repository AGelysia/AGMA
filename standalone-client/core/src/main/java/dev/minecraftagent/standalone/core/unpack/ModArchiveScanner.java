package dev.minecraftagent.standalone.core.unpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Scans a mods directory and unpacks recipe, tag, lang, guide book, and advancement data from mod
 * archives using pure file IO. Only the zip central directory is read; entries are decompressed in
 * memory under byte caps and nothing is ever written to disk. Archives without mod metadata are
 * treated as libraries and skipped. Every failure mode is counted in the {@link ScanReport} and
 * skipped, never thrown, so a single corrupt archive cannot break the catalog.
 */
public final class ModArchiveScanner {
  /** Bounds the localized category and entry documents collected from one archive. */
  public static final int MAXIMUM_BOOK_FILES_PER_JAR = 384;

  /** Bounds the advancement documents collected from one archive. */
  public static final int MAXIMUM_ADVANCEMENTS_PER_JAR = 1024;

  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9_.-]+$");
  private static final Pattern DOCUMENT_PATH = Pattern.compile("^[a-z0-9_./-]+$");
  private static final Pattern TOML_MOD_VALUE =
      Pattern.compile("^\\s*(modId|displayName|version)\\s*=\\s*\"([^\"]*)\"\\s*$");
  private static final List<String> SCAN_LOCALES = List.of("en_us", "zh_cn");

  private final Limits limits;

  public ModArchiveScanner() {
    this(Limits.defaults());
  }

  public ModArchiveScanner(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /** Tunable bounds for one scan. All values must be positive. */
  public record Limits(
      int maximumJars,
      long maximumJarBytes,
      int maximumJsonBytes,
      int maximumEntriesPerJar,
      int maximumRecipes) {
    public Limits {
      if (maximumJars <= 0
          || maximumJarBytes <= 0
          || maximumJsonBytes <= 0
          || maximumEntriesPerJar <= 0
          || maximumRecipes <= 0) {
        throw new IllegalArgumentException("scan limits must be positive");
      }
    }

    public static Limits defaults() {
      return new Limits(512, 256L * 1024 * 1024, 1024 * 1024, 20_000, 200_000);
    }
  }

  public UnpackedModpack scan(Path modsDirectory) {
    Objects.requireNonNull(modsDirectory, "modsDirectory");
    if (!Files.isDirectory(modsDirectory)) {
      return UnpackedModpack.empty();
    }
    final List<Path> jars;
    try (var stream = Files.list(modsDirectory)) {
      jars =
          stream
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .limit(limits.maximumJars())
              .toList();
    } catch (IOException | SecurityException failure) {
      return UnpackedModpack.empty();
    }

    var counters = new Counters();
    var mods = new ArrayList<UnpackedMod>();
    for (var jar : jars) {
      counters.considered++;
      scanJar(jar, counters).ifPresent(mods::add);
    }
    return new UnpackedModpack(mods, counters.report());
  }

  private java.util.Optional<UnpackedMod> scanJar(Path jar, Counters counters) {
    final long jarBytes;
    try {
      jarBytes = Files.size(jar);
    } catch (IOException | SecurityException failure) {
      counters.unreadable++;
      return java.util.Optional.empty();
    }
    if (jarBytes > limits.maximumJarBytes()) {
      counters.oversizeJars++;
      return java.util.Optional.empty();
    }
    var jarName = jar.getFileName().toString();
    try (var zip = new ZipFile(jar.toFile())) {
      var metadata = readMetadata(zip, counters);
      if (metadata == null) {
        counters.noMetadata++;
        return java.util.Optional.empty();
      }
      var data = new JarData();
      var lang = new LinkedHashMap<String, Map<String, String>>();
      var entries = zip.entries();
      var read = 0;
      while (entries.hasMoreElements() && read < limits.maximumEntriesPerJar()) {
        var entry = entries.nextElement();
        read++;
        if (entry.isDirectory()) {
          continue;
        }
        var name = entry.getName();
        if (name.startsWith("data/")) {
          if (name.endsWith(".json")) {
            readDataEntry(zip, entry, name, data, counters);
          }
        } else if (name.startsWith("assets/") && name.endsWith(".json")) {
          readAssetsEntry(zip, entry, name, data, lang, counters);
        }
      }
      counters.totalRecipes += data.recipes.size();
      counters.totalTags += data.tags.size();
      counters.totalAdvancements += data.advancements.size();
      var langEntries = lang.values().stream().mapToInt(Map::size).sum();
      counters.totalLangEntries += langEntries;
      var patchouliBooks = new ArrayList<UnpackedPatchouliBook>();
      data.books.forEach(
          (id, accumulator) ->
              patchouliBooks.add(
                  new UnpackedPatchouliBook(id, accumulator.definition, accumulator.files)));
      counters.totalPatchouliBooks += patchouliBooks.size();
      counters.summaries.add(
          new ScanReport.ModSummary(
              metadata.modId(), jarName, data.recipes.size(), data.tags.size(), langEntries));
      counters.scanned++;
      return java.util.Optional.of(
          new UnpackedMod(
              metadata.modId(),
              metadata.modName(),
              metadata.modVersion(),
              jarName,
              data.recipes,
              data.tags,
              lang,
              patchouliBooks,
              data.advancements));
    } catch (IOException | RuntimeException failure) {
      counters.unreadable++;
      return java.util.Optional.empty();
    }
  }

  private void readDataEntry(
      ZipFile zip, java.util.zip.ZipEntry entry, String name, JarData data, Counters counters) {
    var relative = name.substring("data/".length());
    var separator = relative.indexOf('/');
    if (separator <= 0) {
      return;
    }
    var namespace = relative.substring(0, separator);
    if (!NAMESPACE.matcher(namespace).matches()) {
      return;
    }
    var tail = relative.substring(separator + 1);
    if (tail.startsWith("recipes/") && tail.length() > "recipes/.json".length()) {
      if (data.recipes.size() + counters.totalRecipes >= limits.maximumRecipes()) {
        counters.recipeLimitReached = true;
        return;
      }
      var path = tail.substring("recipes/".length(), tail.length() - ".json".length());
      var id = namespace + ":" + path;
      if (!NAMESPACED_ID.matcher(id).matches()) {
        counters.malformed++;
        return;
      }
      var json = readJson(zip, entry, counters);
      if (json == null) {
        return;
      }
      var type = json.get("type") instanceof String value ? value : "";
      data.recipes.add(new UnpackedRecipe(id, type, json));
    } else if (tail.startsWith("tags/items/") || tail.startsWith("tags/fluids/")) {
      var itemTag = tail.startsWith("tags/items/");
      var prefix = itemTag ? "tags/items/" : "tags/fluids/";
      if (tail.length() <= prefix.length() + ".json".length()) {
        return;
      }
      var path = tail.substring(prefix.length(), tail.length() - ".json".length());
      var id = namespace + ":" + path;
      if (!NAMESPACED_ID.matcher(id).matches()) {
        counters.malformed++;
        return;
      }
      var json = readJson(zip, entry, counters);
      if (json == null) {
        return;
      }
      var replace = Boolean.TRUE.equals(json.get("replace"));
      var values = new ArrayList<UnpackedTag.Entry>();
      if (json.get("values") instanceof List<?> rawValues) {
        for (var raw : rawValues) {
          if (raw instanceof String value) {
            values.add(new UnpackedTag.Entry(value, true));
          } else if (raw instanceof Map<?, ?> object && object.get("id") instanceof String value) {
            var required = !(Boolean.FALSE.equals(object.get("required")));
            values.add(new UnpackedTag.Entry(value, required));
          }
        }
      }
      data.tags.add(
          new UnpackedTag(
              itemTag ? UnpackedTag.Kind.ITEM : UnpackedTag.Kind.FLUID, id, replace, values));
    } else if (tail.startsWith("patchouli_books/")) {
      readBookEntry(zip, entry, namespace, tail, data, counters);
    } else if (tail.startsWith("advancements/") && tail.length() > "advancements/.json".length()) {
      readAdvancementEntry(zip, entry, namespace, tail, data, counters);
    }
  }

  /**
   * Dispatches one {@code assets/<namespace>/...} JSON document: {@code lang/<locale>.json} files
   * feed the translation maps and {@code patchouli_books/<book>/<locale>/(categories|entries)/**
   * .json} files feed the guide books (their {@code book.json} definitions live on the {@code
   * data/} side). Only the scanned locales are kept.
   */
  private void readAssetsEntry(
      ZipFile zip,
      java.util.zip.ZipEntry entry,
      String name,
      JarData data,
      Map<String, Map<String, String>> lang,
      Counters counters) {
    var relative = name.substring("assets/".length());
    var separator = relative.indexOf('/');
    if (separator <= 0) {
      return;
    }
    var namespace = relative.substring(0, separator);
    if (!NAMESPACE.matcher(namespace).matches()) {
      return;
    }
    var tail = relative.substring(separator + 1);
    if (tail.startsWith("lang/")) {
      readLangEntry(zip, entry, tail, lang, counters);
    } else if (tail.startsWith("patchouli_books/")) {
      readBookEntry(zip, entry, namespace, tail, data, counters);
    }
  }

  /**
   * Collects one guide book document. The real archive layout is hybrid: the definition sits at
   * {@code data/<namespace>/patchouli_books/<book>/book.json} while the localized {@code
   * <locale>/categories/<path>.json} and {@code <locale>/entries/<path>.json} documents are
   * resources at {@code assets/<namespace>/patchouli_books/<book>/...}. Both sides funnel here with
   * {@code tail} being the path after the namespace; only the scanned locales are kept.
   */
  private void readBookEntry(
      ZipFile zip,
      java.util.zip.ZipEntry entry,
      String namespace,
      String tail,
      JarData data,
      Counters counters) {
    if (data.bookFiles >= MAXIMUM_BOOK_FILES_PER_JAR) {
      return;
    }
    var relative = tail.substring("patchouli_books/".length());
    var separator = relative.indexOf('/');
    if (separator <= 0) {
      return;
    }
    var book = relative.substring(0, separator);
    if (!NAMESPACE.matcher(book).matches()) {
      counters.malformed++;
      return;
    }
    var rest = relative.substring(separator + 1);
    var accumulator =
        data.books.computeIfAbsent(namespace + ":" + book, id -> new BookAccumulator());
    if (rest.equals("book.json")) {
      var json = readJson(zip, entry, counters);
      if (json != null) {
        accumulator.definition = json;
        data.bookFiles++;
      }
      return;
    }
    var localeSeparator = rest.indexOf('/');
    if (localeSeparator <= 0) {
      return;
    }
    var locale = rest.substring(0, localeSeparator);
    if (!SCAN_LOCALES.contains(locale)) {
      return;
    }
    var kindAndPath = rest.substring(localeSeparator + 1);
    boolean category;
    String path;
    if (kindAndPath.startsWith("categories/")
        && kindAndPath.length() > "categories/.json".length()) {
      category = true;
      path = kindAndPath.substring("categories/".length(), kindAndPath.length() - ".json".length());
    } else if (kindAndPath.startsWith("entries/")
        && kindAndPath.length() > "entries/.json".length()) {
      category = false;
      path = kindAndPath.substring("entries/".length(), kindAndPath.length() - ".json".length());
    } else {
      return;
    }
    if (!DOCUMENT_PATH.matcher(path).matches()) {
      counters.malformed++;
      return;
    }
    var json = readJson(zip, entry, counters);
    if (json == null) {
      return;
    }
    accumulator.files.add(new UnpackedPatchouliBook.BookFile(locale, category, path, json));
    data.bookFiles++;
  }

  private void readAdvancementEntry(
      ZipFile zip,
      java.util.zip.ZipEntry entry,
      String namespace,
      String tail,
      JarData data,
      Counters counters) {
    if (data.advancements.size() >= MAXIMUM_ADVANCEMENTS_PER_JAR) {
      return;
    }
    var path = tail.substring("advancements/".length(), tail.length() - ".json".length());
    var id = namespace + ":" + path;
    if (!NAMESPACED_ID.matcher(id).matches()) {
      counters.malformed++;
      return;
    }
    var json = readJson(zip, entry, counters);
    if (json == null) {
      return;
    }
    data.advancements.add(new UnpackedAdvancement(id, json));
  }

  private void readLangEntry(
      ZipFile zip,
      java.util.zip.ZipEntry entry,
      String tail,
      Map<String, Map<String, String>> lang,
      Counters counters) {
    var file = tail.substring("lang/".length());
    var locale = file.substring(0, file.length() - ".json".length());
    if (!SCAN_LOCALES.contains(locale)) {
      return;
    }
    var json = readJson(zip, entry, counters);
    if (json == null) {
      return;
    }
    var translations = new LinkedHashMap<String, String>();
    json.forEach(
        (key, value) -> {
          if (value instanceof String text) {
            translations.put(key, text);
          }
        });
    lang.merge(locale, translations, ModArchiveScanner::mergeTranslations);
  }

  private static Map<String, String> mergeTranslations(
      Map<String, String> left, Map<String, String> right) {
    right.forEach(left::putIfAbsent);
    return left;
  }

  /**
   * Reads a JSON entry under the byte cap. Returns {@code null} when the entry is oversize (counted
   * separately) or not a parseable JSON object (counted as malformed).
   */
  private Map<String, Object> readJson(
      ZipFile zip, java.util.zip.ZipEntry entry, Counters counters) {
    var bytes = readEntry(zip, entry);
    if (bytes == null) {
      counters.oversizeEntries++;
      return null;
    }
    try {
      return BoundedJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException failure) {
      counters.malformed++;
      return null;
    }
  }

  private byte[] readEntry(ZipFile zip, java.util.zip.ZipEntry entry) {
    if (entry.getSize() > limits.maximumJsonBytes()) {
      return null;
    }
    try (var input = zip.getInputStream(entry)) {
      return readBounded(input, limits.maximumJsonBytes());
    } catch (IOException | RuntimeException failure) {
      return null;
    }
  }

  /** Reads at most {@code limit} bytes; returns {@code null} when the stream holds more. */
  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    var buffer = new ByteArrayOutputStream(Math.min(limit, 65_536));
    var chunk = new byte[8192];
    var remaining = limit + 1L;
    while (remaining > 0) {
      var count = input.read(chunk, 0, (int) Math.min(chunk.length, remaining));
      if (count < 0) {
        return buffer.toByteArray();
      }
      buffer.write(chunk, 0, count);
      remaining -= count;
    }
    return null;
  }

  private record ModMetadata(String modId, String modName, String modVersion) {}

  private ModMetadata readMetadata(ZipFile zip, Counters counters) {
    var fabricEntry = zip.getEntry("fabric.mod.json");
    if (fabricEntry != null) {
      var bytes = readEntry(zip, fabricEntry);
      if (bytes != null) {
        try {
          var json = BoundedJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
          if (json.get("id") instanceof String id && !id.isBlank()) {
            var name =
                json.get("name") instanceof String text && !text.isBlank() ? text.strip() : id;
            var version = metadataVersion(json.get("version"));
            return new ModMetadata(id.strip(), name, version);
          }
        } catch (IllegalArgumentException failure) {
          counters.malformed++;
        }
      }
    }
    var tomlEntry = zip.getEntry("META-INF/mods.toml");
    if (tomlEntry == null) {
      return null;
    }
    var bytes = readEntry(zip, tomlEntry);
    if (bytes == null) {
      return null;
    }
    return parseModsToml(new String(bytes, StandardCharsets.UTF_8));
  }

  private static String metadataVersion(Object raw) {
    if (raw instanceof String text && !text.isBlank()) {
      return text.strip();
    }
    if (raw instanceof Number number) {
      return number.toString();
    }
    return "unknown";
  }

  /**
   * Extracts {@code modId}, {@code displayName}, and {@code version} from the first {@code
   * [[mods]]} table of a mods.toml file with a minimal line parser. Values still holding an
   * unresolved {@code ${...}} placeholder normalize to {@code "unknown"}.
   */
  private static ModMetadata parseModsToml(String text) {
    String modId = null;
    String displayName = null;
    String version = null;
    var inModsTable = false;
    for (var line : text.split("\\R")) {
      var trimmed = line.strip();
      if (trimmed.startsWith("[")) {
        var wasInMods = inModsTable;
        inModsTable = trimmed.equals("[[mods]]") && modId == null;
        if (wasInMods && !inModsTable && modId != null) {
          break;
        }
        continue;
      }
      if (!inModsTable) {
        continue;
      }
      var matcher = TOML_MOD_VALUE.matcher(trimmed);
      if (matcher.matches()) {
        var value = matcher.group(2);
        if (value.contains("${")) {
          value = "unknown";
        }
        switch (matcher.group(1)) {
          case "modId" -> modId = value;
          case "displayName" -> displayName = value;
          default -> version = value;
        }
      }
    }
    if (modId == null || modId.isBlank()) {
      return null;
    }
    return new ModMetadata(
        modId,
        displayName == null || displayName.isBlank() ? modId : displayName,
        version == null || version.isBlank() ? "unknown" : version);
  }

  /** Mutable per-archive collection state for one scan pass. */
  private static final class JarData {
    private final List<UnpackedRecipe> recipes = new ArrayList<>();
    private final List<UnpackedTag> tags = new ArrayList<>();
    private final Map<String, BookAccumulator> books = new LinkedHashMap<>();
    private final List<UnpackedAdvancement> advancements = new ArrayList<>();
    private int bookFiles;
  }

  /** Accumulates the definition and localized documents of one guide book. */
  private static final class BookAccumulator {
    private Map<String, Object> definition = Map.of();
    private final List<UnpackedPatchouliBook.BookFile> files = new ArrayList<>();
  }

  private static final class Counters {
    private int considered;
    private int scanned;
    private int oversizeJars;
    private int noMetadata;
    private int unreadable;
    private int oversizeEntries;
    private int malformed;
    private int totalRecipes;
    private int totalTags;
    private int totalLangEntries;
    private int totalPatchouliBooks;
    private int totalAdvancements;
    private boolean recipeLimitReached;
    private final List<ScanReport.ModSummary> summaries = new ArrayList<>();

    private ScanReport report() {
      return new ScanReport(
          considered,
          scanned,
          oversizeJars,
          noMetadata,
          unreadable,
          oversizeEntries,
          malformed,
          totalRecipes,
          totalTags,
          totalLangEntries,
          totalPatchouliBooks,
          totalAdvancements,
          recipeLimitReached,
          summaries);
    }
  }
}
