package dev.minecraftagent.paper.proposal;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A catalog binding whose executor can receive only decoded, tool-specific argument values. */
public final class TypedProposalTool<A> {
  private static final Pattern TOOL_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");
  private static final Pattern PERMISSION = Pattern.compile("^[a-z0-9_.-]{3,128}$");
  private static final Pattern RESULT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  @FunctionalInterface
  public interface ArgumentDecoder<A> {
    DecodeResult<A> decode(JsonObject arguments);
  }

  @FunctionalInterface
  public interface ExecutionGuard<A> {
    Decision check(ExecutionContext context, A arguments);
  }

  @FunctionalInterface
  public interface Executor<A> {
    ExecutionResult execute(ExecutionContext context, A arguments);
  }

  public record DecodeResult<A>(A value, String rejectionCode) {
    public DecodeResult {
      if ((value == null) == (rejectionCode == null)) {
        throw new IllegalArgumentException("Decode result must contain one outcome");
      }
      if (rejectionCode != null) {
        requireCode(rejectionCode);
      }
    }

    public static <A> DecodeResult<A> decoded(A value) {
      return new DecodeResult<>(Objects.requireNonNull(value), null);
    }

    public static <A> DecodeResult<A> rejected(String code) {
      return new DecodeResult<>(null, code);
    }

    public boolean accepted() {
      return value != null;
    }
  }

  public record Decision(boolean allowed, String code) {
    public Decision {
      requireCode(code);
      if (allowed && !"STATE_VALID".equals(code)) {
        throw new IllegalArgumentException("Allowed guard result must use STATE_VALID");
      }
    }

    public static Decision valid() {
      return new Decision(true, "STATE_VALID");
    }

    public static Decision rejected(String code) {
      return new Decision(false, code);
    }
  }

  public record ExecutionResult(Status status, String code) {
    public enum Status {
      SUCCEEDED,
      REJECTED,
      FAILED
    }

    public ExecutionResult {
      Objects.requireNonNull(status);
      requireCode(code);
    }

    public static ExecutionResult succeeded(String code) {
      return new ExecutionResult(Status.SUCCEEDED, code);
    }

    public static ExecutionResult rejected(String code) {
      return new ExecutionResult(Status.REJECTED, code);
    }

    public static ExecutionResult failed(String code) {
      return new ExecutionResult(Status.FAILED, code);
    }
  }

  public record ExecutionContext(
      String serverId,
      UUID requestId,
      UUID sessionId,
      UUID proposalId,
      UUID playerUuid,
      String argumentHash,
      Instant createdAt,
      Instant expiresAt,
      Instant executionTime) {
    public ExecutionContext {
      Objects.requireNonNull(serverId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(sessionId);
      Objects.requireNonNull(proposalId);
      Objects.requireNonNull(playerUuid);
      Objects.requireNonNull(argumentHash);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(executionTime);
    }
  }

  private final String id;
  private final long catalogGeneration;
  private final RiskLevel risk;
  private final String requiredPermission;
  private final String displayName;
  private final ArgumentDecoder<A> decoder;
  private final ExecutionGuard<A> guard;
  private final Executor<A> executor;

  public TypedProposalTool(
      String id,
      long catalogGeneration,
      RiskLevel risk,
      String requiredPermission,
      String displayName,
      ArgumentDecoder<A> decoder,
      ExecutionGuard<A> guard,
      Executor<A> executor) {
    this.id = Objects.requireNonNull(id);
    this.catalogGeneration = catalogGeneration;
    this.risk = Objects.requireNonNull(risk);
    this.requiredPermission = Objects.requireNonNull(requiredPermission);
    this.displayName = Objects.requireNonNull(displayName);
    this.decoder = Objects.requireNonNull(decoder);
    this.guard = Objects.requireNonNull(guard);
    this.executor = Objects.requireNonNull(executor);
    if (!TOOL_ID.matcher(id).matches() || id.length() > 128) {
      throw new IllegalArgumentException("Invalid proposal tool id");
    }
    if (catalogGeneration < 0) {
      throw new IllegalArgumentException("Invalid catalog generation");
    }
    if (!risk.requiresProposal()) {
      throw new IllegalArgumentException("Read tools cannot create proposals");
    }
    if (!PERMISSION.matcher(requiredPermission).matches()) {
      throw new IllegalArgumentException("Invalid proposal permission");
    }
    if (displayName.isBlank()
        || displayName.length() > 128
        || displayName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid proposal display name");
    }
  }

  public String id() {
    return id;
  }

  public long catalogGeneration() {
    return catalogGeneration;
  }

  public RiskLevel risk() {
    return risk;
  }

  public String requiredPermission() {
    return requiredPermission;
  }

  public String displayName() {
    return displayName;
  }

  String validate(JsonObject arguments) {
    try {
      var decoded = decoder.decode(arguments.deepCopy());
      return decoded.accepted() ? null : decoded.rejectionCode();
    } catch (RuntimeException error) {
      return "ARGUMENTS_INVALID";
    }
  }

  ExecutionResult execute(ExecutionContext context, JsonObject arguments) {
    final DecodeResult<A> decoded;
    try {
      decoded = decoder.decode(arguments.deepCopy());
    } catch (RuntimeException error) {
      return ExecutionResult.rejected("ARGUMENTS_INVALID");
    }
    if (!decoded.accepted()) {
      return ExecutionResult.rejected(decoded.rejectionCode());
    }
    final Decision guarded;
    try {
      guarded = guard.check(context, decoded.value());
    } catch (RuntimeException error) {
      return ExecutionResult.failed("STATE_CHECK_FAILED");
    }
    if (!guarded.allowed()) {
      return ExecutionResult.rejected(guarded.code());
    }
    try {
      return Objects.requireNonNull(executor.execute(context, decoded.value()));
    } catch (RuntimeException error) {
      return ExecutionResult.failed("EXECUTION_FAILED");
    }
  }

  private static void requireCode(String code) {
    Objects.requireNonNull(code);
    if (!RESULT_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Invalid non-sensitive result code");
    }
  }
}
