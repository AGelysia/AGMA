package dev.minecraftagent.paper.proposal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import org.erdtman.jcs.JsonCanonicalizer;

/** Freezes proposal arguments using the protocol's RFC 8785 hash contract. */
public final class CanonicalArguments {
  private static final byte[] HASH_DOMAIN =
      "minecraft-agent/proposal-arguments/v1".getBytes(StandardCharsets.UTF_8);
  private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
  private static final int MAX_DEPTH = 32;
  private static final int MAX_NODES = 4096;
  private static final int MAX_CANONICAL_BYTES = 65_536;

  private CanonicalArguments() {}

  public static Frozen freeze(JsonObject arguments) {
    Objects.requireNonNull(arguments);
    var canonicalJson = canonicalize(arguments);
    return new Frozen(canonicalJson, hashCanonical(canonicalJson));
  }

  public static String hash(JsonObject arguments) {
    return freeze(arguments).sha256();
  }

  /** Restores persisted data without trusting it; callers must invoke {@link Frozen#verified()}. */
  public static Frozen fromPersisted(String canonicalJson, String sha256) {
    return new Frozen(canonicalJson, sha256);
  }

  public static boolean hashesEqual(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }

  private static String canonicalize(JsonElement root) {
    var budget = new NodeBudget();
    validate(root, 0, budget);
    final String canonicalJson;
    try {
      canonicalJson = new JsonCanonicalizer(root.toString()).getEncodedString();
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("Proposal arguments are not canonicalizable", error);
    }
    if (canonicalJson.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Proposal arguments exceed the canonical size limit");
    }
    return canonicalJson;
  }

  private static void validate(JsonElement element, int depth, NodeBudget budget) {
    if (depth > MAX_DEPTH || ++budget.nodes > MAX_NODES) {
      throw new IllegalArgumentException("Proposal arguments exceed structural limits");
    }
    if (element == null || element instanceof JsonNull) {
      return;
    }
    if (element instanceof JsonObject object) {
      for (var entry : object.entrySet()) {
        budget.addText(entry.getKey());
        requireValidUnicode(entry.getKey());
        validate(entry.getValue(), depth + 1, budget);
      }
      return;
    }
    if (element instanceof JsonArray array) {
      for (var child : array) {
        validate(child, depth + 1, budget);
      }
      return;
    }
    var primitive = element.getAsJsonPrimitive();
    if (primitive.isNumber()) {
      final double value;
      try {
        value = primitive.getAsDouble();
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("Proposal arguments contain a non-finite number", error);
      }
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("Proposal arguments contain a non-finite number");
      }
      budget.addText(primitive.getAsString());
    } else if (primitive.isString()) {
      budget.addText(primitive.getAsString());
      requireValidUnicode(primitive.getAsString());
    }
  }

  private static void requireValidUnicode(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException("Proposal arguments contain invalid Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("Proposal arguments contain invalid Unicode");
      }
    }
  }

  private static String hashCanonical(String canonicalJson) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(HASH_DOMAIN);
      digest.update((byte) 0);
      digest.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static final class NodeBudget {
    private int nodes;
    private int textBytes;

    private void addText(String value) {
      if (value.length() > MAX_CANONICAL_BYTES) {
        throw new IllegalArgumentException("Proposal arguments exceed the canonical size limit");
      }
      textBytes = Math.addExact(textBytes, value.getBytes(StandardCharsets.UTF_8).length);
      if (textBytes > MAX_CANONICAL_BYTES) {
        throw new IllegalArgumentException("Proposal arguments exceed the canonical size limit");
      }
    }
  }

  /** Immutable persisted representation. Its string form intentionally omits argument material. */
  public static final class Frozen {
    private final String canonicalJson;
    private final String sha256;

    private Frozen(String canonicalJson, String sha256) {
      this.canonicalJson = Objects.requireNonNull(canonicalJson);
      this.sha256 = Objects.requireNonNull(sha256);
      if (!SHA_256.matcher(sha256).matches()) {
        throw new IllegalArgumentException("Invalid proposal argument hash");
      }
    }

    public String canonicalJson() {
      return canonicalJson;
    }

    public String sha256() {
      return sha256;
    }

    public JsonObject arguments() {
      var parsed = JsonParser.parseString(canonicalJson);
      if (!parsed.isJsonObject()) {
        throw new IllegalStateException("Persisted proposal arguments are not an object");
      }
      return parsed.getAsJsonObject();
    }

    public boolean verified() {
      try {
        var parsed = arguments();
        var recanonicalized = canonicalize(parsed);
        return recanonicalized.equals(canonicalJson)
            && hashesEqual(sha256, hashCanonical(recanonicalized));
      } catch (RuntimeException error) {
        return false;
      }
    }

    @Override
    public String toString() {
      return "Frozen[canonicalJson=<redacted>, sha256=<redacted>]";
    }
  }
}
