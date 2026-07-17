package dev.minecraftagent.paper.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.minecraftagent.paper.client.ClientCapabilitySnapshot;
import dev.minecraftagent.paper.client.ClientProtocolException;
import dev.minecraftagent.paper.client.ClientStructuredView;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import dev.minecraftagent.paper.transport.StrictJson;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Encodes Paper-originated requests and validates terminal Runtime responses. */
public final class AgentProtocolCodec {
  public static final int MAX_APPLICATION_BYTES = 64 * 1024;

  private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
  private static final Duration REPLAY_RETENTION = Duration.ofSeconds(60);
  private static final int MAX_REPLAY_ENTRIES = 2048;
  private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "protocolVersion",
          "messageId",
          "requestId",
          "serverId",
          "type",
          "timestamp",
          "nonce",
          "payload");
  private static final Set<String> COMPLETION_FIELDS =
      Set.of("sessionId", "playerUuid", "fallbackText", "structuredViews");
  private static final Set<String> ERROR_FIELDS =
      Set.of("playerUuid", "code", "fallbackText", "retryable");
  private static final Set<String> SESSION_RESUMED_FIELDS = Set.of("sessionId", "playerUuid");
  private static final Set<String> TOOL_CALL_FIELDS =
      Set.of("toolCallId", "sessionId", "playerUuid", "module", "tool", "arguments", "sequence");
  private static final Set<String> COSTS_FIELDS = Set.of("currentDay", "currentMonth", "budget");
  private static final Set<String> USAGE_WINDOW_FIELDS =
      Set.of(
          "period",
          "admittedRequests",
          "providerCalls",
          "reportedProviderCalls",
          "estimatedProviderCalls",
          "inputTokens",
          "outputTokens",
          "costMicroUsd");
  private static final Set<String> BUDGET_FIELDS =
      Set.of(
          "month",
          "limitMicroUsd",
          "settledMicroUsd",
          "activeReservationsMicroUsd",
          "remainingMicroUsd",
          "exhausted");
  private static final Pattern TOOL_ID = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
  private static final Pattern DAY_PERIOD =
      Pattern.compile("[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])");
  private static final Pattern MONTH_PERIOD = Pattern.compile("[0-9]{4}-(?:0[1-9]|1[0-2])");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private final String serverId;
  private final SecureRandom secureRandom;
  private final Supplier<Instant> now;
  private final ReplayWindow replayWindow = new ReplayWindow();

  public AgentProtocolCodec(String serverId) {
    this(serverId, new SecureRandom(), Instant::now);
  }

  AgentProtocolCodec(String serverId, SecureRandom secureRandom, Supplier<Instant> now) {
    this.serverId = Objects.requireNonNull(serverId);
    this.secureRandom = Objects.requireNonNull(secureRandom);
    this.now = Objects.requireNonNull(now);
    if (!SERVER_ID.matcher(serverId).matches()) {
      throw new IllegalArgumentException("Invalid server ID");
    }
  }

  public String encodeRequest(UUID requestId, UUID playerUuid, String message) {
    return encodeRequest(requestId, playerUuid, null, AgentModule.GENERAL, message);
  }

  public String encodeRequest(
      UUID requestId, UUID playerUuid, UUID sessionId, AgentModule module, String message) {
    return encodeRequest(
        requestId, playerUuid, sessionId, module, message, ClientCapabilitySnapshot.disconnected());
  }

  public String encodeRequest(
      UUID requestId,
      UUID playerUuid,
      UUID sessionId,
      AgentModule module,
      String message,
      ClientCapabilitySnapshot clientCapabilities) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(module);
    Objects.requireNonNull(clientCapabilities);
    requireText(message, 4096);

    var payload = new JsonObject();
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    payload.addProperty("playerUuid", playerUuid.toString());
    payload.addProperty("module", module.protocolName());
    payload.addProperty("message", message);
    payload.add("clientCapabilities", clientCapabilities.toAgentRequestJson());

    return encodeEnvelope(requestId, requestId, "agent.request", payload);
  }

  public String encodeResume(UUID requestId, UUID playerUuid, UUID sessionId) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);

    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerUuid.toString());
    if (sessionId == null) {
      payload.add("sessionId", JsonNull.INSTANCE);
    } else {
      payload.addProperty("sessionId", sessionId.toString());
    }
    return encodeEnvelope(requestId, requestId, "session.resume", payload);
  }

  public String encodeCostsRequest(UUID requestId) {
    Objects.requireNonNull(requestId);
    return encodeEnvelope(requestId, requestId, "management.costs.request", new JsonObject());
  }

  public String encodeCancel(UUID requestId, UUID playerUuid, CancelReason reason) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(reason);

    var payload = new JsonObject();
    payload.addProperty("playerUuid", playerUuid.toString());
    payload.addProperty("reason", reason.name());
    return encodeEnvelope(newMessageId(requestId), requestId, "agent.cancel", payload);
  }

  public String encodeToolResult(UUID requestId, ReadToolCall call, ReadToolResult toolResult) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(call);
    Objects.requireNonNull(toolResult);
    try {
      return encodeToolResultPayload(requestId, call, toolResult);
    } catch (RuntimeConnectionFailure failure) {
      if (!"APPLICATION_MESSAGE_TOO_LARGE".equals(failure.code())
          || toolResult.status() != ReadToolResult.Status.SUCCEEDED) {
        throw failure;
      }
      return encodeToolResultPayload(
          requestId,
          call,
          ReadToolResult.failed(
              call.tool().startsWith("server.recipe.")
                  ? ReadToolResult.Source.SERVER_REGISTRY
                  : ReadToolResult.Source.PAPER_API,
              "TOOL_RESULT_TOO_LARGE",
              "The tool result exceeded the application frame limit.",
              false));
    }
  }

  private String encodeToolResultPayload(
      UUID requestId, ReadToolCall call, ReadToolResult toolResult) {
    var payload = new JsonObject();
    payload.addProperty("toolCallId", call.toolCallId().toString());
    payload.addProperty("sessionId", call.sessionId().toString());
    payload.addProperty("playerUuid", call.playerUuid().toString());
    payload.addProperty("tool", call.tool());
    payload.addProperty("sequence", call.sequence());
    payload.addProperty("status", toolResult.status().protocolName());
    payload.addProperty("source", toolResult.source().protocolName());
    payload.addProperty("trust", toolResult.trust().protocolName());
    if (toolResult.result() == null) {
      payload.add("result", JsonNull.INSTANCE);
    } else {
      payload.add("result", toolResult.result().deepCopy());
    }
    if (toolResult.error() == null) {
      payload.add("error", JsonNull.INSTANCE);
    } else {
      var error = new JsonObject();
      error.addProperty("code", toolResult.error().code());
      error.addProperty("message", toolResult.error().message());
      error.addProperty("retryable", toolResult.error().retryable());
      payload.add("error", error);
    }
    // Runtime's strict JSON scanner accepts 4096 structural tokens for the full envelope.
    if (toolResult.status() == ReadToolResult.Status.SUCCEEDED && jsonTokens(payload) > 3500) {
      throw failure("APPLICATION_MESSAGE_TOO_LARGE");
    }
    return encodeEnvelope(newMessageId(requestId), requestId, "tool.result", payload);
  }

  private static int jsonTokens(JsonElement value) {
    if (value.isJsonObject()) {
      var total = 1;
      for (var entry : value.getAsJsonObject().entrySet()) {
        total = Math.addExact(total, 1 + jsonTokens(entry.getValue()));
      }
      return total;
    }
    if (value.isJsonArray()) {
      var total = 1;
      for (var element : value.getAsJsonArray()) {
        total = Math.addExact(total, jsonTokens(element));
      }
      return total;
    }
    return 1;
  }

  public InboundMessage decode(String source) {
    requireWireText(source);
    var root = StrictJson.parseObject(source, "PROTOCOL_MESSAGE_INVALID", "application-protocol");
    requireFields(root, ENVELOPE_FIELDS);
    if (!"1.0".equals(string(root, "protocolVersion"))) {
      throw failure("PROTOCOL_INCOMPATIBLE");
    }

    var messageId = uuid(root, "messageId");
    var requestId = uuid(root, "requestId");
    if (messageId.equals(requestId)) {
      throw invalid();
    }
    if (!serverId.equals(string(root, "serverId"))) {
      throw failure("SERVER_ID_MISMATCH");
    }

    var timestamp = instant(root, "timestamp");
    var current = now.get();
    if (Duration.between(current, timestamp).abs().compareTo(CLOCK_SKEW) > 0) {
      throw failure("PROTOCOL_MESSAGE_STALE");
    }
    var nonce = randomValue(root, "nonce");
    if (!replayWindow.accept(messageId.toString(), nonce, current)) {
      throw failure("PROTOCOL_MESSAGE_REPLAYED");
    }

    var payload = object(root, "payload");
    return switch (string(root, "type")) {
      case "agent.complete" -> decodeCompletion(messageId, requestId, payload);
      case "agent.error" -> decodeError(messageId, requestId, payload);
      case "session.resumed" -> decodeSessionResumed(messageId, requestId, payload);
      case "tool.call" -> decodeToolCall(messageId, requestId, payload);
      case "management.costs.response" -> decodeManagementCosts(messageId, requestId, payload);
      default -> throw failure("UNSUPPORTED_MESSAGE_TYPE");
    };
  }

  private Completion decodeCompletion(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, COMPLETION_FIELDS);
    var sessionId = nullableUuid(payload, "sessionId");
    var playerUuid = uuid(payload, "playerUuid");
    var fallbackText = boundedText(payload, "fallbackText", 8192);
    var views = array(payload, "structuredViews");
    if (views.size() > 8) {
      throw invalid();
    }
    var structuredViews = new ArrayList<ClientStructuredView>(views.size());
    var viewIds = new HashSet<UUID>();
    try {
      for (var value : views) {
        if (!value.isJsonObject()) {
          throw invalid();
        }
        var view = ClientStructuredView.fromJson(value.getAsJsonObject());
        if (!view.requestId().equals(requestId)
            || !view.fallbackText().equals(fallbackText)
            || !viewIds.add(view.viewId())) {
          throw invalid();
        }
        structuredViews.add(view);
      }
    } catch (ClientProtocolException error) {
      throw invalid();
    }
    return new Completion(
        messageId, requestId, sessionId, playerUuid, fallbackText, List.copyOf(structuredViews));
  }

  private AgentError decodeError(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, ERROR_FIELDS);
    var playerUuid = uuid(payload, "playerUuid");
    AgentErrorCode code;
    try {
      code = AgentErrorCode.valueOf(string(payload, "code"));
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    var fallbackText = boundedText(payload, "fallbackText", 512);
    return new AgentError(
        messageId, requestId, playerUuid, code, fallbackText, bool(payload, "retryable"));
  }

  private SessionResumed decodeSessionResumed(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, SESSION_RESUMED_FIELDS);
    return new SessionResumed(
        messageId, requestId, uuid(payload, "sessionId"), uuid(payload, "playerUuid"));
  }

  private ToolCall decodeToolCall(UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, TOOL_CALL_FIELDS);
    var toolCallId = uuid(payload, "toolCallId");
    var sessionId = uuid(payload, "sessionId");
    var playerUuid = uuid(payload, "playerUuid");
    var moduleName = string(payload, "module");
    var module =
        AgentModule.fromProtocolName(moduleName)
            .filter(value -> value.protocolName().equals(moduleName))
            .orElseThrow(AgentProtocolCodec::invalid);
    var tool = string(payload, "tool");
    if (tool.length() < 3 || tool.length() > 128 || !TOOL_ID.matcher(tool).matches()) {
      throw invalid();
    }
    var arguments = object(payload, "arguments");
    if (arguments.size() > 64) {
      throw invalid();
    }
    var sequence = integer(payload, "sequence", 0, 7);
    return new ToolCall(
        messageId,
        requestId,
        playerUuid,
        new ReadToolCall(
            toolCallId, requestId, sessionId, playerUuid, module, tool, arguments, sequence));
  }

  private ManagementCosts decodeManagementCosts(
      UUID messageId, UUID requestId, JsonObject payload) {
    requireFields(payload, COSTS_FIELDS);
    try {
      var currentDay = usageWindow(object(payload, "currentDay"));
      var currentMonth = usageWindow(object(payload, "currentMonth"));
      var budget = budget(object(payload, "budget"));
      return new ManagementCosts(messageId, requestId, currentDay, currentMonth, budget);
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static UsageWindow usageWindow(JsonObject payload) {
    requireFields(payload, USAGE_WINDOW_FIELDS);
    return new UsageWindow(
        string(payload, "period"),
        nonnegativeInteger(payload, "admittedRequests"),
        nonnegativeInteger(payload, "providerCalls"),
        nonnegativeInteger(payload, "reportedProviderCalls"),
        nonnegativeInteger(payload, "estimatedProviderCalls"),
        nonnegativeInteger(payload, "inputTokens"),
        nonnegativeInteger(payload, "outputTokens"),
        nonnegativeInteger(payload, "costMicroUsd"));
  }

  private static Budget budget(JsonObject payload) {
    requireFields(payload, BUDGET_FIELDS);
    return new Budget(
        string(payload, "month"),
        nonnegativeInteger(payload, "limitMicroUsd"),
        nonnegativeInteger(payload, "settledMicroUsd"),
        nonnegativeInteger(payload, "activeReservationsMicroUsd"),
        nonnegativeInteger(payload, "remainingMicroUsd"),
        bool(payload, "exhausted"));
  }

  private String encodeEnvelope(UUID messageId, UUID requestId, String type, JsonObject payload) {
    var root = new JsonObject();
    root.addProperty("protocolVersion", "1.0");
    root.addProperty("messageId", messageId.toString());
    root.addProperty("requestId", requestId.toString());
    root.addProperty("serverId", serverId);
    root.addProperty("type", type);
    root.addProperty("timestamp", now.get().toString());
    root.addProperty("nonce", newNonce());
    root.add("payload", payload);
    var encoded = root.toString();
    requireWireText(encoded);
    return encoded;
  }

  private UUID newMessageId(UUID requestId) {
    UUID messageId;
    do {
      messageId = UUID.randomUUID();
    } while (messageId.equals(requestId));
    return messageId;
  }

  private String newNonce() {
    var bytes = new byte[16];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void requireWireText(String source) {
    Objects.requireNonNull(source);
    if (source.length() > MAX_APPLICATION_BYTES) {
      throw failure("APPLICATION_MESSAGE_TOO_LARGE");
    }
    try {
      var bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(source))
              .remaining();
      if (bytes > MAX_APPLICATION_BYTES) {
        throw failure("APPLICATION_MESSAGE_TOO_LARGE");
      }
    } catch (CharacterCodingException error) {
      throw invalid();
    }
  }

  private static void requireText(String value, int maximumCodePoints) {
    Objects.requireNonNull(value);
    var codePoints = value.codePointCount(0, value.length());
    if (value.isBlank()
        || codePoints > maximumCodePoints
        || value.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff)) {
      throw new IllegalArgumentException("Invalid Agent text");
    }
  }

  private static String boundedText(JsonObject object, String name, int maximumCodePoints) {
    var value = string(object, name);
    if (value.isBlank()
        || value.codePointCount(0, value.length()) > maximumCodePoints
        || value.codePoints().anyMatch(AgentProtocolCodec::unsafeFallbackCodePoint)) {
      throw invalid();
    }
    return value;
  }

  private static boolean unsafeFallbackCodePoint(int value) {
    if (value == '\n' || value == '\t') {
      return false;
    }
    return value <= 0x1f
        || value >= 0x7f && value <= 0x9f
        || value >= 0xd800 && value <= 0xdfff
        || value == 0x061c
        || value == 0x200e
        || value == 0x200f
        || value >= 0x202a && value <= 0x202e
        || value >= 0x2066 && value <= 0x2069;
  }

  private static void requireFields(JsonObject object, Set<String> expected) {
    if (!object.keySet().equals(expected)) {
      throw invalid();
    }
  }

  private static JsonObject object(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonObject()) {
      throw invalid();
    }
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonArray()) {
      throw invalid();
    }
    return value.getAsJsonArray();
  }

  private static String string(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return value.getAsString();
  }

  private static boolean bool(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw invalid();
    }
    return value.getAsBoolean();
  }

  private static int integer(JsonObject object, String name, int minimum, int maximum) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw invalid();
    }
    try {
      var number = value.getAsBigDecimal();
      var integer = number.intValueExact();
      if (integer < minimum || integer > maximum) {
        throw invalid();
      }
      return integer;
    } catch (ArithmeticException error) {
      throw invalid();
    }
  }

  private static long nonnegativeInteger(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw invalid();
    }
    try {
      var integer = value.getAsBigDecimal().longValueExact();
      if (integer < 0 || integer > MAX_SAFE_INTEGER) {
        throw invalid();
      }
      return integer;
    } catch (ArithmeticException error) {
      throw invalid();
    }
  }

  private static UUID uuid(JsonObject object, String name) {
    return canonicalUuid(string(object, name));
  }

  private static UUID nullableUuid(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null) {
      throw invalid();
    }
    if (value.isJsonNull()) {
      return null;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return canonicalUuid(value.getAsString());
  }

  private static UUID canonicalUuid(String value) {
    try {
      var parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw invalid();
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static Instant instant(JsonObject object, String name) {
    try {
      return Instant.parse(string(object, name));
    } catch (DateTimeParseException error) {
      throw invalid();
    }
  }

  private static String randomValue(JsonObject object, String name) {
    var value = string(object, name);
    if (value.length() < 22 || value.length() > 128 || !BASE64_URL.matcher(value).matches()) {
      throw invalid();
    }
    try {
      var decoded = Base64.getUrlDecoder().decode(value);
      if (decoded.length < 16
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
        throw invalid();
      }
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    return value;
  }

  private static RuntimeConnectionFailure invalid() {
    return failure("PROTOCOL_MESSAGE_INVALID");
  }

  private static RuntimeConnectionFailure failure(String code) {
    return new RuntimeConnectionFailure(code, "application-protocol");
  }

  public enum CancelReason {
    PLAYER_DISCONNECTED,
    PAPER_TIMEOUT,
    AGENT_OFFLINE,
    RUNTIME_DISCONNECTED
  }

  public enum AgentErrorCode {
    MODEL_TIMEOUT,
    MODEL_UNAVAILABLE,
    MODEL_AUTHENTICATION_FAILED,
    MODEL_RESPONSE_INVALID,
    REQUEST_CANCELLED,
    REQUEST_LIMITED,
    BUDGET_EXCEEDED,
    SESSION_NOT_FOUND,
    CONVERSATION_STORAGE_DISABLED,
    TOOL_REJECTED,
    TOOL_ROUND_LIMIT,
    RUNTIME_INTERNAL_ERROR
  }

  public sealed interface InboundMessage permits PlayerMessage, ManagementCosts {
    UUID messageId();

    UUID requestId();
  }

  public sealed interface PlayerMessage extends InboundMessage
      permits Completion, AgentError, SessionResumed, ToolCall {
    UUID playerUuid();
  }

  public record Completion(
      UUID messageId,
      UUID requestId,
      UUID sessionId,
      UUID playerUuid,
      String fallbackText,
      List<ClientStructuredView> structuredViews)
      implements PlayerMessage {
    public Completion {
      structuredViews = List.copyOf(structuredViews);
    }
  }

  public record AgentError(
      UUID messageId,
      UUID requestId,
      UUID playerUuid,
      AgentErrorCode code,
      String fallbackText,
      boolean retryable)
      implements PlayerMessage {}

  public record SessionResumed(UUID messageId, UUID requestId, UUID sessionId, UUID playerUuid)
      implements PlayerMessage {}

  public record ToolCall(UUID messageId, UUID requestId, UUID playerUuid, ReadToolCall call)
      implements PlayerMessage {}

  public record UsageWindow(
      String period,
      long admittedRequests,
      long providerCalls,
      long reportedProviderCalls,
      long estimatedProviderCalls,
      long inputTokens,
      long outputTokens,
      long costMicroUsd) {
    public UsageWindow {
      Objects.requireNonNull(period);
      requireSafeInteger(admittedRequests);
      requireSafeInteger(providerCalls);
      requireSafeInteger(reportedProviderCalls);
      requireSafeInteger(estimatedProviderCalls);
      requireSafeInteger(inputTokens);
      requireSafeInteger(outputTokens);
      requireSafeInteger(costMicroUsd);
      if (providerCalls != reportedProviderCalls + estimatedProviderCalls) {
        throw new IllegalArgumentException("Invalid provider call accounting");
      }
    }
  }

  public record Budget(
      String month,
      long limitMicroUsd,
      long settledMicroUsd,
      long activeReservationsMicroUsd,
      long remainingMicroUsd,
      boolean exhausted) {
    public Budget {
      Objects.requireNonNull(month);
      requireSafeInteger(limitMicroUsd);
      requireSafeInteger(settledMicroUsd);
      requireSafeInteger(activeReservationsMicroUsd);
      requireSafeInteger(remainingMicroUsd);
      var exposure = settledMicroUsd + activeReservationsMicroUsd;
      var expectedRemaining = Math.max(0, limitMicroUsd - exposure);
      if (!MONTH_PERIOD.matcher(month).matches()
          || remainingMicroUsd != expectedRemaining
          || exhausted != (remainingMicroUsd == 0)) {
        throw new IllegalArgumentException("Invalid budget accounting");
      }
    }
  }

  public record ManagementCosts(
      UUID messageId,
      UUID requestId,
      UsageWindow currentDay,
      UsageWindow currentMonth,
      Budget budget)
      implements InboundMessage {
    public ManagementCosts {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(currentDay);
      Objects.requireNonNull(currentMonth);
      Objects.requireNonNull(budget);
      if (!DAY_PERIOD.matcher(currentDay.period()).matches()
          || !MONTH_PERIOD.matcher(currentMonth.period()).matches()
          || !currentDay.period().startsWith(currentMonth.period() + "-")
          || !budget.month().equals(currentMonth.period())
          || budget.settledMicroUsd() != currentMonth.costMicroUsd()) {
        throw new IllegalArgumentException("Invalid management costs periods");
      }
    }
  }

  private static void requireSafeInteger(long value) {
    if (value < 0 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("Invalid management costs integer");
    }
  }

  private static final class ReplayWindow {
    private final Map<String, Instant> entries = new LinkedHashMap<>();

    synchronized boolean accept(String messageId, String nonce, Instant now) {
      removeExpired(now);
      var messageKey = "message:" + messageId;
      var nonceKey = "nonce:" + nonce;
      if (entries.containsKey(messageKey) || entries.containsKey(nonceKey)) {
        return false;
      }
      if (entries.size() + 2 > MAX_REPLAY_ENTRIES) {
        return false;
      }
      var expires = now.plus(REPLAY_RETENTION);
      entries.put(messageKey, expires);
      entries.put(nonceKey, expires);
      return true;
    }

    private void removeExpired(Instant now) {
      Iterator<Map.Entry<String, Instant>> iterator = entries.entrySet().iterator();
      while (iterator.hasNext()) {
        if (!iterator.next().getValue().isAfter(now)) {
          iterator.remove();
        }
      }
    }
  }
}
