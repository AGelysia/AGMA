package dev.minecraftagent.paper.capability.load;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.minecraftagent.paper.capability.argument.CapabilityArgumentException;
import dev.minecraftagent.paper.capability.argument.CompiledCommandTemplate;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic.Code;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CapabilityDeterministicFuzzTest {
  private static final long FUZZ_SEED = 0x5A17C0DEL;
  private static final int SEEDED_MANIFEST_CASES = 40;
  private static final int SEEDED_TEMPLATE_CASES = 64;
  private static final int SEEDED_ARGUMENT_CASES = 48;

  @TempDir Path temporaryDirectory;

  @Test
  void loaderDeterministicallyRejectsSystematicAndSeededManifestMutations() throws Exception {
    var root = createPack(temporaryDirectory, "manifest-fuzz-pack");
    var mutations = systematicManifestMutations();
    var random = new Random(FUZZ_SEED);
    for (var index = 0; index < SEEDED_MANIFEST_CASES; index++) {
      var seededIndex = index;
      mutations.add(
          new ManifestMutation(
              "seeded-" + index, value -> applySeededManifestMutation(value, random, seededIndex)));
    }

    for (var index = 0; index < mutations.size(); index++) {
      var mutation = mutations.get(index);
      var document = document("worldedit.fuzz_" + index);
      mutation.apply().accept(document);
      write(root, "%03d-%s.json".formatted(index, mutation.name()), document.toString());
    }

    var loader = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true);
    var first =
        assertDoesNotThrow(
            () -> loader.load(root),
            () -> "Capability load threw for deterministic seed " + FUZZ_SEED);
    var second = assertDoesNotThrow(() -> loader.load(root));

    assertTrue(first.complete());
    assertTrue(first.effectiveCapabilities().isEmpty());
    assertTrue(first.globalDiagnostics().isEmpty());
    assertEquals(mutations.size(), first.drafts().size());
    assertTrue(
        first.drafts().stream()
            .allMatch(draft -> !draft.enabled() && !draft.diagnostics().isEmpty()));
    assertTrue(
        first.drafts().stream()
            .flatMap(draft -> draft.diagnostics().stream())
            .noneMatch(diagnostic -> diagnostic.code() == Code.CONTENT_HASH_UNAVAILABLE));
    var diagnosticCodes =
        first.drafts().stream()
            .flatMap(draft -> draft.diagnostics().stream())
            .map(diagnostic -> diagnostic.code())
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(
        diagnosticCodes.containsAll(
            Set.of(
                Code.MANIFEST_STRUCTURE_INVALID,
                Code.MANIFEST_VALUE_INVALID,
                Code.TEMPLATE_INVALID,
                Code.POLICY_INCONSISTENT,
                Code.PLUGIN_VERSION_RANGE_INVALID,
                Code.REVERSAL_TARGET_SELF)));
    assertEquals(dispositions(first), dispositions(second));
  }

  @Test
  void loaderRejectsUnsafePathsAndDirectoryDepthWithoutPublishing() throws Exception {
    var unsafeRoot = createPack(temporaryDirectory, "path-fuzz-pack");
    var unsafeNames =
        List.of(
            "back\\slash.json",
            "embedded space.json",
            "line\nbreak.json",
            "x".repeat(129) + ".json");
    for (var index = 0; index < unsafeNames.size(); index++) {
      write(unsafeRoot, unsafeNames.get(index), manifest("worldedit.path_" + index));
    }

    var unsafe =
        assertDoesNotThrow(
            () ->
                new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true)
                    .load(unsafeRoot));
    assertTrue(unsafe.complete());
    assertTrue(unsafe.effectiveCapabilities().isEmpty());
    assertEquals(unsafeNames.size(), unsafe.drafts().size());
    assertTrue(
        unsafe.drafts().stream()
            .allMatch(
                draft ->
                    draft.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code() == Code.PATH_UNSAFE)));

    var depthRoot = createPack(temporaryDirectory, "depth-fuzz-pack");
    var nested = depthRoot;
    for (var index = 0; index < 4; index++) {
      nested = Files.createDirectory(nested.resolve("level" + index));
      Files.setPosixFilePermissions(
          nested,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    }
    write(nested, "hidden.json", manifest("worldedit.hidden"));
    var limits = new CapabilityLoadLimits(32, 16, 64 * 1024, 64 * 1024, 2, 0, 32);

    var deep =
        assertDoesNotThrow(
            () ->
                new CapabilityPackLoader(limits, worldEdit("7.3.1", true), ignored -> true)
                    .load(depthRoot));
    assertFalse(deep.complete());
    assertTrue(deep.effectiveCapabilities().isEmpty());
    assertTrue(
        deep.globalDiagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.code() == Code.DIRECTORY_DEPTH_EXCEEDED));
  }

  @Test
  void compilerRejectsSystematicAndSeededTemplateMutationsWithTypedFailures() {
    var descriptors = new JsonObject();
    descriptors.add("value", stringDescriptor());
    var templates =
        new ArrayList<>(
            List.of(
                "/undo {missing}",
                "/undo {value} {value}",
                "/undo prefix{value}",
                "/undo {value}suffix",
                "/undo {value",
                "/undo value}",
                "/other {value}",
                "/undomore {value}",
                "/undo  {value}",
                " /undo {value}",
                "/undo {value} ",
                "/undo {value};op",
                "/undo {value}\nop",
                "/undo \"{value}\"",
                "/undo \\{value}",
                "/undo {value}|op",
                "/undo {value} & op",
                "\uFF0Fundo {value}",
                "/undo " + "x".repeat(1025)));
    var random = new Random(FUZZ_SEED);
    var unsafe = new char[] {';', '|', '&', '\\', '\'', '"', '\n', '\t', '{', '}'};
    for (var index = 0; index < SEEDED_TEMPLATE_CASES; index++) {
      var baseline = new StringBuilder("/undo {value}");
      var position = random.nextInt(baseline.length() + 1);
      baseline.insert(position, unsafe[random.nextInt(unsafe.length)]);
      templates.add(baseline.toString());
    }

    for (var index = 0; index < templates.size(); index++) {
      var template = templates.get(index);
      var caseIndex = index;
      var error =
          assertThrows(
              CapabilityArgumentException.class,
              () -> CompiledCommandTemplate.compile("undo", template, descriptors),
              () -> "Template mutation " + caseIndex + " from seed " + FUZZ_SEED + " was accepted");
      assertEquals(CapabilityArgumentException.Failure.TEMPLATE_INVALID, error.failure());
      assertTrue(error.getMessage().indexOf('\n') < 0);
    }
  }

  @Test
  void compilerRejectsSeededDescriptorMutationsWithoutLeakingAnotherException() {
    var random = new Random(FUZZ_SEED);
    for (var index = 0; index < SEEDED_ARGUMENT_CASES; index++) {
      var descriptors = new JsonObject();
      var name = "value";
      JsonElement descriptor = stringDescriptor();
      switch (index % 8) {
        case 0 -> descriptor.getAsJsonObject().addProperty("unknown" + random.nextInt(1000), true);
        case 1 -> descriptor.getAsJsonObject().addProperty("required", "true");
        case 2 ->
            descriptor
                .getAsJsonObject()
                .addProperty("description", "x".repeat(513 + random.nextInt(32)));
        case 3 -> descriptor.getAsJsonObject().addProperty("minLength", -1 - random.nextInt(32));
        case 4 -> descriptor.getAsJsonObject().addProperty("maxLength", 1025 + random.nextInt(32));
        case 5 -> descriptor.getAsJsonObject().addProperty("type", "shell:" + random.nextInt());
        case 6 -> {
          name = "../value" + random.nextInt(1000);
          descriptor = stringDescriptor();
        }
        case 7 -> descriptor = new JsonPrimitive("string");
        default -> throw new IllegalStateException("Unreachable descriptor mutation");
      }
      descriptors.add(name, descriptor);
      var caseIndex = index;

      var error =
          assertThrows(
              CapabilityArgumentException.class,
              () -> CompiledCommandTemplate.compile("undo", "/undo {value}", descriptors),
              () ->
                  "Descriptor mutation " + caseIndex + " from seed " + FUZZ_SEED + " was accepted");
      assertTrue(
          error.failure() == CapabilityArgumentException.Failure.DESCRIPTOR_INVALID
              || error.failure() == CapabilityArgumentException.Failure.TEMPLATE_INVALID);
      assertTrue(error.getMessage().indexOf('\n') < 0);
    }
  }

  @Test
  void compiledTemplateRejectsSeededMaliciousRuntimeArguments() {
    var descriptors = new JsonObject();
    descriptors.add("item", descriptor("minecraft:item"));
    descriptors.add("amount", integerDescriptor(1, 64));
    descriptors.add("mode", enumDescriptor("safe", "replace"));
    var compiled =
        CompiledCommandTemplate.compile("give", "/give {item} {amount} {mode}", descriptors);
    var random = new Random(FUZZ_SEED);

    for (var index = 0; index < SEEDED_ARGUMENT_CASES; index++) {
      var values = validRuntimeArguments();
      switch (index % 8) {
        case 0 -> values.addProperty("item", "minecraft:stone;op" + random.nextInt(1000));
        case 1 -> values.addProperty("item", "Minecraft:stone");
        case 2 ->
            values.addProperty("amount", random.nextBoolean() ? 0 : 65 + random.nextInt(1000));
        case 3 -> values.add("amount", new JsonPrimitive(new BigDecimal("1.5")));
        case 4 -> values.addProperty("mode", "unsafe;" + random.nextInt(1000));
        case 5 -> values.remove("item");
        case 6 -> values.addProperty("undeclared" + random.nextInt(1000), true);
        case 7 -> values.add("mode", JsonNull.INSTANCE);
        default -> throw new IllegalStateException("Unreachable runtime argument mutation");
      }
      var caseIndex = index;

      var error =
          assertThrows(
              CapabilityArgumentException.class,
              () -> compiled.render(values),
              () ->
                  "Runtime argument mutation "
                      + caseIndex
                      + " from seed "
                      + FUZZ_SEED
                      + " rendered");
      assertTrue(error.getMessage().indexOf('\n') < 0);
    }
  }

  private static ArrayList<ManifestMutation> systematicManifestMutations() {
    var mutations = new ArrayList<ManifestMutation>();
    mutations.add(mutation("unknown-root-path", value -> value.addProperty("path", "../../ops")));
    mutations.add(
        mutation(
            "unknown-execution-field",
            value -> execution(value).addProperty("timeout", "$(op @a)")));
    mutations.add(
        mutation(
            "unknown-plugin-field",
            value -> plugin(value).addProperty("download", "file:///etc/passwd")));
    mutations.add(
        mutation(
            "unknown-argument-field", value -> stringArgument(value).addProperty("pattern", ".*")));
    mutations.add(mutation("id-without-namespace", value -> value.addProperty("id", "worldedit")));
    mutations.add(mutation("id-uppercase", value -> value.addProperty("id", "WorldEdit.undo")));
    mutations.add(mutation("id-traversal", value -> value.addProperty("id", "../worldedit.undo")));
    mutations.add(
        mutation("id-too-long", value -> value.addProperty("id", "a." + "b".repeat(127))));
    mutations.add(mutation("version-zero", value -> value.addProperty("version", 0)));
    mutations.add(mutation("version-negative", value -> value.addProperty("version", -1)));
    mutations.add(
        mutation(
            "version-overflow",
            value -> value.add("version", new JsonPrimitive(new BigInteger("2147483648")))));
    mutations.add(
        mutation(
            "version-decimal",
            value -> value.add("version", new JsonPrimitive(new BigDecimal("1.0")))));
    mutations.add(mutation("version-string", value -> value.addProperty("version", "1")));
    mutations.add(
        mutation(
            "description-control", value -> value.addProperty("description", "bad\u0000text")));
    mutations.add(
        mutation(
            "description-too-long", value -> value.addProperty("description", "x".repeat(1025))));
    mutations.add(
        mutation("requirements-wrong-type", value -> value.addProperty("requirements", "all")));
    mutations.add(
        mutation(
            "plugins-wrong-type",
            value -> requirements(value).addProperty("plugins", "WorldEdit")));
    mutations.add(
        mutation(
            "plugins-too-many",
            value -> requirements(value).add("plugins", plugins(33, ">=7.3 <8"))));
    mutations.add(
        mutation("plugin-name-path", value -> plugin(value).addProperty("name", "../WorldEdit")));
    mutations.add(
        mutation(
            "plugin-name-too-long", value -> plugin(value).addProperty("name", "x".repeat(65))));
    mutations.add(
        mutation(
            "plugin-version-leading-zero",
            value -> plugin(value).addProperty("version", ">=07.3 <8")));
    mutations.add(
        mutation(
            "plugin-version-too-long",
            value -> plugin(value).addProperty("version", ">=" + "1".repeat(129))));
    mutations.add(mutation("execution-wrong-type", value -> value.addProperty("execution", "op")));
    mutations.add(
        mutation(
            "command-root-path", value -> execution(value).addProperty("commandRoot", "../op")));
    mutations.add(
        mutation(
            "command-root-too-long",
            value -> {
              var root = "x".repeat(65);
              execution(value).addProperty("commandRoot", root);
              execution(value).addProperty("template", "/" + root);
            }));

    var invalidTemplates =
        List.of(
            "/undo {missing}",
            "/undo {value} {value}",
            "/undo prefix{value}",
            "/undo {value}suffix",
            "/undo {value};op",
            "/undo {value}\nop",
            "/other {value}",
            "/undo {value} ");
    for (var index = 0; index < invalidTemplates.size(); index++) {
      var template = invalidTemplates.get(index);
      mutations.add(
          mutation(
              "template-" + index,
              value -> {
                stringArgument(value);
                execution(value).addProperty("template", template);
              }));
    }

    mutations.add(
        mutation("arguments-wrong-type", value -> value.add("arguments", new JsonArray())));
    mutations.add(
        mutation(
            "arguments-too-many",
            value -> {
              var arguments = new JsonObject();
              for (var index = 0; index < 65; index++) {
                arguments.add("value" + index, stringDescriptor());
              }
              value.add("arguments", arguments);
            }));
    mutations.add(
        mutation(
            "argument-name-path",
            value -> {
              var arguments = new JsonObject();
              arguments.add("../value", stringDescriptor());
              value.add("arguments", arguments);
            }));
    mutations.add(
        mutation(
            "argument-type-unknown", value -> stringArgument(value).addProperty("type", "shell")));
    mutations.add(
        mutation(
            "argument-description-too-long",
            value -> stringArgument(value).addProperty("description", "x".repeat(513))));
    mutations.add(
        mutation(
            "argument-optional", value -> stringArgument(value).addProperty("required", false)));
    mutations.add(
        mutation(
            "argument-string-bounds-reversed",
            value -> {
              var argument = stringArgument(value);
              argument.addProperty("minLength", 9);
              argument.addProperty("maxLength", 8);
            }));
    mutations.add(
        mutation(
            "argument-enum-too-many",
            value -> {
              var argument = enumArgument(value);
              var values = new JsonArray();
              for (var index = 0; index < 129; index++) {
                values.add("value" + index);
              }
              argument.add("values", values);
            }));
    mutations.add(
        mutation(
            "argument-enum-injection",
            value -> enumArgument(value).getAsJsonArray("values").add("bad;op")));
    mutations.add(
        mutation(
            "argument-number-nonfinite",
            value -> {
              var argument = numberArgument(value);
              argument.add("minimum", new JsonPrimitive(new BigDecimal("1e999")));
              argument.add("maximum", new JsonPrimitive(new BigDecimal("1e1000")));
            }));
    mutations.add(
        mutation(
            "argument-number-jcs-collision",
            value -> {
              var argument = numberArgument(value);
              var collision = new JsonPrimitive(new BigDecimal("0.10000000000000001"));
              argument.add("minimum", collision);
              argument.add("maximum", collision.deepCopy());
            }));
    mutations.add(
        mutation(
            "effects-category-unknown", value -> effects(value).addProperty("category", "SHELL")));
    mutations.add(
        mutation("effects-scope-path", value -> effects(value).addProperty("scope", "../world")));
    mutations.add(
        mutation("maximum-blocks-zero", value -> effects(value).addProperty("maximumBlocks", 0)));
    mutations.add(
        mutation(
            "maximum-blocks-over-limit",
            value -> effects(value).addProperty("maximumBlocks", 250001)));
    mutations.add(
        mutation(
            "maximum-blocks-decimal",
            value ->
                effects(value).add("maximumBlocks", new JsonPrimitive(new BigDecimal("5000.0")))));
    mutations.add(
        mutation("permissions-wrong-type", value -> value.addProperty("permissions", "OWNER")));
    mutations.add(
        mutation(
            "permissions-unknown",
            value -> value.getAsJsonObject("permissions").addProperty("minimum", "ROOT")));
    mutations.add(
        mutation(
            "confirmation-wrong-type",
            value -> value.getAsJsonObject("confirmation").addProperty("required", "true")));
    mutations.add(
        mutation(
            "reversal-self",
            value -> {
              var reversal = new JsonObject();
              reversal.addProperty("type", "capability");
              reversal.addProperty("capability", value.get("id").getAsString());
              value.add("reversibility", reversal);
            }));
    mutations.add(
        mutation(
            "yaml-depth",
            value -> value.add("unexpected", nestedObject(40, new JsonPrimitive("payload")))));
    return mutations;
  }

  private static void applySeededManifestMutation(JsonObject value, Random random, int index) {
    switch (index % 10) {
      case 0 -> execution(value).addProperty("unknown" + random.nextInt(10_000), true);
      case 1 ->
          value.add(
              requiredRootFields().get(random.nextInt(requiredRootFields().size())),
              wrongType(random));
      case 2 -> value.addProperty("id", "../worldedit.fuzz" + random.nextInt(10_000));
      case 3 ->
          value.add(
              "version",
              new JsonPrimitive(
                  BigInteger.valueOf(Integer.MAX_VALUE)
                      .add(BigInteger.valueOf(1 + random.nextInt(10_000)))));
      case 4 -> {
        stringArgument(value);
        var template = new StringBuilder("/undo {value}");
        var unsafe = new char[] {';', '|', '&', '\\', '\'', '"', '\n'};
        template.insert(
            random.nextInt(template.length() + 1), unsafe[random.nextInt(unsafe.length)]);
        execution(value).addProperty("template", template.toString());
      }
      case 5 -> {
        var argument = numberArgument(value);
        var exponent = 1025 + random.nextInt(100);
        argument.add("minimum", new JsonPrimitive(new BigDecimal("1e" + exponent)));
        argument.add("maximum", new JsonPrimitive(new BigDecimal("1e" + (exponent + 1))));
      }
      case 6 -> value.addProperty("description", "x".repeat(1025 + random.nextInt(64)));
      case 7 ->
          value.add(
              "unknown" + random.nextInt(10_000),
              nestedObject(33 + random.nextInt(8), new JsonPrimitive("payload")));
      case 8 -> plugin(value).addProperty("version", ">=0" + (1 + random.nextInt(9)) + ".0 <8");
      case 9 -> {
        var arguments = new JsonObject();
        for (var argumentIndex = 0; argumentIndex < 65; argumentIndex++) {
          arguments.add("value" + argumentIndex, stringDescriptor());
        }
        value.add("arguments", arguments);
      }
      default -> throw new IllegalStateException("Unreachable manifest mutation");
    }
  }

  private static JsonElement wrongType(Random random) {
    return switch (random.nextInt(4)) {
      case 0 -> JsonNull.INSTANCE;
      case 1 -> new JsonPrimitive(false);
      case 2 -> new JsonPrimitive("wrong");
      case 3 -> new JsonArray();
      default -> throw new IllegalStateException("Unreachable wrong type");
    };
  }

  private static List<String> requiredRootFields() {
    return List.of(
        "id",
        "version",
        "description",
        "requirements",
        "execution",
        "arguments",
        "effects",
        "permissions",
        "confirmation",
        "reversibility");
  }

  private static ManifestMutation mutation(String name, Consumer<JsonObject> apply) {
    return new ManifestMutation(name, apply);
  }

  private static JsonObject document(String id) {
    return JsonParser.parseString(manifest(id)).getAsJsonObject();
  }

  private static JsonObject requirements(JsonObject document) {
    return document.getAsJsonObject("requirements");
  }

  private static JsonObject plugin(JsonObject document) {
    return requirements(document).getAsJsonArray("plugins").get(0).getAsJsonObject();
  }

  private static JsonArray plugins(int count, String version) {
    var plugins = new JsonArray();
    for (var index = 0; index < count; index++) {
      var plugin = new JsonObject();
      plugin.addProperty("name", "Plugin" + index);
      plugin.addProperty("version", version);
      plugins.add(plugin);
    }
    return plugins;
  }

  private static JsonObject execution(JsonObject document) {
    return document.getAsJsonObject("execution");
  }

  private static JsonObject effects(JsonObject document) {
    return document.getAsJsonObject("effects");
  }

  private static JsonObject stringArgument(JsonObject document) {
    execution(document).addProperty("template", "/undo {value}");
    var argument = stringDescriptor();
    var arguments = new JsonObject();
    arguments.add("value", argument);
    document.add("arguments", arguments);
    return argument;
  }

  private static JsonObject enumArgument(JsonObject document) {
    execution(document).addProperty("template", "/undo {value}");
    var argument = enumDescriptor("safe");
    var arguments = new JsonObject();
    arguments.add("value", argument);
    document.add("arguments", arguments);
    return argument;
  }

  private static JsonObject numberArgument(JsonObject document) {
    execution(document).addProperty("template", "/undo {value}");
    var argument = descriptor("number");
    argument.addProperty("minimum", 0);
    argument.addProperty("maximum", 1);
    var arguments = new JsonObject();
    arguments.add("value", argument);
    document.add("arguments", arguments);
    return argument;
  }

  private static JsonObject descriptor(String type) {
    var descriptor = new JsonObject();
    descriptor.addProperty("type", type);
    descriptor.addProperty("description", "Fuzzed argument.");
    descriptor.addProperty("required", true);
    return descriptor;
  }

  private static JsonObject stringDescriptor() {
    var descriptor = descriptor("string");
    descriptor.addProperty("minLength", 1);
    descriptor.addProperty("maxLength", 64);
    return descriptor;
  }

  private static JsonObject integerDescriptor(int minimum, int maximum) {
    var descriptor = descriptor("integer");
    descriptor.addProperty("minimum", minimum);
    descriptor.addProperty("maximum", maximum);
    return descriptor;
  }

  private static JsonObject enumDescriptor(String... values) {
    var descriptor = descriptor("enum");
    var allowed = new JsonArray();
    for (var value : values) {
      allowed.add(value);
    }
    descriptor.add("values", allowed);
    return descriptor;
  }

  private static JsonObject validRuntimeArguments() {
    var values = new JsonObject();
    values.addProperty("item", "minecraft:stone");
    values.addProperty("amount", 1);
    values.addProperty("mode", "safe");
    return values;
  }

  private static JsonElement nestedObject(int depth, JsonElement leaf) {
    JsonElement value = leaf;
    for (var index = 0; index < depth; index++) {
      var parent = new JsonObject();
      parent.add("level" + index, value);
      value = parent;
    }
    return value;
  }

  private static List<String> dispositions(CapabilityLoadResult result) {
    return result.drafts().stream()
        .map(
            draft ->
                draft.source()
                    + ":"
                    + draft.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code() + "@" + diagnostic.field())
                        .toList())
        .toList();
  }

  private record ManifestMutation(String name, Consumer<JsonObject> apply) {}
}
