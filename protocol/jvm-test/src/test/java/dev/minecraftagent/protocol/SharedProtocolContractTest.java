package dev.minecraftagent.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.AllowSchemaLoader;
import com.networknt.schema.resource.UriSchemaLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class SharedProtocolContractTest {
    private static final int MAX_PREVIEW_COMPRESSED_BYTES = 16_777_216;
    private static final int MAX_PREVIEW_UNCOMPRESSED_BYTES = 67_108_864;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,63})}");
    private static final Pattern CAPABILITY_PLUGIN_VERSION_RANGE = Pattern.compile(
            "^(?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){0,2}"
                    + "(?: (?:=|>=|>|<=|<)(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){0,2})*$");
    private static final Pattern CAPABILITY_PLUGIN_VERSION =
            Pattern.compile("^(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){0,2}$");
    private static final Pattern CAPABILITY_PLUGIN_COMPARISON = Pattern.compile(
            "^(>=|<=|=|>|<)((?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){0,2})$");
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = schemaConfig();
    private static final String SCHEMA_ID_PREFIX = "https://minecraft-agent.dev/schemas/1.0/";
    private static final String PROPOSAL_ARGUMENT_HASH_DOMAIN =
            "minecraft-agent/proposal-arguments/v1";

    private final Path protocolRoot =
            Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
    private final JsonSchemaFactory schemaFactory = createSchemaFactory();
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    @TestFactory
    Stream<DynamicTest> allSharedFixturesMatchTheirExpectedResults() throws IOException {
        var manifest = readJson(protocolRoot.resolve("fixtures/manifest.json"));
        assertEquals("1.0", manifest.path("manifestVersion").asText());
        assertEquals("1.0", manifest.path("protocolVersion").asText());
        assertTrue(manifest.path("cases").isArray());

        return StreamSupport.stream(manifest.path("cases").spliterator(), false)
                .map(testCase ->
                        DynamicTest.dynamicTest(testCase.path("id").asText(), () -> verifyCase(testCase)));
    }

    @Test
    void rejectsOptionalGzipHeaderChecksumFlag() {
        var compressed = new byte[18];
        compressed[0] = 0x1f;
        compressed[1] = (byte) 0x8b;
        compressed[2] = 8;
        compressed[3] = 0x02;

        assertThrows(IOException.class, () -> gunzip(compressed, 64));
    }

    private void verifyCase(JsonNode testCase) throws IOException {
        var fixturePath = resolveInsideProtocol("fixtures/" + testCase.path("file").asText());
        var fixture = readJson(fixturePath);

        for (var validation : testCase.path("validations")) {
            var document = fixture.at(validation.path("documentPointer").asText());
            assertFalse(document.isMissingNode(), "documentPointer must resolve in " + fixturePath);

            var schemaName = validation.path("schema").asText();
            var errors = loadSchema(schemaName).validate(document);
            var actualValid = errors.isEmpty();
            var expectedValid = validation.path("expectedValid").asBoolean();
            assertEquals(
                    expectedValid,
                    actualValid,
                    () -> "Schema " + schemaName + " errors for " + fixturePath + ": " + errors);
        }

        if (testCase.has("semanticValidation")) {
            verifySemantics(testCase.path("semanticValidation"), fixture, fixturePath);
        }
    }

    private void verifySemantics(JsonNode expectation, JsonNode fixture, Path fixturePath) {
        var validator = expectation.path("validator").asText();
        var issues = switch (validator) {
            case "handshake-proof-v1" -> validateHandshakeProof(fixture);
            case "build-preview-transfer-v1" -> validateBuildPreview(fixture);
            case "recipe-view-v1" -> validateRecipeView(fixture);
            case "recipe-view-v2" -> validateRecipeViewV2(fixture);
            case "capability-manifest-v1" -> validateCapability(fixture);
            case "capability-pack-v1" -> validateCapabilityPack(fixture);
            case "capability-plugin-version-v1" -> validateCapabilityPluginVersion(fixture);
            case "view-negotiation-v1" -> validateViewNegotiation(fixture);
            case "proposal-argument-hash-v1" -> validateProposalArgumentHash(fixture);
            default -> List.of("SEMANTIC_VALIDATOR_UNKNOWN");
        };

        var expectedValid = expectation.path("expectedValid").asBoolean();
        assertEquals(
                expectedValid,
                issues.isEmpty(),
                () -> "Semantic issues for " + fixturePath + ": " + issues);
        if (expectation.has("errorCode")) {
            assertTrue(
                    issues.contains(expectation.path("errorCode").asText()),
                    () -> "Expected semantic error "
                            + expectation.path("errorCode").asText()
                            + " for "
                            + fixturePath
                            + ", received "
                            + issues);
            if (validator.equals("recipe-view-v2")) {
                assertEquals(expectation.path("errorCode").asText(), issues.getFirst());
            }
        }
    }

    private List<String> validateHandshakeProof(JsonNode exchange) {
        if (!exchange.path("publicTestToken").isTextual()) {
            return List.of("HANDSHAKE_GOLDEN_STRUCTURE_INVALID");
        }
        var paper = exchange.path("paper");
        var runtime = exchange.path("runtime");
        if (!hasHandshakeGoldenFields(paper) || !hasHandshakeGoldenFields(runtime)) {
            return List.of("HANDSHAKE_GOLDEN_STRUCTURE_INVALID");
        }
        var paperPayload = paper.path("payload");
        var runtimePayload = runtime.path("payload");
        var paperAuthentication = paperPayload.path("authentication");
        var runtimeAuthentication = runtimePayload.path("authentication");

        if (!"1.0".equals(paper.path("protocolVersion").asText())
                || !"1.0".equals(runtime.path("protocolVersion").asText())
                || !"paper.hello".equals(paper.path("type").asText())
                || !"runtime.hello".equals(runtime.path("type").asText())
                || !"paper".equals(paperPayload.path("component").asText())
                || !"runtime".equals(runtimePayload.path("component").asText())
                || !"hmac-sha256".equals(paperAuthentication.path("scheme").asText())
                || !"hmac-sha256".equals(runtimeAuthentication.path("scheme").asText())) {
            return List.of("HANDSHAKE_IDENTITY_INVALID");
        }
        if (!paper.path("messageId").asText().equals(paper.path("requestId").asText())
                || !runtime.path("requestId").asText().equals(paper.path("requestId").asText())
                || runtime.path("messageId").asText().equals(paper.path("messageId").asText())
                || !runtime.path("serverId").asText().equals(paper.path("serverId").asText())
                || !paperAuthentication
                        .path("keyId")
                        .asText()
                        .equals(paper.path("serverId").asText())
                || !runtimeAuthentication
                        .path("keyId")
                        .asText()
                        .equals(paper.path("serverId").asText())) {
            return List.of("HANDSHAKE_CORRELATION_INVALID");
        }
        if (!supportsOnlyProtocolOne(paperPayload)
                || !supportsOnlyProtocolOne(runtimePayload)
                || !paperPayload.path("selectedProtocolVersion").isNull()
                || !"1.0".equals(runtimePayload.path("selectedProtocolVersion").asText())) {
            return List.of("HANDSHAKE_NEGOTIATION_INVALID");
        }
        if (!runtimeAuthentication
                .path("challenge")
                .asText()
                .equals(paperAuthentication.path("challenge").asText())) {
            return List.of("HANDSHAKE_CHALLENGE_MISMATCH");
        }
        if (paper.path("nonce").asText().equals(runtime.path("nonce").asText())
                || decodeCanonicalBase64Url(paper.path("nonce").asText(), 16, -1) == null
                || decodeCanonicalBase64Url(runtime.path("nonce").asText(), 16, -1) == null
                || decodeCanonicalBase64Url(
                                paperAuthentication.path("challenge").asText(), 16, -1)
                        == null
                || decodeCanonicalBase64Url(paperAuthentication.path("proof").asText(), 0, 32)
                        == null
                || decodeCanonicalBase64Url(runtimeAuthentication.path("proof").asText(), 0, 32)
                        == null) {
            return List.of("HANDSHAKE_BASE64URL_INVALID");
        }

        var token = exchange.path("publicTestToken").asText();
        if (!handshakeProofMatches(token, paper) || !handshakeProofMatches(token, runtime)) {
            return List.of("HANDSHAKE_PROOF_INVALID");
        }
        return List.of();
    }

    private List<String> validateProposalArgumentHash(JsonNode fixture) {
        var hashContract = fixture.path("hashContract");
        var proposal = fixture.path("proposal");
        var arguments = proposal.path("arguments");
        if (!fixture.isObject()
                || !hashContract.isObject()
                || !proposal.isObject()
                || !arguments.isObject()) {
            return List.of("PROPOSAL_ARGUMENT_HASH_STRUCTURE_INVALID");
        }
        if (!"SHA-256".equals(hashContract.path("algorithm").asText())
                || !PROPOSAL_ARGUMENT_HASH_DOMAIN.equals(
                        hashContract.path("domainUtf8").asText())
                || !"00".equals(hashContract.path("separatorHex").asText())
                || !"RFC8785".equals(hashContract.path("canonicalization").asText())) {
            return List.of("PROPOSAL_ARGUMENT_HASH_CONTRACT_INVALID");
        }

        final String canonical;
        try {
            canonical =
                    new JsonCanonicalizer(JSON.writeValueAsString(arguments)).getEncodedString();
        } catch (IOException | RuntimeException error) {
            return List.of("PROPOSAL_ARGUMENT_CANONICALIZATION_INVALID");
        }
        if (!canonical.equals(hashContract.path("canonicalArguments").asText())) {
            return List.of("PROPOSAL_ARGUMENT_CANONICAL_MISMATCH");
        }

        var canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (canonicalBytes.length
                != hashContract.path("canonicalUtf8ByteLength").asInt(-1)) {
            return List.of("PROPOSAL_ARGUMENT_CANONICAL_LENGTH_MISMATCH");
        }
        var expectedHash = sha256(concatenate(List.of(
                PROPOSAL_ARGUMENT_HASH_DOMAIN.getBytes(StandardCharsets.UTF_8),
                new byte[] {0},
                canonicalBytes)));
        if (!expectedHash.equals(hashContract.path("argumentHash").asText())
                || !expectedHash.equals(proposal.path("argumentHash").asText())) {
            return List.of("PROPOSAL_ARGUMENT_HASH_MISMATCH");
        }
        return List.of();
    }

    private static boolean hasHandshakeGoldenFields(JsonNode envelope) {
        var payload = envelope.path("payload");
        var authentication = payload.path("authentication");
        return envelope.isObject()
                && payload.isObject()
                && authentication.isObject()
                && Stream.of(
                                envelope.path("protocolVersion"),
                                envelope.path("messageId"),
                                envelope.path("requestId"),
                                envelope.path("serverId"),
                                envelope.path("type"),
                                envelope.path("timestamp"),
                                envelope.path("nonce"),
                                payload.path("component"),
                                payload.path("componentVersion"),
                                authentication.path("scheme"),
                                authentication.path("keyId"),
                                authentication.path("challenge"),
                                authentication.path("proof"))
                        .allMatch(JsonNode::isTextual)
                && payload.path("supportedProtocolVersions").isArray()
                && (payload.path("selectedProtocolVersion").isNull()
                        || payload.path("selectedProtocolVersion").isTextual());
    }

    private static boolean supportsOnlyProtocolOne(JsonNode payload) {
        var supported = payload.path("supportedProtocolVersions");
        return supported.size() == 1 && "1.0".equals(supported.path(0).asText());
    }

    private static byte[] decodeCanonicalBase64Url(
            String value, int minimumBytes, int expectedBytes) {
        if (!value.matches("^[A-Za-z0-9_-]+$")) {
            return null;
        }
        try {
            var decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length < minimumBytes
                    || (expectedBytes >= 0 && decoded.length != expectedBytes)
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean handshakeProofMatches(String token, JsonNode envelope) {
        var authentication = envelope.path("payload").path("authentication");
        var suppliedProof = decodeCanonicalBase64Url(authentication.path("proof").asText(), 0, 32);
        var expectedProof = hmacSha256(token, handshakeTranscript(envelope));
        return suppliedProof != null && MessageDigest.isEqual(suppliedProof, expectedProof);
    }

    private static String handshakeTranscript(JsonNode envelope) {
        var payload = envelope.path("payload");
        return String.join(
                "\n",
                "minecraft-agent-handshake-v1",
                envelope.path("serverId").asText(),
                envelope.path("type").asText(),
                envelope.path("timestamp").asText(),
                envelope.path("nonce").asText(),
                payload.path("component").asText(),
                payload.path("componentVersion").asText(),
                payload.path("authentication").path("challenge").asText());
    }

    private static byte[] hmacSha256(String token, String transcript) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(transcript.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", error);
        }
    }

    private List<String> validateBuildPreview(JsonNode preview) {
        var chunks = preview.path("chunks");
        var expectedCount = preview.path("chunkCount").asInt(-1);
        var orderedChunks = new ArrayList<JsonNode>();
        chunks.forEach(orderedChunks::add);
        var seenIndexes = new HashSet<Integer>();
        for (var chunk : orderedChunks) {
            var index = chunk.path("index").asInt(-1);
            if (!seenIndexes.add(index)) {
                return List.of("CHUNK_INDEX_DUPLICATE");
            }
        }
        if (expectedCount != chunks.size()
                || seenIndexes.size() != expectedCount
                || !seenIndexes.containsAll(java.util.stream.IntStream.range(0, expectedCount).boxed().toList())) {
            return List.of("CHUNK_SET_INCOMPLETE");
        }
        orderedChunks.sort(Comparator.comparingInt(chunk -> chunk.path("index").asInt()));

        var declaredCompressedBytes = preview.path("compressedBytes").asInt(-1);
        if (declaredCompressedBytes < 1 || declaredCompressedBytes > MAX_PREVIEW_COMPRESSED_BYTES) {
            return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
        }
        var decodedChunks = new ArrayList<byte[]>();
        var decodedByteCount = 0;
        for (var chunk : orderedChunks) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(chunk.path("data").asText());
            } catch (IllegalArgumentException error) {
                return List.of("CHUNK_BASE64_INVALID");
            }
            if (!Base64.getEncoder().encodeToString(decoded).equals(chunk.path("data").asText())) {
                return List.of("CHUNK_BASE64_INVALID");
            }
            decodedByteCount += decoded.length;
            if (decodedByteCount > declaredCompressedBytes
                    || decodedByteCount > MAX_PREVIEW_COMPRESSED_BYTES) {
                return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
            }
            decodedChunks.add(decoded);
        }
        for (int index = 0; index < orderedChunks.size(); index++) {
            if (orderedChunks.get(index).path("byteLength").asInt(-1)
                    != decodedChunks.get(index).length) {
                return List.of("CHUNK_LENGTH_MISMATCH");
            }
        }
        for (int index = 0; index < orderedChunks.size(); index++) {
            if (!sha256(decodedChunks.get(index))
                    .equals(orderedChunks.get(index).path("sha256").asText())) {
                return List.of("CHUNK_HASH_MISMATCH");
            }
        }

        var compressed = concatenate(decodedChunks);
        if (declaredCompressedBytes != compressed.length) {
            return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
        }

        var declaredUncompressedBytes = preview.path("uncompressedBytes").asInt(-1);
        byte[] content;
        try {
            content = switch (preview.path("encoding").asText()) {
                case "identity+base64" -> compressed;
                case "gzip+base64" ->
                    gunzip(
                            compressed,
                            Math.min(declaredUncompressedBytes, MAX_PREVIEW_UNCOMPRESSED_BYTES));
                default -> throw new IOException("unsupported encoding");
            };
        } catch (IOException error) {
            return List.of("CONTENT_DECOMPRESSION_FAILED");
        }

        if (declaredUncompressedBytes != content.length) {
            return List.of("CONTENT_UNCOMPRESSED_LENGTH_MISMATCH");
        }
        if (!sha256(content).equals(preview.path("contentHash").asText())) {
            return List.of("CONTENT_HASH_MISMATCH");
        }
        if ("minecraft-agent.palette-v1".equals(preview.path("contentFormat").asText())) {
            var contentIssue = validateBuildContent(preview, content);
            if (contentIssue != null) {
                return List.of(contentIssue);
            }
        }
        return List.of();
    }

    private String validateBuildContent(JsonNode preview, byte[] content) {
        final String source;
        try {
            source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException error) {
            return "CONTENT_JSON_INVALID";
        }
        final JsonNode parsed;
        try {
            parsed = STRICT_JSON.readTree(source);
        } catch (IOException error) {
            return "CONTENT_JSON_INVALID";
        }
        if (!parsed.isObject()
                || parsed.path("version").asInt(-1) != 1
                || !parsed.path("blocks").isArray()) {
            return "CONTENT_JSON_INVALID";
        }
        try {
            if (!new JsonCanonicalizer(source).getEncodedString().equals(source)) {
                return "CONTENT_CANONICAL_MISMATCH";
            }
        } catch (IOException | RuntimeException error) {
            return "CONTENT_CANONICAL_MISMATCH";
        }

        var palette = preview.path("palette");
        if (!palette.isArray()) {
            return "PALETTE_ID_INVALID";
        }
        try {
            var canonicalPalette = new JsonCanonicalizer(palette.toString()).getEncodedString();
            if (!sha256(canonicalPalette.getBytes(StandardCharsets.UTF_8))
                    .equals(preview.path("paletteHash").asText())) {
                return "PALETTE_HASH_MISMATCH";
            }
        } catch (IOException | RuntimeException error) {
            return "PALETTE_HASH_MISMATCH";
        }
        var states = new HashSet<String>();
        String previousState = null;
        for (int index = 0; index < palette.size(); index++) {
            var entry = palette.path(index);
            if (!entry.isObject()
                    || entry.path("id").asInt(-1) != index
                    || !entry.path("properties").isObject()) {
                return "PALETTE_ID_INVALID";
            }
            var blockId = entry.path("blockId").asText();
            if (blockId.isBlank() || "minecraft:air".equals(blockId)) {
                return "PALETTE_STATE_INVALID";
            }
            var properties = new java.util.TreeMap<String, String>();
            entry.path("properties").properties().forEach(property -> {
                if (property.getValue().isTextual()) {
                    properties.put(property.getKey(), property.getValue().asText());
                }
            });
            if (properties.size() != entry.path("properties").size()) {
                return "PALETTE_STATE_INVALID";
            }
            var state = blockId;
            if (!properties.isEmpty()) {
                state += "[" + properties.entrySet().stream()
                        .map(property -> property.getKey() + "=" + property.getValue())
                        .collect(java.util.stream.Collectors.joining(",")) + "]";
            }
            if (!states.add(state)) {
                return "PALETTE_STATE_DUPLICATE";
            }
            if (previousState != null && previousState.compareTo(state) >= 0) {
                return "PALETTE_ORDER_INVALID";
            }
            previousState = state;
        }

        var minimum = preview.path("bounds").path("min");
        var maximum = preview.path("bounds").path("max");
        if (!validPosition(minimum)
                || !validPosition(maximum)
                || minimum.path("x").asInt() > maximum.path("x").asInt()
                || minimum.path("y").asInt() > maximum.path("y").asInt()
                || minimum.path("z").asInt() > maximum.path("z").asInt()) {
            return "BLOCK_GEOMETRY_INVALID";
        }
        var minX = minimum.path("x").asInt();
        var minY = minimum.path("y").asInt();
        var minZ = minimum.path("z").asInt();
        var maxX = maximum.path("x").asInt();
        var maxY = maximum.path("y").asInt();
        var maxZ = maximum.path("z").asInt();
        var origin = preview.path("origin");
        if (!validPosition(origin)
                || origin.path("x").asInt() < minX
                || origin.path("x").asInt() > maxX
                || origin.path("y").asInt() < minY
                || origin.path("y").asInt() > maxY
                || origin.path("z").asInt() < minZ
                || origin.path("z").asInt() > maxZ) {
            return "BLOCK_GEOMETRY_INVALID";
        }
        var sizeX = (long) maxX - minX + 1;
        var sizeY = (long) maxY - minY + 1;
        var sizeZ = (long) maxZ - minZ + 1;
        var volume = sizeX * sizeY * sizeZ;
        if (sizeX > 32 || sizeY > 32 || sizeZ > 32 || volume > 4096) {
            return "BLOCK_GEOMETRY_INVALID";
        }
        var blocks = parsed.path("blocks");
        if (preview.path("blockCount").asInt(-1) != blocks.size() || blocks.size() > volume) {
            return "BLOCK_COUNT_MISMATCH";
        }
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        int previousZ = Integer.MIN_VALUE;
        var first = true;
        for (var block : blocks) {
            if (!block.isObject()
                    || !integral(block.path("x"))
                    || !integral(block.path("y"))
                    || !integral(block.path("z"))
                    || !integral(block.path("state"))) {
                return "BLOCK_GEOMETRY_INVALID";
            }
            var x = block.path("x").asInt();
            var y = block.path("y").asInt();
            var z = block.path("z").asInt();
            var state = block.path("state").asInt();
            if (x < minX
                    || x > maxX
                    || y < minY
                    || y > maxY
                    || z < minZ
                    || z > maxZ
                    || state < 0
                    || state >= palette.size()) {
                return "BLOCK_GEOMETRY_INVALID";
            }
            if (!first
                    && (y < previousY
                            || (y == previousY && z < previousZ)
                            || (y == previousY && z == previousZ && x <= previousX))) {
                return "BLOCK_ORDER_INVALID";
            }
            first = false;
            previousX = x;
            previousY = y;
            previousZ = z;
        }
        var difference = preview.path("difference");
        if (!Stream.of("added", "replaced", "removed")
                        .allMatch(name -> integral(difference.path(name))
                                && difference.path(name).asInt() >= 0)
                || difference.path("added").asLong()
                                + difference.path("replaced").asLong()
                                + difference.path("removed").asLong()
                        > volume) {
            return "DIFFERENCE_COUNT_INVALID";
        }
        return null;
    }

    private static boolean validPosition(JsonNode position) {
        return position.isObject()
                && integral(position.path("x"))
                && integral(position.path("y"))
                && integral(position.path("z"));
    }

    private List<String> validateRecipeView(JsonNode view) {
        var issues = new ArrayList<String>();
        var recipes = view.path("recipes");
        var selectedRecipe = view.path("selectedRecipe").asInt(-1);
        if (selectedRecipe < 0 || selectedRecipe >= recipes.size()) {
            issues.add("RECIPE_SELECTED_INDEX_OUT_OF_RANGE");
        }

        var recipeIds = new HashSet<String>();
        for (var recipe : recipes) {
            if (!recipeIds.add(recipe.path("recipeId").asText())) {
                issues.add("RECIPE_ID_DUPLICATE");
            }
            var layout = recipe.path("layout");
            var width = layout.path("width").asInt();
            var height = layout.path("height").asInt();
            var occupiedCoordinates = new HashSet<String>();
            var occupiedSlots = new HashSet<Integer>();
            for (var ingredient : layout.path("ingredients")) {
                var x = ingredient.path("x").asInt();
                var y = ingredient.path("y").asInt();
                var slot = ingredient.path("slot").asInt();
                if (x >= width || y >= height) {
                    issues.add("RECIPE_INGREDIENT_OUT_OF_BOUNDS");
                }
                if (!occupiedCoordinates.add(x + ":" + y) || !occupiedSlots.add(slot)) {
                    issues.add("RECIPE_INGREDIENT_DUPLICATE");
                }
                if (slot != y * width + x) {
                    issues.add("RECIPE_SLOT_COORDINATE_MISMATCH");
                }
            }
        }
        return distinct(issues);
    }

    private List<String> validateRecipeViewV2(JsonNode view) {
        if (!view.isObject()
                || !view.path("schemaVersion").asText().equals("2.0")
                || !view.path("recipes").isArray()
                || view.path("recipes").isEmpty()
                || view.path("recipes").size() > 16) {
            return List.of("RECIPE_VIEW_STRUCTURE_INVALID");
        }
        var recipes = view.path("recipes");
        if (!integral(view.path("selectedRecipe"))
                || view.path("selectedRecipe").asInt() < 0
                || view.path("selectedRecipe").asInt() >= recipes.size()) {
            return List.of("RECIPE_SELECTED_INDEX_OUT_OF_RANGE");
        }
        if (!integral(view.path("totalMatches"))
                || view.path("totalMatches").asInt() < recipes.size()
                || !view.path("truncated").isBoolean()
                || (!view.path("truncated").asBoolean()
                        && view.path("totalMatches").asInt() != recipes.size())) {
            return List.of("RECIPE_RESULT_SUMMARY_INVALID");
        }

        var recipeIds = new HashSet<String>();
        var inspections = new ArrayList<RecipeV2Inspection>();
        for (var index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            if (!recipe.isObject() || !recipe.path("recipeId").isTextual()) {
                return List.of("RECIPE_VIEW_STRUCTURE_INVALID");
            }
            if (!recipeIds.add(recipe.path("recipeId").asText())) {
                return List.of("RECIPE_ID_DUPLICATE");
            }
            var inspection = new RecipeV2Inspection(
                    recipe, new HashSet<>(), new ArrayList<>(), new ArrayList<>());
            if (!recipe.path("result").isNull()) {
                inspection.itemStacks().add(recipe.path("result"));
            }
            inspections.add(inspection);
        }

        for (var inspection : inspections) {
            var issue = inspectRecipeV2Layout(inspection);
            if (issue != null) {
                return List.of(issue);
            }
        }
        for (var inspection : inspections) {
            for (var choice : inspection.choices()) {
                var issue = inspectRecipeV2Choice(inspection, choice);
                if (issue != null) {
                    return List.of(issue);
                }
            }
        }
        for (var inspection : inspections) {
            var recipe = inspection.recipe();
            var cooking = Set.of("smelting", "blasting", "smoking", "campfire_cooking")
                    .contains(recipe.path("recipeType").asText());
            if (cooking) {
                var processing = recipe.path("processing");
                if (!processing.isObject()
                        || !integral(processing.path("timeTicks"))
                        || processing.path("timeTicks").asInt() < 0
                        || processing.path("timeTicks").asInt() > 120_000
                        || !processing.path("experience").isNumber()
                        || !Double.isFinite(processing.path("experience").asDouble())
                        || processing.path("experience").asDouble() < 0
                        || processing.path("experience").asDouble() > 1_000_000) {
                    return List.of("RECIPE_PROCESSING_INVALID");
                }
            } else if (recipe.has("processing")) {
                return List.of("RECIPE_PROCESSING_INVALID");
            }
        }
        for (var inspection : inspections) {
            var source = inspection.recipe().path("source");
            var kind = source.path("kind").asText();
            var provider = source.path("providerId");
            if (!source.isObject()
                    || (kind.equals("server_registry") && !provider.isNull())
                    || (kind.equals("plugin_provider") && !provider.isTextual())
                    || (!kind.equals("server_registry") && !kind.equals("plugin_provider"))) {
                return List.of("RECIPE_SOURCE_INVALID");
            }
        }
        for (var inspection : inspections) {
            var remainingItems = inspection.recipe().path("remainingItems");
            if (!remainingItems.isArray()) {
                return List.of("RECIPE_REMAINING_ITEM_INVALID");
            }
            var remainingSlots = new HashSet<Integer>();
            for (var remaining : remainingItems) {
                if (!remaining.isObject() || !integral(remaining.path("slot"))) {
                    return List.of("RECIPE_REMAINING_ITEM_INVALID");
                }
                var slot = remaining.path("slot").asInt();
                if (!inspection.logicalSlots().contains(slot) || !remainingSlots.add(slot)) {
                    return List.of("RECIPE_REMAINING_ITEM_INVALID");
                }
                inspection.itemStacks().add(remaining.path("item"));
            }
        }
        for (var inspection : inspections) {
            for (var stack : inspection.itemStacks()) {
                if (recipeV2ComponentInvalid(stack)) {
                    return List.of("RECIPE_COMPONENT_INVALID");
                }
            }
        }
        return List.of();
    }

    private String inspectRecipeV2Layout(RecipeV2Inspection inspection) {
        var recipe = inspection.recipe();
        var layout = recipe.path("layout");
        if (!layout.isObject()) {
            return "RECIPE_LAYOUT_INVALID";
        }
        var expectedKind = expectedRecipeV2Layout(recipe.path("recipeType").asText());
        if (expectedKind == null || !layout.path("kind").asText().equals(expectedKind)) {
            return "RECIPE_LAYOUT_INVALID";
        }
        if (expectedKind.equals("grid")) {
            var width = layout.path("width");
            var height = layout.path("height");
            var ingredients = layout.path("ingredients");
            if (!integral(width)
                    || !integral(height)
                    || width.asInt() < 1
                    || width.asInt() > 3
                    || height.asInt() < 1
                    || height.asInt() > 3
                    || !ingredients.isArray()
                    || ingredients.isEmpty()
                    || ingredients.size() > 9) {
                return "RECIPE_LAYOUT_INVALID";
            }
            var slots = new HashSet<Integer>();
            var positions = new HashSet<String>();
            for (var ingredient : ingredients) {
                if (!ingredient.isObject()
                        || !integral(ingredient.path("slot"))
                        || !integral(ingredient.path("x"))
                        || !integral(ingredient.path("y"))) {
                    return "RECIPE_LAYOUT_INVALID";
                }
                var slot = ingredient.path("slot").asInt();
                var x = ingredient.path("x").asInt();
                var y = ingredient.path("y").asInt();
                if (x < 0 || x >= width.asInt() || y < 0 || y >= height.asInt()) {
                    return "RECIPE_INGREDIENT_OUT_OF_BOUNDS";
                }
                if (!slots.add(slot) || !positions.add(x + ":" + y)) {
                    return "RECIPE_INGREDIENT_DUPLICATE";
                }
                if (slot != y * width.asInt() + x) {
                    return "RECIPE_SLOT_COORDINATE_MISMATCH";
                }
                inspection.logicalSlots().add(slot);
                inspection.choices().add(ingredient.path("ingredient"));
            }
        } else if (expectedKind.equals("single_input")) {
            inspection.logicalSlots().add(0);
            inspection.choices().add(layout.path("ingredient"));
        } else if (expectedKind.equals("smithing")) {
            inspection.logicalSlots().addAll(List.of(0, 1, 2));
            inspection.choices().add(layout.path("template"));
            inspection.choices().add(layout.path("base"));
            inspection.choices().add(layout.path("addition"));
        } else if (expectedKind.equals("transmute")) {
            inspection.logicalSlots().addAll(List.of(0, 1));
            inspection.choices().add(layout.path("input"));
            inspection.choices().add(layout.path("material"));
        } else if (!layout.path("reason").asText().equals("UNSUPPORTED_RECIPE_LAYOUT")) {
            return "RECIPE_LAYOUT_INVALID";
        }
        return null;
    }

    private String inspectRecipeV2Choice(RecipeV2Inspection inspection, JsonNode choice) {
        var alternatives = choice.path("alternatives");
        if (!choice.isObject() || !alternatives.isArray()) {
            return "RECIPE_INGREDIENT_CHOICE_INVALID";
        }
        var choiceType = choice.path("choiceType").asText();
        if (choiceType.equals("unsupported")) {
            return choice.path("reason").asText().equals("UNSUPPORTED_INGREDIENT_CHOICE")
                            && alternatives.isEmpty()
                            && !choice.has("tagId")
                    ? null
                    : "RECIPE_INGREDIENT_CHOICE_INVALID";
        }
        if (!Set.of("material", "exact", "item_type", "tag").contains(choiceType)
                || alternatives.isEmpty()
                || alternatives.size() > 64
                || choiceType.equals("tag") != choice.has("tagId")
                || choice.has("reason")) {
            return "RECIPE_INGREDIENT_CHOICE_INVALID";
        }
        alternatives.forEach(inspection.itemStacks()::add);
        return null;
    }

    private static String expectedRecipeV2Layout(String recipeType) {
        if (Set.of("shaped", "shapeless").contains(recipeType)) {
            return "grid";
        }
        if (Set.of("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting")
                .contains(recipeType)) {
            return "single_input";
        }
        if (Set.of("smithing_transform", "smithing_trim").contains(recipeType)) {
            return "smithing";
        }
        if (recipeType.equals("transmute")) {
            return "transmute";
        }
        if (Set.of("complex", "custom").contains(recipeType)) {
            return "unsupported";
        }
        return null;
    }

    private static boolean recipeV2ComponentInvalid(JsonNode stack) {
        if (!stack.isObject() || !stack.path("components").isObject()) {
            return true;
        }
        var components = stack.path("components");
        var invalidDamage = components.path("damage").isNumber()
                && components.path("maxDamage").isNumber()
                && components.path("damage").asLong() > components.path("maxDamage").asLong();
        var invalidName = components.has("customName")
                && unsafeRecipeDisplayText(components.path("customName"));
        var lore = components.path("lore");
        var invalidLore = components.has("lore")
                && (!lore.isArray()
                        || StreamSupport.stream(lore.spliterator(), false)
                                .anyMatch(SharedProtocolContractTest::unsafeRecipeDisplayText));
        return invalidDamage || invalidName || invalidLore;
    }

    private static boolean unsafeRecipeDisplayText(JsonNode value) {
        if (!value.isTextual()) {
            return true;
        }
        return value.textValue().codePoints().anyMatch(codePoint -> codePoint <= 0x1f
                || codePoint >= 0x7f && codePoint <= 0x9f
                || codePoint >= 0xd800 && codePoint <= 0xdfff
                || codePoint == 0x061c
                || codePoint == 0x200e
                || codePoint == 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static boolean integral(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }

    private record RecipeV2Inspection(
            JsonNode recipe,
            Set<Integer> logicalSlots,
            List<JsonNode> choices,
            List<JsonNode> itemStacks) {}

    private List<String> validateCapability(JsonNode manifest) {
        if (!manifest.isObject()
                || !manifest.path("requirements").path("plugins").isArray()
                || !manifest.path("execution").isObject()
                || !manifest.path("arguments").isObject()
                || !manifest.path("effects").isObject()
                || !manifest.path("confirmation").isObject()
                || !manifest.path("reversibility").isObject()) {
            return List.of("CAPABILITY_STRUCTURE_INVALID");
        }
        var execution = manifest.path("execution");
        var template = execution.path("template").asText();
        var arguments = manifest.path("arguments");
        var argumentUses = new HashMap<String, Integer>();
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (matcher.start() == 0
                    || template.charAt(matcher.start() - 1) != ' '
                    || (matcher.end() < template.length()
                            && template.charAt(matcher.end()) != ' ')) {
                return List.of("CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED");
            }
            var argument = matcher.group(1);
            argumentUses.merge(argument, 1, Integer::sum);
        }
        if (PLACEHOLDER.matcher(template).replaceAll("").matches(".*[{}].*")) {
            return List.of("CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED");
        }
        if (argumentUses.keySet().stream().anyMatch(argument -> !arguments.has(argument))) {
            return List.of("CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED");
        }
        if (argumentUses.values().stream().anyMatch(count -> count > 1)) {
            return List.of("CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE");
        }
        for (var entry : arguments.properties()) {
            if (entry.getValue().path("required").asBoolean()
                    && !argumentUses.containsKey(entry.getKey())) {
                return List.of("CAPABILITY_REQUIRED_ARGUMENT_UNUSED");
            }
        }
        for (var entry : arguments.properties()) {
            if (!entry.getValue().path("required").asBoolean()) {
                return List.of("CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED");
            }
        }

        var command = template.substring(1).split("\\s+", 2)[0];
        if (!command.equals(execution.path("commandRoot").asText())) {
            return List.of("CAPABILITY_COMMAND_ROOT_MISMATCH");
        }
        for (var entry : arguments.properties()) {
            var minimum = entry.getValue().has("minimum")
                    ? entry.getValue().path("minimum").asDouble()
                    : entry.getValue().path("minLength").asDouble(Double.NaN);
            var maximum = entry.getValue().has("maximum")
                    ? entry.getValue().path("maximum").asDouble()
                    : entry.getValue().path("maxLength").asDouble(Double.NaN);
            if (!Double.isNaN(minimum) && !Double.isNaN(maximum) && minimum > maximum) {
                return List.of("CAPABILITY_ARGUMENT_RANGE_INVALID");
            }
        }

        var pluginNames = new HashSet<String>();
        for (var plugin : manifest.path("requirements").path("plugins")) {
            var normalizedName = plugin.path("name").asText().toLowerCase(Locale.ROOT);
            if (!pluginNames.add(normalizedName)) {
                return List.of("CAPABILITY_PLUGIN_REQUIREMENT_DUPLICATE");
            }
            var versionRange = plugin.path("version").asText();
            if (!CAPABILITY_PLUGIN_VERSION_RANGE.matcher(versionRange).matches()
                    || versionRange.split(" ").length > 16) {
                return List.of("CAPABILITY_PLUGIN_VERSION_RANGE_INVALID");
            }
        }

        var effects = manifest.path("effects");
        var category = effects.path("category").asText();
        var maximumBlocks = effects.path("maximumBlocks");
        if (("WRITE_WORLD".equals(category) && !maximumBlocks.isNumber())
                || (!"WRITE_WORLD".equals(category) && !maximumBlocks.isNull())) {
            return List.of("CAPABILITY_EFFECT_CONSTRAINT_INVALID");
        }
        if (!"READ".equals(category)
                && !manifest.path("confirmation").path("required").asBoolean()) {
            return List.of("CAPABILITY_CONFIRMATION_POLICY_INVALID");
        }
        return List.of();
    }

    private List<String> validateCapabilityPack(JsonNode pack) {
        var capabilities = pack.path("capabilities");
        if (!pack.isObject() || !capabilities.isArray()) {
            return List.of("CAPABILITY_PACK_STRUCTURE_INVALID");
        }
        var byId = new HashMap<String, JsonNode>();
        for (var capability : capabilities) {
            var manifestIssues = validateCapability(capability);
            if (!manifestIssues.isEmpty()) {
                return manifestIssues;
            }
            var id = capability.path("id").asText();
            if (byId.putIfAbsent(id, capability) != null) {
                return List.of("CAPABILITY_PACK_ID_DUPLICATE");
            }
        }
        for (var capability : capabilities) {
            var reversibility = capability.path("reversibility");
            if (!"capability".equals(reversibility.path("type").asText())) {
                continue;
            }
            var target = byId.get(reversibility.path("capability").asText());
            if (target == null
                    || target == capability
                    || target.has("status")
                    || !target.path("execution")
                            .path("source")
                            .equals(capability.path("execution").path("source"))
                    || !target.path("effects")
                            .path("category")
                            .equals(capability.path("effects").path("category"))
                    || !target.path("effects")
                            .path("scope")
                            .equals(capability.path("effects").path("scope"))
                    || !pluginRequirements(target).equals(pluginRequirements(capability))) {
                return List.of("CAPABILITY_REVERSIBILITY_TARGET_INVALID");
            }
        }
        return List.of();
    }

    private Map<String, String> pluginRequirements(JsonNode capability) {
        var requirements = new HashMap<String, String>();
        for (var plugin : capability.path("requirements").path("plugins")) {
            requirements.put(
                    plugin.path("name").asText().toLowerCase(Locale.ROOT),
                    plugin.path("version").asText());
        }
        return requirements;
    }

    private List<String> validateCapabilityPluginVersion(JsonNode golden) {
        var manifest = golden.path("manifest");
        var cases = golden.path("cases");
        if (!golden.isObject() || !manifest.isObject() || !cases.isArray() || cases.isEmpty()) {
            return List.of("CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID");
        }
        var manifestIssues = validateCapability(manifest);
        if (!manifestIssues.isEmpty()) {
            return manifestIssues;
        }
        var plugins = manifest.path("requirements").path("plugins");
        if (plugins.size() != 1) {
            return List.of("CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID");
        }
        var range = plugins.get(0).path("version").asText();
        for (var testCase : cases) {
            if (!testCase.path("installedVersion").isTextual()
                    || !Set.of("match", "mismatch", "invalid")
                            .contains(testCase.path("expected").asText())) {
                return List.of("CAPABILITY_PLUGIN_VERSION_GOLDEN_STRUCTURE_INVALID");
            }
            var installedVersion = testCase.path("installedVersion").asText();
            var expected = testCase.path("expected").asText();
            var installed = parseCapabilityVersion(installedVersion);
            var actual = installed == null
                    ? "invalid"
                    : capabilityVersionMatches(range, installed) ? "match" : "mismatch";
            if (!actual.equals(expected)) {
                return List.of("CAPABILITY_PLUGIN_VERSION_GOLDEN_MISMATCH");
            }
        }
        return List.of();
    }

    private List<BigInteger> parseCapabilityVersion(String value) {
        if (value.length() > 128 || !CAPABILITY_PLUGIN_VERSION.matcher(value).matches()) {
            return null;
        }
        var components = new ArrayList<BigInteger>(3);
        for (var component : value.split("\\.")) {
            components.add(new BigInteger(component));
        }
        while (components.size() < 3) {
            components.add(BigInteger.ZERO);
        }
        return components;
    }

    private boolean capabilityVersionMatches(String range, List<BigInteger> installed) {
        for (var token : range.split(" ")) {
            var comparison = CAPABILITY_PLUGIN_COMPARISON.matcher(token);
            if (!comparison.matches()) {
                return false;
            }
            var boundary = parseCapabilityVersion(comparison.group(2));
            if (boundary == null) {
                return false;
            }
            var order = compareCapabilityVersions(installed, boundary);
            var matches = switch (comparison.group(1)) {
                case "=" -> order == 0;
                case ">" -> order > 0;
                case ">=" -> order >= 0;
                case "<" -> order < 0;
                case "<=" -> order <= 0;
                default -> false;
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private int compareCapabilityVersions(List<BigInteger> left, List<BigInteger> right) {
        for (var index = 0; index < 3; index++) {
            var comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private List<String> validateViewNegotiation(JsonNode negotiation) {
        var client = negotiation.path("client");
        var view = negotiation.path("view");
        if (!Set.of("1.0", "1.1").contains(client.path("clientProtocolVersion").asText())) {
            return List.of("VIEW_PROTOCOL_UNSUPPORTED");
        }

        var requiredCapabilities = switch (view.path("viewType").asText()) {
            case "text", "selection_list", "proposal" -> List.of("overlay");
            case "item_stack", "item_list" -> List.of("overlay", "itemIcons");
            case "recipe" -> List.of("overlay", "itemIcons", "recipeView");
            case "build_preview" -> List.of("overlay", "litematicaPreview");
            default -> List.<String>of();
        };
        var capabilities = client.path("capabilities");
        for (var capability : requiredCapabilities) {
            var requiredVersion = view.path("viewType").asText().equals("recipe")
                            && capability.equals("recipeView")
                            && view.path("content").path("schemaVersion").asText().equals("2.0")
                    ? 2
                    : 1;
            if (capabilities.path(capability).asInt(0) != requiredVersion) {
                return List.of("VIEW_CAPABILITY_UNDECLARED");
            }
        }
        return List.of();
    }

    private JsonSchema loadSchema(String name) throws IOException {
        var cached = schemas.get(name);
        if (cached != null) {
            return cached;
        }
        var schemaNode = readJson(resolveInsideProtocol("schemas/" + name));
        var schema = schemaFactory.getSchema(schemaNode, SCHEMA_CONFIG);
        schemas.put(name, schema);
        return schema;
    }

    private Path resolveInsideProtocol(String relativePath) {
        var resolved = protocolRoot.resolve(relativePath).normalize();
        assertTrue(resolved.startsWith(protocolRoot), "protocol path must remain inside protocol root");
        return resolved;
    }

    private static SchemaValidatorsConfig schemaConfig() {
        return SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .failFast(false)
                .build();
    }

    private JsonSchemaFactory createSchemaFactory() {
        var localSchemaPrefix = protocolRoot.resolve("schemas").toUri().toString();
        return JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> {
                    builder.schemaMappers(
                            mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, localSchemaPrefix));
                    builder.schemaLoaders(loaders -> loaders.values(values -> {
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
    }

    private static JsonNode readJson(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return JSON.readTree(input);
        }
    }

    private static byte[] concatenate(List<byte[]> chunks) {
        var length = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        var result = new byte[length];
        var offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private static byte[] gunzip(byte[] compressed, int maximumOutputBytes) throws IOException {
        if (maximumOutputBytes < 0
                || compressed.length < 18
                || unsigned(compressed[0]) != 0x1f
                || unsigned(compressed[1]) != 0x8b
                || unsigned(compressed[2]) != 8) {
            throw new IOException("invalid uncompressed byte limit");
        }
        var flags = unsigned(compressed[3]);
        if ((flags & 0xe0) != 0) {
            throw new IOException("gzip reserved flags are set");
        }
        if ((flags & 0x02) != 0) {
            throw new IOException("gzip header checksum flag is unsupported");
        }
        var offset = 10;
        if ((flags & 0x04) != 0) {
            requireGzipHeader(compressed, offset, 2);
            var length = unsigned(compressed[offset]) | unsigned(compressed[offset + 1]) << 8;
            offset += 2;
            requireGzipHeader(compressed, offset, length);
            offset += length;
        }
        for (var flag : List.of(0x08, 0x10)) {
            if ((flags & flag) == 0) {
                continue;
            }
            while (offset < compressed.length - 8 && compressed[offset] != 0) {
                offset++;
            }
            requireGzipHeader(compressed, offset, 1);
            offset++;
        }
        var inflater = new Inflater(true);
        try (var output = new ByteArrayOutputStream(Math.min(maximumOutputBytes, 8192))) {
            inflater.setInput(compressed, offset, compressed.length - offset);
            var buffer = new byte[8192];
            var total = 0;
            while (!inflater.finished()) {
                final int read;
                try {
                    read = inflater.inflate(buffer);
                } catch (DataFormatException error) {
                    throw new IOException("invalid deflate data", error);
                }
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("truncated deflate data");
                }
                total += read;
                if (total > maximumOutputBytes) {
                    throw new IOException("gzip content exceeds declared uncompressed byte limit");
                }
                output.write(buffer, 0, read);
            }
            var trailer = offset + Math.toIntExact(inflater.getBytesRead());
            if (trailer + 8 != compressed.length) {
                throw new IOException("gzip must have one member and no trailing data");
            }
            var content = output.toByteArray();
            var crc = new CRC32();
            crc.update(content);
            if (crc.getValue() != littleEndianUnsignedInt(compressed, trailer)
                    || Integer.toUnsignedLong(content.length)
                            != littleEndianUnsignedInt(compressed, trailer + 4)) {
                throw new IOException("gzip trailer mismatch");
            }
            return content;
        } finally {
            inflater.end();
        }
    }

    private static void requireGzipHeader(byte[] source, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > source.length - 8) {
            throw new IOException("truncated gzip header");
        }
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static long littleEndianUnsignedInt(byte[] source, int offset) {
        return Integer.toUnsignedLong(unsigned(source[offset])
                | unsigned(source[offset + 1]) << 8
                | unsigned(source[offset + 2]) << 16
                | unsigned(source[offset + 3]) << 24);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }

    private static List<String> distinct(List<String> issues) {
        return issues.stream().distinct().toList();
    }
}
