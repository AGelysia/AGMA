package dev.minecraftagent.paper.startup;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

public final class PaperConfigLoader {
  private static final int MAX_CONFIG_BYTES = 64 * 1024;
  private static final int MAX_TIMEOUT_MILLIS = 30_000;
  private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");
  private static final Pattern PLACEHOLDER_SECRET =
      Pattern.compile("(?i)(?:change-?me|replace-with-.*|your[-_].*)");
  private static final Pattern DIRECTORY_SEGMENT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern OWNER_UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern CAPABILITY_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final int MAX_OWNERS = 128;
  private static final int MAX_CAPABILITY_APPROVALS = 128;

  private static final Set<String> PHASE_3_ROOT_KEYS =
      Set.of("config-version", "server", "runtime", "state", "security", "capabilities");
  private static final Set<String> ROOT_KEYS_WITH_OWNERS =
      Set.of("config-version", "server", "owners", "runtime", "state", "security", "capabilities");
  private static final Set<String> SERVER_KEYS = Set.of("id");
  private static final Set<String> RUNTIME_KEYS =
      Set.of("url", "server-token", "connect-timeout-millis", "handshake-timeout-millis");
  private static final Set<String> VERSION_2_RUNTIME_KEYS =
      Set.of("mode", "url", "server-token", "connect-timeout-millis", "handshake-timeout-millis");
  private static final Set<String> STATE_KEYS = Set.of("directory");
  private static final Set<String> SECURITY_KEYS =
      Set.of("policy-version", "world-write", "player-write", "server-admin", "allow-op-toggle");
  private static final Set<String> CAPABILITY_KEYS = Set.of("directory");
  private static final Set<String> CAPABILITY_KEYS_WITH_APPROVALS =
      Set.of("directory", "approvals");
  private static final Set<String> CAPABILITY_APPROVAL_KEYS = Set.of("id", "version", "sha256");

  public PaperStartupConfig load(
      Path configFile, Path dataDirectory, Map<String, String> environment) throws StartupFailure {
    Objects.requireNonNull(configFile);
    Objects.requireNonNull(dataDirectory);
    Objects.requireNonNull(environment);

    var root = parseRoot(configFile);
    requireExactKeys(
        root,
        root.containsKey("owners") ? ROOT_KEYS_WITH_OWNERS : PHASE_3_ROOT_KEYS,
        StartupFailure.Code.PAPER_CONFIG_INVALID);
    var configVersion =
        requireInteger(root, "config-version", StartupFailure.Code.PAPER_CONFIG_INVALID);
    if (configVersion != 1 && configVersion != 2) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }

