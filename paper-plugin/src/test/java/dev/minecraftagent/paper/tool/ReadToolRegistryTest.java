package dev.minecraftagent.paper.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import org.junit.jupiter.api.Test;

class ReadToolRegistryTest {
  private final ReadToolRegistry registry = new ReadToolRegistry();

  @Test
  void containsExactlyTheClosedReadToolsWithFixedSources() {
    assertEquals(
        ReadToolRegistry.REQUIRED_TOOL_IDS,
        registry.descriptors().stream()
            .map(ReadToolRegistry.Descriptor::id)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        ReadToolResult.Source.SERVER_REGISTRY,
        registry.find("server.recipe.lookup").orElseThrow().source());
    assertEquals(
        ReadToolResult.Source.PAPER_API,
        registry.find("player.context.read").orElseThrow().source());
    assertEquals(
        ReadToolResult.Source.PAPER_API, registry.find("landmark.search").orElseThrow().source());
  }

  @Test
  void rejectsUnknownToolsExtraArgumentsAndModuleEscapes() {
    assertFalse(
        registry
            .validate("server.command.execute", AgentModule.GENERAL, new JsonObject())
            .accepted());

    var extra = new JsonObject();
    extra.addProperty("playerUuid", java.util.UUID.randomUUID().toString());
    assertFalse(registry.validate("player.context.read", AgentModule.GENERAL, extra).accepted());

    assertFalse(
        registry
            .validate("server.recipe.lookup", AgentModule.BUILD, item("minecraft:stone"))
            .accepted());
    assertTrue(
        registry
            .validate("server.recipe.lookup", AgentModule.RECIPE, item("minecraft:stone"))
            .accepted());
  }

  @Test
  void recipeItemIdSchemaIsCanonicalAndClosed() {
    assertFalse(
        registry.validate("server.recipe.uses", AgentModule.RECIPE, item("STONE")).accepted());
    assertFalse(
        registry
            .validate("server.recipe.uses", AgentModule.RECIPE, item("minecraft:stone;op me"))
            .accepted());
    var extra = item("minecraft:stone");
    extra.addProperty("limit", 1000000);
    assertFalse(registry.validate("server.recipe.uses", AgentModule.RECIPE, extra).accepted());
  }

  @Test
  void landmarkSearchIsLocateOnlyAndCannotAcceptAuthorityArguments() {
    var query = new JsonObject();
    query.addProperty("query", "spawn market");
    assertTrue(registry.validate("landmark.search", AgentModule.LOCATE, query).accepted());
    assertTrue(registry.validate("landmark.search", AgentModule.BUILD, query).accepted());
    assertFalse(registry.validate("landmark.search", AgentModule.GENERAL, query).accepted());

    query.addProperty("permission", "*");
    assertFalse(registry.validate("landmark.search", AgentModule.LOCATE, query).accepted());
    var unsafe = new JsonObject();
    unsafe.addProperty("query", "spawn\u202e");
    assertFalse(registry.validate("landmark.search", AgentModule.LOCATE, unsafe).accepted());
  }

  @Test
  void buildPreviewPlanIsBoundedClosedAndBuildOnly() {
    var plan = buildPlan();
    assertTrue(registry.validate("build.preview.create", AgentModule.BUILD, plan).accepted());
    assertFalse(registry.validate("build.preview.create", AgentModule.GENERAL, plan).accepted());

    plan.addProperty("apply", true);
    assertFalse(registry.validate("build.preview.create", AgentModule.BUILD, plan).accepted());
    plan = buildPlan();
    plan.getAsJsonObject("bounds").getAsJsonObject("max").addProperty("x", 100);
    assertFalse(registry.validate("build.preview.create", AgentModule.BUILD, plan).accepted());
    plan = buildPlan();
    plan.add("blockState", com.google.gson.JsonNull.INSTANCE);
    assertFalse(registry.validate("build.preview.create", AgentModule.BUILD, plan).accepted());
  }

  private static JsonObject buildPlan() {
    var minimum = new JsonObject();
    minimum.addProperty("x", 0);
    minimum.addProperty("y", 64);
    minimum.addProperty("z", 0);
    var maximum = minimum.deepCopy();
    maximum.addProperty("x", 2);
    maximum.addProperty("y", 66);
    maximum.addProperty("z", 2);
    var bounds = new JsonObject();
    bounds.add("min", minimum.deepCopy());
    bounds.add("max", maximum);
    var result = new JsonObject();
    result.addProperty("projectId", "99999999-9999-4999-8999-999999999999");
    result.addProperty("revision", 1);
    result.addProperty("operation", "create");
    result.addProperty("dimension", "minecraft:overworld");
    result.add("bounds", bounds);
    result.add("origin", minimum);
    result.addProperty("pattern", "hollow");
    result.addProperty("blockState", "minecraft:stone");
    result.addProperty("rotation", 0);
    result.addProperty("mirror", "NONE");
    return result;
  }

  private static JsonObject item(String itemId) {
    var result = new JsonObject();
    result.addProperty("itemId", itemId);
    return result;
  }
}
