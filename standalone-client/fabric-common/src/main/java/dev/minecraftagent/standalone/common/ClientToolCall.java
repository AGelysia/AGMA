package dev.minecraftagent.standalone.common;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ClientToolCall(
    UUID requestId,
    UUID toolCallId,
    UUID subjectId,
    String tool,
    int sequence,
    Map<String, Object> arguments) {
  public ClientToolCall {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(subjectId, "subjectId");
    tool = ClientToolPayloads.requireTool(tool);
    if (sequence < 0 || sequence > 7 || requestId.equals(toolCallId)) {
      throw new IllegalArgumentException("Client tool call identity is invalid");
    }
    arguments = ClientToolPayloads.copyObject(arguments, 8);
    ClientToolPayloads.validateArguments(tool, arguments);
  }
}
