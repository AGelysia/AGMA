package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AdvancementTreeExtractorTest {
  private final AdvancementTreeExtractor extractor = new AdvancementTreeExtractor();

  @Test
  void rendersTreeWithResolvedTranslationsAndHiddenMarks() {
    var mod = mod(advancements());
    var result = extractor.extract(modpack(mod));
    assertEquals(1, result.documents().size());
    var document = result.documents().get(0);
    assertEquals("agma-modadv-testmod.md", document.fileName());
    assertEquals("Test Mod Advancements", document.title());
    var markdown = document.markdown();
    // zh_cn wins for the root title; en_us is the fallback for the child.
    assertTrue(markdown.contains("## 根源 (testmod:root)"), () -> markdown);
    assertTrue(markdown.contains("- 根源 — The root of it all (id: testmod:root)"), () -> markdown);
    assertTrue(
        markdown.contains(
            "- [hidden] Child — child description (id: testmod:child, parent: testmod:root)"),
        () -> markdown);
    // The raw key is the last resort when no locale knows it.
    assertTrue(markdown.contains("advancement.testmod.unknown.title"), () -> markdown);
    // The explicit component fallback beats the raw key.
    assertTrue(markdown.contains("- Fallback Title"), () -> markdown);
    // Advancements without a display object never render.
    assertFalse(markdown.contains("testmod:nodisplay"), () -> markdown);
  }

  @Test
  void groupsOrphansUnderUngrouped() {
    var mod = mod(advancements());
    var document = extractor.extract(modpack(mod)).documents().get(0);
    var markdown = document.markdown();
    var ungrouped = markdown.indexOf("## Ungrouped");
    assertTrue(ungrouped > markdown.indexOf("## 根源"), () -> markdown);
    var orphan = markdown.indexOf("testmod:orphan");
    assertTrue(orphan > ungrouped, () -> markdown);
  }

  @Test
  void skipsModsWithoutDisplayedAdvancements() {
    var mod =
        new UnpackedMod(
            "plain",
            "Plain",
            "1.0",
            "plain.jar",
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            List.of(new UnpackedAdvancement("plain:task", Map.of("criteria", Map.of()))));
    var result = extractor.extract(modpack(mod));
    assertTrue(result.documents().isEmpty());
    assertEquals(0, result.modsSeen());
  }

  @Test
  void boundsAdvancementsPerMod() {
    var advancements = new ArrayList<UnpackedAdvancement>();
    for (var index = 0; index < 300; index++) {
      advancements.add(
          new UnpackedAdvancement(
              "testmod:a" + index,
              Map.of(
                  "display",
                  Map.of("title", Map.of("translate", "t" + index), "description", "d"))));
    }
    var mod =
        new UnpackedMod(
            "testmod",
            "Test Mod",
            "1.0",
            "testmod.jar",
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            advancements);
    var document = extractor.extract(modpack(mod)).documents().get(0);
    var markdown = document.markdown();
    assertFalse(
        markdown.contains(
            "testmod:a" + (AdvancementTreeExtractor.MAXIMUM_ADVANCEMENTS_PER_MOD + 1)));
    var lines = markdown.lines().filter(line -> line.startsWith("- ")).count();
    assertTrue(
        lines <= AdvancementTreeExtractor.MAXIMUM_ADVANCEMENTS_PER_MOD,
        () -> "rendered lines " + lines);
  }

  private static UnpackedModpack modpack(UnpackedMod mod) {
    return new UnpackedModpack(List.of(mod), ScanReport.empty());
  }

  private static UnpackedMod mod(List<UnpackedAdvancement> advancements) {
    var lang =
        Map.of(
            "en_us",
            Map.of(
                "advancement.testmod.root.title", "Root",
                "advancement.testmod.root.description", "The root of it all",
                "advancement.testmod.child.title", "Child"),
            "zh_cn",
            Map.of("advancement.testmod.root.title", "根源"));
    return new UnpackedMod(
        "testmod",
        "Test Mod",
        "1.0",
        "testmod.jar",
        List.of(),
        List.of(),
        lang,
        List.of(),
        advancements);
  }

  private static List<UnpackedAdvancement> advancements() {
    return List.of(
        new UnpackedAdvancement(
            "testmod:root",
            BoundedJson.parseObject(
                "{\"display\":{\"title\":{\"translate\":\"advancement.testmod.root.title\"},"
                    + "\"description\":{\"translate\":\"advancement.testmod.root.description\"}}}")),
        new UnpackedAdvancement(
            "testmod:child",
            BoundedJson.parseObject(
                "{\"parent\":\"testmod:root\",\"display\":{\"hidden\":true,"
                    + "\"title\":{\"translate\":\"advancement.testmod.child.title\"},"
                    + "\"description\":\"child description\"}}")),
        new UnpackedAdvancement(
            "testmod:unknown",
            BoundedJson.parseObject(
                "{\"parent\":\"testmod:root\",\"display\":{\"title\":{\"translate\":\"advancement.testmod.unknown.title\"},"
                    + "\"description\":\"d\"}}")),
        new UnpackedAdvancement(
            "testmod:fallback",
            BoundedJson.parseObject(
                "{\"parent\":\"testmod:root\",\"display\":{\"title\":{\"translate\":\"advancement.testmod.missing\",\"fallback\":\"Fallback Title\"},"
                    + "\"description\":\"d\"}}")),
        new UnpackedAdvancement(
            "testmod:orphan",
            BoundedJson.parseObject(
                "{\"parent\":\"testmod:missing\",\"display\":{\"title\":\"Orphan\",\"description\":\"d\"}}")),
        new UnpackedAdvancement("testmod:nodisplay", Map.of("criteria", Map.of())));
  }
}
