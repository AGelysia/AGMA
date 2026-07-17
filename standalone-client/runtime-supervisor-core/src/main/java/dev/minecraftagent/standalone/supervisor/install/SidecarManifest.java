package dev.minecraftagent.standalone.supervisor.install;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record SidecarManifest(
    String runtimeVersion,
    String nodeVersion,
    RuntimePlatform platform,
    String nodeExecutable,
    String entrypoint,
    Map<String, ManifestFile> files) {
  static final String NAME = "sidecar-manifest.json";
  static final String PRODUCT = "agma-standalone-runtime";
  static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
  static final int MAXIMUM_FILES = 16 * 1024;
  static final long MAXIMUM_FILE_BYTES = 256L * 1024L * 1024L;
  static final long MAXIMUM_EXPANDED_BYTES = 768L * 1024L * 1024L;
  private static final Pattern VERSION =
      Pattern.compile(
          "^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
              + "(?:[-+][0-9A-Za-z.-]+)?$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schemaVersion",
          "product",
          "runtimeVersion",
          "nodeVersion",
          "platform",
          "nodeExecutable",
          "entrypoint",
          "files");
  private static final Set<String> FILE_FIELDS = Set.of("path", "size", "sha256", "executable");

  static SidecarManifest parse(byte[] bytes) throws ManagedRuntimeInstallException {
    if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
      throw failure("MANIFEST_INVALID");
    }
    try {
      var reader = new JsonReader(new StringReader(decodeUtf8(bytes)));
      reader.setLenient(false);
      var manifest = readRoot(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw failure("MANIFEST_INVALID");
      }
      return manifest;
    } catch (ManagedRuntimeInstallException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw failure("MANIFEST_INVALID");
    }
  }

  private static SidecarManifest readRoot(JsonReader reader)
      throws IOException, ManagedRuntimeInstallException {
    require(reader.peek() == JsonToken.BEGIN_OBJECT);
    reader.beginObject();
    var seen = new HashSet<String>();
    Integer schemaVersion = null;
    String product = null;
    String runtimeVersion = null;
    String nodeVersion = null;
    RuntimePlatform platform = null;
    String nodeExecutable = null;
    String entrypoint = null;
    Map<String, ManifestFile> files = null;
    while (reader.hasNext()) {
      var name = reader.nextName();
      require(ROOT_FIELDS.contains(name) && seen.add(name));
      switch (name) {
        case "schemaVersion" -> schemaVersion = Math.toIntExact(integer(reader, 1, 1));
        case "product" -> product = string(reader, 64);
        case "runtimeVersion" -> runtimeVersion = version(reader);
        case "nodeVersion" -> nodeVersion = version(reader);
        case "platform" -> platform = platform(reader);
        case "nodeExecutable" ->
            nodeExecutable = InstallPathRules.manifestPath(string(reader, 240));
        case "entrypoint" -> entrypoint = InstallPathRules.manifestPath(string(reader, 240));
        case "files" -> files = files(reader);
        default -> throw failure("MANIFEST_INVALID");
      }
    }
    reader.endObject();
    require(seen.equals(ROOT_FIELDS));
    require(schemaVersion != null && PRODUCT.equals(product));
    require(runtimeVersion != null && nodeVersion != null && platform != null);
    require(nodeExecutable != null && entrypoint != null && files != null);
    require(platform.nodeExecutable().equals(nodeExecutable));
    require("app/dist/standalone/bootstrap/index.js".equals(entrypoint));
    require(files.containsKey(nodeExecutable) && files.get(nodeExecutable).executable());
    require(files.containsKey(entrypoint) && !files.get(entrypoint).executable());
    require(files.values().stream().filter(ManifestFile::executable).count() == 1);
    require(files.containsKey("app/package.json"));
    require(files.containsKey("app/package-lock.json"));
    require(files.containsKey("licenses/node/LICENSE"));
    require(files.containsKey("licenses/npm-production-packages.json"));
    require(
        files.keySet().stream()
            .anyMatch(path -> path.startsWith("standalone-client/contracts/schemas/")));
    return new SidecarManifest(
        runtimeVersion, nodeVersion, platform, nodeExecutable, entrypoint, Map.copyOf(files));
  }

  private static Map<String, ManifestFile> files(JsonReader reader)
      throws IOException, ManagedRuntimeInstallException {
    require(reader.peek() == JsonToken.BEGIN_ARRAY);
    reader.beginArray();
    var files = new LinkedHashMap<String, ManifestFile>();
    var folded = new HashSet<String>();
    String previous = null;
    long expanded = 0;
    while (reader.hasNext()) {
      require(files.size() < MAXIMUM_FILES);
      var file = file(reader);
      require(previous == null || previous.compareTo(file.path()) < 0);
      require(folded.add(InstallPathRules.folded(file.path())));
      require(files.putIfAbsent(file.path(), file) == null);
      require(expanded <= MAXIMUM_EXPANDED_BYTES - file.size());
      expanded += file.size();
      previous = file.path();
    }
    reader.endArray();
    require(!files.isEmpty());
    rejectPathConflicts(files.keySet());
    return files;
  }

  private static ManifestFile file(JsonReader reader)
      throws IOException, ManagedRuntimeInstallException {
    require(reader.peek() == JsonToken.BEGIN_OBJECT);
    reader.beginObject();
    var seen = new HashSet<String>();
    String path = null;
    Long size = null;
    String sha256 = null;
    Boolean executable = null;
    while (reader.hasNext()) {
      var name = reader.nextName();
      require(FILE_FIELDS.contains(name) && seen.add(name));
      switch (name) {
        case "path" -> path = InstallPathRules.manifestPath(string(reader, 240));
        case "size" -> size = integer(reader, 0, MAXIMUM_FILE_BYTES);
        case "sha256" -> {
          sha256 = string(reader, 64);
          require(SHA256.matcher(sha256).matches());
        }
        case "executable" -> {
          require(reader.peek() == JsonToken.BOOLEAN);
          executable = reader.nextBoolean();
        }
        default -> throw failure("MANIFEST_INVALID");
      }
    }
    reader.endObject();
    require(seen.equals(FILE_FIELDS));
    require(path != null && size != null && sha256 != null && executable != null);
    return new ManifestFile(path, size, sha256, executable);
  }

  private static RuntimePlatform platform(JsonReader reader)
      throws IOException, ManagedRuntimeInstallException {
    try {
      return RuntimePlatform.fromId(string(reader, 32));
    } catch (IllegalArgumentException failure) {
      throw failure("MANIFEST_INVALID");
    }
  }

  private static String version(JsonReader reader)
      throws IOException, ManagedRuntimeInstallException {
    var value = string(reader, 64);
    require(VERSION.matcher(value).matches());
    return value;
  }

  private static String string(JsonReader reader, int maximum)
      throws IOException, ManagedRuntimeInstallException {
    require(reader.peek() == JsonToken.STRING);
    var value = reader.nextString();
    require(!value.isEmpty() && value.length() <= maximum);
    require(value.codePoints().noneMatch(Character::isISOControl));
    return value;
  }

  private static long integer(JsonReader reader, long minimum, long maximum)
      throws IOException, ManagedRuntimeInstallException {
    require(reader.peek() == JsonToken.NUMBER);
    try {
      var value = new BigDecimal(reader.nextString()).longValueExact();
      require(value >= minimum && value <= maximum);
      return value;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw failure("MANIFEST_INVALID");
    }
  }

  private static void rejectPathConflicts(Set<String> paths) throws ManagedRuntimeInstallException {
    var folded = new HashSet<String>();
    paths.forEach(path -> folded.add(InstallPathRules.folded(path)));
    for (var path : folded) {
      var separator = path.lastIndexOf('/');
      while (separator > 0) {
        require(!folded.contains(path.substring(0, separator)));
        separator = path.lastIndexOf('/', separator - 1);
      }
    }
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void require(boolean condition) throws ManagedRuntimeInstallException {
    if (!condition) {
      throw failure("MANIFEST_INVALID");
    }
  }

  private static ManagedRuntimeInstallException failure(String code) {
    return new ManagedRuntimeInstallException(code);
  }
}

record ManifestFile(String path, long size, String sha256, boolean executable) {}
