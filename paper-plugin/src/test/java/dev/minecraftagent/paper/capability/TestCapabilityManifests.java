package dev.minecraftagent.paper.capability;

import dev.minecraftagent.paper.capability.load.CapabilityApprovalStore;
import dev.minecraftagent.paper.capability.load.CapabilityPackLoader;
import dev.minecraftagent.paper.capability.load.InstalledPluginInventory;
import dev.minecraftagent.paper.capability.load.InstalledPluginInventory.InstalledPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

public final class TestCapabilityManifests {
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

  private TestCapabilityManifests() {}

  public static String manifest(String id) {
    return manifest(
        id, "Undo one bounded operation.", "player", null, "frozen_selection", "none", ">=7.3 <8");
  }

  public static String manifest(
      String id,
      String description,
      String source,
      String status,
      String scope,
      String reversalId,
      String versionRange) {
    var statusProperty = status == null ? "" : "\"status\": \"" + status + "\",";
    var reversal =
        "none".equals(reversalId)
            ? "{\"type\": \"none\"}"
            : "{\"type\": \"capability\", \"capability\": \"" + reversalId + "\"}";
    return """
        {
          "id": "%s",
          "version": 1,
          "description": "%s",
          %s
          "requirements": {
            "plugins": [{"name": "WorldEdit", "version": "%s"}]
          },
          "execution": {
            "type": "command",
            "source": "%s",
            "commandRoot": "undo",
            "template": "/undo"
          },
          "arguments": {},
          "effects": {
            "category": "WRITE_WORLD",
            "scope": "%s",
            "maximumBlocks": 5000
          },
          "permissions": {"minimum": "OP"},
          "confirmation": {"required": true},
          "reversibility": %s
        }
        """
        .formatted(id, description, statusProperty, versionRange, source, scope, reversal);
  }

  public static Path createPack(Path parent, String name) throws IOException {
    var root = Files.createDirectory(parent.resolve(name));
    Files.setPosixFilePermissions(
        root,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    return root;
  }

  public static Path write(Path root, String name, String source) throws IOException {
    var file = Files.writeString(root.resolve(name), source);
    Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
    return file;
  }

  public static InstalledPluginInventory worldEdit(String version, boolean enabled) {
    return () -> List.of(new InstalledPlugin("WorldEdit", version, enabled));
  }

  public static CapabilityPackLoader loader(
      InstalledPluginInventory inventory, CapabilityApprovalStore approvals) {
    return new CapabilityPackLoader(inventory, approvals);
  }
}
