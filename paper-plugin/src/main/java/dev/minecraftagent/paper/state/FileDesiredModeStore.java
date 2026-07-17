package dev.minecraftagent.paper.state;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import dev.minecraftagent.paper.lifecycle.DesiredMode;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;

public final class FileDesiredModeStore implements DesiredModeStore {
  static final String STATE_FILE_NAME = "agent-state.yml";

  private static final int MAX_STATE_BYTES = 4 * 1024;
  private static final Set<String> STATE_KEYS = Set.of("state-version", "desired-mode");
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final Path stateDirectory;
  private final Path stateFile;
  private final AtomicMover atomicMover;

  public FileDesiredModeStore(Path stateDirectory) {
    this(
        stateDirectory,
        (source, target) -> Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING));
  }

  FileDesiredModeStore(Path stateDirectory, AtomicMover atomicMover) {
    this.stateDirectory = Objects.requireNonNull(stateDirectory).toAbsolutePath().normalize();
    this.stateFile = this.stateDirectory.resolve(STATE_FILE_NAME);
    this.atomicMover = Objects.requireNonNull(atomicMover);
  }

  @Override
  public synchronized DesiredMode load() throws StartupFailure {
    try {
      var directoryOwner = verifyPrivateDirectory();
      var attributes = readFileAttributesIfPresent();
      if (attributes == null) {
        return DesiredMode.ENABLED;
      }
      verifyPrivateFile(attributes, directoryOwner);
      return parse(readBounded());
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException | YAMLException | IllegalArgumentException error) {
      throw invalid();
    } catch (SecurityException | UnsupportedOperationException error) {
      throw unsafe();
    }
  }

  @Override
  public synchronized void save(DesiredMode desiredMode) throws StartupFailure {
    Objects.requireNonNull(desiredMode);
    Path temporary = null;
    try {
      var directoryOwner = verifyPrivateDirectory();
      var existing = readFileAttributesIfPresent();
      if (existing != null) {
        verifyPrivateFile(existing, directoryOwner);
      }

      temporary = stateDirectory.resolve(".agent-state-" + UUID.randomUUID() + ".tmp");
      writeTemporary(temporary, serialize(desiredMode));
      verifyPrivateFile(readRequiredFileAttributes(temporary), directoryOwner);
      atomicMover.move(temporary, stateFile);
      temporary = null;
      verifyPrivateFile(readRequiredFileAttributes(stateFile), directoryOwner);
      forceDirectoryBestEffort();
    } catch (AtomicMoveNotSupportedException error) {
      throw persistenceFailed();
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException error) {
      throw persistenceFailed();
    } catch (SecurityException | UnsupportedOperationException error) {
      throw unsafe();
    } finally {
      deleteTemporaryBestEffort(temporary);
    }
  }

  private java.nio.file.attribute.UserPrincipal verifyPrivateDirectory() throws StartupFailure {
    try {
      var attributes =
          Files.readAttributes(stateDirectory, PosixFileAttributes.class, NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()
          || !attributes.isDirectory()
          || !attributes.permissions().equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
        throw unsafe();
      }
      return attributes.owner();
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException error) {
      throw unsafe();
    }
  }

  private PosixFileAttributes readFileAttributesIfPresent() throws IOException, StartupFailure {
    try {
      return readRequiredFileAttributes(stateFile);
    } catch (NoSuchFileException error) {
      return null;
    }
  }

  private static PosixFileAttributes readRequiredFileAttributes(Path path) throws IOException {
    return Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
  }

  private static void verifyPrivateFile(
      PosixFileAttributes attributes, java.nio.file.attribute.UserPrincipal expectedOwner)
      throws StartupFailure {
    if (attributes.isSymbolicLink()
        || !attributes.isRegularFile()
        || !attributes.permissions().equals(PRIVATE_FILE_PERMISSIONS)
        || !attributes.owner().equals(expectedOwner)) {
      throw unsafe();
    }
  }

  private byte[] readBounded() throws IOException, StartupFailure {
    try (InputStream input = Files.newInputStream(stateFile, NOFOLLOW_LINKS)) {
      var bytes = input.readNBytes(MAX_STATE_BYTES + 1);
      if (bytes.length > MAX_STATE_BYTES) {
        throw invalid();
      }
      return bytes;
    }
  }

  private static DesiredMode parse(byte[] bytes) throws CharacterCodingException, StartupFailure {
    var source =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();

    try {
      var node = yaml().compose(new StringReader(source));
      if (node == null) {
        throw invalid();
      }
      rejectAnchors(node);

      var loaded = yaml().load(source);
      if (!(loaded instanceof Map<?, ?> mapping) || mapping.size() != STATE_KEYS.size()) {
        throw invalid();
      }
      for (var key : mapping.keySet()) {
        if (!(key instanceof String stringKey) || !STATE_KEYS.contains(stringKey)) {
          throw invalid();
        }
      }
      if (!(mapping.get("state-version") instanceof Integer version) || version != 1) {
        throw invalid();
      }
      if (!(mapping.get("desired-mode") instanceof String desiredMode)) {
        throw invalid();
      }
      try {
        return DesiredMode.valueOf(desiredMode);
      } catch (IllegalArgumentException error) {
        throw invalid();
      }
    } catch (StartupFailure failure) {
      throw failure;
    } catch (YAMLException | ClassCastException error) {
      throw invalid();
    }
  }

  private static Yaml yaml() {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(8);
    options.setCodePointLimit(MAX_STATE_BYTES);
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

  private static byte[] serialize(DesiredMode desiredMode) throws StartupFailure {
    var options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setLineBreak(DumperOptions.LineBreak.UNIX);
    options.setPrettyFlow(false);
    options.setSplitLines(false);
    var state = new LinkedHashMap<String, Object>();
    state.put("state-version", 1);
    state.put("desired-mode", desiredMode.name());
    var bytes = new Yaml(options).dump(state).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_STATE_BYTES) {
      throw persistenceFailed();
    }
    return bytes;
  }

  private static void writeTemporary(Path temporary, byte[] content) throws IOException {
    Set<OpenOption> options = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
    try (var channel =
        FileChannel.open(
            temporary, options, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))) {
      var buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private void forceDirectoryBestEffort() {
    try (var channel = FileChannel.open(stateDirectory, READ)) {
      channel.force(true);
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // The state file was already atomically committed; directory fsync is not portable in Java.
    }
  }

  private static void deleteTemporaryBestEffort(Path temporary) {
    if (temporary == null) {
      return;
    }
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException | SecurityException ignored) {
      // The original persistence failure remains authoritative.
    }
  }

  private static StartupFailure invalid() {
    return new StartupFailure(StartupFailure.Code.STATE_FILE_INVALID, StartupFailure.Stage.STATE);
  }

  private static StartupFailure unsafe() {
    return new StartupFailure(StartupFailure.Code.STATE_FILE_UNSAFE, StartupFailure.Stage.STATE);
  }

  private static StartupFailure persistenceFailed() {
    return new StartupFailure(
        StartupFailure.Code.STATE_PERSISTENCE_FAILED, StartupFailure.Stage.STATE);
  }

  @FunctionalInterface
  interface AtomicMover {
    void move(Path source, Path target) throws IOException;
  }
}
