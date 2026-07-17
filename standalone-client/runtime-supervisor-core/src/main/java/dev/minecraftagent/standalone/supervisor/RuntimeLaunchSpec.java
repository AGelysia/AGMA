package dev.minecraftagent.standalone.supervisor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable launch input. Environment values are deliberately redacted from {@link #toString()}.
 */
public record RuntimeLaunchSpec(
    UUID instanceId,
    Path stateRoot,
    Path executable,
    List<String> arguments,
    Map<String, String> environment) {
  private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final int MAX_ARGUMENTS = 64;
  private static final int MAX_ARGUMENT_LENGTH = 4096;
  private static final int MAX_ENVIRONMENT_ENTRIES = 32;
  private static final int MAX_ENVIRONMENT_VALUE_LENGTH = 16 * 1024;

  public RuntimeLaunchSpec {
    Objects.requireNonNull(instanceId, "instanceId");
    stateRoot = absoluteNormalized(stateRoot, "stateRoot");
    executable = absoluteNormalized(executable, "executable");
    arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    if (arguments.size() > MAX_ARGUMENTS || environment.size() > MAX_ENVIRONMENT_ENTRIES) {
      throw new IllegalArgumentException("Runtime launch input exceeds its entry limit");
    }
    for (var argument : arguments) {
      if (argument == null
          || argument.isEmpty()
          || argument.length() > MAX_ARGUMENT_LENGTH
          || containsControl(argument)) {
        throw new IllegalArgumentException("Runtime argument is invalid");
      }
    }
    for (var entry : environment.entrySet()) {
      if (!ENVIRONMENT_NAME.matcher(entry.getKey()).matches()
          || entry.getValue().isEmpty()
          || entry.getValue().length() > MAX_ENVIRONMENT_VALUE_LENGTH
          || entry.getValue().indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Runtime environment entry is invalid");
      }
    }
  }

  @Override
  public String toString() {
    return "RuntimeLaunchSpec[instanceId="
        + instanceId
        + ", stateRoot="
        + stateRoot
        + ", executable="
        + executable
        + ", arguments="
        + arguments.size()
        + ", environmentKeys="
        + environment.keySet().stream().sorted().toList()
        + "]";
  }

  private static Path absoluteNormalized(Path value, String name) {
    Objects.requireNonNull(value, name);
    var normalized = value.normalize();
    if (!normalized.isAbsolute() || !normalized.equals(value)) {
      throw new IllegalArgumentException(name + " must be an absolute normalized path");
    }
    return normalized;
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(character -> Character.isISOControl(character));
  }
}