    var server =
        requireMapping(root, "server", SERVER_KEYS, StartupFailure.Code.PAPER_CONFIG_INVALID);
    var serverId = requireString(server, "id", StartupFailure.Code.PAPER_CONFIG_INVALID);
    if (!SERVER_ID.matcher(serverId).matches()) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }
    var owners = root.containsKey("owners") ? requireOwners(root) : Set.<UUID>of();

    var runtime =
        requireMapping(
            root,
            "runtime",
            configVersion == 1 ? RUNTIME_KEYS : VERSION_2_RUNTIME_KEYS,
            StartupFailure.Code.PAPER_CONFIG_INVALID);
    var runtimeMode =
        configVersion == 1 ? RuntimeDeploymentMode.EXTERNAL : parseRuntimeDeploymentMode(runtime);
    var endpoint =
        parseEndpoint(requireString(runtime, "url", StartupFailure.Code.RUNTIME_ENDPOINT_INVALID));
    var serverToken = resolveServerToken(runtime, environment);
    var connectTimeout =
        parseTimeout(runtime, "connect-timeout-millis", StartupFailure.Code.PAPER_CONFIG_INVALID);
    var handshakeTimeout =
        parseTimeout(runtime, "handshake-timeout-millis", StartupFailure.Code.PAPER_CONFIG_INVALID);

    var normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize();
    var state = requireMapping(root, "state", STATE_KEYS, StartupFailure.Code.PAPER_CONFIG_INVALID);
    var stateDirectory =
        resolveContainedDirectory(
            normalizedDataDirectory,
            requireString(state, "directory", StartupFailure.Code.STATE_DIRECTORY_UNSAFE),
            StartupFailure.Code.STATE_DIRECTORY_UNSAFE);

    var security =
        requireMapping(root, "security", SECURITY_KEYS, StartupFailure.Code.CORE_POLICY_INVALID);
    var securityPolicy = parseSecurityPolicy(security);
    securityPolicy.validate();

    var capabilities = requireCapabilityMapping(root);
    var optionalCapabilityDirectory =
        resolveContainedDirectory(
            normalizedDataDirectory,
            requireString(capabilities, "directory", StartupFailure.Code.PAPER_CONFIG_INVALID),
            StartupFailure.Code.PAPER_CONFIG_INVALID);
    var capabilityApprovals =
        capabilities.containsKey("approvals")
            ? requireCapabilityApprovals(capabilities)
            : Set.<CapabilityApproval>of();

    return new PaperStartupConfig(
        serverId,
        owners,
        new PaperStartupConfig.RuntimeSettings(
            runtimeMode, endpoint, serverToken, connectTimeout, handshakeTimeout),
        stateDirectory,
        securityPolicy,
        optionalCapabilityDirectory,
        capabilityApprovals);
  }

  private static RuntimeDeploymentMode parseRuntimeDeploymentMode(Map<?, ?> runtime)
      throws StartupFailure {
    try {
      return RuntimeDeploymentMode.valueOf(
          requireString(runtime, "mode", StartupFailure.Code.PAPER_CONFIG_INVALID));
    } catch (IllegalArgumentException exception) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }
  }

  private static Map<?, ?> parseRoot(Path configFile) throws StartupFailure {
    byte[] bytes;
    try {
      if (Files.isSymbolicLink(configFile)
          || !Files.readAttributes(
                  configFile, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW_LINKS)
              .isRegularFile()) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      var permissionView =
          Files.getFileAttributeView(configFile, PosixFileAttributeView.class, NOFOLLOW_LINKS);
      if (permissionView == null) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      var permissions = permissionView.readAttributes().permissions();
      if (permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      bytes = readBounded(configFile);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException | SecurityException exception) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }

    String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }

    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(32);
    options.setCodePointLimit(MAX_CONFIG_BYTES);
    try {
      var loaded = new Yaml(new SafeConstructor(options)).load(source);
      if (!(loaded instanceof Map<?, ?> mapping)) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      return mapping;
    } catch (StartupFailure failure) {
      throw failure;
    } catch (YAMLException | ClassCastException exception) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }
  }

  private static byte[] readBounded(Path configFile) throws IOException, StartupFailure {
    try (InputStream input = Files.newInputStream(configFile, NOFOLLOW_LINKS)) {
      var bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
      if (bytes.length > MAX_CONFIG_BYTES) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_TOO_LARGE, StartupFailure.Stage.CONFIG);
      }
      return bytes;
    }
  }

  private static URI parseEndpoint(String value) throws StartupFailure {
    try {
      var endpoint = new URI(value);
      if (!"ws".equals(endpoint.getScheme())
          || !"127.0.0.1".equals(endpoint.getHost())
          || endpoint.getPort() < 1024
          || endpoint.getPort() > 65_535
          || !"/agent".equals(endpoint.getRawPath())
          || endpoint.getRawUserInfo() != null
          || endpoint.getRawQuery() != null
          || endpoint.getRawFragment() != null) {
        throw failure(StartupFailure.Code.RUNTIME_ENDPOINT_INVALID, StartupFailure.Stage.CONFIG);
      }
      return endpoint;
    } catch (URISyntaxException exception) {
      throw failure(StartupFailure.Code.RUNTIME_ENDPOINT_INVALID, StartupFailure.Stage.CONFIG);
    }
  }

  private static String resolveServerToken(Map<?, ?> runtime, Map<String, String> environment)
      throws StartupFailure {
    var reference = requireString(runtime, "server-token", StartupFailure.Code.SERVER_TOKEN_UNSAFE);
    var match = ENVIRONMENT_REFERENCE.matcher(reference);
    if (!match.matches()) {
      throw failure(StartupFailure.Code.SERVER_TOKEN_UNSAFE, StartupFailure.Stage.CONFIG);
    }

    var token = environment.get(match.group(1));
    if (token == null || token.isBlank()) {
      throw failure(StartupFailure.Code.SERVER_TOKEN_MISSING, StartupFailure.Stage.CONFIG);
    }
    if (token.length() < 32
        || token.length() > 8192
        || PLACEHOLDER_SECRET.matcher(token).matches()
        || token.codePoints().anyMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f)) {
      throw failure(StartupFailure.Code.SERVER_TOKEN_UNSAFE, StartupFailure.Stage.CONFIG);
    }
    return token;
  }

  private static Duration parseTimeout(Map<?, ?> mapping, String key, StartupFailure.Code code)
      throws StartupFailure {
    var value = requireInteger(mapping, key, code);
    if (value < 100 || value > MAX_TIMEOUT_MILLIS) {
      throw failure(code, StartupFailure.Stage.CONFIG);
    }
    return Duration.ofMillis(value);
  }

  private static SecurityPolicy parseSecurityPolicy(Map<?, ?> mapping) throws StartupFailure {
    try {
      return new SecurityPolicy(
          requireInteger(mapping, "policy-version", StartupFailure.Code.CORE_POLICY_INVALID),
          SecurityPolicy.AccessLevel.valueOf(
              requireString(mapping, "world-write", StartupFailure.Code.CORE_POLICY_INVALID)),
          SecurityPolicy.AccessLevel.valueOf(
              requireString(mapping, "player-write", StartupFailure.Code.CORE_POLICY_INVALID)),
          SecurityPolicy.AccessLevel.valueOf(
              requireString(mapping, "server-admin", StartupFailure.Code.CORE_POLICY_INVALID)),
          requireBoolean(mapping, "allow-op-toggle", StartupFailure.Code.CORE_POLICY_INVALID));
    } catch (IllegalArgumentException exception) {
      throw failure(StartupFailure.Code.CORE_POLICY_INVALID, StartupFailure.Stage.SECURITY_POLICY);
    }
  }

  private static Set<UUID> requireOwners(Map<?, ?> root) throws StartupFailure {
    var value = root.get("owners");
    if (!(value instanceof List<?> entries) || entries.size() > MAX_OWNERS) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }

    var owners = new LinkedHashSet<UUID>();
    for (var entry : entries) {
      if (!(entry instanceof String text) || !OWNER_UUID.matcher(text).matches()) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      var owner = UUID.fromString(text);
      if (!owners.add(owner)) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
    }
    return Set.copyOf(owners);
  }

  private static Map<?, ?> requireCapabilityMapping(Map<?, ?> root) throws StartupFailure {
    var value = root.get("capabilities");
    if (!(value instanceof Map<?, ?> mapping)) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }
    requireExactKeys(
        mapping,
        mapping.containsKey("approvals") ? CAPABILITY_KEYS_WITH_APPROVALS : CAPABILITY_KEYS,
        StartupFailure.Code.PAPER_CONFIG_INVALID);
    return mapping;
  }

  private static Set<CapabilityApproval> requireCapabilityApprovals(Map<?, ?> capabilities)
      throws StartupFailure {
    var value = capabilities.get("approvals");
    if (!(value instanceof List<?> entries) || entries.size() > MAX_CAPABILITY_APPROVALS) {
      throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
    }

    var approvals = new LinkedHashSet<CapabilityApproval>();
    for (var entry : entries) {
      if (!(entry instanceof Map<?, ?> approval)) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      requireExactKeys(
          approval, CAPABILITY_APPROVAL_KEYS, StartupFailure.Code.PAPER_CONFIG_INVALID);
      var id = requireString(approval, "id", StartupFailure.Code.PAPER_CONFIG_INVALID);
      var version = requireInteger(approval, "version", StartupFailure.Code.PAPER_CONFIG_INVALID);
      var sha256 = requireString(approval, "sha256", StartupFailure.Code.PAPER_CONFIG_INVALID);
      if (!CAPABILITY_ID.matcher(id).matches()
          || id.length() > 128
          || version < 1
          || !SHA256.matcher(sha256).matches()) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
      if (!approvals.add(new CapabilityApproval(id, version, sha256))) {
        throw failure(StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
      }
    }
    return Set.copyOf(approvals);
  }

  private static Path resolveContainedDirectory(
      Path dataDirectory, String value, StartupFailure.Code code) throws StartupFailure {
    if (value.isBlank()
        || value.length() > 512
        || value.contains("\\")
        || value.contains("\0")
        || value.startsWith("/")) {
      throw failure(code, stageFor(code));
    }
    try {
      var relative = Path.of(value);
      if (relative.isAbsolute()
          || relative.getNameCount() == 0
          || relative.toString().equals(".")
          || containsDotSegment(value)) {
        throw failure(code, stageFor(code));
      }
      var resolved = dataDirectory.resolve(relative).normalize();
      if (resolved.equals(dataDirectory) || !resolved.startsWith(dataDirectory)) {
        throw failure(code, stageFor(code));
      }
      return resolved;
    } catch (InvalidPathException exception) {
      throw failure(code, stageFor(code));
    }
  }

  private static boolean containsDotSegment(String value) {
    for (var segment : value.split("/", -1)) {
      if (segment.equals(".")
          || segment.equals("..")
          || !DIRECTORY_SEGMENT.matcher(segment).matches()) {
        return true;
      }
    }
    return false;
  }

  private static Map<?, ?> requireMapping(
      Map<?, ?> parent, String key, Set<String> keys, StartupFailure.Code code)
      throws StartupFailure {
    var value = parent.get(key);
    if (!(value instanceof Map<?, ?> mapping)) {
      throw failure(code, stageFor(code));
    }
    requireExactKeys(mapping, keys, code);
    return mapping;
  }

  private static void requireExactKeys(
      Map<?, ?> mapping, Set<String> expectedKeys, StartupFailure.Code code) throws StartupFailure {
    if (mapping.size() != expectedKeys.size()) {
      throw failure(code, stageFor(code));
    }
    for (var key : mapping.keySet()) {
      if (!(key instanceof String stringKey) || !expectedKeys.contains(stringKey)) {
        throw failure(code, stageFor(code));
      }
    }
  }

  private static String requireString(Map<?, ?> mapping, String key, StartupFailure.Code code)
      throws StartupFailure {
    var value = mapping.get(key);
    if (!(value instanceof String string) || string.isBlank()) {
      throw failure(code, stageFor(code));
    }
    return string;
  }

  private static int requireInteger(Map<?, ?> mapping, String key, StartupFailure.Code code)
      throws StartupFailure {
    var value = mapping.get(key);
    if (!(value instanceof Integer integer)) {
      throw failure(code, stageFor(code));
    }
    return integer;
  }

  private static boolean requireBoolean(Map<?, ?> mapping, String key, StartupFailure.Code code)
      throws StartupFailure {
    var value = mapping.get(key);
    if (!(value instanceof Boolean bool)) {
      throw failure(code, stageFor(code));
    }
    return bool;
  }

  private static StartupFailure.Stage stageFor(StartupFailure.Code code) {
    return switch (code) {
      case STATE_DIRECTORY_UNAVAILABLE, STATE_DIRECTORY_UNSAFE -> StartupFailure.Stage.STATE;
      case CORE_POLICY_INVALID -> StartupFailure.Stage.SECURITY_POLICY;
      case CORE_TOOL_MISSING,
          CORE_TOOL_DUPLICATE,
          CORE_TOOL_UNSAFE,
          CORE_TOOL_INITIALIZATION_FAILED ->
          StartupFailure.Stage.CORE_TOOLS;
      case JAVA_VERSION_UNSUPPORTED, PAPER_VERSION_UNSUPPORTED -> StartupFailure.Stage.ENVIRONMENT;
      default -> StartupFailure.Stage.CONFIG;
    };
  }

  private static StartupFailure failure(StartupFailure.Code code, StartupFailure.Stage stage) {
    return new StartupFailure(code, stage);
  }
}
