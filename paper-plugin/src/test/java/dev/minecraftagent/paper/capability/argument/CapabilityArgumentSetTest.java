package dev.minecraftagent.paper.capability.argument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CapabilityArgumentSetTest {
  @Test
  void encodesEveryClosedTypeToItsCanonicalRepresentation() {
    var descriptors = new JsonObject();
    descriptors.add("text", stringDescriptor(1, 16));
    descriptors.add("count", integerDescriptor(-10, 10));
    descriptors.add("ratio", numberDescriptor("-100", "100"));
    descriptors.add("enabled", descriptor("boolean"));
    descriptors.add("mode", enumDescriptor("survival", "creative"));
    descriptors.add("blocks", descriptor("minecraft:block-pattern"));
    descriptors.add("item", descriptor("minecraft:item"));
    descriptors.add("player", descriptor("minecraft:player"));
    descriptors.add("dimension", descriptor("minecraft:dimension"));
    descriptors.add("position", descriptor("minecraft:coordinates"));

    var values = new JsonObject();
    values.addProperty("text", "\u6751\u5e84");
    values.add("count", new JsonPrimitive(new BigDecimal("1.0")));
    values.add("ratio", new JsonPrimitive(new BigDecimal("12.3400")));
    values.addProperty("enabled", true);
    values.addProperty("mode", "creative");
    values.addProperty("blocks", "50%minecraft:stone,50%minecraft:dirt");
    values.addProperty("item", "minecraft:diamond_sword");
    values.addProperty("player", "Builder_1");
    values.addProperty("dimension", "minecraft:the_nether");
    values.add("position", coordinates(new BigDecimal("-0.0"), "~1.50", "~-0"));

    var compiled = CapabilityArgumentSet.compile(descriptors);

    assertEquals(
        Map.ofEntries(
            Map.entry("text", "\u6751\u5e84"),
            Map.entry("count", "1"),
            Map.entry("ratio", "12.34"),
            Map.entry("enabled", "true"),
            Map.entry("mode", "creative"),
            Map.entry("blocks", "50%minecraft:stone,50%minecraft:dirt"),
            Map.entry("item", "minecraft:diamond_sword"),
            Map.entry("player", "Builder_1"),
            Map.entry("dimension", "minecraft:the_nether"),
            Map.entry("position", "0 ~1.5 ~")),
        compiled.encode(values));
    assertEquals(CapabilityArgumentType.COORDINATES, compiled.types().get("position"));
    assertThrows(UnsupportedOperationException.class, () -> compiled.types().clear());
  }

  @Test
  void rejectsMissingNullAndUndeclaredValues() {
    var arguments = CapabilityArgumentSet.compile(single("text", stringDescriptor(1, 16)));

    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_MISSING,
        "text",
        () -> arguments.encode(new JsonObject()));

    var nullValue = new JsonObject();
    nullValue.add("text", null);
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_MISSING,
        "text",
        () -> arguments.encode(nullValue));

    var extraValue = new JsonObject();
    extraValue.addProperty("text", "safe");
    extraValue.addProperty("extra", "safe");
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_UNDECLARED,
        "extra",
        () -> arguments.encode(extraValue));

    var unsafeName = new JsonObject();
    unsafeName.addProperty("text", "safe");
    unsafeName.addProperty("bad\nname", "safe");
    var error = assertThrows(CapabilityArgumentException.class, () -> arguments.encode(unsafeName));
    assertEquals(CapabilityArgumentException.Failure.ARGUMENT_UNDECLARED, error.failure());
    assertTrue(error.argumentName().isEmpty());
    assertTrue(error.getMessage().indexOf('\n') < 0);
  }

  @Test
  void rejectsTokenBreakingAndAmbiguousStrings() {
    var arguments = CapabilityArgumentSet.compile(single("text", stringDescriptor(1, 64)));
    var unsafe =
        new String[] {
          "two words",
          "line\nop",
          "carriage\rreturn",
          "semi;op",
          "quoted\"value",
          "single'value",
          "back\\slash",
          "pipe|value",
          "selector@a",
          "\uff4f\uff50",
          "e\u0301",
          "abc\u202edef"
        };

    for (var value : unsafe) {
      var supplied = new JsonObject();
      supplied.addProperty("text", value);
      assertFailure(
          CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
          "text",
          () -> arguments.encode(supplied));
    }

    var safeUnicode = new JsonObject();
    safeUnicode.addProperty("text", "\u00e9\u6751");
    assertEquals("\u00e9\u6751", arguments.encode(safeUnicode).get("text"));
  }

  @Test
  void enforcesIntegerAndFiniteNumberBounds() {
    var descriptors = new JsonObject();
    descriptors.add("integer", integerDescriptor(Integer.MIN_VALUE, Integer.MAX_VALUE));
    descriptors.add("number", numberDescriptor("-1.5", "1.5"));
    var arguments = CapabilityArgumentSet.compile(descriptors);

    var boundary = new JsonObject();
    boundary.addProperty("integer", Integer.MAX_VALUE);
    boundary.add("number", new JsonPrimitive(new BigDecimal("-1.5000")));
    assertEquals("2147483647", arguments.encode(boundary).get("integer"));
    assertEquals("-1.5", arguments.encode(boundary).get("number"));

    var nonInteger = boundary.deepCopy();
    nonInteger.addProperty("integer", 1.5);
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID,
        "integer",
        () -> arguments.encode(nonInteger));

    var integerOverflow = boundary.deepCopy();
    integerOverflow.add("integer", new JsonPrimitive(new BigDecimal("2147483648")));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID,
        "integer",
        () -> arguments.encode(integerOverflow));

    var numberOverflow = boundary.deepCopy();
    numberOverflow.add("number", new JsonPrimitive(new BigDecimal("1.50001")));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID,
        "number",
        () -> arguments.encode(numberOverflow));

    var notFinite = boundary.deepCopy();
    notFinite.add("number", new JsonPrimitive(Double.NaN));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID,
        "number",
        () -> arguments.encode(notFinite));
  }

  @Test
  void rejectsUnsafeDescriptorsAndOptionalV1ArgumentsExplicitly() {
    var optional = stringDescriptor(1, 8);
    optional.addProperty("required", false);
    assertFailure(
        CapabilityArgumentException.Failure.OPTIONAL_ARGUMENT_UNSUPPORTED,
        "text",
        () -> CapabilityArgumentSet.compile(single("text", optional)));

    var extraField = stringDescriptor(1, 8);
    extraField.addProperty("pattern", ".*");
    assertFailure(
        CapabilityArgumentException.Failure.DESCRIPTOR_INVALID,
        "text",
        () -> CapabilityArgumentSet.compile(single("text", extraField)));

    assertFailure(
        CapabilityArgumentException.Failure.DESCRIPTOR_INVALID,
        "text",
        () -> CapabilityArgumentSet.compile(single("text", stringDescriptor(9, 8))));

    var unsafeEnum = enumDescriptor("safe", "bad;op");
    assertFailure(
        CapabilityArgumentException.Failure.DESCRIPTOR_INVALID,
        "mode",
        () -> CapabilityArgumentSet.compile(single("mode", unsafeEnum)));

    assertFailure(
        CapabilityArgumentException.Failure.DESCRIPTOR_INVALID,
        "text",
        () -> CapabilityArgumentSet.compile(single("text", descriptor("unknown"))));
  }

  @Test
  void validatesMinecraftResourcePlayerAndBlockPatternTokens() {
    var descriptors = new JsonObject();
    descriptors.add("blocks", descriptor("minecraft:block-pattern"));
    descriptors.add("item", descriptor("minecraft:item"));
    descriptors.add("player", descriptor("minecraft:player"));
    descriptors.add("dimension", descriptor("minecraft:dimension"));
    var arguments = CapabilityArgumentSet.compile(descriptors);
    var valid = new JsonObject();
    valid.addProperty("blocks", "minecraft:oak_log[axis=y]");
    valid.addProperty("item", "example:tools/hammer");
    valid.addProperty("player", "A_Valid_Name");
    valid.addProperty("dimension", "example:moon/base");
    assertEquals("minecraft:oak_log[axis=y]", arguments.encode(valid).get("blocks"));

    for (var badBlock :
        new String[] {
          "minecraft:stone;op", "minecraft:stone\\evil", "minecraft:stone[axis=y", "Stone"
        }) {
      var values = valid.deepCopy();
      values.addProperty("blocks", badBlock);
      assertFailure(
          CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
          "blocks",
          () -> arguments.encode(values));
    }

    var selector = valid.deepCopy();
    selector.addProperty("player", "@a");
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
        "player",
        () -> arguments.encode(selector));

    var defaultNamespace = valid.deepCopy();
    defaultNamespace.addProperty("item", "stone");
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
        "item",
        () -> arguments.encode(defaultNamespace));

    var uppercaseDimension = valid.deepCopy();
    uppercaseDimension.addProperty("dimension", "Minecraft:overworld");
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
        "dimension",
        () -> arguments.encode(uppercaseDimension));
  }

  @Test
  void validatesAbsoluteRelativeAndLocalCoordinateObjects() {
    var arguments =
        CapabilityArgumentSet.compile(single("position", descriptor("minecraft:coordinates")));

    var local = new JsonObject();
    local.add("position", coordinates("^", "^1.0", "^-2.50"));
    assertEquals("^ ^1 ^-2.5", arguments.encode(local).get("position"));

    var edge = new JsonObject();
    edge.add(
        "position",
        coordinates(
            new BigDecimal("-30000000"), new BigDecimal("2048"), new BigDecimal("30000000")));
    assertEquals("-30000000 2048 30000000", arguments.encode(edge).get("position"));

    var mixedLocal = new JsonObject();
    mixedLocal.add("position", coordinates("^", "~", "^1"));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
        "position",
        () -> arguments.encode(mixedLocal));

    var outOfRange = new JsonObject();
    outOfRange.add("position", coordinates(0, 2049, 0));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_RANGE_INVALID,
        "position",
        () -> arguments.encode(outOfRange));

    var extraAxis = coordinates(0, 64, 0);
    extraAxis.addProperty("yaw", 90);
    var extra = new JsonObject();
    extra.add("position", extraAxis);
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TYPE_INVALID,
        "position",
        () -> arguments.encode(extra));

    var malformed = new JsonObject();
    malformed.add("position", coordinates("~+1", "~", "~"));
    assertFailure(
        CapabilityArgumentException.Failure.ARGUMENT_TOKEN_INVALID,
        "position",
        () -> arguments.encode(malformed));
  }

  private static JsonObject descriptor(String type) {
    var result = new JsonObject();
    result.addProperty("type", type);
    result.addProperty("description", "Test argument.");
    result.addProperty("required", true);
    return result;
  }

  private static JsonObject stringDescriptor(int minimum, int maximum) {
    var result = descriptor("string");
    result.addProperty("minLength", minimum);
    result.addProperty("maxLength", maximum);
    return result;
  }

  private static JsonObject integerDescriptor(int minimum, int maximum) {
    var result = descriptor("integer");
    result.addProperty("minimum", minimum);
    result.addProperty("maximum", maximum);
    return result;
  }

  private static JsonObject numberDescriptor(String minimum, String maximum) {
    var result = descriptor("number");
    result.add("minimum", new JsonPrimitive(new BigDecimal(minimum)));
    result.add("maximum", new JsonPrimitive(new BigDecimal(maximum)));
    return result;
  }

  private static JsonObject enumDescriptor(String... values) {
    var result = descriptor("enum");
    var allowed = new JsonArray();
    for (var value : values) {
      allowed.add(value);
    }
    result.add("values", allowed);
    return result;
  }

  private static JsonObject single(String name, JsonObject descriptor) {
    var result = new JsonObject();
    result.add(name, descriptor);
    return result;
  }

  private static JsonObject coordinates(Object x, Object y, Object z) {
    var result = new JsonObject();
    addCoordinate(result, "x", x);
    addCoordinate(result, "y", y);
    addCoordinate(result, "z", z);
    return result;
  }

  private static void addCoordinate(JsonObject object, String name, Object value) {
    if (value instanceof String string) {
      object.addProperty(name, string);
    } else if (value instanceof Number number) {
      object.add(name, new JsonPrimitive(number));
    } else {
      throw new IllegalArgumentException("Unsupported test coordinate");
    }
  }

  private static void assertFailure(
      CapabilityArgumentException.Failure failure, String name, Runnable action) {
    var error = assertThrows(CapabilityArgumentException.class, action::run);
    assertEquals(failure, error.failure());
    assertEquals(name, error.argumentName().orElseThrow());
    assertTrue(error.getMessage().indexOf('\n') < 0);
  }
}
