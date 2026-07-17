package dev.minecraftagent.paper.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReadToolRegistry {
  public static final Set<String> REQUIRED_TOOL_IDS =
      Set.of(
          "player.context.read",
          "player.held_item.read",
          "server.info.read",
          "server.plugins.list",
          "server.recipe.lookup",
          "server.recipe.uses",
          "landmark.search",
          "build.preview.create");

  private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]{1,240}");
  private final Map<String, Descriptor> descriptors;

  public ReadToolRegistry() {
    var entries = new LinkedHashMap<String, Descriptor>();
    register(
        entries,
        "player.context.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(
            AgentModule.GENERAL,
            AgentModule.GUIDE,
            AgentModule.LOCATE,
            AgentModule.BUILD,
            AgentModule.PROJECT));
    register(
        entries,
        "player.held_item.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE, AgentModule.BUILD));
    register(
        entries,
        "server.info.read",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(
            AgentModule.GENERAL,
            AgentModule.GUIDE,
            AgentModule.LOCATE,
            AgentModule.BUILD,
            AgentModule.PROJECT));
    register(
        entries,
        "server.plugins.list",
        ArgumentKind.NONE,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.GENERAL, AgentModule.GUIDE, AgentModule.PROJECT));
    register(
        entries,
        "server.recipe.lookup",
        ArgumentKind.ITEM_ID,
        ReadToolResult.Source.SERVER_REGISTRY,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE));
    register(
        entries,
        "server.recipe.uses",
        ArgumentKind.ITEM_ID,
        ReadToolResult.Source.SERVER_REGISTRY,
        Set.of(AgentModule.GENERAL, AgentModule.RECIPE, AgentModule.GUIDE));
    register(
        entries,
        "landmark.search",
        ArgumentKind.QUERY,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.LOCATE, AgentModule.BUILD));
    register(
        entries,
        "build.preview.create",
        ArgumentKind.BUILD_PLAN,
        ReadToolResult.Source.PAPER_API,
        Set.of(AgentModule.BUILD));
    if (!entries.keySet().equals(REQUIRED_TOOL_IDS)) {
      throw new IllegalStateException("Core read tool registry is incomplete");
    }
    descriptors = Map.copyOf(entries);
  }

  public List<Descriptor> descriptors() {
    return descriptors.values().stream()
        .sorted(java.util.Comparator.comparing(Descriptor::id))
        .toList();
  }

  public Optional<Descriptor> find(String id) {
    return Optional.ofNullable(descriptors.get(id));
  }

  public Validation validate(String id, AgentModule module, JsonObject arguments) {
    Objects.requireNonNull(module);
    Objects.requireNonNull(arguments);
    var descriptor = descriptors.get(id);
    if (descriptor == null) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_UNKNOWN",
              "The requested tool is not registered."));
    }
    if (!descriptor.modules().contains(module)) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_NOT_ALLOWED",
              "The requested tool is not allowed for this module."));
    }
    if (!validArguments(descriptor.argumentKind(), arguments)) {
      return Validation.rejected(
          ReadToolResult.rejected(
              ReadToolResult.Source.PAPER_POLICY,
              "TOOL_ARGUMENTS_INVALID",
              "The tool arguments do not match its closed schema."));
    }
    return Validation.accepted(descriptor);
  }

  private static boolean validArguments(ArgumentKind kind, JsonObject arguments) {
    if (kind == ArgumentKind.NONE) {
      return arguments.isEmpty();
    }
    if (kind == ArgumentKind.BUILD_PLAN) {
      return validBuildPlan(arguments);
    }
    var key = kind == ArgumentKind.ITEM_ID ? "itemId" : "query";
    if (!arguments.keySet().equals(Set.of(key))) {
      return false;
    }
    JsonElement value = arguments.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      return false;
    }
    var text = value.getAsString();
    if (kind == ArgumentKind.ITEM_ID) {
      return text.length() <= 256 && ITEM_ID.matcher(text).matches();
    }
    return text.length() <= 128
        && !text.isBlank()
        && Normalizer.normalize(text, Normalizer.Form.NFKC).equals(text)
        && text.codePoints().noneMatch(ReadToolRegistry::unsafeQueryCodePoint);
  }

  private static boolean validBuildPlan(JsonObject arguments) {
    if (!arguments
        .keySet()
        .equals(
            Set.of(
                "projectId",
                "revision",
                "operation",
                "dimension",
                "bounds",
                "origin",
                "pattern",
                "blockState",
                "rotation",
                "mirror"))) {
      return false;
    }
    var projectId = stringValue(arguments.get("projectId"));
    var dimension = stringValue(arguments.get("dimension"));
    var operation = stringValue(arguments.get("operation"));
    var pattern = stringValue(arguments.get("pattern"));
    var mirror = stringValue(arguments.get("mirror"));
    var revision = exactInteger(arguments.get("revision"));
    var rotation = exactInteger(arguments.get("rotation"));
    if (projectId == null
        || !canonicalUuid(projectId)
        || dimension == null
        || !ITEM_ID.matcher(dimension).matches()
        || !Set.of("create", "modify").contains(operation)
        || !Set.of("solid", "hollow", "walls", "floor", "clear").contains(pattern)
        || !Set.of("NONE", "LEFT_RIGHT", "FRONT_BACK").contains(mirror)
        || revision == null
        || revision < 1
        || !Set.of(0, 90, 180, 270).contains(rotation)) {
      return false;
    }
    var state = arguments.get("blockState");
    if (state == null
        || (!state.isJsonNull()
            && (stringValue(state) == null || stringValue(state).length() > 512))
        || ("clear".equals(pattern) != state.isJsonNull())) {
      return false;
    }
    var bounds = objectValue(arguments.get("bounds"));
    var origin = position(arguments.get("origin"));
    if (bounds == null || !bounds.keySet().equals(Set.of("min", "max")) || origin == null) {
      return false;
    }
    var minimum = position(bounds.get("min"));
    var maximum = position(bounds.get("max"));
    if (minimum == null
        || maximum == null
        || minimum[0] > maximum[0]
        || minimum[1] > maximum[1]
        || minimum[2] > maximum[2]) {
      return false;
    }
    var x = (long) maximum[0] - minimum[0] + 1;
    var y = (long) maximum[1] - minimum[1] + 1;
    var z = (long) maximum[2] - minimum[2] + 1;
    return x <= 32
        && y <= 32
        && z <= 32
        && x * y * z <= 4096
        && origin[0] >= minimum[0]
        && origin[0] <= maximum[0]
        && origin[1] >= minimum[1]
        && origin[1] <= maximum[1]
        && origin[2] >= minimum[2]
        && origin[2] <= maximum[2];
  }

  private static int[] position(JsonElement value) {
    var object = objectValue(value);
    if (object == null || !object.keySet().equals(Set.of("x", "y", "z"))) {
      return null;
    }
    var x = exactInteger(object.get("x"));
    var y = exactInteger(object.get("y"));
    var z = exactInteger(object.get("z"));
    if (x == null
        || y == null
        || z == null
        || x < -30_000_000
        || x > 30_000_000
        || y < -2048
        || y > 2048
        || z < -30_000_000
        || z > 30_000_000) {
      return null;
    }
    return new int[] {x, y, z};
  }

  private static JsonObject objectValue(JsonElement value) {
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String stringValue(JsonElement value) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : null;
  }

  private static Integer exactInteger(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    try {
      return value.getAsBigDecimal().intValueExact();
    } catch (ArithmeticException | NumberFormatException error) {
      return null;
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      return java.util.UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static boolean unsafeQueryCodePoint(int codePoint) {
    return codePoint <= 0x1f
        || (codePoint >= 0x7f && codePoint <= 0x9f)
        || codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || (codePoint >= 0x202a && codePoint <= 0x202e)
        || (codePoint >= 0x2066 && codePoint <= 0x2069);
  }

  private static void register(
      Map<String, Descriptor> target,
      String id,
      ArgumentKind argumentKind,
      ReadToolResult.Source source,
      Set<AgentModule> modules) {
    var descriptor = new Descriptor(id, argumentKind, source, modules);
    if (target.putIfAbsent(id, descriptor) != null) {
      throw new IllegalStateException("Duplicate read tool descriptor");
    }
  }

  public enum ArgumentKind {
    NONE,
    ITEM_ID,
    QUERY,
    BUILD_PLAN
  }

  public record Descriptor(
      String id,
      ArgumentKind argumentKind,
      ReadToolResult.Source source,
      Set<AgentModule> modules) {
    public Descriptor {
      Objects.requireNonNull(id);
      Objects.requireNonNull(argumentKind);
      Objects.requireNonNull(source);
      modules = Set.copyOf(modules);
    }
  }

  public record Validation(Descriptor descriptor, ReadToolResult rejection) {
    private static Validation accepted(Descriptor descriptor) {
      return new Validation(Objects.requireNonNull(descriptor), null);
    }

    private static Validation rejected(ReadToolResult rejection) {
      return new Validation(null, Objects.requireNonNull(rejection));
    }

    public boolean accepted() {
      return descriptor != null;
    }
  }
}
