package dev.minecraftagent.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class BuildPreviewDecoderTest {
  private static final UUID PREVIEW_ID = UUID.fromString("b0000000-0000-4000-8000-000000000001");
  private static final String CONTENT =
      "{\"blocks\":[{\"state\":0,\"x\":4,\"y\":65,\"z\":-2}],\"version\":1}";
  private static final String PALETTE =
      "[{\"id\":0,\"blockId\":\"minecraft:stone\",\"properties\":{}}]";

  private final StructuredViewDecoder decoder =
      new StructuredViewDecoder(
          (blockId, properties) -> {
            if (!blockId.equals("minecraft:stone") || !properties.isEmpty()) {
              throw new IllegalArgumentException("not in the test registry");
            }
          });

  @Test
  void decodesOutOfOrderChunksAndBindsBakedMetadata() throws Exception {
    Preview preview = new Preview();
    StructuredView view = decoder.decode(preview.envelope());
    BuildPreviewView build = assertInstanceOf(BuildPreviewView.class, view.content());

    assertEquals(PREVIEW_ID, build.previewId());
    assertEquals(1, build.revision());
    assertEquals(90, build.transform().rotation());
    assertEquals(BuildPreviewView.Mirror.FRONT_BACK, build.transform().mirror());
    assertEquals(new BuildPreviewView.Position(5, 65, -2), build.origin());
    assertEquals(new BuildPreviewView.Position(4, 65, -2), build.blocks().getFirst().position());
    assertEquals("minecraft:stone", build.palette().getFirst().blockId());
    assertEquals("a".repeat(64), build.baseRegionHash());
    assertEquals("b".repeat(64), build.changeSetHash());
  }

  @Test
  void decodesTheSharedOutOfOrderBuildPreviewFixture() throws Exception {
    Path protocol = Path.of(System.getProperty("minecraftAgent.protocolDir"));
    String content =
        Files.readString(protocol.resolve("fixtures/valid/build-preview-out-of-order-chunks.json"));
    byte[] envelope =
        ("{\"viewSchemaVersion\":\"1.0\",\"viewId\":\""
                + PREVIEW_ID
                + "\",\"requestId\":\"b0000000-0000-4000-8000-000000000003\","
                + "\"viewType\":\"build_preview\",\"revision\":1,\"title\":\"Build\","
                + "\"fallbackText\":\"Build preview\",\"pinnable\":true,\"content\":"
                + content
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    BuildPreviewView build =
        assertInstanceOf(BuildPreviewView.class, decoder.decode(envelope).content());
    assertEquals("b".repeat(64), build.changeSetHash());
    assertEquals(1, build.blocks().size());
  }

  @Test
  void requiresOuterAndInnerIdentityToMatch() {
    Preview preview = new Preview();
    preview.previewId = "b0000000-0000-4000-8000-000000000009";
    assertCode(ViewDecodeException.Code.METADATA_MISMATCH, preview);

    preview = new Preview();
    preview.revision = 2;
    assertCode(ViewDecodeException.Code.METADATA_MISMATCH, preview);
  }

  @Test
  void checksDuplicateIndexesBeforeCompleteness() {
    Preview duplicate = new Preview();
    duplicate.chunkIndexes = new int[] {1, 1};
    assertCode(ViewDecodeException.Code.CHUNK_INDEX_DUPLICATE, duplicate);

    Preview incomplete = new Preview();
    incomplete.chunkCount = 3;
    assertCode(ViewDecodeException.Code.CHUNK_SET_INCOMPLETE, incomplete);
  }

  @Test
  void rejectsNonCanonicalBase64AndChunkIntegrityFailures() {
    Preview base64 = new Preview();
    base64.dataTransform = value -> value.substring(0, value.length() - 1);
    assertCode(ViewDecodeException.Code.CHUNK_BASE64_INVALID, base64);

    Preview length = new Preview();
    length.declaredChunkLengthDelta = 1;
    assertCode(ViewDecodeException.Code.CHUNK_LENGTH_MISMATCH, length);

    Preview hash = new Preview();
    hash.chunkHashOverride = "0".repeat(64);
    assertCode(ViewDecodeException.Code.CHUNK_HASH_MISMATCH, hash);
  }

  @Test
  void validatesWholeTransferLengthsAndContentHash() {
    Preview compressed = new Preview();
    compressed.declaredCompressedDelta = 1;
    assertCode(ViewDecodeException.Code.CONTENT_COMPRESSED_LENGTH_MISMATCH, compressed);

    Preview uncompressed = new Preview();
    uncompressed.declaredUncompressedDelta = 1;
    assertCode(ViewDecodeException.Code.CONTENT_UNCOMPRESSED_LENGTH_MISMATCH, uncompressed);

    Preview hash = new Preview();
    hash.contentHashOverride = "0".repeat(64);
    assertCode(ViewDecodeException.Code.CONTENT_HASH_MISMATCH, hash);
  }

  @Test
  void rejectsTrailingConcatenatedAndBadCrcGzipMembers() throws Exception {
    byte[] gzip = gzip(CONTENT.getBytes(StandardCharsets.UTF_8));

    Preview trailing = Preview.gzip(append(gzip, new byte[] {0}));
    assertCode(ViewDecodeException.Code.CONTENT_DECOMPRESSION_FAILED, trailing);

    Preview concatenated = Preview.gzip(append(gzip, gzip));
    assertCode(ViewDecodeException.Code.CONTENT_DECOMPRESSION_FAILED, concatenated);

    byte[] badCrc = Arrays.copyOf(gzip, gzip.length);
    badCrc[badCrc.length - 8] ^= 1;
    Preview crc = Preview.gzip(badCrc);
    assertCode(ViewDecodeException.Code.CONTENT_DECOMPRESSION_FAILED, crc);
  }

  @Test
  void requiresStrictUtf8DuplicateFreeCanonicalJson() {
    Preview utf8 = new Preview();
    utf8.content = new byte[] {(byte) 0xc3, 0x28};
    assertCode(ViewDecodeException.Code.CONTENT_JSON_INVALID, utf8);

    Preview duplicate = new Preview();
    duplicate.content =
        "{\"blocks\":[],\"blocks\":[],\"version\":1}".getBytes(StandardCharsets.UTF_8);
    assertCode(ViewDecodeException.Code.CONTENT_JSON_INVALID, duplicate);

    Preview whitespace = new Preview();
    whitespace.content =
        "{\"blocks\": [{\"state\":0,\"x\":4,\"y\":65,\"z\":-2}],\"version\":1}"
            .getBytes(StandardCharsets.UTF_8);
    assertCode(ViewDecodeException.Code.CONTENT_NOT_CANONICAL, whitespace);
  }

  @Test
  void validatesPaletteHashContinuityOrderingAndRegistryState() {
    Preview hash = new Preview();
    hash.paletteHashOverride = "0".repeat(64);
    assertCode(ViewDecodeException.Code.PALETTE_HASH_MISMATCH, hash);

    Preview gap = new Preview();
    gap.palette = "[{\"id\":1,\"blockId\":\"minecraft:stone\",\"properties\":{}}]";
    assertCode(ViewDecodeException.Code.PALETTE_INVALID, gap);

    Preview registry = new Preview();
    registry.palette = "[{\"id\":0,\"blockId\":\"minecraft:dirt\",\"properties\":{}}]";
    assertCode(ViewDecodeException.Code.PALETTE_INVALID, registry);

    Preview ordering = new Preview();
    ordering.palette =
        "[{\"id\":0,\"blockId\":\"minecraft:stone\",\"properties\":{}},"
            + "{\"id\":1,\"blockId\":\"minecraft:andesite\",\"properties\":{}}]";
    assertCode(ViewDecodeException.Code.PALETTE_INVALID, ordering);
  }

  @Test
  void productionResolverRequiresACompleteRegisteredNonAirState() {
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    var resolver = new MinecraftBlockStateResolver();

    resolver.validate("minecraft:stone", Map.of());
    resolver.validate("minecraft:oak_log", Map.of("axis", "y"));
    assertThrows(
        IllegalArgumentException.class, () -> resolver.validate("minecraft:oak_log", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> resolver.validate("minecraft:oak_log", Map.of("axis", "diagonal")));
    assertThrows(
        IllegalArgumentException.class, () -> resolver.validate("minecraft:not_a_block", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> resolver.validate("minecraft:air", Map.of()));
  }

  @Test
  void validatesBoundsThenSortedUniqueBlockContent() {
    Preview bounds = new Preview();
    bounds.bounds = "{\"min\":{\"x\":6,\"y\":65,\"z\":-2},\"max\":{\"x\":5,\"y\":66,\"z\":-1}}";
    assertCode(ViewDecodeException.Code.BOUNDS_INVALID, bounds);

    Preview origin = new Preview();
    origin.bounds = "{\"min\":{\"x\":4,\"y\":65,\"z\":-2},\"max\":{\"x\":4,\"y\":65,\"z\":-2}}";
    assertCode(ViewDecodeException.Code.BOUNDS_INVALID, origin);

    Preview axis = new Preview();
    axis.bounds = "{\"min\":{\"x\":4,\"y\":65,\"z\":-2},\"max\":{\"x\":36,\"y\":65,\"z\":-2}}";
    assertCode(ViewDecodeException.Code.BOUNDS_INVALID, axis);

    Preview volume = new Preview();
    volume.bounds = "{\"min\":{\"x\":4,\"y\":65,\"z\":-2},\"max\":{\"x\":20,\"y\":81,\"z\":14}}";
    assertCode(ViewDecodeException.Code.BOUNDS_INVALID, volume);

    Preview unsorted = new Preview();
    unsorted.blockCount = 2;
    unsorted.content =
        "{\"blocks\":[{\"state\":0,\"x\":5,\"y\":65,\"z\":-2},{\"state\":0,\"x\":4,\"y\":65,\"z\":-2}],\"version\":1}"
            .getBytes(StandardCharsets.UTF_8);
    assertCode(ViewDecodeException.Code.BLOCK_CONTENT_INVALID, unsorted);

    Preview state = new Preview();
    state.content =
        "{\"blocks\":[{\"state\":1,\"x\":4,\"y\":65,\"z\":-2}],\"version\":1}"
            .getBytes(StandardCharsets.UTF_8);
    assertCode(ViewDecodeException.Code.BLOCK_CONTENT_INVALID, state);

    Preview count = new Preview();
    count.blockCount = 2;
    assertCode(ViewDecodeException.Code.BLOCK_CONTENT_INVALID, count);
  }

  @Test
  void validatesContentShapeAndChangeLimit() {
    Preview shape = new Preview();
    shape.content = "{\"blocks\":[],\"extra\":true,\"version\":1}".getBytes(StandardCharsets.UTF_8);
    assertCode(ViewDecodeException.Code.CONTENT_SHAPE_INVALID, shape);

    Preview changes = new Preview();
    changes.difference = "{\"added\":4097,\"replaced\":0,\"removed\":0}";
    assertCode(ViewDecodeException.Code.CHANGE_LIMIT_EXCEEDED, changes);
  }

  @Test
  void acceptsRemovalOnlyTargetWithAnEmptyPalette() throws Exception {
    Preview removal = new Preview();
    removal.content = "{\"blocks\":[],\"version\":1}".getBytes(StandardCharsets.UTF_8);
    removal.palette = "[]";
    removal.blockCount = 0;
    removal.difference = "{\"added\":0,\"replaced\":0,\"removed\":1}";

    BuildPreviewView build =
        assertInstanceOf(BuildPreviewView.class, decoder.decode(removal.envelope()).content());
    assertTrue(build.palette().isEmpty());
    assertTrue(build.blocks().isEmpty());
  }

  private void assertCode(ViewDecodeException.Code code, Preview preview) {
    ViewDecodeException failure =
        assertThrows(ViewDecodeException.class, () -> decoder.decode(preview.envelope()));
    assertEquals(code, failure.code());
  }

  private static byte[] gzip(byte[] value) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(value);
    }
    return output.toByteArray();
  }

  private static byte[] append(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class Preview {
    private byte[] content = CONTENT.getBytes(StandardCharsets.UTF_8);
    private byte[] transfer;
    private String encoding = "identity+base64";
    private String previewId = PREVIEW_ID.toString();
    private int revision = 1;
    private String operation = "create";
    private String baseRegionHash = "\"" + "a".repeat(64) + "\"";
    private String changeSetHash = "\"" + "b".repeat(64) + "\"";
    private String bounds =
        "{\"min\":{\"x\":4,\"y\":65,\"z\":-2},\"max\":{\"x\":5,\"y\":66,\"z\":-1}}";
    private String difference = "{\"added\":1,\"replaced\":0,\"removed\":0}";
    private String palette = PALETTE;
    private String paletteHashOverride;
    private int blockCount = 1;
    private int chunkCount = 2;
    private int[] chunkIndexes = new int[] {1, 0};
    private int declaredChunkLengthDelta;
    private int declaredCompressedDelta;
    private int declaredUncompressedDelta;
    private String chunkHashOverride;
    private String contentHashOverride;
    private java.util.function.UnaryOperator<String> dataTransform = value -> value;

    private static Preview gzip(byte[] transfer) {
      Preview preview = new Preview();
      preview.encoding = "gzip+base64";
      preview.transfer = transfer;
      return preview;
    }

    private byte[] envelope() {
      byte[] transferred = transfer == null ? content : transfer;
      int split = Math.max(1, transferred.length / 2);
      byte[][] pieces =
          new byte[][] {
            Arrays.copyOfRange(transferred, split, transferred.length),
            Arrays.copyOfRange(transferred, 0, split)
          };
      StringBuilder chunks = new StringBuilder("[");
      for (int index = 0; index < pieces.length; index++) {
        if (index > 0) {
          chunks.append(',');
        }
        byte[] piece = pieces[index];
        String encoded = dataTransform.apply(Base64.getEncoder().encodeToString(piece));
        chunks
            .append("{\"index\":")
            .append(chunkIndexes[index])
            .append(",\"byteLength\":")
            .append(piece.length + declaredChunkLengthDelta)
            .append(",\"sha256\":\"")
            .append(chunkHashOverride == null ? sha256(piece) : chunkHashOverride)
            .append("\",\"data\":\"")
            .append(encoded)
            .append("\"}");
      }
      chunks.append(']');
      String paletteHash =
          paletteHashOverride == null ? sha256(canonicalPalette(palette)) : paletteHashOverride;
      String preview =
          "{\"schemaVersion\":\"1.0\",\"previewId\":\""
              + previewId
              + "\",\"projectId\":\"b0000000-0000-4000-8000-000000000002\","
              + "\"revision\":"
              + revision
              + ",\"operation\":\""
              + operation
              + "\",\"dimension\":\"minecraft:overworld\",\"bounds\":"
              + bounds
              + ",\"origin\":{\"x\":5,\"y\":65,\"z\":-2},"
              + "\"transform\":{\"rotation\":90,\"mirror\":\"FRONT_BACK\"},"
              + "\"baseRegionHash\":"
              + baseRegionHash
              + ",\"changeSetHash\":"
              + changeSetHash
              + ",\"contentHash\":\""
              + (contentHashOverride == null ? sha256(content) : contentHashOverride)
              + "\",\"paletteHash\":\""
              + paletteHash
              + "\",\"contentFormat\":\"minecraft-agent.palette-v1\",\"encoding\":\""
              + encoding
              + "\",\"compressedBytes\":"
              + (transferred.length + declaredCompressedDelta)
              + ",\"uncompressedBytes\":"
              + (content.length + declaredUncompressedDelta)
              + ",\"blockCount\":"
              + blockCount
              + ",\"difference\":"
              + difference
              + ",\"palette\":"
              + palette
              + ",\"chunkCount\":"
              + chunkCount
              + ",\"chunks\":"
              + chunks
              + "}";
      String envelope =
          "{\"viewSchemaVersion\":\"1.0\",\"viewId\":\""
              + PREVIEW_ID
              + "\",\"requestId\":\"b0000000-0000-4000-8000-000000000003\","
              + "\"viewType\":\"build_preview\",\"revision\":1,\"title\":\"Build\","
              + "\"fallbackText\":\"Build preview\",\"pinnable\":true,\"content\":"
              + preview
              + "}";
      return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] canonicalPalette(String palette) {
      try {
        return new org.erdtman.jcs.JsonCanonicalizer(palette).getEncodedUTF8();
      } catch (IOException exception) {
        throw new AssertionError(exception);
      }
    }
  }
}
