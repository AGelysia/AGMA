package dev.minecraftagent.paper.capability.registry;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.load.CapabilityPackLoader;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityRegistryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void previewsAndAtomicallyPublishesAddedRemovedChangedAndUnchangedIds() throws Exception {
    var firstRoot = createPack(temporaryDirectory, "first-pack");
    write(firstRoot, "a.json", manifest("worldedit.a"));
    write(firstRoot, "b.json", manifest("worldedit.b"));
    write(firstRoot, "removed.json", manifest("worldedit.removed"));
    var loader = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true);
    var registry = new CapabilityRegistry();

    var first = registry.preview(loader.load(firstRoot));
    assertEquals(Set.of("worldedit.a", "worldedit.b", "worldedit.removed"), first.diff().added());
    assertEquals(CapabilityRegistry.PublishStatus.PUBLISHED, registry.publish(first).status());
    assertEquals(1, registry.snapshot().generation());

    var secondRoot = createPack(temporaryDirectory, "second-pack");
    write(
        secondRoot,
        "a.json",
        manifest("worldedit.a").replace("Undo one bounded operation.", "Changed."));
    write(secondRoot, "b.json", manifest("worldedit.b"));
    write(secondRoot, "added.json", manifest("worldedit.added"));
    var second = registry.preview(loader.load(secondRoot));

    assertEquals(Set.of("worldedit.added"), second.diff().added());
    assertEquals(Set.of("worldedit.removed"), second.diff().removed());
    assertEquals(Set.of("worldedit.a"), second.diff().changed());
    assertEquals(Set.of("worldedit.b"), second.diff().unchanged());
    assertEquals(CapabilityRegistry.PublishStatus.PUBLISHED, registry.publish(second).status());
    assertEquals(2, registry.snapshot().generation());
    assertEquals(
        CapabilityRegistry.LookupStatus.EFFECTIVE, registry.lookup("worldedit.a").status());
    assertEquals(registry.snapshot().generation(), registry.lookup("worldedit.a").generation());
  }

  @Test
  void staleOrIncompletePreviewsNeverReplaceTheCurrentSnapshot() throws Exception {
    var root = createPack(temporaryDirectory, "atomic-pack");
    write(root, "a.json", manifest("worldedit.a"));
    var loader = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true);
    var registry = new CapabilityRegistry();
    var firstPreview = registry.preview(loader.load(root));
    var stalePreview = registry.preview(loader.load(root));

    assertEquals(
        CapabilityRegistry.PublishStatus.PUBLISHED, registry.publish(firstPreview).status());
    var published = registry.snapshot();
    assertEquals(CapabilityRegistry.PublishStatus.STALE, registry.publish(stalePreview).status());
    assertSame(published, registry.snapshot());

    var unavailable =
        new CapabilityPackLoader(
                worldEdit("7.3.1", true),
                ignored -> {
                  throw new IllegalStateException("approval unavailable");
                })
            .load(root);
    var incomplete = registry.preview(unavailable);
    assertFalse(incomplete.publishable());
    assertEquals(CapabilityRegistry.PublishStatus.REJECTED, registry.publish(incomplete).status());
    assertSame(published, registry.snapshot());
  }

  @Test
  void lookupDistinguishesDisabledDraftsFromUnknownProposalOnlyIds() throws Exception {
    var root = createPack(temporaryDirectory, "draft-pack");
    write(
        root,
        "draft.json",
        manifest(
            "worldedit.draft", "Draft", "player", "draft", "frozen_selection", "none", ">=7.3 <8"));
    var registry = new CapabilityRegistry();
    var preview =
        registry.preview(
            new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root));
    assertTrue(preview.publishable());
    registry.publish(preview);

    assertEquals(
        CapabilityRegistry.LookupStatus.DISABLED_DRAFT,
        registry.lookup("worldedit.draft").status());
    assertEquals(
        CapabilityRegistry.LookupStatus.UNKNOWN_PROPOSAL_ONLY,
        registry.lookup("worldedit.unknown").status());
    assertEquals(
        CapabilityRegistry.LookupStatus.UNKNOWN_PROPOSAL_ONLY,
        registry.lookup("not-an-id").status());
    assertEquals(
        CapabilityRegistry.LookupStatus.UNKNOWN_PROPOSAL_ONLY,
        registry.lookup("a." + "a".repeat(127)).status());
    assertEquals(
        registry.snapshot().generation(), registry.lookup("a." + "a".repeat(127)).generation());
  }
}
