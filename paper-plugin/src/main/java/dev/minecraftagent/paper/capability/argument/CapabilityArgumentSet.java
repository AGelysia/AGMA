package dev.minecraftagent.paper.capability.argument;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable codecs for one capability's closed, required-only argument object. */
public final class CapabilityArgumentSet {
  private static final Pattern ARGUMENT_NAME = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");
  private static final Pattern RESOURCE_LOCATION =
      Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,192}$");
  private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
  private static final Pattern BLOCK_PATTERN =
      Pattern.compile("^[a-z0-9_#][a-z0-9_:#%.,=/\\[\\]*~+!?-]{0,255}$");
  private static final Pattern RELATIVE_COORDINATE =
      Pattern.compile("^[~^](?:-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?)?$");
  private static final Set<String> COMMON_FIELDS = Set.of("type", "description", "required");
  private static final BigDecimal HORIZONTAL_COORDINATE_LIMIT = new BigDecimal("30000000");
  private static final BigDecimal VERTICAL_COORDINATE_LIMIT = new BigDecimal("2048");
  private static final int MAX_ARGUMENTS = 64;
  private static final int MAX_TOKEN_CODE_POINTS = 1024;
  private static final int MAX_DECIMAL_COMPONENTS = 1024;
  private static final String SAFE_GENERIC_PUNCTUATION = "._:+-";

  private final Map<String, ArgumentCodec> codecs;
  private final Map<String, CapabilityArgumentType> types;

  private CapabilityArgumentSet(Map<String, ArgumentCodec> codecs) {
    this.codecs = Collections.unmodifiableMap(new LinkedHashMap<>(codecs));
    var publicTypes = new LinkedHashMap<String, CapabilityArgumentType>();
    codecs.forEach((name, codec) -> publicTypes.put(name, codec.type()));
    this.types = Collections.unmodifiableMap(publicTypes);
  }

  public static CapabilityArgumentSet compile(JsonObject descriptors) {
    Objects.requireNonNull(descriptors);
    if (descriptors.size() > MAX_ARGUMENTS) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, null);
    }

    var codecs = new LinkedHashMap<String, ArgumentCodec>();
    for (var entry : descriptors.entrySet()) {
      var name = entry.getKey();
      if (!ARGUMENT_NAME.matcher(name).matches() || !entry.getValue().isJsonObject()) {
        throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
      }
      codecs.put(name, compileDescriptor(name, entry.getValue().getAsJsonObject()));
    }
    return new CapabilityArgumentSet(codecs);
  }

  public Map<String, CapabilityArgumentType> types() {
    return types;
  }

  public Map<String, String> encode(JsonObject values) {
    Objects.requireNonNull(values);
    for (var name : codecs.keySet()) {
      if (!values.has(name) || values.get(name).isJsonNull()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_MISSING, name);
      }
    }
    for (var name : values.keySet()) {
      if (!codecs.containsKey(name)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_UNDECLARED, name);
      }
    }

    var encoded = new LinkedHashMap<String, String>();
    codecs.forEach((name, codec) -> encoded.put(name, codec.encode(values.get(name), name)));
    return Collections.unmodifiableMap(encoded);
  }

  private static ArgumentCodec compileDescriptor(String name, JsonObject descriptor) {
    requireCommonDescriptor(name, descriptor);
    var type = CapabilityArgumentType.parse(requireString(descriptor, "type", name), name);
    return switch (type) {
      case STRING -> compileString(name, descriptor);
      case INTEGER -> compileInteger(name, descriptor);
      case NUMBER -> compileNumber(name, descriptor);
      case BOOLEAN -> {
        requireFields(descriptor, COMMON_FIELDS, name);
        yield new BooleanCodec();
      }
      case ENUM -> compileEnum(name, descriptor);
      case BLOCK_PATTERN, ITEM, PLAYER, DIMENSION, COORDINATES -> {
        requireFields(descriptor, COMMON_FIELDS, name);
        yield new MinecraftCodec(type);
      }
    };
  }

  private static void requireCommonDescriptor(String name, JsonObject descriptor) {
    var description = requireString(descriptor, "description", name);
    var descriptionLength = description.codePointCount(0, description.length());
    if (descriptionLength < 1 || descriptionLength > 512) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    var required = descriptor.get("required");
    if (required == null
        || !required.isJsonPrimitive()
        || !required.getAsJsonPrimitive().isBoolean()) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    if (!required.getAsBoolean()) {
      throw failure(CapabilityArgumentException.Failure.OPTIONAL_ARGUMENT_UNSUPPORTED, name);
    }
  }

  private static ArgumentCodec compileString(String name, JsonObject descriptor) {
    requireFields(descriptor, union(COMMON_FIELDS, Set.of("minLength", "maxLength")), name);
    var minimum = requireInteger(descriptor.get("minLength"), name);
    var maximum = requireInteger(descriptor.get("maxLength"), name);
    if (minimum < 0 || maximum < 1 || maximum > 1024 || minimum > maximum) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    return new StringCodec(minimum, maximum);
  }

  private static ArgumentCodec compileInteger(String name, JsonObject descriptor) {
    requireFields(descriptor, union(COMMON_FIELDS, Set.of("minimum", "maximum")), name);
    var minimum = requireInteger(descriptor.get("minimum"), name);
    var maximum = requireInteger(descriptor.get("maximum"), name);
    if (minimum > maximum) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    return new IntegerCodec(minimum, maximum);
  }

  private static ArgumentCodec compileNumber(String name, JsonObject descriptor) {
    requireFields(descriptor, union(COMMON_FIELDS, Set.of("minimum", "maximum")), name);
    var minimum = requireDecimal(descriptor.get("minimum"), name);
    var maximum = requireDecimal(descriptor.get("maximum"), name);
    if (minimum.compareTo(maximum) > 0) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    return new NumberCodec(minimum, maximum);
  }

  private static ArgumentCodec compileEnum(String name, JsonObject descriptor) {
    requireFields(descriptor, union(COMMON_FIELDS, Set.of("values")), name);
    var valuesElement = descriptor.get("values");
    if (valuesElement == null || !valuesElement.isJsonArray()) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    JsonArray values = valuesElement.getAsJsonArray();
    if (values.isEmpty() || values.size() > 128) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    var allowed = new ArrayList<String>(values.size());
    var unique = new HashSet<String>();
    for (var value : values) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
      }
      var token = value.getAsString();
      if (token.codePointCount(0, token.length()) > 128
          || !safeGenericToken(token)
          || !unique.add(token)) {
        throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
      }
      allowed.add(token);
    }
    return new EnumCodec(List.copyOf(allowed));
  }

  private static void requireFields(JsonObject object, Set<String> expected, String name) {
    if (!object.keySet().equals(expected)) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
  }

  private static Set<String> union(Set<String> left, Set<String> right) {
    var result = new HashSet<>(left);
    result.addAll(right);
    return Set.copyOf(result);
  }

  private static String requireString(JsonObject object, String field, String name) {
    var value = object.get(field);
    if (!isString(value)) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    return value.getAsString();
  }

  private static int requireInteger(JsonElement value, String name) {
    var decimal = requireDecimal(value, name);
    final BigInteger integer;
    try {
      integer = decimal.toBigIntegerExact();
    } catch (ArithmeticException error) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    if (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
        || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    return integer.intValue();
  }

  private static BigDecimal requireDecimal(JsonElement value, String name) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
    try {
      var decimal = value.getAsBigDecimal();
      if (decimal.precision() > MAX_DECIMAL_COMPONENTS
          || Math.abs((long) decimal.scale()) > MAX_DECIMAL_COMPONENTS) {
        throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
      }
      return decimal;
    } catch (NumberFormatException error) {
      throw failure(CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, name);
    }
  }

  private static String encodeInteger(JsonElement value, String name, int minimum, int maximum) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
    }
    final BigDecimal decimal;
    try {
      decimal = value.getAsBigDecimal();
    } catch (NumberFormatException error) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
    }
    if (decimal.precision() > MAX_DECIMAL_COMPONENTS
        || Math.abs((long) decimal.scale()) > MAX_DECIMAL_COMPONENTS
        || decimal.compareTo(BigDecimal.valueOf(minimum)) < 0
        || decimal.compareTo(BigDecimal.valueOf(maximum)) > 0) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
    }
    final BigInteger integer;
    try {
      integer = decimal.toBigIntegerExact();
    } catch (ArithmeticException error) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
    }
    return integer.toString();
  }

  private static String encodeNumber(
      JsonElement value, String name, BigDecimal minimum, BigDecimal maximum) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
    }
    final BigDecimal decimal;
    try {
      decimal = value.getAsBigDecimal();
    } catch (NumberFormatException error) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
    }
    if (decimal.precision() > MAX_DECIMAL_COMPONENTS
        || Math.abs((long) decimal.scale()) > MAX_DECIMAL_COMPONENTS) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
    }
    if (decimal.compareTo(minimum) < 0 || decimal.compareTo(maximum) > 0) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
    }
    var normalized = decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    var encoded = normalized.toPlainString();
    if (encoded.length() > MAX_TOKEN_CODE_POINTS) {
      throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
    }
    return encoded;
  }

  private static boolean safeGenericToken(String value) {
    var codePoints = value.codePointCount(0, value.length());
    if (codePoints < 1
        || codePoints > MAX_TOKEN_CODE_POINTS
        || !Normalizer.normalize(value, Normalizer.Form.NFKC).equals(value)) {
      return false;
    }
    return value
        .codePoints()
        .allMatch(
            codePoint ->
                Character.isLetterOrDigit(codePoint)
                    || (codePoint < 128
                        && SAFE_GENERIC_PUNCTUATION.indexOf((char) codePoint) >= 0));
  }

  private static boolean balancedBlockState(String value) {
    var depth = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '[') {
        if (++depth > 1) {
          return false;
        }
      } else if (value.charAt(index) == ']' && --depth < 0) {
        return false;
      }
    }
    return depth == 0;
  }

  private static boolean isString(JsonElement value) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
  }

  private static CapabilityArgumentException failure(
      CapabilityArgumentException.Failure failure, String name) {
    return new CapabilityArgumentException(failure, name);
  }

  private sealed interface ArgumentCodec
      permits BooleanCodec, EnumCodec, IntegerCodec, MinecraftCodec, NumberCodec, StringCodec {
    CapabilityArgumentType type();

    String encode(JsonElement value, String name);
  }

  private record StringCodec(int minimum, int maximum) implements ArgumentCodec {
    @Override
    public CapabilityArgumentType type() {
      return CapabilityArgumentType.STRING;
    }

    @Override
    public String encode(JsonElement value, String name) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var string = value.getAsString();
      var length = string.codePointCount(0, string.length());
      if (length < minimum || length > maximum) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
      }
      if (!safeGenericToken(string)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      return string;
    }
  }

  private record IntegerCodec(int minimum, int maximum) implements ArgumentCodec {
    @Override
    public CapabilityArgumentType type() {
      return CapabilityArgumentType.INTEGER;
    }

    @Override
    public String encode(JsonElement value, String name) {
      return encodeInteger(value, name, minimum, maximum);
    }
  }

  private record NumberCodec(BigDecimal minimum, BigDecimal maximum) implements ArgumentCodec {
    @Override
    public CapabilityArgumentType type() {
      return CapabilityArgumentType.NUMBER;
    }

    @Override
    public String encode(JsonElement value, String name) {
      return encodeNumber(value, name, minimum, maximum);
    }
  }

  private record BooleanCodec() implements ArgumentCodec {
    @Override
    public CapabilityArgumentType type() {
      return CapabilityArgumentType.BOOLEAN;
    }

    @Override
    public String encode(JsonElement value, String name) {
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      return Boolean.toString(value.getAsBoolean());
    }
  }

  private record EnumCodec(List<String> allowed) implements ArgumentCodec {
    @Override
    public CapabilityArgumentType type() {
      return CapabilityArgumentType.ENUM;
    }

    @Override
    public String encode(JsonElement value, String name) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var string = value.getAsString();
      if (!allowed.contains(string)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID, name);
      }
      return string;
    }
  }

  private record MinecraftCodec(CapabilityArgumentType type) implements ArgumentCodec {
    @Override
    public String encode(JsonElement value, String name) {
      return switch (type) {
        case BLOCK_PATTERN -> encodeBlockPattern(value, name);
        case ITEM, DIMENSION -> encodeResourceLocation(value, name);
        case PLAYER -> encodePlayer(value, name);
        case COORDINATES -> encodeCoordinates(value, name);
        default -> throw new IllegalStateException("Non-Minecraft argument codec");
      };
    }

    private static String encodeBlockPattern(JsonElement value, String name) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var pattern = value.getAsString();
      if (!BLOCK_PATTERN.matcher(pattern).matches() || !balancedBlockState(pattern)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      return pattern;
    }

    private static String encodeResourceLocation(JsonElement value, String name) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var resource = value.getAsString();
      if (resource.length() > 256 || !RESOURCE_LOCATION.matcher(resource).matches()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      return resource;
    }

    private static String encodePlayer(JsonElement value, String name) {
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var player = value.getAsString();
      if (!PLAYER_NAME.matcher(player).matches()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      return player;
    }

    private static String encodeCoordinates(JsonElement value, String name) {
      if (value == null || !value.isJsonObject()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var coordinates = value.getAsJsonObject();
      if (!coordinates.keySet().equals(Set.of("x", "y", "z"))) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var x = coordinate(coordinates.get("x"), name, HORIZONTAL_COORDINATE_LIMIT);
      var y = coordinate(coordinates.get("y"), name, VERTICAL_COORDINATE_LIMIT);
      var z = coordinate(coordinates.get("z"), name, HORIZONTAL_COORDINATE_LIMIT);
      var anyLocal = x.startsWith("^") || y.startsWith("^") || z.startsWith("^");
      var allLocal = x.startsWith("^") && y.startsWith("^") && z.startsWith("^");
      if (anyLocal && !allLocal) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      return String.join(" ", x, y, z);
    }

    private static String coordinate(JsonElement value, String name, BigDecimal limit) {
      if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
        return encodeNumber(value, name, limit.negate(), limit);
      }
      if (!isString(value)) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID, name);
      }
      var coordinate = value.getAsString();
      if (coordinate.length() > MAX_TOKEN_CODE_POINTS
          || !RELATIVE_COORDINATE.matcher(coordinate).matches()) {
        throw failure(CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID, name);
      }
      var prefix = coordinate.substring(0, 1);
      if (coordinate.length() == 1) {
        return prefix;
      }
      var numeric = new JsonPrimitive(new BigDecimal(coordinate.substring(1)));
      var encoded = encodeNumber(numeric, name, limit.negate(), limit);
      return encoded.equals("0") ? prefix : prefix + encoded;
    }
  }
}
