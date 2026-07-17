package dev.minecraftagent.client.ui;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/** Atomic, bounded storage for non-authoritative local HUD preferences. */
public final class OverlayPreferenceStore {
  public static final int MAX_FILE_BYTES = 4096;

  private static final String FILE_NAME = "overlay.json";
  private static final Set<String> FIELDS =
      Set.of("version", "x", "y", "width", "height", "pinned");
  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private final Path root;
  private final Path configDirectory;
  private final Path target;

  public OverlayPreferenceStore(Path gameDirectory) {
    Path absolute = gameDirectory.toAbsolutePath().normalize();
    Path resolved = absolute;
    try {
      resolved = absolute.toRealPath();
    } catch (IOException ignored) {
      // A not-yet-created game directory will be reported as IO_ERROR by load/save.
    }
    root = resolved;
    configDirectory = root.resolve("config").resolve("minecraftagent").normalize();
    target = configDirectory.resolve(FILE_NAME).normalize();
    if (!configDirectory.startsWith(root) || !target.startsWith(configDirectory)) {
      throw new IllegalArgumentException("Preference path escapes the game directory");
    }
  }

  public Path path() {
    return target;
  }

  public LoadResult load() {
    try {
      if (!safeDirectory(false)) {
        return new LoadResult(LoadStatus.MISSING, OverlayPreferences.defaults());
      }
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        return new LoadResult(LoadStatus.MISSING, OverlayPreferences.defaults());
      }
      if (Files.isSymbolicLink(target)) {
        return new LoadResult(LoadStatus.UNSAFE_PATH, OverlayPreferences.defaults());
      }
      BasicFileAttributes attributes =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES) {
        return new LoadResult(LoadStatus.INVALID, OverlayPreferences.defaults());
      }
      byte[] bytes = readBounded();
      return new LoadResult(LoadStatus.LOADED, parse(bytes));
    } catch (UnsafePathException exception) {
      return new LoadResult(LoadStatus.UNSAFE_PATH, OverlayPreferences.defaults());
    } catch (InvalidPreferenceException | IllegalArgumentException exception) {
      return new LoadResult(LoadStatus.INVALID, OverlayPreferences.defaults());
    } catch (IOException exception) {
      return new LoadResult(LoadStatus.IO_ERROR, OverlayPreferences.defaults());
    }
  }

  public void save(OverlayPreferences preferences) throws IOException {
    if (!safeDirectory(true)) {
      throw new IOException("Preference directory is unavailable");
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(target)
          || !Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
              .isRegularFile()) {
        throw new UnsafePathException();
      }
    }

    byte[] data = serialize(preferences).getBytes(StandardCharsets.UTF_8);
    if (data.length > MAX_FILE_BYTES) {
      throw new IOException("Preference serialization exceeded its limit");
    }
    Path temporary = createTemporaryFile();
    boolean moved = false;
    try {
      try (FileChannel channel =
          FileChannel.open(
              temporary,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      setOwnerOnly(temporary);
      safeDirectory(true);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("Atomic preference replacement is not supported", exception);
      }
      moved = true;
      forceDirectory();
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private boolean safeDirectory(boolean create) throws IOException {
    if (!Files.isDirectory(root)) {
      throw new IOException("Game directory is unavailable");
    }
    Path config = root.resolve("config");
    if (!ensureDirectory(config, create)) {
      return false;
    }
    return ensureDirectory(configDirectory, create);
  }

  private static boolean ensureDirectory(Path directory, boolean create) throws IOException {
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new UnsafePathException();
      }
      return true;
    }
    if (!create) {
      return false;
    }
    Files.createDirectory(directory);
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new UnsafePathException();
    }
    return true;
  }

  private byte[] readBounded() throws IOException, InvalidPreferenceException {
    ByteBuffer buffer = ByteBuffer.allocate(MAX_FILE_BYTES + 1);
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
        // FileChannel may complete the bounded read in multiple operations.
      }
    }
    if (buffer.position() > MAX_FILE_BYTES) {
      throw new InvalidPreferenceException();
    }
    byte[] bytes = new byte[buffer.position()];
    buffer.flip();
    buffer.get(bytes);
    return bytes;
  }

  private static OverlayPreferences parse(byte[] bytes) throws InvalidPreferenceException {
    String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      throw new InvalidPreferenceException();
    }

    Integer version = null;
    Integer x = null;
    Integer y = null;
    Integer width = null;
    Integer height = null;
    Boolean pinned = null;
    Set<String> seen = new HashSet<>();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (!FIELDS.contains(name) || !seen.add(name)) {
          throw new InvalidPreferenceException();
        }
        switch (name) {
          case "version" -> version = readInteger(reader);
          case "x" -> x = readInteger(reader);
          case "y" -> y = readInteger(reader);
          case "width" -> width = readInteger(reader);
          case "height" -> height = readInteger(reader);
          case "pinned" -> {
            if (reader.peek() != JsonToken.BOOLEAN) {
              throw new InvalidPreferenceException();
            }
            pinned = reader.nextBoolean();
          }
          default -> throw new InvalidPreferenceException();
        }
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT
          || seen.size() != FIELDS.size()
          || version == null
          || version != 1
          || x == null
          || y == null
          || width == null
          || height == null
          || pinned == null) {
        throw new InvalidPreferenceException();
      }
      return new OverlayPreferences(x, y, width, height, pinned);
    } catch (IOException | IllegalStateException | NumberFormatException exception) {
      throw new InvalidPreferenceException();
    }
  }

  private static int readInteger(JsonReader reader) throws IOException, InvalidPreferenceException {
    if (reader.peek() != JsonToken.NUMBER) {
      throw new InvalidPreferenceException();
    }
    String value = reader.nextString();
    if (!value.matches("-?(?:0|[1-9][0-9]*)")) {
      throw new InvalidPreferenceException();
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new InvalidPreferenceException();
    }
  }

  private Path createTemporaryFile() throws IOException {
    Path temporary;
    if (configDirectory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      temporary =
          Files.createTempFile(
              configDirectory,
              ".overlay-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } else {
      temporary = Files.createTempFile(configDirectory, ".overlay-", ".tmp");
    }
    if (Files.isSymbolicLink(temporary)) {
      Files.deleteIfExists(temporary);
      throw new UnsafePathException();
    }
    return temporary;
  }

  private static void setOwnerOnly(Path file) throws IOException {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, OWNER_ONLY);
    }
  }

  private void forceDirectory() {
    try (FileChannel channel = FileChannel.open(configDirectory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException | UnsupportedOperationException ignored) {
      // Atomic rename is already complete; directory fsync is not portable.
    }
  }

  private static String serialize(OverlayPreferences preferences) {
    return "{\"version\":1,\"x\":"
        + preferences.x()
        + ",\"y\":"
        + preferences.y()
        + ",\"width\":"
        + preferences.width()
        + ",\"height\":"
        + preferences.height()
        + ",\"pinned\":"
        + preferences.pinned()
        + "}";
  }

  public enum LoadStatus {
    LOADED,
    MISSING,
    INVALID,
    UNSAFE_PATH,
    IO_ERROR
  }

  public record LoadResult(LoadStatus status, OverlayPreferences preferences) {}

  private static final class InvalidPreferenceException extends Exception {}

  private static final class UnsafePathException extends IOException {}
}
