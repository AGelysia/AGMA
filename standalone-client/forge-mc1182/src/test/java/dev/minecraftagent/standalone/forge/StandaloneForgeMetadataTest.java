package dev.minecraftagent.standalone.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    assertEquals(true, descriptor.get("showAsResourcePack"));
    assertFalse(descriptor.contains("mixins"));

    List<? extends UnmodifiableConfig> mods = descriptor.get("mods");
    assertEquals(1, mods.size());
    var mod = mods.get(0);
    assertEquals("agma_standalone", mod.get("modId"));
    assertEquals("AGMA Standalone Client", mod.get("displayName"));
    assertEquals(System.getProperty("agma.expectedVersion"), mod.get("version"));

    List<? extends UnmodifiableConfig> dependencies =
        descriptor.get(List.of("dependencies", "agma_standalone"));
    assertEquals(2, dependencies.size());
    assertDependency(dependencies.get(0), "forge", "[40.3.12,41)");
    assertDependency(dependencies.get(1), "minecraft", "[1.18.2,1.18.3)");
    assertFalse(descriptor.toString().contains("fabric"));
    assertFalse(descriptor.toString().contains("paper"));
  }

  @Test
  void primaryClassesLoadWithoutViewerApisAndDoNotLinkThem() throws Exception {
    assertNotNull(StandaloneForgeMod.class.getDeclaredConstructor());
    assertNotNull(StandaloneCatalogService.class.getDeclaredConstructor());
    for (var type : List.of(StandaloneForgeMod.class, StandaloneCatalogService.class)) {
      var path = "/" + type.getName().replace('.', '/') + ".class";
      try (var input = type.getResourceAsStream(path)) {
        assertNotNull(input);
        var constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("mezz/jei"));
        assertFalse(constants.contains("dev/emi"));
        assertFalse(constants.contains("net/fabricmc"));
        assertTrue(constants.contains("agma"));
      }
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
