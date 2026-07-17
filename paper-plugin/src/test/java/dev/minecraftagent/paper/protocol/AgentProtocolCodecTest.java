package dev.minecraftagent.paper.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.AgentError;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.AgentErrorCode;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.CancelReason;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.Completion;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.ManagementCosts;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.SessionResumed;
import dev.minecraftagent.paper.protocol.AgentProtocolCodec.ToolCall;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.tool.ReadToolCall;
import dev.minecraftagent.paper.tool.ReadToolResult;
import dev.minecraftagent.paper.transport.RuntimeConnectionFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProtocolCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SERVER_ID = "survival-main";
  private static final Instant NOW = Instant.parse("2026-07-11T08:00:01Z");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYER_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID SESSION_ID = UUID.fromString("71717171-7171-4171-8171-717171717171");

  @Test
  void encodesSchemaValidGeneralRequestWithTrustedIdentityAndNoClient() throws Exception {
    var codec = codec();

    var source = codec.encodeRequest(REQUEST_ID, PLAYER_UUID, "How do I craft a table?");
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-request.schema.json", document.path("payload"));
    assertEquals(document.path("messageId"), document.path("requestId"));
    assertEquals(SERVER_ID, document.path("serverId").asText());
    assertEquals("agent.request", document.path("type").asText());
    assertEquals(PLAYER_UUID.toString(), document.path("payload").path("playerUuid").asText());
    assertEquals("general", document.path("payload").path("module").asText());
    assertTrue(document.path("payload").path("sessionId").isNull());
    assertEquals(
        false, document.path("payload").path("clientCapabilities").path("connected").asBoolean());
    assertTrue(
        document.path("payload").path("clientCapabilities").path("clientProtocolVersion").isNull());
  }

  @Test
  void encodesSelectedSessionAndOneShotModule() throws Exception {
    var source =
        codec()
            .encodeRequest(
                REQUEST_ID, PLAYER_UUID, SESSION_ID, AgentModule.RECIPE, "Comparator recipe");
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-request.schema.json", document.path("payload"));
    assertEquals(SESSION_ID.toString(), document.path("payload").path("sessionId").asText());
    assertEquals("recipe", document.path("payload").path("module").asText());
  }

  @Test
  void encodesSchemaValidLatestAndExplicitResumeRequests() throws Exception {
    var latest = JSON.readTree(codec().encodeResume(REQUEST_ID, PLAYER_UUID, null));
    assertSchema("envelope.schema.json", latest);
    assertSchema("session-resume.schema.json", latest.path("payload"));
    assertEquals("session.resume", latest.path("type").asText());
    assertTrue(latest.path("payload").path("sessionId").isNull());

    var explicit = JSON.readTree(codec().encodeResume(REQUEST_ID, PLAYER_UUID, SESSION_ID));
    assertSchema("session-resume.schema.json", explicit.path("payload"));
    assertEquals(SESSION_ID.toString(), explicit.path("payload").path("sessionId").asText());
  }

  @Test
  void encodesSchemaValidCorrelatedCancellation() throws Exception {
    var source = codec().encodeCancel(REQUEST_ID, PLAYER_UUID, CancelReason.PLAYER_DISCONNECTED);
    var document = JSON.readTree(source);

    assertSchema("envelope.schema.json", document);
    assertSchema("agent-cancel.schema.json", document.path("payload"));
    assertNotEquals(document.path("messageId"), document.path("requestId"));
    assertEquals(REQUEST_ID.toString(), document.path("requestId").asText());
    assertEquals("PLAYER_DISCONNECTED", document.path("payload").path("reason").asText());
  }

  @Test
  void encodesSchemaValidManagementCostsRequest() throws Exception {
    var document = JSON.readTree(codec().encodeCostsRequest(REQUEST_ID));

    assertSchema("envelope.schema.json", document);
    assertSchema("management-costs-request.schema.json", document.path("payload"));
    assertEquals(REQUEST_ID.toString(), document.path("messageId").asText());
    assertEquals(REQUEST_ID.toString(), document.path("requestId").asText());
    assertEquals("management.costs.request", document.path("type").asText());
    assertEquals(0, document.path("payload").size());
  }

  @Test
  void decodesStrictManagementCostsResponse() throws Exception {
    var source = managementCostsResponse();
    var costs = assertInstanceOf(ManagementCosts.class, codec().decode(source));

    assertEquals(REQUEST_ID, costs.requestId());
    assertEquals("2026-07-14", costs.currentDay().period());
    assertEquals(3, costs.currentDay().admittedRequests());
    assertEquals(5, costs.currentDay().providerCalls());
    assertEquals(1200, costs.currentDay().inputTokens());
    assertEquals(18_000, costs.currentMonth().costMicroUsd());
    assertEquals(50_000_000, costs.budget().limitMicroUsd());
    assertEquals(49_980_000, costs.budget().remainingMicroUsd());
    assertEquals(false, costs.budget().exhausted());
  }

  @Test
  void rejectsMalformedOrInconsistentManagementCosts() throws Exception {
    var valid = managementCostsResponse();

    assertFailure(
        codec(),
        valid.replace("\"currentDay\":", "\"unknown\":0,\"currentDay\":"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"inputTokens\":1200", "\"inputTokens\":-1"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"inputTokens\":1200", "\"inputTokens\":1.5"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"inputTokens\":1200", "\"inputTokens\":9007199254740992"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"providerCalls\":5", "\"providerCalls\":6"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"period\":\"2026-07-14\"", "\"period\":\"2026-08-14\""),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace("\"remainingMicroUsd\":49980000", "\"remainingMicroUsd\":49980001"),
        "PROTOCOL_MESSAGE_INVALID");
  }

  @Test
  void decodesStrictCompletionAndErrorFixtures() throws Exception {
    var codec = codec();

    var completion =
        assertInstanceOf(Completion.class, codec.decode(fixture("envelope-agent-complete.json")));
    assertEquals(REQUEST_ID, completion.requestId());
    assertEquals(PLAYER_UUID, completion.playerUuid());
    assertNull(completion.sessionId());
    assertEquals("Place four planks in a 2x2 crafting grid.", completion.fallbackText());

    var error =
        assertInstanceOf(AgentError.class, codec.decode(fixture("envelope-agent-error.json")));
    assertEquals(REQUEST_ID, error.requestId());
    assertEquals(PLAYER_UUID, error.playerUuid());
    assertEquals(AgentErrorCode.MODEL_TIMEOUT, error.code());
    assertTrue(error.retryable());
  }

  @Test
  void acceptsToolLoopTerminalErrorCodes() throws Exception {
    var rejected =
        assertInstanceOf(
            AgentError.class,
            codec()
                .decode(
                    fixture("envelope-agent-error.json")
                        .replace("MODEL_TIMEOUT", "TOOL_REJECTED")));
    assertEquals(AgentErrorCode.TOOL_REJECTED, rejected.code());

    var limited =
        assertInstanceOf(
            AgentError.class,
            codec()
                .decode(
                    fixture("envelope-agent-error.json")
                        .replace("MODEL_TIMEOUT", "TOOL_ROUND_LIMIT")));
    assertEquals(AgentErrorCode.TOOL_ROUND_LIMIT, limited.code());

    var budget =
        assertInstanceOf(
            AgentError.class,
            codec()
                .decode(
                    fixture("envelope-agent-error.json")
                        .replace("MODEL_TIMEOUT", "BUDGET_EXCEEDED")));
    assertEquals(AgentErrorCode.BUDGET_EXCEEDED, budget.code());
  }

  @Test
  void decodesStrictSessionResumedFixture() throws Exception {
    var source =
        fixture("envelope-session-resumed.json").replace("2026-07-13T08:00:02Z", NOW.toString());
    var resumed = assertInstanceOf(SessionResumed.class, codec().decode(source));

    assertEquals(PLAYER_UUID, resumed.playerUuid());
    assertEquals(SESSION_ID, resumed.sessionId());
  }

  @Test
  void rejectsWrongServerIdentity() throws Exception {
    var source = fixture("envelope-agent-complete.json").replace(SERVER_ID, "other-server");

    assertFailure(codec(), source, "SERVER_ID_MISMATCH");
  }

  @Test
  void rejectsReplayedMessageIdOrNonce() throws Exception {
    var codec = codec();
    var source = fixture("envelope-agent-complete.json");
    codec.decode(source);

    assertFailure(codec, source, "PROTOCOL_MESSAGE_REPLAYED");
  }

  @Test
  void decodesValidStructuredViewsAndRejectsMismatchedCorrelation() throws Exception {
    var viewId = UUID.fromString("81818181-8181-4181-8181-818181818181");
    var view =
        """
        {"viewSchemaVersion":"1.0","viewId":"%s","requestId":"%s",\
        "viewType":"text","revision":1,"title":"Agent response",\
        "fallbackText":"Place four planks in a 2x2 crafting grid.",\
        "pinnable":true,"content":{"text":"Place four planks in a 2x2 crafting grid."}}
        """
            .formatted(viewId, REQUEST_ID)
            .replace("\n", "");
    var source =
        fixture("envelope-agent-complete.json")
            .replace("\"structuredViews\": []", "\"structuredViews\": [" + view + "]");

    var completion = assertInstanceOf(Completion.class, codec().decode(source));
    assertEquals(1, completion.structuredViews().size());
    assertEquals(viewId, completion.structuredViews().getFirst().viewId());

    var mismatched =
        source.replace(
            "\"requestId\":\"" + REQUEST_ID + "\",\"viewType\"",
            "\"requestId\":\"91919191-9191-4191-8191-919191919191\",\"viewType\"");
    assertFailure(codec(), mismatched, "PROTOCOL_MESSAGE_INVALID");
  }

  @Test
  void rejectsAnUnpairedSurrogateFallback() throws Exception {
    var source =
        fixture("envelope-agent-complete.json")
            .replace("Place four planks in a 2x2 crafting grid.", "\\ud800");

    assertFailure(codec(), source, "PROTOCOL_MESSAGE_INVALID");
  }

  @Test
  void rejectsControlAndBidirectionalFormattingInFallbackWithoutStructuredViews() throws Exception {
    var source = fixture("envelope-agent-complete.json");

    assertFailure(
        codec(),
        source.replace("Place four planks in a 2x2 crafting grid.", "hidden\\u0000text"),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        source.replace("Place four planks in a 2x2 crafting grid.", "spoof\\u202etext"),
        "PROTOCOL_MESSAGE_INVALID");
  }

  @Test
  void rejectsDuplicateFieldsAndStaleMessages() throws Exception {
    var valid = fixture("envelope-agent-complete.json");
    assertFailure(
        codec(),
        valid.replaceFirst("\\{", "{\"protocolVersion\":\"1.0\","),
        "PROTOCOL_MESSAGE_INVALID");
    assertFailure(
        codec(),
        valid.replace(NOW.toString(), NOW.minusSeconds(31).toString()),
        "PROTOCOL_MESSAGE_STALE");
  }

  @Test
  void decodesStrictToolCallAndEncodesSchemaValidBoundResult() throws Exception {
    var codec = codec();
    var call = assertInstanceOf(ToolCall.class, codec.decode(fixture("envelope-tool-call.json")));

    assertEquals(REQUEST_ID, call.requestId());
    assertEquals(PLAYER_UUID, call.playerUuid());
    assertEquals("server.recipe.lookup", call.call().tool());
    assertEquals(AgentModule.RECIPE, call.call().module());
    assertEquals(0, call.call().sequence());

    var data = new com.google.gson.JsonObject();
    data.addProperty("recipeCount", 1);
    var encoded =
        JSON.readTree(
            codec.encodeToolResult(
                REQUEST_ID,
                call.call(),
                ReadToolResult.succeeded(ReadToolResult.Source.SERVER_REGISTRY, data)));
    assertSchema("envelope.schema.json", encoded);
    assertSchema("tool-result.schema.json", encoded.path("payload"));
    assertEquals("tool.result", encoded.path("type").asText());
    assertEquals(
        call.call().toolCallId().toString(), encoded.path("payload").path("toolCallId").asText());
    assertEquals(
        call.call().sessionId().toString(), encoded.path("payload").path("sessionId").asText());
  }

  @Test
  void downgradesToolResultsThatExceedTheRuntimeStructuralTokenLimit() throws Exception {
    var entries = new JsonArray();
    for (var index = 0; index < 1_800; index++) {
      var entry = new JsonObject();
      entry.addProperty("value", index);
      entries.add(entry);
    }
    var result = new JsonObject();
    result.add("entries", entries);

    assertTooLargeFallback(result);
  }

  @Test
  void downgradesToolResultsThatExceedTheApplicationFrameLimit() throws Exception {
    var result = new JsonObject();
    result.addProperty("value", "x".repeat(AgentProtocolCodec.MAX_APPLICATION_BYTES));

    assertTooLargeFallback(result);
  }

  private static void assertTooLargeFallback(JsonObject result) throws Exception {
    var call =
        new ReadToolCall(
            UUID.randomUUID(),
            REQUEST_ID,
            SESSION_ID,
            PLAYER_UUID,
            AgentModule.GENERAL,
            "player.context.read",
            new JsonObject(),
            0);
    var document =
        JSON.readTree(
            codec()
                .encodeToolResult(
                    REQUEST_ID,
                    call,
                    ReadToolResult.succeeded(ReadToolResult.Source.PAPER_API, result)));

    assertSchema("envelope.schema.json", document);
    assertSchema("tool-result.schema.json", document.path("payload"));
    assertEquals("failed", document.path("payload").path("status").asText());
    assertEquals("paper_api", document.path("payload").path("source").asText());
    assertEquals("authoritative", document.path("payload").path("trust").asText());
    assertTrue(document.path("payload").path("result").isNull());
    assertEquals(
        "TOOL_RESULT_TOO_LARGE", document.path("payload").path("error").path("code").asText());
    assertEquals(false, document.path("payload").path("error").path("retryable").asBoolean());
  }

  private static AgentProtocolCodec codec() {
    return new AgentProtocolCodec(SERVER_ID, new SecureRandom(), () -> NOW);
  }

  private static String fixture(String name) throws Exception {
    var path = protocolRoot().resolve("fixtures/valid/" + name);
    return Files.readString(path);
  }

  private static String managementCostsResponse() throws Exception {
    var path = protocolRoot().resolve("fixtures/valid/management-costs.json");
    return JSON.readTree(Files.newInputStream(path))
        .path("response")
        .toString()
        .replace("2026-07-14T12:00:01Z", NOW.toString())
        .replace("91000000-0000-4000-8000-000000000001", REQUEST_ID.toString());
  }

  private static void assertFailure(AgentProtocolCodec codec, String source, String expectedCode) {
    try {
      codec.decode(source);
      throw new AssertionError("message unexpectedly decoded");
    } catch (RuntimeConnectionFailure failure) {
      assertEquals(expectedCode, failure.code());
      assertEquals("application-protocol", failure.stage());
    }
  }

  private static void assertSchema(String name, JsonNode document) throws Exception {
    var schemaNode = JSON.readTree(Files.newInputStream(protocolRoot().resolve("schemas/" + name)));
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                schemaNode, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    var errors = schema.validate(document);
    assertTrue(errors.isEmpty(), () -> name + " errors: " + errors);
  }

  private static Path protocolRoot() {
    return Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
  }
}
