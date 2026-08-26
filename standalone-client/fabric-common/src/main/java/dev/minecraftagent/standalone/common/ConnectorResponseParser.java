package dev.minecraftagent.standalone.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decodes validated Runtime response envelopes into typed payloads.
 *
 * <p>Parsing is pure: it reads only the supplied envelope and every wire violation raises {@link
 * JsonFields#invalid(String)} naming the offending field, which the session translates into a
 * protocol failure. It performs no correlation, replay, or session state checks.
 */
final class ConnectorResponseParser {
  private static final Set<String> ERROR_CODES =
      Set.of(
          "MODEL_TIMEOUT",
          "MODEL_UNAVAILABLE",
          "MODEL_AUTHENTICATION_FAILED",
          "MODEL_RESPONSE_INVALID",
          "REQUEST_CANCELLED",
          "REQUEST_LIMITED",
          "BUDGET_EXCEEDED",
          "SESSION_NOT_FOUND",
          "CONVERSATION_STORAGE_DISABLED",
          "TOOL_REJECTED",
          "TOOL_ROUND_LIMIT",
          "RUNTIME_INTERNAL_ERROR");

  private ConnectorResponseParser() {}

  static Response parse(ConnectorEnvelope envelope) {
    return switch (envelope.type()) {
      case "client.complete" -> {
        var payload =
            JsonFields.exactObject(
                envelope.payload(),
                "/payload",
                "sessionId",
                "text",
                "costMicroUsd",
                "costKind",
                "sources");
        var costMicroUsd =
            JsonFields.longInteger(payload.get("costMicroUsd"), "/payload/costMicroUsd");
        if (costMicroUsd < 0 || costMicroUsd > TextCompletion.MAXIMUM_COST_MICRO_USD) {
          throw JsonFields.invalid("/payload/costMicroUsd");
        }
        yield new Complete(
            JsonFields.nullableUuid(payload.get("sessionId"), "/payload/sessionId"),
            JsonFields.string(
                payload.get("text"), "/payload/text", TextCompletion.MAXIMUM_TEXT_LENGTH),
            costMicroUsd,
            TextCompletion.CostKind.fromWire(
                JsonFields.string(payload.get("costKind"), "/payload/costKind", 16)),
            parseSources(payload.get("sources")));
      }
      case "client.error" -> {
        var payload =
            JsonFields.exactObject(envelope.payload(), "/payload", "code", "message", "retryable");
        var code = JsonFields.string(payload.get("code"), "/payload/code", 64);
        if (!ERROR_CODES.contains(code)) {
          throw JsonFields.invalid("/payload/code");
        }
        yield new Error(
            code,
            JsonFields.string(payload.get("message"), "/payload/message", 512),
            JsonFields.bool(payload.get("retryable"), "/payload/retryable"));
      }
      case "client.status" -> {
        var payload =
            JsonFields.exactObject(
                envelope.payload(), "/payload", "state", "activeRequests", "queuedRequests");
        yield new Status(
            RuntimeStatus.State.valueOf(
                JsonFields.string(payload.get("state"), "/payload/state", 16)),
            JsonFields.integer(payload.get("activeRequests"), "/payload/activeRequests"),
            JsonFields.integer(payload.get("queuedRequests"), "/payload/queuedRequests"));
      }
      case "client.tool.call" -> {
        var payload =
            JsonFields.exactObject(
                envelope.payload(),
                "/payload",
                "requestId",
                "toolCallId",
                "subjectId",
                "tool",
                "sequence",
                "arguments");
        var requestId = JsonFields.uuid(payload.get("requestId"), "/payload/requestId");
        if (!requestId.equals(envelope.requestId())) {
          throw JsonFields.invalid("/payload/requestId");
        }
        yield new ToolCall(
            new ClientToolCall(
                requestId,
                JsonFields.uuid(payload.get("toolCallId"), "/payload/toolCallId"),
                JsonFields.uuid(payload.get("subjectId"), "/payload/subjectId"),
                JsonFields.string(payload.get("tool"), "/payload/tool", 64),
                JsonFields.integer(payload.get("sequence"), "/payload/sequence"),
                JsonFields.object(payload.get("arguments"), "/payload/arguments")));
      }
      case "client.tool.cancel" -> {
        var payload =
            JsonFields.exactObject(
                envelope.payload(),
                "/payload",
                "requestId",
                "toolCallId",
                "subjectId",
                "tool",
                "sequence",
                "reason");
        var requestId = JsonFields.uuid(payload.get("requestId"), "/payload/requestId");
        if (!requestId.equals(envelope.requestId())) {
          throw JsonFields.invalid("/payload/requestId");
        }
        yield new ToolCancellation(
            new ClientToolCancellation(
                requestId,
                JsonFields.uuid(payload.get("toolCallId"), "/payload/toolCallId"),
                JsonFields.uuid(payload.get("subjectId"), "/payload/subjectId"),
                JsonFields.string(payload.get("tool"), "/payload/tool", 64),
                JsonFields.integer(payload.get("sequence"), "/payload/sequence"),
                ClientToolCancellation.Reason.valueOf(
                    JsonFields.string(payload.get("reason"), "/payload/reason", 32))));
      }
      default -> throw JsonFields.invalid("/type");
    };
  }

  private static List<TextCompletion.Source> parseSources(Object value) {
    var parsed = new ArrayList<TextCompletion.Source>();
    var entries = JsonFields.array(value, "/payload/sources", 5);
    for (var index = 0; index < entries.size(); index++) {
      var field = "/payload/sources/" + index;
      var source =
          JsonFields.exactObject(
              entries.get(index),
              field,
              "claimId",
              "title",
              "url",
              "publisher",
              "retrievedAt",
              "applicability",
              "warnings");
      var applicability =
          JsonFields.exactObject(
              source.get("applicability"),
              field + "/applicability",
              "minecraftVersion",
              "modVersions",
              "modpackVersion",
              "match");
      var modVersions = new java.util.TreeMap<String, String>();
      JsonFields.object(applicability.get("modVersions"), field + "/applicability/modVersions")
          .forEach(
              (modId, version) ->
                  modVersions.put(
                      modId,
                      JsonFields.string(
                          version, field + "/applicability/modVersions/" + modId, 128)));
      parsed.add(
          new TextCompletion.Source(
              JsonFields.string(source.get("claimId"), field + "/claimId", 30),
              JsonFields.string(source.get("title"), field + "/title", 256),
              URI.create(JsonFields.string(source.get("url"), field + "/url", 2048)),
              JsonFields.string(source.get("publisher"), field + "/publisher", 256),
              JsonFields.instant(source.get("retrievedAt"), field + "/retrievedAt"),
              new TextCompletion.Applicability(
                  JsonFields.string(
                      applicability.get("minecraftVersion"),
                      field + "/applicability/minecraftVersion",
                      128),
                  modVersions,
                  JsonFields.nullableString(
                      applicability.get("modpackVersion"),
                      field + "/applicability/modpackVersion",
                      128),
                  TextCompletion.Match.fromWire(
                      JsonFields.string(
                          applicability.get("match"), field + "/applicability/match", 16))),
              JsonFields.stringArray(source.get("warnings"), field + "/warnings", 4)));
    }
    return List.copyOf(parsed);
  }

  /** A fully decoded Runtime response payload. */
  sealed interface Response permits Complete, Error, Status, ToolCall, ToolCancellation {}

  record Complete(
      UUID sessionId,
      String text,
      long costMicroUsd,
      TextCompletion.CostKind costKind,
      List<TextCompletion.Source> sources)
      implements Response {}

  record Error(String code, String message, boolean retryable) implements Response {}

  record ToolCall(ClientToolCall call) implements Response {}

  record ToolCancellation(ClientToolCancellation cancellation) implements Response {}

  record Status(RuntimeStatus.State state, int activeRequests, int queuedRequests)
      implements Response {
    Status {
      if (activeRequests < 0 || activeRequests > 8 || queuedRequests < 0 || queuedRequests > 128) {
        throw JsonFields.invalid("/payload");
      }
    }
  }
}
