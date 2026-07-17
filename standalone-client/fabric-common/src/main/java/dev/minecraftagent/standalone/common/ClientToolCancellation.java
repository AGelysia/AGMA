package dev.minecraftagent.standalone.common;

import java.util.Objects;
import java.util.UUID;

public record ClientToolCancellation(
    UUID requestId, UUID toolCallId, UUID subjectId, String tool, int sequence, Reason reason) {
  public ClientToolCancellation {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(subjectId, "subjectId");
    tool = ClientToolPayloads.requireTool(tool);
    if (sequence < 0 || sequence > 7 || requestId.equals(toolCallId)) {
      throw new IllegalArgumentException("Client tool cancellation identity is invalid");
    }
    Objects.requireNonNull(reason, "reason");
  }

  public enum Reason {
    REQUEST_CANCELLED,
    MODEL_TIMEOUT,
    TOOL_TIMEOUT,
    RUNTIME_SHUTDOWN
  }
}
