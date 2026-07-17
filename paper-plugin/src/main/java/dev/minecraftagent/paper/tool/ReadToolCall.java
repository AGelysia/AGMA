package dev.minecraftagent.paper.tool;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.request.AgentModule;
import java.util.Objects;
import java.util.UUID;

public record ReadToolCall(
    UUID toolCallId,
    UUID requestId,
    UUID sessionId,
    UUID playerUuid,
    AgentModule module,
    String tool,
    JsonObject arguments,
    int sequence) {
  public ReadToolCall {
    Objects.requireNonNull(toolCallId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(module);
    Objects.requireNonNull(tool);
    arguments = Objects.requireNonNull(arguments).deepCopy();
    if (sequence < 0 || sequence > 7) {
      throw new IllegalArgumentException("Invalid tool sequence");
    }
  }
}
