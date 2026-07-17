package dev.minecraftagent.paper.capability.load;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.capability.model.CapabilityManifest;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.BooleanArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.EnumArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.IntegerArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.MinecraftArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.NumberArgument;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.StringArgument;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.erdtman.jcs.JsonCanonicalizer;

/** Canonicalizes typed content, then hashes the RFC 8785 UTF-8 representation. */
final class CapabilityCanonicalizer {
  static BigDecimal exactJcsNumber(BigDecimal value) {
    var normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    var binary = normalized.doubleValue();
    if (!Double.isFinite(binary) || normalized.compareTo(BigDecimal.valueOf(binary)) != 0) {
      throw new IllegalArgumentException("Capability number loses IEEE-754 precision");
    }
    final String canonical;
    try {
      canonical = new JsonCanonicalizer("{\"number\":" + normalized + "}").getEncodedString();
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Capability number is not JCS canonicalizable", exception);
    }
    final BigDecimal roundTrip;
    try {
      roundTrip =
          new BigDecimal(canonical.substring("{\"number\":".length(), canonical.length() - 1));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Capability number has no decimal JCS form", exception);
    }
    if (normalized.compareTo(roundTrip) != 0) {
      throw new IllegalArgumentException("Capability number loses precision under JCS");
    }
    return normalized;
  }

  String sha256(CapabilityManifest manifest) throws IOException {
    var canonical = new JsonCanonicalizer(toJson(manifest).toString()).getEncodedString();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private JsonObject toJson(CapabilityManifest manifest) {
    var root = new JsonObject();
    root.addProperty("id", manifest.id());
    root.addProperty("version", manifest.version());
    root.addProperty("description", manifest.description());
    manifest
        .status()
        .ifPresent(status -> root.addProperty("status", status.name().toLowerCase(Locale.ROOT)));

    var requirements = new JsonObject();
    var plugins = new JsonArray();
    for (var requirement : manifest.pluginRequirements()) {
      var plugin = new JsonObject();
      plugin.addProperty("name", requirement.name());
      plugin.addProperty("version", requirement.versionRange());
      plugins.add(plugin);
    }
    requirements.add("plugins", plugins);
    root.add("requirements", requirements);

    var execution = new JsonObject();
    execution.addProperty("type", "command");
    execution.addProperty("source", manifest.execution().source().name().toLowerCase(Locale.ROOT));
    execution.addProperty("commandRoot", manifest.execution().commandRoot());
    execution.addProperty("template", manifest.execution().template());
    root.add("execution", execution);

    root.add("arguments", argumentDescriptors(manifest));

    var effects = new JsonObject();
    effects.addProperty("category", manifest.effects().category().name());
    effects.addProperty("scope", manifest.effects().scope());
    if (manifest.effects().maximumBlocks().isPresent()) {
      effects.addProperty("maximumBlocks", manifest.effects().maximumBlocks().orElseThrow());
    } else {
      effects.add("maximumBlocks", null);
    }
    root.add("effects", effects);

    var permissions = new JsonObject();
    permissions.addProperty("minimum", manifest.permission().minimum().name());
    manifest.permission().node().ifPresent(node -> permissions.addProperty("node", node));
    root.add("permissions", permissions);

    var confirmation = new JsonObject();
    confirmation.addProperty("required", manifest.confirmation().required());
    root.add("confirmation", confirmation);

    var reversibility = new JsonObject();
    reversibility.addProperty(
        "type", manifest.reversibility().type().name().toLowerCase(Locale.ROOT));
    manifest
        .reversibility()
        .capabilityId()
        .ifPresent(capability -> reversibility.addProperty("capability", capability));
    root.add("reversibility", reversibility);
    return root;
  }

  JsonObject argumentDescriptors(CapabilityManifest manifest) {
    var arguments = new JsonObject();
    for (var entry : manifest.arguments().entrySet()) {
      arguments.add(entry.getKey(), argument(entry.getValue()));
    }
    return arguments;
  }

  private JsonObject argument(CapabilityManifest.ArgumentDefinition definition) {
    var result = new JsonObject();
    switch (definition) {
      case StringArgument argument -> {
        result.addProperty("type", "string");
        commonArgument(result, argument.description(), argument.required());
        result.addProperty("minLength", argument.minimumLength());
        result.addProperty("maxLength", argument.maximumLength());
      }
      case IntegerArgument argument -> {
        result.addProperty("type", "integer");
        commonArgument(result, argument.description(), argument.required());
        result.addProperty("minimum", argument.minimum());
        result.addProperty("maximum", argument.maximum());
      }
      case NumberArgument argument -> {
        result.addProperty("type", "number");
        commonArgument(result, argument.description(), argument.required());
        result.addProperty("minimum", argument.minimum());
        result.addProperty("maximum", argument.maximum());
      }
      case BooleanArgument argument -> {
        result.addProperty("type", "boolean");
        commonArgument(result, argument.description(), argument.required());
      }
      case EnumArgument argument -> {
        result.addProperty("type", "enum");
        commonArgument(result, argument.description(), argument.required());
        var values = new JsonArray();
        argument.values().forEach(values::add);
        result.add("values", values);
      }
      case MinecraftArgument argument -> {
        result.addProperty("type", argument.type().manifestName());
        commonArgument(result, argument.description(), argument.required());
      }
    }
    return result;
  }

  private void commonArgument(JsonObject target, String description, boolean required) {
    target.addProperty("description", description);
    target.addProperty("required", required);
  }
}
