package dev.minecraftagent.standalone.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandaloneForgeMetadataTest {
  @Test
  void descriptorLocksTheMinecraft1182ClientOnlyTuple() throws Exception {
    var resource = getClass().getClassLoader().getResourceAsStream("META-INF/mods.toml");
    assertNotNull(resource);
    UnmodifiableConfig descriptor;
    try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
      descriptor = new TomlParser().parse(reader);
    }

    assertEquals("javafml", descriptor.get("modLoader"));
    assertEquals("[40,)", descriptor.get("loaderVersion"));
    assertEquals("Apache-2.0", descriptor.get("license"));
    assertEquals(true, descriptor.get("clientSideOnly"));
    assertEquals(false, descriptor.get("showAsResourcePack"));
    assertFalse(descriptor.contains("mixins"));

    List<? extends UnmodifiableConfig> mods = descriptor.get("mods");
    assertEquals(1, mods.size());
    var mod = mods.get(0);
    assertEquals("agma_standalone", mod.get("modId"));
    assertEquals("AGMA Standalone Client", mod.get("displayName"));
    assertEquals("IGNORE_ALL_VERSION", mod.get("displayTest"));
    assertEquals(System.getProperty("agma.expectedVersion"), mod.get("version"));

    List<? extends UnmodifiableConfig> dependencies =
        descriptor.get(List.of("dependencies", "agma_standalone"));
    assertEquals(2, dependencies.size());
    assertDependency(dependencies.get(0), "forge", "[40.2.21,41)");
    assertDependency(dependencies.get(1), "minecraft", "[1.18.2,1.18.3)");
    assertFalse(descriptor.toString().contains("fabric"));
    assertFalse(descriptor.toString().contains("paper"));
  }

  @Test
  void primaryClassesLoadWithoutViewerApisAndDoNotLinkThem() throws Exception {
    assertNotNull(StandaloneForgeMod.class.getDeclaredConstructor());
    assertNotNull(StandaloneCatalogService.class.getDeclaredConstructor());
    for (var type :
        List.of(
            StandaloneForgeMod.class,
            StandaloneForgeClient.class,
            StandaloneCatalogService.class)) {
      var constants = classConstants(type);
      assertFalse(constants.contains("mezz/jei"));
      assertFalse(constants.contains("dev/emi"));
      assertFalse(constants.contains("net/fabricmc"));
      assertTrue(constants.contains("agma"));
    }
  }

  @Test
  void commonBootstrapDoesNotLinkClientOnlyApis() throws Exception {
    var constants = classConstants(StandaloneForgeMod.class);
    assertFalse(constants.contains("net/minecraft/client"));
    assertFalse(constants.contains("net/minecraftforge/client"));
    assertFalse(constants.contains("org/lwjgl"));
    assertTrue(constants.contains("net/minecraftforge/fml/DistExecutor"));
    assertTrue(constants.contains("unsafeRunWhenOn"));
    assertTrue(constants.contains("StandaloneForgeClient"));
  }

  @Test
  void clientResourcesContainUsableLanguageBundles() throws Exception {
    var english = languageKeys("assets/agma_standalone/lang/en_us.json");
    var chinese = languageKeys("assets/agma_standalone/lang/zh_cn.json");
    assertEquals(english, chinese);
    assertTrue(
        english.containsAll(
            Set.of(
                "key.agma_standalone.open",
                "key.categories.agma_standalone",
                "screen.agma_standalone.catalog",
                "screen.agma_standalone.ask",
                "screen.agma_standalone.settings",
                "screen.agma_standalone.start_runtime",
                "screen.agma_standalone.stop_runtime",
                "screen.agma_standalone.runtime_state",
                "screen.agma_standalone.input_price",
                "screen.agma_standalone.output_price",
                "screen.agma_standalone.invalid_settings",
                "screen.agma_standalone.model_api_key_configured",
                "screen.agma_standalone.search_api_key_configured",
                "screen.agma_standalone.invalid_model_api_key",
                "screen.agma_standalone.invalid_search_api_key",
                "screen.agma_standalone.invalid_input_price",
                "screen.agma_standalone.invalid_output_price",
                "screen.agma_standalone.invalid_model_budget",
                "screen.agma_standalone.invalid_search_cost",
                "screen.agma_standalone.invalid_search_budget",
                "screen.agma_standalone.runtime_unconfigured",
                "screen.agma_standalone.runtime_unconfigured_detail",
                "screen.agma_standalone.runtime_stopped_state",
                "screen.agma_standalone.runtime_stopped_state_detail",
                "screen.agma_standalone.runtime_starting_state",
                "screen.agma_standalone.runtime_starting_state_detail",
                "screen.agma_standalone.runtime_connecting_state",
                "screen.agma_standalone.runtime_connecting_state_detail",
                "screen.agma_standalone.runtime_running_state",
                "screen.agma_standalone.runtime_running_state_detail",
                "screen.agma_standalone.runtime_running_counts",
                "screen.agma_standalone.runtime_recovering_state",
                "screen.agma_standalone.runtime_recovering_state_detail",
                "screen.agma_standalone.runtime_stopping_state",
                "screen.agma_standalone.runtime_stopping_state_detail",
                "screen.agma_standalone.runtime_error_state",
                "screen.agma_standalone.runtime_error_state_detail",
                "screen.agma_standalone.runtime_configure",
                "screen.agma_standalone.runtime_cancel_start",
                "screen.agma_standalone.runtime_restart",
                "screen.agma_standalone.runtime_wait",
                "screen.agma_standalone.runtime_retry")));

    try (var input = getClass().getClassLoader().getResourceAsStream("pack.mcmeta")) {
      assertNotNull(input);
      var pack = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      assertTrue(pack.isJsonObject());
      JsonObject packSection = pack.getAsJsonObject().getAsJsonObject("pack");
      assertNotNull(packSection);
      assertEquals(
          "AGMA Standalone Client resources", packSection.get("description").getAsString());
      assertEquals(8, packSection.get("pack_format").getAsInt());
    }
  }

  private static Set<String> languageKeys(String path) throws Exception {
    try (var input = StandaloneForgeMetadataTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(input);
      var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      assertTrue(json.isJsonObject());
      var object = json.getAsJsonObject();
      for (var entry : object.entrySet()) {
        var value = entry.getValue();
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString());
        assertFalse(value.getAsString().isBlank());
      }
      return Set.copyOf(object.keySet());
    }
  }

  private static String classConstants(Class<?> type) throws Exception {
    var path = "/" + type.getName().replace('.', '/') + ".class";
    try (var input = type.getResourceAsStream(path)) {
      assertNotNull(input);
      return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static void assertDependency(
      UnmodifiableConfig dependency, String modId, String versionRange) {
    assertEquals(modId, dependency.get("modId"));
    assertEquals(true, dependency.get("mandatory"));
    assertEquals(versionRange, dependency.get("versionRange"));
    assertEquals("NONE", dependency.get("ordering"));
    assertEquals("CLIENT", dependency.get("side"));
  }
}
