package dev.minecraftagent.paper.capability.argument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class CompiledCommandTemplateTest {
  @Test
  void rendersOnlyFixedLiteralsAndTypedCanonicalValues() {
    var descriptors = new JsonObject();
    descriptors.add("from", descriptor("minecraft:block-pattern"));
    descriptors.add("to", descriptor("minecraft:block-pattern"));
    var template = CompiledCommandTemplate.compile("replace", "/replace {from} {to}", descriptors);
    var values = new JsonObject();
    values.addProperty("from", "minecraft:stone");
    values.addProperty("to", "minecraft:gold_block");

    assertEquals("replace", template.commandRoot());
    assertEquals("/replace minecraft:stone minecraft:gold_block", template.render(values));
  }

  @Test
  void permitsAnExactFixedCommandWithNoArguments() {
    var template =
        CompiledCommandTemplate.compile("worldedit:undo", "/worldedit:undo", new JsonObject());

    assertEquals("/worldedit:undo", template.render(new JsonObject()));
  }

  @Test
  void enforcesOneToOneStandalonePlaceholders() {
    var descriptors = new JsonObject();
    descriptors.add("text", stringDescriptor());

    assertTemplateRejected("say", "/say {missing}", descriptors);
    assertTemplateRejected("say", "/say {text} {text}", descriptors);
    assertTemplateRejected("say", "/say prefix{text}", descriptors);
    assertTemplateRejected("say", "/say {text}suffix", descriptors);
    assertTemplateRejected("say", "/say {text", descriptors);
    assertTemplateRejected("say", "/say text}", descriptors);

    var unused = descriptors.deepCopy();
    unused.add("other", stringDescriptor());
    assertTemplateRejected("say", "/say {text}", unused);
  }

  @Test
  void rejectsRootConfusionAndUnsafeFixedTemplateText() {
    var descriptors = new JsonObject();
    descriptors.add("text", stringDescriptor());

    assertTemplateRejected("say", "/saymore {text}", descriptors);
    assertTemplateRejected("say", "/other {text}", descriptors);
    assertTemplateRejected("say", "/say {text};op", descriptors);
    assertTemplateRejected("say", "/say {text}\nop", descriptors);
    assertTemplateRejected("say", "/say \"{text}\"", descriptors);
    assertTemplateRejected("say", "/say \\{text}", descriptors);
    assertTemplateRejected("say", "/say {text}|op", descriptors);
    assertTemplateRejected("say", "/say {text} & op", descriptors);
    assertTemplateRejected("say", "/say \uff4f\uff50 {text}", descriptors);
  }

  @Test
  void renderedCommandCannotExceedTheProtocolCommandLimit() {
    var descriptors = new JsonObject();
    var values = new JsonObject();
    var template = new StringBuilder("/echo");
    for (int index = 0; index < 5; index++) {
      var name = "value" + index;
      descriptors.add(name, stringDescriptor());
      values.addProperty(name, "a".repeat(1024));
      template.append(" {").append(name).append('}');
    }
    var compiled = CompiledCommandTemplate.compile("echo", template.toString(), descriptors);

    var error = assertThrows(CapabilityArgumentException.class, () -> compiled.render(values));
    assertEquals(CapabilityArgumentException.Failure.COMMAND_TOO_LONG, error.failure());
  }

  @Test
  void optionalArgumentsHaveAnExplicitV1CompileFailure() {
    var optional = stringDescriptor();
    optional.addProperty("required", false);
    var descriptors = new JsonObject();
    descriptors.add("text", optional);

    var error =
        assertThrows(
            CapabilityArgumentException.class,
            () -> CompiledCommandTemplate.compile("say", "/say {text}", descriptors));
    assertEquals(
        CapabilityArgumentException.Failure.OPTIONAL_ARGUMENT_UNSUPPORTED, error.failure());
  }

  private static JsonObject descriptor(String type) {
    var result = new JsonObject();
    result.addProperty("type", type);
    result.addProperty("description", "Test argument.");
    result.addProperty("required", true);
    return result;
  }

  private static JsonObject stringDescriptor() {
    var result = descriptor("string");
    result.addProperty("minLength", 1);
    result.addProperty("maxLength", 1024);
    return result;
  }

  private static void assertTemplateRejected(
      String commandRoot, String template, JsonObject descriptors) {
    var error =
        assertThrows(
            CapabilityArgumentException.class,
            () -> CompiledCommandTemplate.compile(commandRoot, template, descriptors));
    assertEquals(CapabilityArgumentException.Failure.TEMPLATE_INVALID, error.failure());
  }
}
