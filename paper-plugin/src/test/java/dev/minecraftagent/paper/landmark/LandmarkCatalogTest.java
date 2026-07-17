package dev.minecraftagent.paper.landmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LandmarkCatalogTest {
  @Test
  void filtersPrivateRowsBeforeCountsAndSortsSameDimensionByLiveDistance() {
    var catalog =
        new LandmarkCatalog(
            List.of(
                landmark("far", "Market Far", "minecraft:overworld", 20, null),
                landmark("private", "Market Private", "minecraft:overworld", 1, "agent.vip"),
                landmark("near", "Market Near", "minecraft:overworld", 3, null),
                landmark("nether", "Market Nether", "minecraft:the_nether", 0, null)));

    var hidden = catalog.search("market", "minecraft:overworld", 0, 64, 0, ignored -> false);
    assertEquals(3, hidden.get("totalMatches").getAsInt());
    assertEquals(
        "near",
        hidden.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(
        "far", hidden.getAsJsonArray("landmarks").get(1).getAsJsonObject().get("id").getAsString());
    assertEquals(
        "nether",
        hidden.getAsJsonArray("landmarks").get(2).getAsJsonObject().get("id").getAsString());
    assertTrue(
        hidden.getAsJsonArray("landmarks").get(2).getAsJsonObject().get("distance").isJsonNull());
    assertFalse(hidden.toString().contains("agent.vip"));
    assertFalse(hidden.toString().contains("private"));

    var allowed = catalog.search("market", "minecraft:overworld", 0, 64, 0, "agent.vip"::equals);
    assertEquals(4, allowed.get("totalMatches").getAsInt());
    assertEquals(
        "private",
        allowed.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("id").getAsString());
    assertNull(allowed.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("permission"));
  }

  @Test
  void matchesAliasesAndTagsWithoutResolvingAmbiguityForTheModel() {
    var catalog =
        new LandmarkCatalog(
            List.of(
                new Landmark(
                    "north-shop",
                    "North Shop",
                    List.of("market"),
                    List.of("trade"),
                    "minecraft:overworld",
                    10,
                    64,
                    0,
                    null),
                new Landmark(
                    "south-shop",
                    "South Shop",
                    List.of("market"),
                    List.of("trade"),
                    "minecraft:overworld",
                    -10,
                    64,
                    0,
                    null)));

    var result = catalog.search("market trade", "minecraft:overworld", 0, 64, 0, ignored -> false);
    assertEquals(2, result.get("totalMatches").getAsInt());
    assertEquals(2, result.getAsJsonArray("landmarks").size());
  }

  private static Landmark landmark(
      String id, String name, String dimension, double x, String permission) {
    return new Landmark(id, name, List.of(), List.of("trade"), dimension, x, 64, 0, permission);
  }
}
