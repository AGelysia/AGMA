package dev.minecraftagent.standalone.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.catalog.ResourceKey;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CatalogAssemblerTest {
  private static final String GENERATION = "generation-assembler-1";
  private static final String FINGERPRINT = "3".repeat(64);
  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private final CatalogAssembler assembler = new CatalogAssembler();

  @Test
  void twoContributionOverloadBehavesLikeBefore() {
    var stone = item("minecraft:stone", "Stone", registrySource());
    var gravel = item("minecraft:gravel", "Gravel", registrySource());
    var process = process("test:smash", item("minecraft:gravel", "Gravel", processSource()));
    var snapshot =
        assembler.assemble(
            GENERATION,
            FINGERPRINT,
            NOW,
            contribution("minecraft_registry", List.of(stone, gravel), List.of()),
            contribution("vanilla_client", List.of(), List.of(process)));
    assertEquals(1, snapshot.processes().size());
    assertEquals(2, snapshot.resources().size());
  }

  @Test
  void gapProcessesAreAddedOnlyWhenAbsent() {
    var base = item("minecraft:stone", "Stone", registrySource());
    var gravel = item("minecraft:gravel", "Gravel", registrySource());
    var selected = process("test:shared", item("minecraft:gravel", "Gravel", processSource()));
    var gapOnly = process("test:gap_only", item("test:dust", "Dust", gapSource()));
    var gapDuplicate = process("test:shared", item("test:dust", "Fake", gapSource()));
    var snapshot =
        assembler.assemble(
            GENERATION,
            FINGERPRINT,
            NOW,
            contribution("minecraft_registry", List.of(base, gravel), List.of()),
            contribution("vanilla_client", List.of(), List.of(selected)),
            contribution(
                "mod_archive",
                List.of(item("test:dust", "Dust", gapSource())),
                List.of(gapOnly, gapDuplicate)));
    // The base process with the same id wins; the unique gap process is added.
    assertEquals(2, snapshot.processes().size());
    assertEquals(
        "minecraft:gravel",
        snapshot.process("test:shared").orElseThrow().outputs().get(0).resource().id());
    assertTrue(snapshot.process("test:gap_only").isPresent());
  }

  @Test
  void gapResourcesAreAddedOnlyWhenAbsentWithoutMetadataCheck() {
    var base = item("minecraft:stone", "Stone", registrySource());
    // Same key as the base resource but with deliberately conflicting metadata; the base must win
    // silently instead of failing the assembly.
    var conflicting =
        new ResourceRef(
            ResourceRef.Kind.ITEM,
            "minecraft:stone",
            null,
            "Definitely Not Stone",
            null,
            "othermod",
            "Other Mod",
            "0.0",
            BigDecimal.ONE,
            "item",
            gapSource());
    var gapOnly = item("test:dust", "Dust", gapSource());
    var snapshot =
        assembler.assemble(
            GENERATION,
            FINGERPRINT,
            NOW,
            contribution("minecraft_registry", List.of(base), List.of()),
            contribution("vanilla_client", List.of(), List.of()),
            contribution("mod_archive", List.of(conflicting, gapOnly), List.of()));
    var stone = snapshot.resource(new ResourceKey(ResourceRef.Kind.ITEM, "minecraft:stone", null));
    assertTrue(stone.isPresent());
    assertEquals("Stone", stone.get().displayName());
    assertTrue(
        snapshot.resource(new ResourceKey(ResourceRef.Kind.ITEM, "test:dust", null)).isPresent());
  }

  @Test
  void baseDisagreementsStillFail() {
    var stone = item("minecraft:stone", "Stone", registrySource());
    var conflicting =
        new ResourceRef(
            ResourceRef.Kind.ITEM,
            "minecraft:stone",
            null,
            "Stone",
            "item.minecraft.stone",
            "othermod",
            "Other Mod",
            "0.0",
            BigDecimal.ONE,
            "item",
            processSource());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            assembler.assemble(
                GENERATION,
                FINGERPRINT,
                NOW,
                contribution("minecraft_registry", List.of(stone), List.of()),
                contribution("vanilla_client", List.of(conflicting), List.of())));
  }

  @Test
  void selectedDuplicateProcessIdsStillFail() {
    var process = process("test:dup", item("minecraft:gravel", "Gravel", processSource()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            assembler.assemble(
                GENERATION,
                FINGERPRINT,
                NOW,
                contribution("minecraft_registry", List.of(), List.of()),
                contribution("vanilla_client", List.of(), List.of(process, process))));
  }

  @Test
  void gapContributionMustShareTheGeneration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            assembler.assemble(
                GENERATION,
                FINGERPRINT,
                NOW,
                contribution("minecraft_registry", List.of(), List.of()),
                contribution("vanilla_client", List.of(), List.of()),
                new CatalogAdapter.Contribution(
                    "mod_archive", "other-generation", List.of(), List.of(), List.of())));
  }

  private static CatalogAdapter.Contribution contribution(
      String adapterId, List<ResourceRef> resources, List<ProcessRecord> processes) {
    return new CatalogAdapter.Contribution(adapterId, GENERATION, resources, processes, List.of());
  }

  private static ResourceRef.Source registrySource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_REGISTRY,
        "vanilla_client",
        ResourceRef.Trust.L0B,
        ResourceRef.Completeness.COMPLETE,
        GENERATION);
  }

  private static ResourceRef.Source processSource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_RECIPE,
        "vanilla_client",
        ResourceRef.Trust.L1,
        ResourceRef.Completeness.PARTIAL,
        GENERATION);
  }

  private static ResourceRef.Source gapSource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.LOCAL_RESOURCE_PACK,
        "mod_archive",
        ResourceRef.Trust.L2,
        ResourceRef.Completeness.PARTIAL,
        GENERATION);
  }

  private static ResourceRef item(String id, String displayName, ResourceRef.Source source) {
    var namespace = id.substring(0, id.indexOf(':'));
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id,
        null,
        displayName,
        "item." + id.replace(':', '.'),
        namespace,
        namespace,
        "1.0",
        BigDecimal.ONE,
        "item",
        source);
  }

  private static ProcessRecord process(String processId, ResourceRef output) {
    return new ProcessRecord(
        processId,
        "test:machine",
        output.displayName() + " recipe",
        List.of(),
        List.of(),
        List.of(),
        List.of(new ProcessRecord.Output(output, BigDecimal.ONE, true)),
        null,
        null,
        List.of(),
        List.of(),
        output.source(),
        true,
        List.of());
  }
}
