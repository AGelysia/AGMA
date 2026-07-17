package dev.minecraftagent.paper.capability.load;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.loader;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic.Code;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.PermissionMinimum;
import dev.minecraftagent.paper.proposal.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityPackLoaderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void loadsJsonAndYamlThroughTheSameCanonicalTypedPipeline() throws Exception {
    var jsonRoot = createPack(temporaryDirectory, "json-pack");
    var yamlRoot = createPack(temporaryDirectory, "yaml-pack");
    write(jsonRoot, "undo.json", manifest("worldedit.undo"));
    write(yamlRoot, "undo.yaml", "# YAML comment\n" + manifest("worldedit.undo"));

    var json = loader(worldEdit("7.3.1", true), ignored -> true).load(jsonRoot);
    var yaml = loader(worldEdit("7.3.1", true), ignored -> true).load(yamlRoot);

    assertTrue(json.complete());
    assertTrue(yaml.complete());
    assertEquals(Set.of("worldedit.undo"), json.effectiveCapabilities().keySet());
    assertEquals(
        json.effectiveCapabilities().get("worldedit.undo").identity(),
        yaml.effectiveCapabilities().get("worldedit.undo").identity());
    var policy = json.effectiveCapabilities().get("worldedit.undo").policy();
    assertEquals(RiskLevel.WRITE_WORLD, policy.proposalRisk());
    assertEquals(PermissionMinimum.OP, policy.minimum());
    assertTrue(policy.confirmationRequired());
    assertEquals(5000, policy.maximumBlocks().orElseThrow());
  }

  @Test
  void approvalIsBoundToIdVersionAndCanonicalContentHash() throws Exception {
    var root = createPack(temporaryDirectory, "approval-pack");
    var file = write(root, "undo.json", manifest("worldedit.undo"));
    var approvalsSeen = new ArrayList<CapabilityApproval>();
    var first =
        loader(worldEdit("7.3.1", true), approval -> approvalsSeen.add(approval)).load(root);
    var approved = approvalsSeen.getFirst();

    assertTrue(first.effectiveCapabilities().containsKey("worldedit.undo"));
    assertEquals(
        first.effectiveCapabilities().get("worldedit.undo").identity().contentSha256(),
        approved.contentSha256());

    write(
        root,
        "undo.json",
        manifest("worldedit.undo").replace("Undo one bounded operation.", "Changed content."));
    var changed = loader(worldEdit("7.3.1", true), approved::equals).load(root);

    assertFalse(changed.effectiveCapabilities().containsKey("worldedit.undo"));
    assertTrue(has(changed, "undo.json", Code.APPROVAL_REQUIRED));
    assertNotEquals(
        approved.contentSha256(),
        changed.drafts().stream()
            .filter(draft -> draft.source().equals("undo.json"))
            .findFirst()
            .orElseThrow()
            .identity()
            .orElseThrow()
            .contentSha256());
  }

  @Test
  void draftConsoleMissingDisabledAndMismatchedPluginsNeverBecomeEffective() throws Exception {
    var root = createPack(temporaryDirectory, "disabled-pack");
    write(
        root,
        "draft.json",
        manifest(
            "worldedit.draft", "Draft", "player", "draft", "frozen_selection", "none", ">=7.3 <8"));
    write(
        root,
        "example.json",
        manifest(
            "worldedit.example",
            "Example",
            "player",
            "example",
            "frozen_selection",
            "none",
            ">=7.3 <8"));
    write(
        root,
        "console.json",
        manifest(
            "worldedit.console",
            "Console",
            "console",
            null,
            "frozen_selection",
            "none",
            ">=7.3 <8"));

    var result = loader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.effectiveCapabilities().isEmpty());
    assertTrue(has(result, "draft.json", Code.DRAFT_ONLY));
    assertTrue(has(result, "example.json", Code.EXAMPLE_ONLY));
    assertTrue(has(result, "console.json", Code.CONSOLE_SOURCE_DISABLED));

    var disabledRoot = createPack(temporaryDirectory, "plugin-disabled-pack");
    write(disabledRoot, "undo.json", manifest("worldedit.undo"));
    var disabled = loader(worldEdit("7.3.1", false), ignored -> true).load(disabledRoot);
    assertTrue(has(disabled, "undo.json", Code.PLUGIN_UNAVAILABLE));

    var mismatch = loader(worldEdit("8", true), ignored -> true).load(disabledRoot);
    assertTrue(has(mismatch, "undo.json", Code.PLUGIN_VERSION_MISMATCH));

    var missing = loader(List::of, ignored -> true).load(disabledRoot);
    assertTrue(has(missing, "undo.json", Code.PLUGIN_MISSING));
  }

  @Test
  void approvalOrInventoryAuthorityFailureKeepsTheLoadNonPublishable() throws Exception {
    var root = createPack(temporaryDirectory, "authority-pack");
    write(root, "undo.json", manifest("worldedit.undo"));

    var approvalFailure =
        loader(
                worldEdit("7.3.1", true),
                ignored -> {
                  throw new IllegalStateException("unavailable");
                })
            .load(root);
    assertFalse(approvalFailure.complete());
    assertTrue(approvalFailure.effectiveCapabilities().isEmpty());
    assertTrue(
        approvalFailure.globalDiagnostics().stream()
            .anyMatch(value -> value.code() == Code.APPROVAL_SOURCE_UNAVAILABLE));

    var inventoryFailure =
        loader(
                () -> {
                  throw new IllegalStateException("unavailable");
                },
                ignored -> true)
            .load(root);
    assertFalse(inventoryFailure.complete());
    assertTrue(
        inventoryFailure.globalDiagnostics().stream()
            .anyMatch(value -> value.code() == Code.PLUGIN_INVENTORY_UNAVAILABLE));
  }

  @Test
  void rejectsClosedSemanticPolicyAndVersionViolationsBeforeApproval() throws Exception {
    var root = createPack(temporaryDirectory, "semantic-pack");
    var argument =
        "\"value\": {\"type\": \"string\", \"description\": \"Value\", \"required\": true,"
            + " \"minLength\": 1, \"maxLength\": 16}";
    var withArgument =
        manifest("worldedit.argument")
            .replace("\"template\": \"/undo\"", "\"template\": \"/undo {value}\"")
            .replace("\"arguments\": {}", "\"arguments\": {" + argument + "}");
    write(root, "optional.json", withArgument.replace("\"required\": true", "\"required\": false"));
    write(root, "injection.json", withArgument.replace("/undo {value}", "/undo fixed; {value}"));
    write(
        root,
        "temporary-blocks.json",
        manifest("worldedit.temporary").replace("WRITE_WORLD", "WRITE_TEMPORARY"));
    write(
        root,
        "bad-range.json",
        manifest(
            "worldedit.range", "Range", "player", null, "frozen_selection", "none", ">=07.3 <8"));
    var numberArgument =
        "\"value\": {\"type\": \"number\", \"description\": \"Value\", \"required\": true,"
            + " \"minimum\": 1e999, \"maximum\": 1e1000}";
    write(
        root,
        "number.json",
        manifest("worldedit.number")
            .replace("\"template\": \"/undo\"", "\"template\": \"/undo {value}\"")
            .replace("\"arguments\": {}", "\"arguments\": {" + numberArgument + "}"));

    var result = loader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.complete());
    assertTrue(result.effectiveCapabilities().isEmpty());
    assertTrue(has(result, "optional.json", Code.POLICY_INCONSISTENT));
    assertTrue(has(result, "injection.json", Code.TEMPLATE_INVALID));
    assertTrue(has(result, "temporary-blocks.json", Code.POLICY_INCONSISTENT));
    assertTrue(has(result, "bad-range.json", Code.PLUGIN_VERSION_RANGE_INVALID));
    assertTrue(has(result, "number.json", Code.MANIFEST_VALUE_INVALID));
  }

  @Test
  void rejectsDuplicateIdsAndInvalidUtf8WithoutPublishingThoseEntries() throws Exception {
    var root = createPack(temporaryDirectory, "invalid-pack");
    write(root, "one.json", manifest("worldedit.duplicate"));
    write(root, "two.yaml", manifest("worldedit.duplicate"));
    var invalid = Files.write(root.resolve("utf8.json"), new byte[] {(byte) 0xc3, (byte) 0x28});
    Files.setPosixFilePermissions(
        invalid, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    var result = loader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.complete());
    assertTrue(result.effectiveCapabilities().isEmpty());
    assertTrue(has(result, "one.json", Code.DUPLICATE_ID));
    assertTrue(has(result, "two.yaml", Code.DUPLICATE_ID));
    assertTrue(has(result, "utf8.json", Code.UTF8_INVALID));
  }

  @Test
  void rejectsNumbersThatWouldCollideUnderJcsAndNormalizesNegativeZero() throws Exception {
    var root = createPack(temporaryDirectory, "jcs-number-pack");
    write(
        root, "decimal-collision.json", numberManifest("worldedit.decimal", "0.10000000000000001"));
    write(root, "integer-collision.json", numberManifest("worldedit.large", "9007199254740993"));
    write(root, "decimal-exact.json", numberManifest("worldedit.exact_decimal", "0.1"));
    write(
        root, "integer-exact.json", numberManifest("worldedit.exact_integer", "9007199254740992"));
    write(root, "negative-zero.json", numberManifest("worldedit.zero", "-0.0"));

    var result = loader(worldEdit("7.3.1", true), ignored -> true).load(root);

    assertTrue(has(result, "decimal-collision.json", Code.MANIFEST_VALUE_INVALID));
    assertTrue(has(result, "integer-collision.json", Code.MANIFEST_VALUE_INVALID));
    assertTrue(
        result.drafts().stream()
            .filter(
                draft ->
                    draft.source().equals("decimal-collision.json")
                        || draft.source().equals("integer-collision.json"))
            .allMatch(draft -> draft.identity().isEmpty()));
    assertTrue(
        result.effectiveCapabilities().containsKey("worldedit.exact_decimal"),
        () -> result.drafts().toString());
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.exact_integer"));
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.zero"));
    var zero =
        (dev.minecraftagent.paper.capability.model.CapabilityManifest.NumberArgument)
            result
                .effectiveCapabilities()
                .get("worldedit.zero")
                .manifest()
                .arguments()
                .get("value");
    assertEquals(0, zero.minimum().signum());
    assertEquals("0", zero.minimum().toString());
  }

  private static String numberManifest(String id, String value) {
    var descriptor =
        "\"value\": {\"type\": \"number\", \"description\": \"Value\", \"required\": true,"
            + " \"minimum\": "
            + value
            + ", \"maximum\": "
            + value
            + "}";
    return manifest(id)
        .replace("\"template\": \"/undo\"", "\"template\": \"/undo {value}\"")
        .replace("\"arguments\": {}", "\"arguments\": {" + descriptor + "}");
  }

  private static boolean has(CapabilityLoadResult result, String source, Code code) {
    return result.drafts().stream()
        .filter(draft -> draft.source().equals(source))
        .flatMap(draft -> draft.diagnostics().stream())
        .anyMatch(diagnostic -> diagnostic.code() == code);
  }
}
