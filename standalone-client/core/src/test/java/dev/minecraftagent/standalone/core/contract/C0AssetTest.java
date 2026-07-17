package dev.minecraftagent.standalone.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

final class C0AssetTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> CATEGORIES =
      Set.of(
          "exact_id",
          "fuzzy_name",
          "ambiguity",
          "alternatives",
          "cycle",
          "quantity",
          "machine",
          "boss_drop",
          "version_conflict",
          "client_hidden",
          "inventory_preference");

  @Test
  void evaluationCorpusHasBoundedBilingualCoverage() throws IOException {
    var root = read("evaluation/questions-v1.json");
    assertEquals("1.0", root.path("schemaVersion").asText());
    assertEquals("1.21.11", root.path("minecraftVersion").asText());
    var questions = root.path("questions");
    assertTrue(questions.isArray());
    assertEquals(64, questions.size());

    var ids = new HashSet<String>();
    var locales = new HashMap<String, Set<String>>();
    for (var question : questions) {
      assertTrue(ids.add(question.path("id").asText()), "question ids must be unique");
      var category = question.path("category").asText();
      assertTrue(CATEGORIES.contains(category), "unexpected category " + category);
      locales
          .computeIfAbsent(category, ignored -> new HashSet<>())
          .add(question.path("locale").asText());
      assertTrue(question.path("question").asText().length() > 3);
      assertTrue(question.path("requiredSignals").isArray());
      assertTrue(question.path("requiredSignals").size() >= 1);
      assertTrue(question.path("requiredSignals").size() <= 8);
      var serialized = question.toString().toLowerCase(java.util.Locale.ROOT);
      assertFalse(serialized.contains("api_key"));
      assertFalse(serialized.contains("bearer "));
      assertFalse(serialized.contains("sk-"));
    }
    assertEquals(CATEGORIES, locales.keySet());
    for (var category : CATEGORIES) {
      assertEquals(Set.of("zh_cn", "en_us"), locales.get(category));
    }
  }

  @Test
  void benchmarkSetsPinReviewedArtifactsAndRejectEmiSubstitution() throws IOException {
    var root = read("benchmarks/mod-sets-v1.json");
    assertEquals(
        "47f4791dd07526465b0246e9d48d939bfee038ed", root.at("/agmaBaseline/commit").asText());
    assertEquals("1.21.11", root.at("/lockedVersions/minecraft").asText());
    assertEquals("0.19.3", root.at("/lockedVersions/fabricLoader").asText());
    assertEquals("0.141.4+1.21.11", root.at("/lockedVersions/fabricApi").asText());
    assertEquals("27.17.0.50", root.at("/lockedVersions/jei").asText());
    assertEquals(2, root.path("modSets").size());
    assertEquals(4, root.path("fixtureScenarios").size());
    assertEquals("unavailable", root.at("/emiPolicy/status").asText());
    assertFalse(root.at("/emiPolicy/allowOlderArtifactSubstitution").asBoolean(true));
    assertFalse(root.at("/localViewerInventory/jeiInstalled").asBoolean(true));
    assertFalse(root.at("/localViewerInventory/emiInstalled").asBoolean(true));
    assertEquals(128, root.at("/artifacts/fabricApi/sha512").asText().length());
    assertEquals(128, root.at("/artifacts/jeiRuntime/sha512").asText().length());
    assertEquals(128, root.at("/artifacts/jeiApi/sha512").asText().length());
    assertFalse(root.at("/secrets/containsRealKeys").asBoolean(true));
  }

  @Test
  void performanceBudgetsAreConcreteAndFailPartial() throws IOException {
    var root = read("benchmarks/performance-budgets-v1.json");
    assertEquals("c0-locked", root.path("status").asText());
    assertEquals(100_000, root.at("/catalog/resourceCount").asInt());
    assertEquals(100_000, root.at("/catalog/processCount").asInt());
    assertEquals(256, root.at("/catalog/residentMemoryMiBMaximum").asInt());
    assertEquals(50, root.at("/catalog/queryP95MillisecondsMaximum").asInt());
    assertEquals(2_000, root.at("/planner/nodeMaximum").asInt());
    assertEquals(12, root.at("/planner/depthMaximum").asInt());
    assertEquals(3, root.at("/planner/topKMaximum").asInt());
    assertEquals(500, root.at("/planner/wallClockMillisecondsMaximum").asInt());
    assertEquals("partial", root.at("/planner/overBudgetDisposition").asText());
    assertFalse(root.at("/webEvidence/enabledBeforeC6").asBoolean(true));
    assertEquals(5, root.at("/webEvidence/searchResultMaximum").asInt());
    assertEquals(3, root.at("/webEvidence/bodyPageMaximum").asInt());
    assertFalse(root.at("/threadPolicy/networkOnClientThread").asBoolean(true));
    assertFalse(root.at("/threadPolicy/sqliteOnClientThread").asBoolean(true));
  }

  @Test
  void viewerFixturesCoverTheMatrixAndSelectOneSource() throws IOException {
    var fixtures =
        Map.of(
            "standalone-viewer-no-viewer.json", "vanilla_client",
            "standalone-viewer-jei-only.json", "jei",
            "standalone-viewer-emi-only.json", "vanilla_client",
            "standalone-viewer-jei-emi.json", "jei");
    var scenarios = new HashSet<String>();
    for (var entry : fixtures.entrySet()) {
      var fixture =
          JSON.readTree(
              Files.readString(protocolRoot().resolve("fixtures/valid").resolve(entry.getKey())));
      assertTrue(scenarios.add(fixture.path("scenario").asText()));
      assertEquals(entry.getValue(), fixture.path("selectedSource").asText());

      var viewers = new HashSet<String>();
      fixture.path("adapterStates").forEach(state -> viewers.add(state.path("viewer").asText()));
      assertEquals(Set.of("jei", "emi"), viewers);

      var processIds = new HashSet<String>();
      fixture
          .at("/catalog/processes")
          .forEach(process -> assertTrue(processIds.add(process.path("processId").asText())));
      assertFalse(fixture.toString().contains("apiKey"));
      assertFalse(fixture.toString().contains("connectorToken"));
    }
    assertEquals(Set.of("no_viewer", "jei_only", "emi_only", "jei_and_emi"), scenarios);
  }

  @Test
  void contractFixturesRejectBrokenGenerationAndReferences() throws IOException {
    var fixture =
        JSON.readTree(
            Files.readString(protocolRoot().resolve("fixtures/valid/standalone-contracts.json")));
    var process = fixture.path("process");
    var guide = fixture.path("guide");
    assertTrue(processSemanticsValid(process));
    assertTrue(guideSemanticsValid(guide));
    assertTrue(guideSemanticsValid(fixture.path("notFoundGuide")));

    var badStage = process.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) badStage.at("/stages/0/inputGroupIds"))
        .set(0, JSON.getNodeFactory().textNode("missing"));
    assertFalse(processSemanticsValid(badStage));

    var badGeneration = process.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            badGeneration.at("/outputs/0/resource/source"))
        .put("generationId", "Other-generation");
    assertFalse(processSemanticsValid(badGeneration));

    var missingClaim = guide.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) missingClaim.at("/bossAndDrops/0/claimIds"))
        .set(0, JSON.getNodeFactory().textNode("claim.missing"));
    assertFalse(guideSemanticsValid(missingClaim));

    var staleTarget = guide.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) staleTarget.at("/target/source"))
        .put("generationId", "Other-generation");
    assertFalse(guideSemanticsValid(staleTarget));
  }

  @Test
  void connectorGoldenBindsNegotiationAndCorrelatesTheResponse()
      throws IOException, GeneralSecurityException {
    var fixture =
        JSON.readTree(
            Files.readString(protocolRoot().resolve("fixtures/valid/standalone-contracts.json")));
    var request = fixture.path("connectorRequest");
    var response = fixture.path("connectorResponse");
    var token = fixture.path("publicTestToken").asText();

    assertTrue(connectorSemanticsValid(request));
    assertTrue(connectorSemanticsValid(response));
    assertEquals(request.path("messageId").asText(), response.path("requestId").asText());
    assertEquals(
        request.at("/authentication/challenge").asText(),
        response.at("/authentication/challenge").asText());
    assertFalse(request.path("nonce").asText().equals(response.path("nonce").asText()));
    assertEquals(request.at("/authentication/proof").asText(), connectorProof(token, request));
    assertEquals(response.at("/authentication/proof").asText(), connectorProof(token, response));

    var tampered = request.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("componentVersion", "0.2.1");
    assertFalse(
        request.at("/authentication/proof").asText().equals(connectorProof(token, tampered)));

    var duplicateCapability = request.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicateCapability.path("capabilities"))
        .addObject()
        .put("id", "game.resource.search")
        .put("version", 2);
    assertFalse(connectorSemanticsValid(duplicateCapability));

    var unsortedCapabilities = request.deepCopy();
    var capabilities =
        (com.fasterxml.jackson.databind.node.ArrayNode) unsortedCapabilities.path("capabilities");
    capabilities.removeAll();
    capabilities.addObject().put("id", "game.zeta").put("version", 1);
    capabilities.addObject().put("id", "game.alpha").put("version", 1);
    assertFalse(connectorSemanticsValid(unsortedCapabilities));

    for (var pointer : Set.of("/nonce", "/authentication/challenge", "/authentication/proof")) {
      var nonCanonical = request.deepCopy();
      var parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
      var field = pointer.substring(pointer.lastIndexOf('/') + 1);
      var parent = parentPointer.isEmpty() ? nonCanonical : nonCanonical.at(parentPointer);
      var current = nonCanonical.at(pointer).asText();
      ((com.fasterxml.jackson.databind.node.ObjectNode) parent)
          .put(field, current.substring(0, current.length() - 1) + "B");
      assertFalse(connectorSemanticsValid(nonCanonical), pointer);
    }
  }

  @Test
  void developmentPlanRemainsOutsideRepository() throws IOException {
    var standalone = standaloneRoot().toRealPath();
    var repository = standalone.getParent().toRealPath();
    var externalPlan =
        repository
            .getParent()
            .resolve("AGMA-development-plans/01-standalone-client-development-plan.md")
            .toAbsolutePath()
            .normalize();
    assertFalse(externalPlan.startsWith(repository));
    if (Files.exists(externalPlan)) {
      assertFalse(externalPlan.toRealPath().startsWith(repository));
    }
    try (var files = Files.walk(repository)) {
      assertFalse(
          files.anyMatch(
              path ->
                  path.getFileName()
                      .toString()
                      .equals("01-standalone-client-development-plan.md")));
    }
  }

  private static JsonNode read(String relative) throws IOException {
    return JSON.readTree(Files.readString(standaloneRoot().resolve(relative)));
  }

  private static Path standaloneRoot() {
    return Path.of(System.getProperty("minecraftAgent.standaloneDir"));
  }

  private static Path protocolRoot() {
    return Path.of(System.getProperty("minecraftAgent.protocolDir"));
  }

  private static boolean processSemanticsValid(JsonNode process) {
    var generation = process.at("/source/generationId").asText();
    var groupIds = new HashSet<String>();
    process.path("inputs").forEach(group -> groupIds.add(group.path("groupId").asText()));
    if (groupIds.size() != process.path("inputs").size()) {
      return false;
    }
    for (var index = 0; index < process.path("stages").size(); index++) {
      var stage = process.path("stages").get(index);
      if (stage.path("index").asInt(-1) != index) {
        return false;
      }
      for (var inputId : stage.path("inputGroupIds")) {
        if (!groupIds.contains(inputId.asText())) {
          return false;
        }
      }
    }

    var resources = new java.util.ArrayList<JsonNode>();
    process.path("workstations").forEach(resources::add);
    process.path("inputs").forEach(group -> group.path("alternatives").forEach(resources::add));
    process
        .path("catalysts")
        .forEach(
            catalyst -> {
              resources.add(catalyst.path("resource"));
              if (!catalyst.path("returnedResource").isNull()) {
                resources.add(catalyst.path("returnedResource"));
              }
            });
    process.path("outputs").forEach(output -> resources.add(output.path("resource")));
    if (!process.path("energy").isNull()) {
      resources.add(process.path("energy"));
    }
    return resources.stream()
        .allMatch(resource -> generation.equals(resource.at("/source/generationId").asText()));
  }

  private static boolean guideSemanticsValid(JsonNode guide) {
    var generation = guide.at("/snapshot/generationId").asText();
    var unresolved =
        Set.of("ambiguous", "not_found").contains(guide.path("targetResolution").asText());
    if (unresolved
        != (guide.path("target").isNull()
            && guide.path("materials").isEmpty()
            && guide.path("processes").isEmpty())) {
      return false;
    }
    if (!guide.path("target").isNull()
        && !generation.equals(guide.at("/target/source/generationId").asText())) {
      return false;
    }
    for (var material : guide.path("materials")) {
      if (!generation.equals(material.at("/resource/source/generationId").asText())) {
        return false;
      }
    }
    for (var process : guide.path("processes")) {
      if (!generation.equals(process.at("/source/generationId").asText())
          || !processSemanticsValid(process)) {
        return false;
      }
    }

    var claims = new HashSet<String>();
    guide.path("evidence").forEach(claim -> claims.add(claim.path("claimId").asText()));
    if (claims.size() != guide.path("evidence").size()) {
      return false;
    }
    for (var sectionName : Set.of("bossAndDrops", "preparation")) {
      for (var binding : guide.path(sectionName)) {
        for (var claimId : binding.path("claimIds")) {
          if (!claims.contains(claimId.asText())) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static String connectorProof(String token, JsonNode hello)
      throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(connectorTranscript(hello).getBytes(StandardCharsets.UTF_8)));
  }

  private static boolean connectorSemanticsValid(JsonNode hello) {
    try {
      ContractChecks.canonicalBase64Url(hello.path("nonce").asText(), "nonce", 16, 64);
      ContractChecks.canonicalBase64Url(
          hello.at("/authentication/challenge").asText(), "challenge", 16, 96);
      ContractChecks.canonicalBase64Url(
          hello.at("/authentication/proof").asText(), "proof", 32, 32);
    } catch (IllegalArgumentException error) {
      return false;
    }
    var ids =
        StreamSupport.stream(hello.path("capabilities").spliterator(), false)
            .map(capability -> capability.path("id").asText())
            .toList();
    return ids.equals(ids.stream().sorted().toList()) && new HashSet<>(ids).size() == ids.size();
  }

  private static String connectorTranscript(JsonNode hello) {
    var capabilities =
        StreamSupport.stream(hello.path("capabilities").spliterator(), false)
            .map(
                capability ->
                    capability.path("id").asText() + "=" + capability.path("version").asInt())
            .collect(Collectors.joining(","));
    return String.join(
        "\n",
        "agma-connector-handshake-v1",
        hello.path("schemaVersion").asText(),
        hello.path("connectorKind").asText(),
        hello.path("type").asText(),
        hello.path("messageId").asText(),
        hello.path("requestId").isNull() ? "-" : hello.path("requestId").asText(),
        hello.path("timestamp").asText(),
        hello.path("nonce").asText(),
        hello.path("component").asText(),
        hello.path("componentVersion").asText(),
        hello.path("scopeId").asText(),
        StreamSupport.stream(hello.path("supportedProtocolVersions").spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.joining(",")),
        hello.path("selectedProtocolVersion").isNull()
            ? "-"
            : hello.path("selectedProtocolVersion").asText(),
        capabilities,
        hello.at("/authentication/scheme").asText(),
        hello.at("/authentication/keyId").asText(),
        hello.at("/authentication/challenge").asText());
  }
}
