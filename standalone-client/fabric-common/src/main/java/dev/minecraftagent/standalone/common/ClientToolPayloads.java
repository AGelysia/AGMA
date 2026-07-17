package dev.minecraftagent.standalone.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ClientToolPayloads {
  static final Set<String> TOOLS =
      Set.of(
          "game.resource.search",
          "game.process.lookup",
          "game.process.uses",
          "game.process.plan",
          "game.inventory.snapshot");

  private ClientToolPayloads() {}

  static String requireTool(String tool) {
    if (!TOOLS.contains(tool)) {
      throw new IllegalArgumentException("Client tool id is invalid");
    }
    return tool;
  }

  static Map<String, Object> copyObject(Map<String, ?> value, int maximumProperties) {
    if (value == null || value.size() > maximumProperties) {
      throw new IllegalArgumentException("Client tool payload is invalid");
    }
    @SuppressWarnings("unchecked")
    var copy = (Map<String, Object>) copy(value, 0, new Counter());
    if (StrictJson.write(copy).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > ConnectorEnvelopeCodec.MAXIMUM_BYTES) {
      throw new IllegalArgumentException("Client tool payload is too large");
    }
    return copy;
  }

  static void validateArguments(String tool, Map<String, Object> arguments) {
    switch (tool) {
      case "game.resource.search" -> {
        exact(arguments, "query", "limit");
        string(arguments.get("query"), 256);
        integer(arguments.get("limit"), 1, 20);
      }
      case "game.process.lookup", "game.process.uses" -> {
        exact(arguments, "resourceId", "generationId", "limit");
        namespacedId(arguments.get("resourceId"));
        generationId(arguments.get("generationId"));
        integer(arguments.get("limit"), 1, 16);
      }
      case "game.process.plan" -> {
        exact(arguments, "resourceId", "amount", "generationId", "maxDepth", "maxNodes", "topK");
        namespacedId(arguments.get("resourceId"));
        number(arguments.get("amount"), false, BigDecimal.valueOf(1_000_000_000L));
        generationId(arguments.get("generationId"));
        integer(arguments.get("maxDepth"), 1, 12);
        integer(arguments.get("maxNodes"), 1, 2000);
        integer(arguments.get("topK"), 1, 3);
      }
      case "game.inventory.snapshot" -> {
        exact(arguments, "authorizationId", "generationId", "resourceIds");
        uuid(arguments.get("authorizationId"));
        generationId(arguments.get("generationId"));
        var resources = array(arguments.get("resourceIds"), 1, 64);
        for (var resource : resources) {
          namespacedId(resource);
        }
        if (Set.copyOf(resources).size() != resources.size()) {
          throw invalid();
        }
      }
      default -> throw invalid();
    }
  }

  static void validateResult(String tool, Map<String, Object> result) {
    switch (tool) {
      case "game.resource.search" -> {
        exact(
            result,
            "generationId",
            "visibility",
            "completeness",
            "candidates",
            "ambiguous",
            "truncated",
            "warnings");
        generationId(result.get("generationId"));
        oneOf(result.get("visibility"), "main_menu", "no_world", "singleplayer", "multiplayer");
        oneOf(result.get("completeness"), "complete", "partial", "unavailable");
        array(result.get("candidates"), 0, 20);
        bool(result.get("ambiguous"));
        bool(result.get("truncated"));
        warnings(result.get("warnings"), 32);
      }
      case "game.process.lookup", "game.process.uses" -> {
        exact(result, "generationId", "status", "processes", "truncated", "warnings");
        generationId(result.get("generationId"));
        oneOf(result.get("status"), "ready", "reloading", "unavailable", "stale_generation");
        array(result.get("processes"), 0, 16);
        bool(result.get("truncated"));
        warnings(result.get("warnings"), 32);
      }
      case "game.process.plan" -> {
        exact(
            result,
            "generationId",
            "status",
            "target",
            "routes",
            "unresolved",
            "cycles",
            "exploredNodes",
            "inventoryApplied",
            "warnings");
        generationId(result.get("generationId"));
        oneOf(
            result.get("status"), "complete", "partial", "unresolved", "cycle", "stale_generation");
        object(result.get("target"));
        array(result.get("routes"), 0, 3);
        array(result.get("unresolved"), 0, 64);
        array(result.get("cycles"), 0, 32);
        integer(result.get("exploredNodes"), 0, 2000);
        bool(result.get("inventoryApplied"));
        warnings(result.get("warnings"), 64);
      }
      case "game.inventory.snapshot" -> {
        exact(result, "generationId", "authorizationId", "entries", "truncated", "warnings");
        generationId(result.get("generationId"));
        uuid(result.get("authorizationId"));
        array(result.get("entries"), 0, 64);
        bool(result.get("truncated"));
        warnings(result.get("warnings"), 32);
      }
      default -> throw invalid();
    }
  }

  private static Object copy(Object value, int depth, Counter counter) {
    if (depth > StrictJson.MAXIMUM_NESTING || ++counter.values > StrictJson.MAXIMUM_VALUES) {
      throw invalid();
    }
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String text) {
      if (text.length() > StrictJson.MAXIMUM_STRING_LENGTH) {
        throw invalid();
      }
      StrictJson.write(text);
      return text;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Float number) {
      if (!Float.isFinite(number)) {
        throw invalid();
      }
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (value instanceof Double number) {
      if (!Double.isFinite(number)) {
        throw invalid();
      }
      return BigDecimal.valueOf(number);
    }
    if (value instanceof Map<?, ?> source) {
      var result = new LinkedHashMap<String, Object>();
      for (var entry : source.entrySet()) {
        if (!(entry.getKey() instanceof String key) || result.containsKey(key)) {
          throw invalid();
        }
        result.put(key, copy(entry.getValue(), depth + 1, counter));
      }
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> source) {
      var result = new ArrayList<>();
      for (var entry : source) {
        result.add(copy(entry, depth + 1, counter));
      }
      return Collections.unmodifiableList(result);
    }
    throw invalid();
  }

  private static void exact(Map<String, Object> value, String... fields) {
    if (!value.keySet().equals(Set.of(fields))) {
      throw invalid();
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      throw invalid();
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid();
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static List<?> array(Object value, int minimum, int maximum) {
    if (!(value instanceof List<?> list) || list.size() < minimum || list.size() > maximum) {
      throw invalid();
    }
    return list;
  }

  private static String string(Object value, int maximum) {
    if (!(value instanceof String text)
        || text.isEmpty()
        || text.codePointCount(0, text.length()) > maximum) {
      throw invalid();
    }
    return text;
  }

  private static void generationId(Object value) {
    if (!string(value, 128).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw invalid();
    }
  }

  private static void namespacedId(Object value) {
    if (!string(value, 256).matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw invalid();
    }
  }

  private static void uuid(Object value) {
    try {
      var text = string(value, 36);
      if (!UUID.fromString(text).toString().equals(text)) {
        throw invalid();
      }
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static int integer(Object value, int minimum, int maximum) {
    try {
      if (!(value instanceof Number)) {
        throw invalid();
      }
      var number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
      var result = number.intValueExact();
      if (result < minimum || result > maximum) {
        throw invalid();
      }
      return result;
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static void number(Object value, boolean zeroAllowed, BigDecimal maximum) {
    try {
      if (!(value instanceof Number)) {
        throw invalid();
      }
      var number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
      if ((zeroAllowed ? number.signum() < 0 : number.signum() <= 0)
          || number.compareTo(maximum) > 0) {
        throw invalid();
      }
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static void bool(Object value) {
    if (!(value instanceof Boolean)) {
      throw invalid();
    }
  }

  private static void oneOf(Object value, String... accepted) {
    if (!(value instanceof String text) || !Set.of(accepted).contains(text)) {
      throw invalid();
    }
  }

  private static void warnings(Object value, int maximum) {
    for (var warning : array(value, 0, maximum)) {
      string(warning, 512);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Client tool payload is invalid");
  }

  private static final class Counter {
    private int values;
  }
}
