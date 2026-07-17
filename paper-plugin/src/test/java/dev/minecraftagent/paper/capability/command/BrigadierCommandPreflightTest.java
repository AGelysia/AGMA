package dev.minecraftagent.paper.capability.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BrigadierCommandPreflightTest {
  private final AtomicInteger executions = new AtomicInteger();
  private final CommandDispatcher<Source> dispatcher = dispatcher();
  private final BrigadierCommandPreflight<Source> preflight =
      new BrigadierCommandPreflight<>(() -> dispatcher);

  @Test
  void completeParseIsValidWithoutExecutingTheCommand() {
    var result = preflight.validate("bounded", "/bounded 3", new Source(true));

    assertTrue(result.validCommand());
    assertEquals("COMMAND_PARSE_VALID", result.code());
    assertEquals(0, executions.get());
  }

  @Test
  void rejectsUnknownRootsIncompleteInputLeftoversAndDeniedSources() {
    assertEquals(
        "COMMAND_ROOT_UNKNOWN",
        preflight.validate("missing", "/missing 3", new Source(true)).code());
    assertEquals(
        "COMMAND_PARSE_INCOMPLETE",
        preflight.validate("bounded", "/bounded", new Source(true)).code());
    assertEquals(
        "COMMAND_PARSE_INCOMPLETE",
        preflight.validate("bounded", "/bounded 3 trailing", new Source(true)).code());
    assertEquals(
        "COMMAND_PARSE_INCOMPLETE",
        preflight.validate("bounded", "/bounded 3", new Source(false)).code());
    assertEquals(0, executions.get());
  }

  @Test
  void rejectsMalformedRenderedCommandsAndUnavailableDispatcher() {
    assertEquals(
        "COMMAND_ROOT_MISMATCH",
        preflight.validate("bounded", "/other 3", new Source(true)).code());
    assertEquals(
        "COMMAND_RENDER_INVALID",
        preflight.validate("bounded", "/bounded 3\nstop", new Source(true)).code());
    assertFalse(
        new BrigadierCommandPreflight<Source>(() -> null)
            .validate("bounded", "/bounded 3", new Source(true))
            .validCommand());
    assertEquals(0, executions.get());
  }

  private CommandDispatcher<Source> dispatcher() {
    var result = new CommandDispatcher<Source>();
    result.register(
        LiteralArgumentBuilder.<Source>literal("bounded")
            .requires(Source::allowed)
            .then(
                RequiredArgumentBuilder.<Source, Integer>argument("amount", integer(1, 5))
                    .executes(
                        ignored -> {
                          executions.incrementAndGet();
                          return 1;
                        })));
    return result;
  }

  private record Source(boolean allowed) {}
}
