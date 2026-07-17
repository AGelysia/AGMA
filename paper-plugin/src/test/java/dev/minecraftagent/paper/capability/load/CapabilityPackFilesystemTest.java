package dev.minecraftagent.paper.capability.load;

import static dev.minecraftagent.paper.capability.TestCapabilityManifests.createPack;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.manifest;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.worldEdit;
import static dev.minecraftagent.paper.capability.TestCapabilityManifests.write;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic.Code;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityPackFilesystemTest {
  @TempDir Path temporaryDirectory;

  @Test
  void symlinkHardlinkAndWritableManifestFilesRemainDisabled() throws Exception {
    var root = createPack(temporaryDirectory, "filesystem-pack");
    write(root, "good.json", manifest("worldedit.good"));

    var outside = write(temporaryDirectory, "outside.json", manifest("worldedit.outside"));
    Files.createSymbolicLink(root.resolve("symlink.json"), outside);

    var hardlinkSource = write(temporaryDirectory, "hard-source.json", manifest("worldedit.hard"));
    Files.createLink(root.resolve("hard.json"), hardlinkSource);

    var writable = write(root, "writable.yaml", manifest("worldedit.writable"));
    Files.setPosixFilePermissions(
        writable,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE));

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);

    assertTrue(result.complete());
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.good"));
    assertTrue(has(result, "symlink.json", Code.PATH_UNSAFE));
    assertTrue(has(result, "hard.json", Code.FILE_HARD_LINKED));
    assertTrue(has(result, "writable.yaml", Code.FILE_NOT_PRIVATE));
  }

  @Test
  void rootSymlinkAndIncompleteTraversalCannotPublish() throws Exception {
    var target = createPack(temporaryDirectory, "target-pack");
    write(target, "good.json", manifest("worldedit.good"));
    var linkedRoot = temporaryDirectory.resolve("linked-pack");
    Files.createSymbolicLink(linkedRoot, target);

    var linked =
        new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(linkedRoot);
    assertFalse(linked.complete());
    assertTrue(linked.effectiveCapabilities().isEmpty());
    assertTrue(
        linked.globalDiagnostics().stream().anyMatch(value -> value.code() == Code.ROOT_UNSAFE));

    var limits = new CapabilityLoadLimits(2, 2, 4096, 8192, 1, 0, 16);
    var root = createPack(temporaryDirectory, "entry-limited-pack");
    write(root, "one.json", manifest("worldedit.one"));
    write(root, "two.json", manifest("worldedit.two"));
    write(root, "three.json", manifest("worldedit.three"));
    var limited =
        new CapabilityPackLoader(limits, worldEdit("7.3.1", true), ignored -> true).load(root);
    assertFalse(limited.complete());
    assertTrue(limited.effectiveCapabilities().isEmpty());
    assertTrue(
        limited.globalDiagnostics().stream()
            .anyMatch(value -> value.code() == Code.ENTRY_LIMIT_EXCEEDED));
  }

  @Test
  void duplicateYamlKeysAndAliasesAreRejectedBySafeConstructor() throws Exception {
    var root = createPack(temporaryDirectory, "yaml-security-pack");
    var duplicate =
        manifest("worldedit.duplicate-key")
            .replace("\"version\": 1,", "\"version\": 1,\n  \"version\": 2,");
    write(root, "duplicate.yaml", duplicate);

    var alias =
        """
        id: worldedit.alias
        version: 1
        description: &description Bounded operation
        requirements:
          plugins:
            - name: WorldEdit
              version: ">=7.3 <8"
        execution:
          type: command
          source: player
          commandRoot: undo
          template: /undo {value}
        arguments:
          value:
            type: string
            description: *description
            required: true
            minLength: 1
            maxLength: 16
        effects:
          category: WRITE_WORLD
          scope: frozen_selection
          maximumBlocks: 5000
        permissions:
          minimum: OP
        confirmation:
          required: true
        reversibility:
          type: none
        """;
    write(root, "alias.yml", alias);

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.complete());
    assertTrue(has(result, "duplicate.yaml", Code.YAML_INVALID));
    assertTrue(has(result, "alias.yml", Code.YAML_INVALID));
  }

  @Test
  void oversizedManifestIsDisabledWhileBoundedDiscoveryCompletes() throws Exception {
    var limits = new CapabilityLoadLimits(16, 8, 1024, 8192, 2, 0, 16);
    var root = createPack(temporaryDirectory, "size-pack");
    write(root, "large.json", " ".repeat(1025));
    write(root, "good.json", manifest("worldedit.good"));

    var result =
        new CapabilityPackLoader(limits, worldEdit("7.3.1", true), ignored -> true).load(root);
    assertTrue(result.complete());
    assertTrue(has(result, "large.json", Code.FILE_TOO_LARGE));
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.good"));
  }

  @Test
  void unsafeFilenameIsStableAndCannotCrashOrAliasAnotherSource() throws Exception {
    var root = createPack(temporaryDirectory, "filename-pack");
    write(root, "good.json", manifest("worldedit.good"));
    write(root, "bad\\name.json", manifest("worldedit.bad"));

    var result = new CapabilityPackLoader(worldEdit("7.3.1", true), ignored -> true).load(root);

    assertTrue(result.complete());
    assertTrue(result.effectiveCapabilities().containsKey("worldedit.good"));
    assertTrue(has(result, "bad%5Cname.json", Code.PATH_UNSAFE));
  }

  @Test
  void mutationBetweenDiscoveryPassesMakesTheWholeLoadNonPublishable() throws Exception {
    var root = createPack(temporaryDirectory, "mutating-pack");
    write(root, "initial.json", manifest("worldedit.initial"));

    var result =
        new CapabilityPackLoader(
                worldEdit("7.3.1", true),
                ignored -> {
                  try {
                    write(root, "late.json", manifest("worldedit.late"));
                    return true;
                  } catch (java.io.IOException exception) {
                    throw new UncheckedIOException(exception);
                  }
                })
            .load(root);

    assertFalse(result.complete());
    assertTrue(result.effectiveCapabilities().isEmpty());
    assertTrue(
        result.globalDiagnostics().stream().anyMatch(value -> value.code() == Code.ROOT_CHANGED));
  }

  private static boolean has(CapabilityLoadResult result, String source, Code code) {
    return result.drafts().stream()
        .filter(draft -> draft.source().equals(source))
        .flatMap(draft -> draft.diagnostics().stream())
        .anyMatch(diagnostic -> diagnostic.code() == code);
  }
}
