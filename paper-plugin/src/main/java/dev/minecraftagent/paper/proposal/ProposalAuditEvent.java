package dev.minecraftagent.paper.proposal;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Structured audit metadata. This type deliberately has no arguments, summary, or free-text field.
 */
public record ProposalAuditEvent(
    Type type,
    Instant timestamp,
    UUID proposalId,
    UUID requestId,
    UUID playerUuid,
    String tool,
    RiskLevel risk,
    long catalogGeneration,
    String outcomeCode) {
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  public enum Type {
    CREATED,
    CLAIM_AUTHORIZED,
    EXECUTED,
    REJECTED,
    INVALIDATED
  }

  public ProposalAuditEvent {
    Objects.requireNonNull(type);
    Objects.requireNonNull(timestamp);
    Objects.requireNonNull(proposalId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(risk);
    Objects.requireNonNull(outcomeCode);
    if (!CODE.matcher(outcomeCode).matches()) {
      throw new IllegalArgumentException("Audit outcome must be a non-sensitive code");
    }
  }

  /** Serializes only the fixed audit allowlist; no reflective object serialization is required. */
  public JsonObject toJson() {
    var json = new JsonObject();
    json.addProperty("type", type.name());
    json.addProperty("timestamp", timestamp.toString());
    json.addProperty("proposalId", proposalId.toString());
    json.addProperty("requestId", requestId.toString());
    json.addProperty("playerUuid", playerUuid.toString());
    json.addProperty("tool", tool);
    json.addProperty("risk", risk.name());
    json.addProperty("catalogGeneration", catalogGeneration);
    json.addProperty("outcomeCode", outcomeCode);
    return json;
  }
}
