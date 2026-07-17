package dev.minecraftagent.paper.capability.command;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Uses Brigadier parse only; this class never calls dispatcher.execute. */
public final class BrigadierCommandPreflight<S> implements CommandPreflight<S> {
  private static final Pattern ROOT = Pattern.compile("^[a-z0-9:_-]{1,64}$");
  private static final int MAX_COMMAND_LENGTH = 1024;

  private final Supplier<CommandDispatcher<S>> dispatcher;

  public BrigadierCommandPreflight(Supplier<CommandDispatcher<S>> dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
  }

  @Override
  public Result validate(String commandRoot, String renderedCommand, S source) {
    Objects.requireNonNull(commandRoot);
    Objects.requireNonNull(renderedCommand);
    Objects.requireNonNull(source);
    if (!ROOT.matcher(commandRoot).matches()
        || renderedCommand.length() < 2
        || renderedCommand.length() > MAX_COMMAND_LENGTH
        || renderedCommand.charAt(0) != '/'
        || renderedCommand.codePoints().anyMatch(Character::isISOControl)) {
      return Result.rejected("COMMAND_RENDER_INVALID");
    }
    var command = renderedCommand.substring(1);
    if (!command.equals(command.strip())
        || !(command.equals(commandRoot) || command.startsWith(commandRoot + " "))) {
      return Result.rejected("COMMAND_ROOT_MISMATCH");
    }

    final CommandDispatcher<S> current;
    try {
      current = dispatcher.get();
    } catch (RuntimeException error) {
      return Result.unavailable();
    }
    if (current == null) {
      return Result.unavailable();
    }
    if (current.getRoot().getChild(commandRoot) == null) {
      return Result.rejected("COMMAND_ROOT_UNKNOWN");
    }

    try {
      var parsed = current.parse(command, source);
      if (parsed.getReader().getCursor() != command.length()) {
        return Result.rejected("COMMAND_PARSE_INCOMPLETE");
      }
      var context = parsed.getContext().build(command);
      if (context.getCommand() == null || !context.hasNodes()) {
        return Result.rejected("COMMAND_PARSE_INCOMPLETE");
      }
      return Result.valid();
    } catch (RuntimeException error) {
      return Result.rejected("COMMAND_PARSE_REJECTED");
    }
  }
}
