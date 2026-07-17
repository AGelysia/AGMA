package dev.minecraftagent.standalone.core.contract;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class ContractChecks {
  private static final BigDecimal MAXIMUM_RESOURCE_AMOUNT = new BigDecimal("1000000000000000");
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern SYMBOLIC_ID = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");
  private static final Pattern OPAQUE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern BASE64_URL = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Pattern SEMANTIC_VERSION =
      Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$");

  private ContractChecks() {}

  static String text(String value, String field, int maximumLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " must contain bounded non-blank text");
    }
    return value;
  }

  static String optionalText(String value, String field, int maximumLength) {
    return value == null ? null : text(value, field, maximumLength);
  }

  static String namespacedId(String value, String field) {
    text(value, field, 256);
    if (!NAMESPACED_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a namespaced id");
    }
    return value;
  }

  static String symbolicId(String value, String field) {
    text(value, field, 128);
    if (!SYMBOLIC_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a symbolic id");
    }
    return value;
  }

  static String opaqueId(String value, String field) {
    text(value, field, 128);
    if (!OPAQUE_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a safe opaque id");
    }
    return value;
  }

  static String sha256(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  static String optionalSha256(String value, String field) {
    return value == null ? null : sha256(value, field);
  }

  static String canonicalBase64Url(String value, String field, int minimumBytes, int maximumBytes) {
    Objects.requireNonNull(value, field);
    if (!BASE64_URL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be bounded unpadded base64url");
    }
    final byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be decodable base64url", error);
    }
    if (decoded.length < minimumBytes
        || decoded.length > maximumBytes
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
      throw new IllegalArgumentException(field + " must be canonical unpadded base64url");
    }
    return value;
  }

  static String semanticVersion(String value, String field) {
    text(value, field, 64);
    if (!SEMANTIC_VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a semantic version");
    }
    return value;
  }

  static BigDecimal positive(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() <= 0
        || value.compareTo(MAXIMUM_RESOURCE_AMOUNT) > 0
        || value.precision() > 24
        || value.scale() > 9) {
      throw new IllegalArgumentException(field + " must be a bounded positive decimal");
    }
    return value;
  }

  static BigDecimal probability(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0 || value.scale() > 9) {
      throw new IllegalArgumentException(field + " must be between zero and one");
    }
    return value;
  }

  static <T> List<T> list(List<T> value, String field, int maximumSize) {
    Objects.requireNonNull(value, field);
    if (value.size() > maximumSize || value.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(field + " is invalid or too large");
    }
    return List.copyOf(value);
  }

  static <T> List<T> nonEmptyList(List<T> value, String field, int maximumSize) {
    var copy = list(value, field, maximumSize);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    return copy;
  }

  static <T> Set<T> set(Set<T> value, String field, int maximumSize) {
    Objects.requireNonNull(value, field);
    if (value.size() > maximumSize || value.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(field + " is invalid or too large");
    }
    return Set.copyOf(value);
  }

  static <K, V> Map<K, V> map(Map<K, V> value, String field, int maximumSize) {
    Objects.requireNonNull(value, field);
    if (value.size() > maximumSize
        || value.entrySet().stream()
            .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
      throw new IllegalArgumentException(field + " is invalid or too large");
    }
    return Map.copyOf(value);
  }

  static URI httpsUri(URI value, String field) {
    Objects.requireNonNull(value, field);
    if (!"https".equalsIgnoreCase(value.getScheme())
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException(field + " must be a public HTTPS URI without credentials");
    }
    return value;
  }
}
