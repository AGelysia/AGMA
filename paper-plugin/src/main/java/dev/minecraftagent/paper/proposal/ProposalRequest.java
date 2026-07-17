package dev.minecraftagent.paper.proposal;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Untrusted creation input. Paper recomputes and compares {@code argumentHash}. */
public record ProposalRequest(
    String serverId,
    UUID requestId,
    UUID sessionId,
    UUID playerUuid,
    String tool,
    JsonObject arguments,
    String argumentHash) {
  private static final Pattern SERVER_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
  private static final Pattern TOOL_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");

  public ProposalRequest {
    Objects.requireNonNull(serverId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(tool);
    arguments = Objects.requireNonNull(arguments).deepCopy();
    Objects.requireNonNull(argumentHash);
    if (!SERVER_ID.matcher(serverId).matches()) {
      throw new IllegalArgumentException("Invalid server id");
    }
    if (!TOOL_ID.matcher(tool).matches() || tool.length() > 128) {
      throw new IllegalArgumentException("Invalid proposal tool id");
    }
  }

  @Override
  public JsonObject arguments() {
    return arguments.deepCopy();
  }

  @Override
  public String toString() {
    return "ProposalRequest[serverId="
        + serverId
        + ", requestId="
        + requestId
        + ", sessionId="
        + sessionId
        + ", playerUuid="
        + playerUuid
        + ", tool="
        + tool
        + ", arguments=<redacted>, argumentHash=<redacted>"
        + "]";
  }
}
