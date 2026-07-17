package dev.minecraftagent.paper.capability.load;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic.Code;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityReversalTest {
  @TempDir Path temporaryDirectory;

  @Test
  void acceptsAnIndependentlyApprovedCompatibleTarget() throws Exception {
    var root = createPack(temporaryDirectory, "valid-reversal");
    write(
        root,
        "replace.json",
        manifest(
            "worldedit.replace",
            "Replace",
            "player",
            null,
            "frozen_selection",
            "worldedit.undo",
            ">=7.3 <8"));
    write(root, "undo.json", manifest("worldedit.undo"));

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertEquals(
        Set.of("worldedit.replace", "worldedit.undo"), result.effectiveCapabilities().keySet());
  }

  @Test
  void requiresMatchingSourceEffectScopeAndNormalizedPluginRequirements() throws Exception {
    var root = createPack(temporaryDirectory, "incompatible-reversal");
    write(
        root,
        "replace.json",
        manifest(
            "worldedit.replace",
            "Replace",
            "player",
            null,
            "frozen_selection",
            "worldedit.undo",
            ">=7.3 <8"));
    write(
        root,
        "undo.json",
        manifest("worldedit.undo", "Undo", "player", null, "other_scope", "none", ">=7.3 <8"));

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(has(result, "replace.json", Code.REVERSAL_TARGET_INCOMPATIBLE));
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.undo"));
    assertTrue(!result.effectiveCapabilities().containsKey("worldedit.replace"));
  }

  @Test
  void cyclesDisableCycleMembersAndEveryUpstreamReference() throws Exception {
    var root = createPack(temporaryDirectory, "cyclic-reversal");
    write(
        root,
        "a.json",
        manifest(
            "worldedit.a", "A", "player", null, "frozen_selection", "worldedit.b", ">=7.3 <8"));
    write(
        root,
        "b.json",
        manifest(
            "worldedit.b", "B", "player", null, "frozen_selection", "worldedit.a", ">=7.3 <8"));
    write(
        root,
        "upstream.json",
        manifest(
            "worldedit.upstream",
            "Upstream",
            "player",
            null,
            "frozen_selection",
            "worldedit.a",
            ">=7.3 <8"));

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.effectiveCapabilities().isEmpty());
    assertTrue(has(result, "a.json", Code.REVERSAL_CYCLE));
    assertTrue(has(result, "b.json", Code.REVERSAL_CYCLE));
    assertTrue(has(result, "upstream.json", Code.REVERSAL_TARGET_UNAVAILABLE));
  }

  @Test
  void missingOrUnapprovedTargetsDisableTheReference() throws Exception {
    var root = createPack(temporaryDirectory, "unavailable-reversal");
    write(
        root,
        "missing.json",
        manifest(
            "worldedit.missing",
            "Missing",
            "player",
            null,
            "frozen_selection",
            "worldedit.absent",
            ">=7.3 <8"));

    var missing = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(has(missing, "missing.json", Code.REVERSAL_TARGET_MISSING));

    write(root, "target.json", manifest("worldedit.absent"));
    var unavailable =
        new CapabilityPackLoader(
                worldEdit("7.3.1", true), approval -> !approval.id().equals("worldedit.absent"))
            .load(root);
    assertTrue(has(unavailable, "missing.json", Code.REVERSAL_TARGET_UNAVAILABLE));
    assertTrue(has(unavailable, "target.json", Code.APPROVAL_REQUIRED));
  }

  private static boolean has(CapabilityLoadResult result, String source, Code code) {
    return result.drafts().stream()
        .filter(draft -> draft.source().equals(source))
        .flatMap(draft -> draft.diagnostics().stream())
        .anyMatch(diagnostic -> diagnostic.code() == code);
  }
}
