package dev.minecraftagent.paper.capability.load;

import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import dev.minecraftagent.paper.capability.model.CapabilityManifest;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ArgumentDefinition;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.BooleanArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.Confirmation;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.Effects;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.EnumArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.Execution;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ExecutionSource;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.IntegerArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ManifestStatus;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.MinecraftArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.MinecraftArgumentType;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.NumberArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.Permission;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.PermissionMinimum;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.PluginRequirement;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.Reversibility;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ReversibilityType;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.RiskCategory;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.StringArgument;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed manual parser layered after SnakeYAML's safe construction. */
final class CapabilityManifestParser {
  private static final Pattern CAPABILITY_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
  private static final Pattern PLUGIN_NAME = Pattern.compile("^[A-Za-z0-9_.-]+$");
  private static final Pattern COMMAND_ROOT = Pattern.compile("^[a-z0-9:_-]+$");
  private static final Pattern ARGUMENT_NAME = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");
  private static final Pattern SCOPE = Pattern.compile("^[a-z][a-z0-9_.-]*$");
  private static final Pattern PERMISSION_NODE = Pattern.compile("^[a-z0-9_.-]+$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,63})}");
  private static final Pattern UNSAFE_TEMPLATE_LITERAL = Pattern.compile("[;|&\\\\'\"]");

  CapabilityManifest parse(Object loaded) throws ManifestException {
    var root =
        object(
            loaded,
            "manifest",
            Set.of(
                "id",
                "version",
                "description",
                "status",
                "requirements",
                "execution",
                "arguments",
                "effects",
                "permissions",
                "confirmation",
                "reversibility"),
            Set.of(
                "id",
                "version",
                "description",
                "requirements",
                "execution",
                "arguments",
                "effects",
                "permissions",
                "confirmation",
                "reversibility"));

    var id = string(root.get("id"), "id", 3, 128, CAPABILITY_ID);
    var version = integer(root.get("version"), "version", 1, Integer.MAX_VALUE);
    var description = string(root.get("description"), "description", 1, 1024, null);
    var status = parseStatus(root.get("status"), root.containsKey("status"));
    var requirements = parseRequirements(root.get("requirements"));
    var execution = parseExecution(root.get("execution"));
    var arguments = parseArguments(root.get("arguments"));
    validateTemplate(execution, arguments.keySet());
    var effects = parseEffects(root.get("effects"));
    var permission = parsePermission(root.get("permissions"));
    var confirmation = parseConfirmation(root.get("confirmation"));
    var reversibility = parseReversibility(root.get("reversibility"), id);
    validatePolicy(effects, permission, confirmation);

    return new CapabilityManifest(
        id,
        version,
        description,
        status,
        requirements,
        execution,
        arguments,
        effects,
        permission,
        confirmation,
        reversibility);
  }

  private Optional<ManifestStatus> parseStatus(Object value, boolean present)
      throws ManifestException {
    if (!present) {
      return Optional.empty();
    }
    return Optional.of(
        switch (string(value, "status", 5, 7, null)) {
          case "example" -> ManifestStatus.EXAMPLE;
          case "draft" -> ManifestStatus.DRAFT;
          default -> throw value("status");
        });
  }

  private List<PluginRequirement> parseRequirements(Object value) throws ManifestException {
    var requirements = object(value, "requirements", Set.of("plugins"), Set.of("plugins"));
    var plugins = list(requirements.get("plugins"), "requirements.plugins", 0, 32);
    var result = new ArrayList<PluginRequirement>(plugins.size());
    var names = new HashSet<String>();
    for (var index = 0; index < plugins.size(); index++) {
      var field = "requirements.plugins[" + index + "]";
      var plugin =
          object(plugins.get(index), field, Set.of("name", "version"), Set.of("name", "version"));
      var name = string(plugin.get("name"), field + ".name", 1, 64, PLUGIN_NAME);
      if (!names.add(name.toLowerCase(Locale.ROOT))) {
        throw value("requirements.plugins");
      }
      var versionRange = string(plugin.get("version"), field + ".version", 1, 128, null);
      try {
        DeterministicVersionRange.parse(versionRange);
      } catch (IllegalArgumentException exception) {
        throw new ManifestException(
            CapabilityDiagnostic.Code.PLUGIN_VERSION_RANGE_INVALID, field + ".version");
      }
      result.add(new PluginRequirement(name, versionRange));
    }
    return List.copyOf(result);
  }

