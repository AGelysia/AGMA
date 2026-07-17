package dev.minecraftagent.paper.runtime;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Detects the intentional placeholders in the managed Runtime template without logging values. */
public final class ManagedRuntimeSetupProbe {
  private static final int MAX_CONFIG_BYTES = 64 * 1024;
  private static final Set<String> PROVIDERS =
      Set.of("openai", "anthropic", "deepseek", "gemini", "openai-compatible");
  private static final Map<String, String> MANAGED_ENVIRONMENT_REFERENCES =
      Map.of(
          "/server/id", "${AGMA_MANAGED_SERVER_ID}",
          "/transport/port", "${AGMA_MANAGED_RUNTIME_PORT}",
          "/transport/serverToken", "${AGMA_MANAGED_SERVER_TOKEN}");
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("(?i)^(?:change-?me|replace-with-.*|your[-_].*|configured-.*)$");
  private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile("^\\$\\{[A-Z_][A-Z0-9_]*}$");

  public void verify(Path configFile) throws StartupFailure {
    byte[] bytes;
    try {
      var parent = configFile.toAbsolutePath().normalize().getParent();
      if (parent == null) {
        throw setupRequired();
      }
      var parentAttributes =
          Files.readAttributes(parent, PosixFileAttributes.class, NOFOLLOW_LINKS);
      if (parentAttributes.isSymbolicLink()
          || !parentAttributes.isDirectory()
          || !parentAttributes.permissions().equals(PRIVATE_DIRECTORY)) {
        throw setupRequired();
      }
      var attributes = Files.readAttributes(configFile, PosixFileAttributes.class, NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()
          || !attributes.isRegularFile()
          || attributes.size() > MAX_CONFIG_BYTES) {
        throw setupRequired();
      }
      var links = Files.getAttribute(configFile, "unix:nlink", NOFOLLOW_LINKS);
      if (!(links instanceof Number count) || count.longValue() != 1L) {
        throw setupRequired();
      }
      if (!attributes.permissions().equals(PRIVATE_FILE)
          || !attributes.owner().equals(parentAttributes.owner())) {
        throw setupRequired();
      }
      try (var input = Files.newInputStream(configFile, NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        if (bytes.length > MAX_CONFIG_BYTES || bytes.length != attributes.size()) {
          throw setupRequired();
        }
      }
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException | SecurityException error) {
      throw setupRequired();
    }

    String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException error) {
      throw setupRequired();
    }

    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(32);
    options.setCodePointLimit(MAX_CONFIG_BYTES);
    try {
      var loaded = new Yaml(new SafeConstructor(options)).load(source);
      if (!(loaded instanceof Map<?, ?> root) || !(root.get("model") instanceof Map<?, ?> model)) {
        throw setupRequired();
      }
      if (!(root.get("server") instanceof Map<?, ?> server)
          || !"${AGMA_MANAGED_SERVER_ID}".equals(server.get("id"))
          || !(root.get("transport") instanceof Map<?, ?> transport)
          || !"127.0.0.1".equals(transport.get("host"))
          || !"${AGMA_MANAGED_RUNTIME_PORT}".equals(transport.get("port"))
          || !"${AGMA_MANAGED_SERVER_TOKEN}".equals(transport.get("serverToken"))) {
        throw setupRequired();
      }
      if (hasUnsupportedEnvironmentReference(root, "")) {
        throw setupRequired();
      }
      var provider = model.get("provider");
      var apiKey = model.get("apiKey");
      var modelName = model.get("model");
      var baseUrl = model.get("baseUrl");
      if (!(provider instanceof String providerName)
          || !PROVIDERS.contains(providerName)
          || !(apiKey instanceof String key)
          || key.isBlank()
          || key.length() > 8192
          || PLACEHOLDER.matcher(key).matches()
          || ENVIRONMENT_REFERENCE.matcher(key).matches()
          || key.codePoints().anyMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f)
          || !(modelName instanceof String selectedModel)
          || selectedModel.isBlank()
          || PLACEHOLDER.matcher(selectedModel).matches()
          || (baseUrl != null
              && (!(baseUrl instanceof String configuredBaseUrl)
                  || configuredBaseUrl.isBlank()
                  || configuredBaseUrl.length() > 2048
                  || PLACEHOLDER.matcher(configuredBaseUrl).matches()
                  || ENVIRONMENT_REFERENCE.matcher(configuredBaseUrl).matches()
                  || configuredBaseUrl
                      .codePoints()
                      .anyMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f)))
          || ("openai-compatible".equals(providerName) && baseUrl == null)) {
        throw setupRequired();
      }
    } catch (StartupFailure failure) {
      throw failure;
    } catch (YAMLException | ClassCastException error) {
      throw setupRequired();
    }
  }

  private static boolean hasUnsupportedEnvironmentReference(Object value, String path) {
    if (value instanceof String scalar) {
      return ENVIRONMENT_REFERENCE.matcher(scalar).matches()
          && !scalar.equals(MANAGED_ENVIRONMENT_REFERENCES.get(path));
    }
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        var childPath = path + "/" + String.valueOf(entry.getKey());
        if (hasUnsupportedEnvironmentReference(entry.getValue(), childPath)) {
          return true;
        }
      }
    }
    if (value instanceof Iterable<?> values) {
      for (var entry : values) {
        if (hasUnsupportedEnvironmentReference(entry, path + "/*")) {
          return true;
        }
      }
    }
    return false;
  }

  private static StartupFailure setupRequired() {
    return new StartupFailure(
        StartupFailure.Code.MANAGED_RUNTIME_SETUP_REQUIRED, StartupFailure.Stage.CONFIG);
  }
}
