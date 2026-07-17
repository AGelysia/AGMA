package dev.minecraftagent.paper.capability.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable, data-only representation of one capability manifest. */
public record CapabilityManifest(
    String id,
    int version,
    String description,
    Optional<ManifestStatus> status,
    List<PluginRequirement> pluginRequirements,
    Execution execution,
    Map<String, ArgumentDefinition> arguments,
    Effects effects,
    Permission permission,
    Confirmation confirmation,
    Reversibility reversibility) {
  public CapabilityManifest {
    Objects.requireNonNull(id);
    Objects.requireNonNull(description);
    status = Objects.requireNonNull(status);
    pluginRequirements = List.copyOf(pluginRequirements);
    Objects.requireNonNull(execution);
    arguments = Collections.unmodifiableMap(new TreeMap<>(arguments));
    Objects.requireNonNull(effects);
    Objects.requireNonNull(permission);
    Objects.requireNonNull(confirmation);
    Objects.requireNonNull(reversibility);
  }

  public enum ManifestStatus {
    EXAMPLE,
    DRAFT
  }

  public record PluginRequirement(String name, String versionRange) {
    public PluginRequirement {
      Objects.requireNonNull(name);
      Objects.requireNonNull(versionRange);
    }
  }

  public record Execution(ExecutionSource source, String commandRoot, String template) {
    public Execution {
      Objects.requireNonNull(source);
      Objects.requireNonNull(commandRoot);
      Objects.requireNonNull(template);
    }
  }

  public enum ExecutionSource {
    PLAYER,
    CONSOLE
  }

  public sealed interface ArgumentDefinition
      permits StringArgument,
          IntegerArgument,
          NumberArgument,
          BooleanArgument,
          EnumArgument,
          MinecraftArgument {
    String description();

    boolean required();
  }

  public record StringArgument(
      String description, boolean required, int minimumLength, int maximumLength)
      implements ArgumentDefinition {
    public StringArgument {
      Objects.requireNonNull(description);
    }
  }

  public record IntegerArgument(String description, boolean required, int minimum, int maximum)
      implements ArgumentDefinition {
    public IntegerArgument {
      Objects.requireNonNull(description);
    }
  }

  public record NumberArgument(
      String description, boolean required, BigDecimal minimum, BigDecimal maximum)
      implements ArgumentDefinition {
    public NumberArgument {
      Objects.requireNonNull(description);
      Objects.requireNonNull(minimum);
      Objects.requireNonNull(maximum);
    }
  }

  public record BooleanArgument(String description, boolean required)
      implements ArgumentDefinition {
    public BooleanArgument {
      Objects.requireNonNull(description);
    }
  }

  public record EnumArgument(String description, boolean required, List<String> values)
      implements ArgumentDefinition {
    public EnumArgument {
      Objects.requireNonNull(description);
      values = List.copyOf(values);
    }
  }

  public record MinecraftArgument(MinecraftArgumentType type, String description, boolean required)
      implements ArgumentDefinition {
    public MinecraftArgument {
      Objects.requireNonNull(type);
      Objects.requireNonNull(description);
    }
  }

  public enum MinecraftArgumentType {
    BLOCK_PATTERN("minecraft:block-pattern"),
    ITEM("minecraft:item"),
    PLAYER("minecraft:player"),
    DIMENSION("minecraft:dimension"),
    COORDINATES("minecraft:coordinates");

    private final String manifestName;

    MinecraftArgumentType(String manifestName) {
      this.manifestName = manifestName;
    }

    public String manifestName() {
      return manifestName;
    }
  }

  public record Effects(RiskCategory category, String scope, Optional<Integer> maximumBlocks) {
    public Effects {
      Objects.requireNonNull(category);
      Objects.requireNonNull(scope);
      maximumBlocks = Objects.requireNonNull(maximumBlocks);
    }
  }

  public enum RiskCategory {
    READ,
    WRITE_TEMPORARY,
    WRITE_WORLD,
    WRITE_PLAYER,
    SERVER_ADMIN
  }

  public record Permission(PermissionMinimum minimum, Optional<String> node) {
    public Permission {
      Objects.requireNonNull(minimum);
      node = Objects.requireNonNull(node);
    }
  }

  public enum PermissionMinimum {
    ANY,
    PERMISSION,
    OP,
    OWNER
  }

  public record Confirmation(boolean required) {}

  public record Reversibility(ReversibilityType type, Optional<String> capabilityId) {
    public Reversibility {
      Objects.requireNonNull(type);
      capabilityId = Objects.requireNonNull(capabilityId);
    }
  }

  public enum ReversibilityType {
    NONE,
    CAPABILITY
  }
}
