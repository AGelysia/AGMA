package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves v3 secret references without ever adding resolved values to the persisted profile. */
public final class ClientSecretResolver {
  private static final int MAXIMUM_SECRET_BYTES = 8192;
  private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("^(?:change-?me|replace-with-|your[-_])", Pattern.CASE_INSENSITIVE);
  private static final Set<PosixFilePermission> FORBIDDEN_POSIX =
      PosixFilePermissions.fromString("---rwxrwx");

  public ResolvedClientSecrets resolve(
      RuntimeClientProfile profile, Path profileRoot, Map<String, String> environment) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(environment, "environment");
    var root = requireRoot(profileRoot);
    SecretMaterial connector = null;
    SecretMaterial model = null;
    SecretMaterial search = null;
    try {
      var connectorValue =
          resolve(
              profile.transport().connectorToken(), "/transport/connectorToken", root, environment);
      if (connectorValue.getBytes(StandardCharsets.UTF_8).length < 32) {
        throw failure(
            "CONNECTOR_TOKEN_MISSING",
            "/transport/connectorToken",
            "Connector token must contain at least 32 UTF-8 bytes");
      }
      var modelValue = resolve(profile.model().apiKey(), "/model/apiKey", root, environment);
      connector = SecretMaterial.fromUtf8(connectorValue);
      model = SecretMaterial.fromUtf8(modelValue);
      if (profile.webEvidence() != null) {
        search =
            SecretMaterial.fromUtf8(
                resolve(profile.webEvidence().apiKey(), "/webEvidence/apiKey", root, environment));
      }
      var connectorBytes = connector.copyBytes();
      var modelBytes = model.copyBytes();
      var searchBytes = search == null ? null : search.copyBytes();
      try {
        if (MessageDigest.isEqual(connectorBytes, modelBytes)) {
          throw failure(
              "SECRET_REUSE",
              "/transport/connectorToken",
              "Connector token must not reuse the model API key");
        }
        if (searchBytes != null
            && (MessageDigest.isEqual(searchBytes, connectorBytes)
                || MessageDigest.isEqual(searchBytes, modelBytes))) {
          throw failure(
              "SECRET_REUSE",
              "/webEvidence/apiKey",
              "Web Search API key must not reuse another client secret");
        }
      } finally {
        Arrays.fill(connectorBytes, (byte) 0);
        Arrays.fill(modelBytes, (byte) 0);
        if (searchBytes != null) {
          Arrays.fill(searchBytes, (byte) 0);
        }
      }
      return new ResolvedClientSecrets(connector, model, search);
    } catch (RuntimeException exception) {
      if (connector != null) {
        connector.close();
      }
      if (model != null) {
        model.close();
      }
      if (search != null) {
        search.close();
      }
      throw exception;
    }
  }

  SecretMaterial resolveSecret(
      RuntimeClientProfile.SecretReference reference,
      String field,
      Path profileRoot,
      Map<String, String> environment) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(environment, "environment");
    return SecretMaterial.fromUtf8(
        resolve(reference, field, requireRoot(profileRoot), environment));
  }

  private static String resolve(
      RuntimeClientProfile.SecretReference reference,
      String field,
      Path root,
      Map<String, String> environment) {
    return switch (reference.source()) {
      case "environment" -> environmentSecret(reference.reference(), field, environment);
      case "private_file" -> privateFileSecret(root, reference.reference(), field);
      case "credential_store" ->
          throw failure(
              "SECRET_REFERENCE_UNSUPPORTED",
              field,
              "Credential-store references are unavailable in C1");
      default -> throw failure("CONFIG_SCHEMA_INVALID", field, "Secret source is invalid");
    };
  }

  private static String environmentSecret(
      String reference, String field, Map<String, String> environment) {
    if (!ENVIRONMENT_NAME.matcher(reference).matches()) {
      throw failure(
          "CONFIG_SCHEMA_INVALID", field + "/reference", "Environment reference is invalid");
    }
    var value = environment.get(reference);
    if (value == null) {
      throw missing(field);
    }
    return validateSecret(value, field);
  }

  private static String privateFileSecret(Path root, String reference, String field) {
    var candidate = root.resolve(reference).normalize();
    if (!candidate.startsWith(root) || candidate.equals(root) || Files.isSymbolicLink(candidate)) {
      throw failure("SECRET_FILE_INVALID", field, "Private secret file is unsafe");
    }
    try {
      verifyNoSymbolicLinks(root, candidate, field);
      var before =
          Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile() || before.size() < 1 || before.size() > MAXIMUM_SECRET_BYTES) {
        throw failure("SECRET_FILE_INVALID", field, "Private secret file is invalid");
      }
      verifyPrivatePermissions(candidate, field);
      var bytes = readExact(candidate, (int) before.size());
      try {
        var after =
            Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile()
            || after.size() != before.size()
            || !Objects.equals(after.fileKey(), before.fileKey())
            || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
          throw failure("SECRET_FILE_INVALID", field, "Private secret file changed while read");
        }
        var decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var value = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        if (value.endsWith("\r\n")) {
          value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("\n")) {
          value = value.substring(0, value.length() - 1);
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
          throw failure("SECRET_FILE_INVALID", field, "Private secret file is invalid");
        }
        return validateSecret(value, field);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw failure(
          "SECRET_FILE_INVALID", field, "Private secret file could not be read", exception);
    }
  }

  private static byte[] readExact(Path path, int expected) throws IOException {
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
      var buffer = ByteBuffer.allocate(expected);
      var emptyReads = 0;
      while (buffer.hasRemaining()) {
        var read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        if (read == 0 && ++emptyReads > 8) {
          Arrays.fill(buffer.array(), (byte) 0);
          throw new IOException("Secret file could not be read atomically");
        }
        if (read > 0) {
          emptyReads = 0;
        }
      }
      if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) >= 0) {
        Arrays.fill(buffer.array(), (byte) 0);
        throw new IOException("Secret file length changed");
      }
      return buffer.array();
    }
  }

  private static void verifyNoSymbolicLinks(Path root, Path candidate, String field) {
    var current = root;
    for (var component : root.relativize(candidate)) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current)) {
        throw failure("SECRET_FILE_INVALID", field, "Private secret file is unsafe");
      }
    }
  }

  private static void verifyPrivatePermissions(Path path, String field) throws IOException {
    FileStore store = Files.getFileStore(path);
    if (store.supportsFileAttributeView("posix")) {
      var attributes =
          Files.readAttributes(
              path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.permissions().stream().anyMatch(FORBIDDEN_POSIX::contains)
          || !attributes.permissions().contains(PosixFilePermission.OWNER_READ)) {
        throw failure("SECRET_FILE_INVALID", field, "Private secret file permissions are unsafe");
      }
      try {
        var links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(links instanceof Number number) || number.longValue() != 1) {
          throw failure("SECRET_FILE_INVALID", field, "Private secret file links are unsafe");
        }
      } catch (UnsupportedOperationException ignored) {
        // A POSIX provider without unix:nlink still enforces owner-only permissions and no links.
      }
      return;
    }

    var view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw failure("SECRET_FILE_INVALID", field, "Private secret file policy is unavailable");
    }
    var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
    for (var entry : view.getAcl()) {
      if (entry.type() == AclEntryType.ALLOW
          && !entry.principal().equals(owner)
          && !entry.permissions().isEmpty()) {
        throw failure("SECRET_FILE_INVALID", field, "Private secret file ACL is unsafe");
      }
    }
  }

  private static String validateSecret(String value, String field) {
    if (value.isEmpty()
        || value.length() > MAXIMUM_SECRET_BYTES
        || value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_SECRET_BYTES
        || value.codePoints().anyMatch(character -> character <= 0x1f || character == 0x7f)
        || PLACEHOLDER.matcher(value).find()) {
      throw missing(field);
    }
    return value;
  }

  private static Path requireRoot(Path root) {
    if (root == null || Files.isSymbolicLink(root)) {
      throw failure("CONFIG_ROOT_INVALID", "/", "Client configuration root is unsafe");
    }
    try {
      var real = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        throw failure("CONFIG_ROOT_INVALID", "/", "Client configuration root is unsafe");
      }
      return real;
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw failure(
          "CONFIG_ROOT_INVALID", "/", "Client configuration root is unavailable", exception);
    }
  }

  private static ClientConfigurationException missing(String field) {
    return failure(
        field.equals("/model/apiKey") ? "API_KEY_MISSING" : "CONNECTOR_TOKEN_MISSING",
        field,
        field.equals("/model/apiKey")
            ? "Model API key is missing or invalid"
            : "Connector token is missing or invalid");
  }

  private static ClientConfigurationException failure(String code, String field, String message) {
    return new ClientConfigurationException(code, field, message);
  }

  private static ClientConfigurationException failure(
      String code, String field, String message, Throwable cause) {
    return new ClientConfigurationException(code, field, message, cause);
  }
}
