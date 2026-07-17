package dev.minecraftagent.paper.capability.argument;

import java.util.Optional;

/** A stable, value-free failure raised while compiling or rendering capability arguments. */
public final class CapabilityArgumentException extends IllegalArgumentException {
  private final Failure failure;
  private final String argumentName;

  CapabilityArgumentException(Failure failure, String argumentName) {
    super("Capability argument failure: " + failure);
    this.failure = failure;
    this.argumentName = safeArgumentName(argumentName) ? argumentName : null;
  }

  public Failure failure() {
    return failure;
  }

  public Optional<String> argumentName() {
    return Optional.ofNullable(argumentName);
  }

  private static boolean safeArgumentName(String value) {
    return value != null && value.matches("^[a-z][a-zA-Z0-9_]{0,63}$");
  }

  public enum Failure {
    DESCRIPTOR_INVALID,
    OPTIONAL_ARGUMENT_UNSUPPORTED,
    TEMPLATE_INVALID,
    ARGUMENT_MISSING,
    ARGUMENT_UNDECLARED,
    ARGUMENT_TYPE_INVALID,
    ARGUMENT_RANGE_INVALID,
    ARGUMENT_TOKEN_INVALID,
    COMMAND_TOO_LONG
  }
}
