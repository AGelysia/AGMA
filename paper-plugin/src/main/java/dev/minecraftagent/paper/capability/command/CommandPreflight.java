package dev.minecraftagent.paper.capability.command;

import java.util.Objects;

/** Side-effect-free validation boundary for one fully rendered command. */
@FunctionalInterface
public interface CommandPreflight<S> {
  enum Status {
    VALID,
    REJECTED,
    UNAVAILABLE
  }

  record Result(Status status, String code) {
    public Result {
      Objects.requireNonNull(status);
      Objects.requireNonNull(code);
      if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
        throw new IllegalArgumentException("Invalid command preflight code");
      }
    }

    public static Result valid() {
      return new Result(Status.VALID, "COMMAND_PARSE_VALID");
    }

    public static Result rejected(String code) {
      return new Result(Status.REJECTED, code);
    }

    public static Result unavailable() {
      return new Result(Status.UNAVAILABLE, "COMMAND_PREFLIGHT_UNAVAILABLE");
    }

    public boolean validCommand() {
      return status == Status.VALID;
    }
  }

  Result validate(String commandRoot, String renderedCommand, S source);
}
