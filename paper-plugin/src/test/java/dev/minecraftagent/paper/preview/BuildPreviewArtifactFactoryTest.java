package dev.minecraftagent.paper.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.AllowSchemaLoader;
import com.networknt.schema.resource.UriSchemaLoader;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Bounds;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Cell;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Pattern;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Position;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactFactory.Request;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildPreviewArtifactFactoryTest {
  private static final UUID REQUEST = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID PROJECT = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID WORLD = UUID.fromString("30000000-0000-4000-8000-000000000003");

  @Test
  void deterministicallyBuildsCompletePaletteAndHashesFromTheSameSnapshot() throws Exception {
    var bounds = new Bounds(new Position(0, 64, 0), new Position(2, 66, 2));
    var request = request(bounds, Pattern.HOLLOW, "minecraft:oak_log[axis=y,waterlogged=false]");
    var snapshot = cells(bounds, "minecraft:air");
    var factory = new BuildPreviewArtifactFactory();

    var first = factory.create(request, snapshot);
    var second = factory.create(request, snapshot);

    assertEquals(first.changeSetHash(), second.changeSetHash());
    assertEquals(first.baseRegionHash(), second.baseRegionHash());
    assertEquals(first.view().viewId(), second.view().viewId());
    assertEquals(first.view().content(), second.view().content());
    assertEquals(
        first.view().viewId().toString(), first.view().content().get("previewId").getAsString());
    assertEquals(26, first.toolResult().get("targetBlockCount").getAsInt());
    assertEquals(26, first.toolResult().get("changeCount").getAsInt());
    var palette = first.view().content().getAsJsonArray("palette");
    assertEquals(1, palette.size());
    assertEquals(0, palette.get(0).getAsJsonObject().get("id").getAsInt());
    assertEquals(
        "minecraft:oak_log", palette.get(0).getAsJsonObject().get("blockId").getAsString());
    assertEquals(
        "y",
        palette.get(0).getAsJsonObject().getAsJsonObject("properties").get("axis").getAsString());
    assertSchema("build-preview.schema.json", first.view().content().toString());
    assertSchema("tools/build-preview-create-result.schema.json", first.toolResult().toString());
  }

  @Test
  void representsPureRemovalAsAnEmptyTargetWithoutLosingTheBaseSnapshot() throws Exception {
    var bounds = new Bounds(new Position(0, 64, 0), new Position(1, 64, 1));
    var clear = request(bounds, Pattern.CLEAR, null);
    var factory = new BuildPreviewArtifactFactory();

    var artifact = factory.create(clear, cells(bounds, "minecraft:stone"));

    assertEquals(0, artifact.toolResult().get("targetBlockCount").getAsInt());
    assertEquals(4, artifact.toolResult().getAsJsonObject("difference").get("removed").getAsInt());
    assertTrue(artifact.view().content().getAsJsonArray("palette").isEmpty());
    assertEquals(0, artifact.view().content().get("blockCount").getAsInt());
    assertSchema("build-preview.schema.json", artifact.view().content().toString());
  }

  @Test
  void aRegionStateChangeInvalidatesBothFrozenHashes() {
    var bounds = new Bounds(new Position(0, 64, 0), new Position(0, 64, 0));
    var request = request(bounds, Pattern.SOLID, "minecraft:stone");
    var factory = new BuildPreviewArtifactFactory();

    var air = factory.create(request, cells(bounds, "minecraft:air"));
    var dirt = factory.create(request, cells(bounds, "minecraft:dirt"));

    assertNotEquals(air.baseRegionHash(), dirt.baseRegionHash());
    assertNotEquals(air.changeSetHash(), dirt.changeSetHash());
  }

  private static Request request(Bounds bounds, Pattern pattern, String state) {
    return new Request(
        REQUEST,
        "test-server",
        WORLD,
        "minecraft:overworld",
        PROJECT,
        1,
        "create",
        bounds,
        bounds.min(),
        pattern,
        state,
        0,
        "NONE");
  }

  private static List<Cell> cells(Bounds bounds, String state) {
    return bounds.positions().stream().map(position -> new Cell(position, state)).toList();
  }

  private static void assertSchema(String schemaName, String json) throws Exception {
    var root =
        Path.of(System.getProperty("minecraftAgent.protocolDir", "../protocol"))
            .toAbsolutePath()
            .normalize()
            .resolve("schemas");
    var localSchemaPrefix = root.toUri().toString();
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
    var schema =
        factory.getSchema(
            new ObjectMapper().readTree(root.resolve(schemaName).toFile()),
            SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    var errors = schema.validate(new ObjectMapper().readTree(json));
    assertTrue(errors.isEmpty(), errors.toString());
  }
}
