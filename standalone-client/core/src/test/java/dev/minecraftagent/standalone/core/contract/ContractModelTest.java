package dev.minecraftagent.standalone.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.AllowSchemaLoader;
import com.networknt.schema.resource.UriSchemaLoader;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ContractModelTest {
  @Test
  void resourceSourceRejectsTrustPromotion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceRef.Source(
                ResourceRef.Layer.CLIENT_REGISTRY,
                "minecraft_registry",
                ResourceRef.Trust.L0A,
                ResourceRef.Completeness.COMPLETE,
                "generation-001"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceRef(
                ResourceRef.Kind.ITEM,
                "minecraft:stone",
                null,
                "Stone",
                null,
                "minecraft",
                "Minecraft",
                "1.21.11",
                new BigDecimal("1000000000000001"),
                "item",
                registrySource()));
  }

  @Test
  void processPreservesAndOrGroupsAndDefensivelyCopiesThem() {
    var alternatives = new ArrayList<ResourceRef>();
    alternatives.add(item("minecraft:oak_planks", 1));
    alternatives.add(item("minecraft:spruce_planks", 1));
    var inputs = new ArrayList<ProcessRecord.InputGroup>();
    inputs.add(new ProcessRecord.InputGroup("planks", alternatives));

    var process =
        new ProcessRecord(
            "minecraft:fixture_chest",
            "minecraft:crafting",
            "Fixture Chest",
            List.of(item("minecraft:crafting_table", 1)),
            inputs,
            List.of(),
            List.of(new ProcessRecord.Output(item("minecraft:chest", 1), BigDecimal.ONE, true)),
            null,
            null,
            List.of(),
            List.of(new ProcessRecord.Stage(0, "Craft the selected planks.", List.of("planks"))),
            jeiSource(),
            true,
            List.of());

    alternatives.clear();
    inputs.clear();
    assertEquals(2, process.inputs().getFirst().alternatives().size());
    assertThrows(UnsupportedOperationException.class, () -> process.inputs().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> process.inputs().getFirst().alternatives().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRecord.Stage(0, "Duplicate input", List.of("planks", "planks")));
  }

  @Test
  void processRequiresOneDeterministicPrimaryOutput() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessRecord(
                "minecraft:invalid",
                "minecraft:crafting",
                "Invalid",
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new ProcessRecord.Output(item("minecraft:stone", 1), BigDecimal.ONE, true),
                    new ProcessRecord.Output(item("minecraft:dirt", 1), BigDecimal.ONE, true)),
                null,
                null,
                List.of(),
                List.of(),
                jeiSource(),
                true,
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessRecord(
                "minecraft:stale",
                "minecraft:crafting",
                "Stale",
                List.of(),
                List.of(),
                List.of(),
                List.of(new ProcessRecord.Output(item("minecraft:stone", 1), BigDecimal.ONE, true)),
                null,
                null,
                List.of(),
                List.of(),
                jeiSource("generation-002"),
                true,
                List.of()));
  }

  @Test
  void evidenceRequiresHttpsAndGuideBindingsRequireKnownClaims() {
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence("claim.invalid", URI.create("http://example.com/invalid")));

    var known = evidence("claim.known", URI.create("https://example.com/known"));
    var notFound =
        new GuideAnswerV1(
            null,
            GuideAnswerV1.TargetResolution.NOT_FOUND,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new GuideAnswerV1.UnknownItem(
                    GuideAnswerV1.UnknownDisposition.UNVERIFIED, "No local match.")),
            List.of(),
            new GuideAnswerV1.SnapshotContext(
                "NoWorld-11111111-1111-4111-8111-111111111111",
                "2222222222222222222222222222222222222222222222222222222222222222",
                Instant.parse("2026-07-17T00:00:00Z"),
                GuideAnswerV1.Visibility.NO_WORLD));
    assertEquals(GuideAnswerV1.TargetResolution.NOT_FOUND, notFound.targetResolution());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuideAnswerV1(
                item("agma_fixture:target", 1),
                GuideAnswerV1.TargetResolution.EXACT,
                List.of(),
                List.of(),
                List.of(
                    new GuideAnswerV1.ClaimBinding(
                        "Unsupported binding", List.of("claim.missing"))),
                List.of(),
                List.of(),
                List.of(known),
                new GuideAnswerV1.SnapshotContext(
                    "generation-001",
                    "1111111111111111111111111111111111111111111111111111111111111111",
                    Instant.parse("2026-07-17T00:00:00Z"),
                    GuideAnswerV1.Visibility.CLIENT_VISIBLE_PARTIAL)));
  }

  @Test
  void clientProfileIsLoopbackReadOnlyAndConsentFirst() {
    var valid = profile("127.0.0.1", false);
    assertEquals(List.of(), valid.toolPolicy().allowed());

    assertThrows(IllegalArgumentException.class, () -> profile("0.0.0.0", false));
    assertThrows(IllegalArgumentException.class, () -> profile("127.0.0.1", true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorHello.Authentication(
                "hmac-sha256", "session-key", "A".repeat(22), "A".repeat(42) + "B"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RuntimeClientProfile.SecretReference("private_file", "keys/../provider"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeClientProfile.ToolPolicy(
                List.of(),
                List.of(
                    "paper.command",
                    "server.payload",
                    "world.write",
                    "arbitrary.web.fetch",
                    "unreviewed.capability"),
                false));
  }

  @Test
  void c1JavaDocumentsMatchTheirJsonSchemas() throws IOException {
    var connector =
        new ConnectorHello(
            "1.0",
            "standalone_client",
            "connector.hello",
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            null,
            "2026-07-17T00:00:00Z",
            "A".repeat(22),
            "standalone_client",
            "0.2.0",
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            List.of("client-1.0"),
            null,
            List.of(new ConnectorHello.Capability("game.resource.search", 1)),
            new ConnectorHello.Authentication(
                "hmac-sha256", "session-key", "A".repeat(22), "A".repeat(43)));

    assertSchema("connector-hello.schema.json", connector);
    assertSchema("runtime-client-profile.schema.json", profile("127.0.0.1", false));
  }

  private static RuntimeClientProfile profile(String host, boolean webSearchEnabled) {
    return new RuntimeClientProfile(
        3,
        "client",
        new RuntimeClientProfile.Identity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "installation"),
        new RuntimeClientProfile.Transport(
            host,
            38127,
            new RuntimeClientProfile.SecretReference("environment", "AGMA_CLIENT_CONNECTOR_TOKEN"),
            "agma-connector-handshake-v1"),
        new RuntimeClientProfile.Model(
            "openai",
            URI.create("https://api.openai.com/v1"),
            new RuntimeClientProfile.SecretReference("environment", "AGMA_TEST_PROVIDER_KEY"),
            "fixture-model",
            60,
            0,
            0),
        new RuntimeClientProfile.Storage("data/client.sqlite"),
        new RuntimeClientProfile.Logging("logs", "info"),
        new RuntimeClientProfile.Limits(1, 8, 4, 30, 32_768, 2, 100, 1_000_000, 10_000),
        new RuntimeClientProfile.Privacy(false, 0, false, false),
        new RuntimeClientProfile.ToolPolicy(
            List.of(),
            List.of("paper.command", "server.payload", "world.write", "arbitrary.web.fetch"),
            false),
        new RuntimeClientProfile.NetworkPolicy(webSearchEnabled, true),
        new RuntimeClientProfile.StoragePolicy("installation", true));
  }

  private static ResourceRef item(String id, int amount) {
    var modId = id.substring(0, id.indexOf(':'));
    return new ResourceRef(
        ResourceRef.Kind.ITEM,
        id,
        null,
        id,
        null,
        modId,
        modId,
        "1.0.0",
        BigDecimal.valueOf(amount),
        "item",
        registrySource());
  }

  private static ResourceRef.Source registrySource() {
    return new ResourceRef.Source(
        ResourceRef.Layer.CLIENT_REGISTRY,
        "minecraft_registry",
        ResourceRef.Trust.L0B,
        ResourceRef.Completeness.COMPLETE,
        "generation-001");
  }

  private static ResourceRef.Source jeiSource() {
    return jeiSource("generation-001");
  }

  private static ResourceRef.Source jeiSource(String generationId) {
    return new ResourceRef.Source(
        ResourceRef.Layer.JEI,
        "jei",
        ResourceRef.Trust.L1,
        ResourceRef.Completeness.COMPLETE,
        generationId);
  }

  private static EvidenceClaim evidence(String claimId, URI uri) {
    return new EvidenceClaim(
        claimId,
        "Controlled fixture statement.",
        uri,
        "Fixture",
        "Fixture Publisher",
        Instant.parse("2026-07-17T00:00:00Z"),
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        new EvidenceClaim.Applicability(
            "1.21.11", Map.of("agma_fixture", "1.0.0"), null, EvidenceClaim.Match.MATCH),
        new BigDecimal("0.8"),
        List.of(),
        List.of());
  }

  private static void assertSchema(String schemaName, Object value) throws IOException {
    var contractRoot = Path.of(System.getProperty("minecraftAgent.protocolDir"));
    var schemaRoot = contractRoot.resolve("schemas");
    var localSchemaPrefix = schemaRoot.toUri().toString();
    var factory =
        JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> {
              builder.schemaMappers(
                  mappers ->
                      mappers.mapPrefix(
                          "https://minecraft-agent.dev/schemas/1.0/", localSchemaPrefix));
              builder.schemaLoaders(
                  loaders ->
                      loaders.values(
                          values -> {
                            var uriLoaderIndex = 0;
                            while (uriLoaderIndex < values.size()
                                && !(values.get(uriLoaderIndex) instanceof UriSchemaLoader)) {
                              uriLoaderIndex++;
                            }
                            values.add(
                                uriLoaderIndex,
                                new AllowSchemaLoader(iri -> iri.toString().startsWith("file:")));
                          }));
            });
    var mapper = new ObjectMapper();
    var schemaNode = mapper.readTree(Files.newInputStream(schemaRoot.resolve(schemaName)));
    var config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    var schema = factory.getSchema(schemaNode, config);
    var document = mapper.valueToTree(value);
    if (value instanceof RuntimeClientProfile profile
        && profile.webEvidence() == null
        && document instanceof ObjectNode object) {
      object.remove("webEvidence");
    }
    var errors = schema.validate(document);
    assertTrue(errors.isEmpty(), errors.toString());
  }
}
