package dev.minecraftagent.paper.landmark;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** Loads the fixed private landmarks.yml catalog with a closed bounded schema. */
public final class LandmarkCatalogLoader {
  public static final String FILE_NAME = "landmarks.yml";
  private static final byte[] DEFAULT_CONTENT =
      "version: 1\nlandmarks: []\n".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_BYTES = 64 * 1024;
  private static final int MAX_LANDMARKS = 128;
  private static final int MAX_LIST_VALUES = 16;
  private static final Set<String> ROOT_KEYS = Set.of("version", "landmarks");
  private static final Set<String> REQUIRED_ENTRY_KEYS =
      Set.of("id", "name", "aliases", "tags", "dimension", "x", "y", "z");
  private static final Set<String> ENTRY_KEYS =
      Set.of("id", "name", "aliases", "tags", "dimension", "x", "y", "z", "permission");
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]{1,240}");
  private static final Pattern PERMISSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
  private static final Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");

  public LandmarkCatalog loadOrCreate(Path dataDirectory) throws StartupFailure {
    var directory = dataDirectory.toAbsolutePath().normalize();
    var file = directory.resolve(FILE_NAME);
    installDefault(file);
    try {
      var directoryAttributes =
          Files.readAttributes(directory, PosixFileAttributes.class, NOFOLLOW_LINKS);
      var attributes = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()
          || !attributes.isRegularFile()
          || !attributes.owner().equals(directoryAttributes.owner())
          || !attributes.permissions().equals(PRIVATE_FILE)
          || linkCount(file) != 1) {
        throw unsafe();
      }
      return parse(readBounded(file));
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw unsafe();
    }
  }

  private static void installDefault(Path file) throws StartupFailure {
    if (Files.exists(file, NOFOLLOW_LINKS)) {
      return;
    }
    Path temporary = null;
    try {
      temporary = file.resolveSibling(".landmarks-install-" + UUID.randomUUID());
      try (var channel =
          FileChannel.open(
              temporary,
              Set.of(CREATE_NEW, WRITE),
              PosixFilePermissions.asFileAttribute(PRIVATE_FILE))) {
        var buffer = ByteBuffer.wrap(DEFAULT_CONTENT);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      try {
        Files.move(temporary, file, ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temporary, file);
      }
      temporary = null;
      Files.setPosixFilePermissions(file, PRIVATE_FILE);
    } catch (java.nio.file.FileAlreadyExistsException ignored) {
      // A concurrent startup task installed the same fixed default.
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw unsafe();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The stable startup failure remains authoritative.
        }
      }
    }
  }

  private static byte[] readBounded(Path file) throws IOException, StartupFailure {
    try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
      var content = input.readNBytes(MAX_BYTES + 1);
      if (content.length > MAX_BYTES) {
        throw invalid();
      }
      return content;
    }
  }

  private static LandmarkCatalog parse(byte[] bytes) throws StartupFailure {
    final String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException error) {
      throw invalid();
    }
    try {
      var yaml = yaml();
      var node = yaml.compose(new StringReader(source));
      if (node == null) {
        throw invalid();
      }
      rejectAnchors(node);
      if (!(yaml.load(source) instanceof Map<?, ?> root) || !exactKeys(root, ROOT_KEYS)) {
        throw invalid();
      }
      if (!(root.get("version") instanceof Integer version) || version != 1) {
        throw invalid();
      }
      if (!(root.get("landmarks") instanceof List<?> rows) || rows.size() > MAX_LANDMARKS) {
        throw invalid();
      }
      var ids = new HashSet<String>();
      var result = new ArrayList<Landmark>(rows.size());
      for (var row : rows) {
        if (!(row instanceof Map<?, ?> entry)
            || !entry.keySet().containsAll(REQUIRED_ENTRY_KEYS)
            || !entry.keySet().stream()
                .allMatch(key -> key instanceof String && ENTRY_KEYS.contains(key))) {
          throw invalid();
        }
        var id = requiredString(entry, "id", 64);
        var name = requiredString(entry, "name", 128);
        var aliases = stringList(entry, "aliases", 128);
        var tags = stringList(entry, "tags", 64);
        var dimension = requiredString(entry, "dimension", 256);
        var permission = nullableString(entry, "permission", 128);
        if (!ID.matcher(id).matches()
            || !DIMENSION.matcher(dimension).matches()
            || (permission != null && !PERMISSION.matcher(permission).matches())
            || !ids.add(id)) {
          throw invalid();
        }
        var entryNames = new HashSet<String>();
        entryNames.add(LandmarkCatalog.normalize(name));
        for (var alias : aliases) {
          if (!entryNames.add(LandmarkCatalog.normalize(alias))) {
            throw invalid();
          }
        }
        result.add(
            new Landmark(
                id,
                name,
                aliases,
                tags,
                dimension,
                coordinate(entry, "x", -30_000_000, 30_000_000),
                coordinate(entry, "y", -2_048, 2_048),
                coordinate(entry, "z", -30_000_000, 30_000_000),
                permission));
      }
      return new LandmarkCatalog(result);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (YAMLException | ClassCastException | IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static List<String> stringList(Map<?, ?> source, String key, int maximumLength)
      throws StartupFailure {
    if (!(source.get(key) instanceof List<?> values) || values.size() > MAX_LIST_VALUES) {
      throw invalid();
    }
    var normalized = new HashSet<String>();
    var result = new ArrayList<String>(values.size());
    for (var value : values) {
      if (!(value instanceof String text)) {
        throw invalid();
      }
      var checked = safeString(text, maximumLength);
      if (!normalized.add(LandmarkCatalog.normalize(checked))) {
        throw invalid();
      }
      result.add(checked);
    }
    return List.copyOf(result);
  }

  private static String requiredString(Map<?, ?> source, String key, int maximumLength)
      throws StartupFailure {
    if (!(source.get(key) instanceof String value)) {
      throw invalid();
    }
    return safeString(value, maximumLength);
  }

  private static String nullableString(Map<?, ?> source, String key, int maximumLength)
      throws StartupFailure {
    if (!source.containsKey(key) || source.get(key) == null) {
      return null;
    }
    return requiredString(source, key, maximumLength);
  }

  private static String safeString(String value, int maximumLength) throws StartupFailure {
    var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
    if (!normalized.equals(value)
        || normalized.isBlank()
        || normalized.length() > maximumLength
        || normalized.codePoints().anyMatch(LandmarkCatalogLoader::unsafeCodePoint)) {
      throw invalid();
    }
    return normalized;
  }

  private static boolean unsafeCodePoint(int codePoint) {
    return codePoint <= 0x1f
        || (codePoint >= 0x7f && codePoint <= 0x9f)
        || codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || (codePoint >= 0x202a && codePoint <= 0x202e)
        || (codePoint >= 0x2066 && codePoint <= 0x2069);
  }

  private static double coordinate(Map<?, ?> source, String key, double minimum, double maximum)
      throws StartupFailure {
    if (!(source.get(key) instanceof Number number)) {
      throw invalid();
    }
    var value = number.doubleValue();
    if (!Double.isFinite(value) || value < minimum || value > maximum) {
      throw invalid();
    }
    return value;
  }

  private static boolean exactKeys(Map<?, ?> mapping, Set<String> keys) {
    return mapping.size() == keys.size()
        && mapping.keySet().stream().allMatch(key -> key instanceof String && keys.contains(key));
  }

  private static int linkCount(Path file) throws IOException {
    try {
      return ((Number) Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS)).intValue();
    } catch (UnsupportedOperationException error) {
      return 1;
    }
  }

  private static Yaml yaml() {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(16);
    options.setCodePointLimit(MAX_BYTES);
    return new Yaml(new SafeConstructor(options));
  }

  private static void rejectAnchors(Node node) throws StartupFailure {
    if (node instanceof AnchorNode || node.getAnchor() != null) {
      throw invalid();
    }
    if (node instanceof MappingNode mapping) {
      for (var entry : mapping.getValue()) {
        rejectAnchors(entry.getKeyNode());
        rejectAnchors(entry.getValueNode());
      }
    } else if (node instanceof SequenceNode sequence) {
      for (var child : sequence.getValue()) {
        rejectAnchors(child);
      }
    }
  }

  private static StartupFailure invalid() {
    return new StartupFailure(
        StartupFailure.Code.LANDMARK_CATALOG_INVALID, StartupFailure.Stage.CORE_TOOLS);
  }

  private static StartupFailure unsafe() {
    return new StartupFailure(
        StartupFailure.Code.LANDMARK_CATALOG_UNSAFE, StartupFailure.Stage.CORE_TOOLS);
  }
}