  private Execution parseExecution(Object value) throws ManifestException {
    var execution =
        object(
            value,
            "execution",
            Set.of("type", "source", "commandRoot", "template"),
            Set.of("type", "source", "commandRoot", "template"));
    if (!"command".equals(string(execution.get("type"), "execution.type", 7, 7, null))) {
      throw value("execution.type");
    }
    var source =
        switch (string(execution.get("source"), "execution.source", 6, 7, null)) {
          case "player" -> ExecutionSource.PLAYER;
          case "console" -> ExecutionSource.CONSOLE;
          default -> throw value("execution.source");
        };
    var root = string(execution.get("commandRoot"), "execution.commandRoot", 1, 64, COMMAND_ROOT);
    var template = string(execution.get("template"), "execution.template", 2, 1024, null);
    return new Execution(source, root, template);
  }

  private Map<String, ArgumentDefinition> parseArguments(Object value) throws ManifestException {
    if (!(value instanceof Map<?, ?> raw)) {
      throw structure("arguments");
    }
    if (raw.size() > 64) {
      throw value("arguments");
    }
    var result = new LinkedHashMap<String, ArgumentDefinition>();
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !ARGUMENT_NAME.matcher(name).matches()) {
        throw value("arguments");
      }
      result.put(name, parseArgument(entry.getValue(), "arguments." + name));
    }
    return Map.copyOf(result);
  }

  private ArgumentDefinition parseArgument(Object value, String field) throws ManifestException {
    if (!(value instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
      throw structure(field);
    }
    return switch (type) {
      case "string" -> parseStringArgument(value, field);
      case "integer" -> parseIntegerArgument(value, field);
      case "number" -> parseNumberArgument(value, field);
      case "boolean" -> parseBooleanArgument(value, field);
      case "enum" -> parseEnumArgument(value, field);
      case "minecraft:block-pattern",
          "minecraft:item",
          "minecraft:player",
          "minecraft:dimension",
          "minecraft:coordinates" ->
          parseMinecraftArgument(value, field, type);
      default -> throw value(field + ".type");
    };
  }

  private StringArgument parseStringArgument(Object value, String field) throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required", "minLength", "maxLength"),
            Set.of("type", "description", "required", "minLength", "maxLength"));
    requireType(argument, field, "string");
    var minimum = integer(argument.get("minLength"), field + ".minLength", 0, 1024);
    var maximum = integer(argument.get("maxLength"), field + ".maxLength", 1, 1024);
    if (minimum > maximum) {
      throw value(field);
    }
    return new StringArgument(
        description(argument, field), required(argument, field), minimum, maximum);
  }

  private IntegerArgument parseIntegerArgument(Object value, String field)
      throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required", "minimum", "maximum"),
            Set.of("type", "description", "required", "minimum", "maximum"));
    requireType(argument, field, "integer");
    var minimum =
        integer(argument.get("minimum"), field + ".minimum", Integer.MIN_VALUE, Integer.MAX_VALUE);
    var maximum =
        integer(argument.get("maximum"), field + ".maximum", Integer.MIN_VALUE, Integer.MAX_VALUE);
    if (minimum > maximum) {
      throw value(field);
    }
    return new IntegerArgument(
        description(argument, field), required(argument, field), minimum, maximum);
  }

  private NumberArgument parseNumberArgument(Object value, String field) throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required", "minimum", "maximum"),
            Set.of("type", "description", "required", "minimum", "maximum"));
    requireType(argument, field, "number");
    var minimum = decimal(argument.get("minimum"), field + ".minimum");
    var maximum = decimal(argument.get("maximum"), field + ".maximum");
    if (minimum.compareTo(maximum) > 0) {
      throw value(field);
    }
    return new NumberArgument(
        description(argument, field), required(argument, field), minimum, maximum);
  }

  private BooleanArgument parseBooleanArgument(Object value, String field)
      throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required"),
            Set.of("type", "description", "required"));
    requireType(argument, field, "boolean");
    return new BooleanArgument(description(argument, field), required(argument, field));
  }

  private EnumArgument parseEnumArgument(Object value, String field) throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required", "values"),
            Set.of("type", "description", "required", "values"));
    requireType(argument, field, "enum");
    var rawValues = list(argument.get("values"), field + ".values", 1, 128);
    var values = new ArrayList<String>(rawValues.size());
    var unique = new HashSet<String>();
    for (var index = 0; index < rawValues.size(); index++) {
      var parsed = string(rawValues.get(index), field + ".values[" + index + "]", 1, 128, null);
      if (!safeGenericToken(parsed) || !unique.add(parsed)) {
        throw value(field + ".values");
      }
      values.add(parsed);
    }
    return new EnumArgument(description(argument, field), required(argument, field), values);
  }

  private MinecraftArgument parseMinecraftArgument(Object value, String field, String type)
      throws ManifestException {
    var argument =
        object(
            value,
            field,
            Set.of("type", "description", "required"),
            Set.of("type", "description", "required"));
    requireType(argument, field, type);
    var parsedType =
        switch (type) {
          case "minecraft:block-pattern" -> MinecraftArgumentType.BLOCK_PATTERN;
          case "minecraft:item" -> MinecraftArgumentType.ITEM;
          case "minecraft:player" -> MinecraftArgumentType.PLAYER;
          case "minecraft:dimension" -> MinecraftArgumentType.DIMENSION;
          case "minecraft:coordinates" -> MinecraftArgumentType.COORDINATES;
          default -> throw value(field + ".type");
        };
    return new MinecraftArgument(
        parsedType, description(argument, field), required(argument, field));
  }

  private Effects parseEffects(Object value) throws ManifestException {
    var effects =
        object(
            value,
            "effects",
            Set.of("category", "scope", "maximumBlocks"),
            Set.of("category", "scope", "maximumBlocks"));
    final RiskCategory category;
    try {
      category =
          RiskCategory.valueOf(string(effects.get("category"), "effects.category", 4, 15, null));
    } catch (IllegalArgumentException exception) {
      throw value("effects.category");
    }
    var scope = string(effects.get("scope"), "effects.scope", 1, 128, SCOPE);
    var maximumBlocks =
        effects.get("maximumBlocks") == null
            ? Optional.<Integer>empty()
            : Optional.of(
                integer(effects.get("maximumBlocks"), "effects.maximumBlocks", 1, 250_000));
    return new Effects(category, scope, maximumBlocks);
  }

  private Permission parsePermission(Object value) throws ManifestException {
    if (!(value instanceof Map<?, ?> raw) || !(raw.get("minimum") instanceof String minimum)) {
      throw structure("permissions");
    }
    if ("PERMISSION".equals(minimum)) {
      var permission =
          object(value, "permissions", Set.of("minimum", "node"), Set.of("minimum", "node"));
      return new Permission(
          PermissionMinimum.PERMISSION,
          Optional.of(string(permission.get("node"), "permissions.node", 3, 128, PERMISSION_NODE)));
    }
    var permission = object(value, "permissions", Set.of("minimum"), Set.of("minimum"));
    final PermissionMinimum parsed;
    try {
      parsed = PermissionMinimum.valueOf(minimum);
    } catch (IllegalArgumentException exception) {
      throw value("permissions.minimum");
    }
    if (parsed == PermissionMinimum.PERMISSION) {
      throw structure("permissions.node");
    }
    return new Permission(parsed, Optional.empty());
  }

  private Confirmation parseConfirmation(Object value) throws ManifestException {
    var confirmation = object(value, "confirmation", Set.of("required"), Set.of("required"));
    return new Confirmation(bool(confirmation.get("required"), "confirmation.required"));
  }

  private Reversibility parseReversibility(Object value, String id) throws ManifestException {
    if (!(value instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)) {
      throw structure("reversibility");
    }
    if ("none".equals(type)) {
      object(value, "reversibility", Set.of("type"), Set.of("type"));
      return new Reversibility(ReversibilityType.NONE, Optional.empty());
    }
    if (!"capability".equals(type)) {
      throw value("reversibility.type");
    }
    var reversibility =
        object(value, "reversibility", Set.of("type", "capability"), Set.of("type", "capability"));
    var target =
        string(reversibility.get("capability"), "reversibility.capability", 3, 128, CAPABILITY_ID);
    if (id.equals(target)) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.REVERSAL_TARGET_SELF, "reversibility.capability");
    }
    return new Reversibility(ReversibilityType.CAPABILITY, Optional.of(target));
  }

  private void validateTemplate(Execution execution, Set<String> arguments)
      throws ManifestException {
    var template = execution.template();
    if (!template.equals(template.strip())
        || template.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint > 0x7e)
        || template.contains("  ")
        || !(template.equals("/" + execution.commandRoot())
            || template.startsWith("/" + execution.commandRoot() + " "))) {
      throw new ManifestException(CapabilityDiagnostic.Code.TEMPLATE_INVALID, "execution.template");
    }

    var counts = new LinkedHashMap<String, Integer>();
    var matcher = PLACEHOLDER.matcher(template);
    var withoutPlaceholders = new StringBuffer();
    while (matcher.find()) {
      if ((matcher.start() > 0 && template.charAt(matcher.start() - 1) != ' ')
          || (matcher.end() < template.length() && template.charAt(matcher.end()) != ' ')) {
        throw new ManifestException(
            CapabilityDiagnostic.Code.TEMPLATE_INVALID, "execution.template");
      }
      counts.merge(matcher.group(1), 1, Integer::sum);
      matcher.appendReplacement(withoutPlaceholders, "");
    }
    matcher.appendTail(withoutPlaceholders);
    if (withoutPlaceholders.indexOf("{") >= 0
        || withoutPlaceholders.indexOf("}") >= 0
        || UNSAFE_TEMPLATE_LITERAL.matcher(withoutPlaceholders).find()) {
      throw new ManifestException(CapabilityDiagnostic.Code.TEMPLATE_INVALID, "execution.template");
    }
    if (!counts.keySet().equals(arguments)
        || counts.values().stream().anyMatch(count -> count != 1)) {
      throw new ManifestException(CapabilityDiagnostic.Code.TEMPLATE_INVALID, "execution.template");
    }
  }

  private void validatePolicy(Effects effects, Permission permission, Confirmation confirmation)
      throws ManifestException {
    if (effects.category() != RiskCategory.READ && !confirmation.required()) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, "confirmation.required");
    }
    if (effects.category() != RiskCategory.READ && permission.minimum() == PermissionMinimum.ANY) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, "permissions.minimum");
    }
    if (effects.category() == RiskCategory.SERVER_ADMIN
        && permission.minimum() != PermissionMinimum.OWNER) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, "permissions.minimum");
    }
    if (effects.category() != RiskCategory.WRITE_WORLD && effects.maximumBlocks().isPresent()) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, "effects.maximumBlocks");
    }
    if (effects.category() == RiskCategory.WRITE_WORLD && effects.maximumBlocks().isEmpty()) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, "effects.maximumBlocks");
    }
  }

  private void requireType(Map<String, Object> value, String field, String expected)
      throws ManifestException {
    if (!expected.equals(string(value.get("type"), field + ".type", 1, 32, null))) {
      throw value(field + ".type");
    }
  }

  private String description(Map<String, Object> value, String field) throws ManifestException {
    return string(value.get("description"), field + ".description", 1, 512, null);
  }

  private boolean required(Map<String, Object> value, String field) throws ManifestException {
    if (!bool(value.get("required"), field + ".required")) {
      throw new ManifestException(
          CapabilityDiagnostic.Code.POLICY_INCONSISTENT, field + ".required");
    }
    return true;
  }

  private boolean safeGenericToken(String value) {
    if (!Normalizer.normalize(value, Normalizer.Form.NFKC).equals(value)) {
      return false;
    }
    return value
        .codePoints()
        .allMatch(
            codePoint ->
                Character.isLetterOrDigit(codePoint)
                    || (codePoint < 128 && "._:+-".indexOf((char) codePoint) >= 0));
  }

  private Map<String, Object> object(
      Object value, String field, Set<String> allowed, Set<String> required)
      throws ManifestException {
    if (!(value instanceof Map<?, ?> raw)) {
      throw structure(field);
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key) || !allowed.contains(key)) {
        throw structure(field);
      }
      result.put(key, entry.getValue());
    }
    if (!result.keySet().containsAll(required)) {
      throw structure(field);
    }
    return result;
  }

  private List<?> list(Object value, String field, int minimum, int maximum)
      throws ManifestException {
    if (!(value instanceof List<?> list) || list.size() < minimum || list.size() > maximum) {
      throw value(field);
    }
    return list;
  }

  private String string(Object value, String field, int minimum, int maximum, Pattern pattern)
      throws ManifestException {
    if (!(value instanceof String string)) {
      throw structure(field);
    }
    var length = string.codePointCount(0, string.length());
    if (length < minimum
        || length > maximum
        || string.codePoints().anyMatch(Character::isISOControl)
        || (pattern != null && !pattern.matcher(string).matches())) {
      throw value(field);
    }
    return string;
  }

  private boolean bool(Object value, String field) throws ManifestException {
    if (!(value instanceof Boolean bool)) {
      throw structure(field);
    }
    return bool;
  }

  private int integer(Object value, String field, int minimum, int maximum)
      throws ManifestException {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger)) {
      throw structure(field);
    }
    final BigInteger parsed;
    try {
      parsed = new BigInteger(value.toString());
    } catch (NumberFormatException exception) {
      throw value(field);
    }
    if (parsed.compareTo(BigInteger.valueOf(minimum)) < 0
        || parsed.compareTo(BigInteger.valueOf(maximum)) > 0) {
      throw value(field);
    }
    return parsed.intValueExact();
  }

  private BigDecimal decimal(Object value, String field) throws ManifestException {
    if (!(value instanceof Number)) {
      throw structure(field);
    }
    final BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.toString());
    } catch (NumberFormatException exception) {
      throw value(field);
    }
    var normalized = parsed.signum() == 0 ? BigDecimal.ZERO : parsed.stripTrailingZeros();
    if (normalized.precision() > 1024
        || normalized.scale() < -1024
        || normalized.scale() > 1024
        || !Double.isFinite(normalized.doubleValue())) {
      throw value(field);
    }
    try {
      return CapabilityCanonicalizer.exactJcsNumber(normalized);
    } catch (IllegalArgumentException exception) {
      throw value(field);
    }
  }

  private ManifestException structure(String field) {
    return new ManifestException(CapabilityDiagnostic.Code.MANIFEST_STRUCTURE_INVALID, field);
  }

  private ManifestException value(String field) {
    return new ManifestException(CapabilityDiagnostic.Code.MANIFEST_VALUE_INVALID, field);
  }

  static final class ManifestException extends Exception {
    private final CapabilityDiagnostic.Code code;
    private final String field;

    ManifestException(CapabilityDiagnostic.Code code, String field) {
      this.code = code;
      this.field = field;
    }

    CapabilityDiagnostic diagnostic() {
      return new CapabilityDiagnostic(code, field);
    }
  }
}
