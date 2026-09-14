package dev.minecraftagent.standalone.core.unpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Scans the instance script directories of a game directory — {@code kubejs/server_scripts/**.js}
 * and {@code scripts/**.zs} — and parses them into a {@link ScriptRecipeData}. This is plain
 * instance-directory IO, not archive IO; directories that do not exist are skipped silently and
 * unreadable or oversize files are counted and skipped, never thrown. Bounds: {@value
 * #MAXIMUM_FILES} files, {@value #MAXIMUM_FILE_BYTES} bytes per file, {@value #MAXIMUM_TOTAL_BYTES}
 * bytes per scan.
 */
public final class InstanceScriptExtractor {
  public static final int MAXIMUM_FILES = 64;
  public static final int MAXIMUM_FILE_BYTES = 256 * 1024;
  public static final int MAXIMUM_TOTAL_BYTES = 2 * 1024 * 1024;
  private static final int MAXIMUM_DEPTH = 16;

  public ScriptRecipeData extract(Path gameDirectory) {
    Objects.requireNonNull(gameDirectory, "gameDirectory");
    var recipes = new ArrayList<UnpackedRecipe>();
    var removals = new ArrayList<ScriptRemoval>();
    var accumulator = new Accumulator();
    var kubeJs = new KubeJsScriptParser();
    var craftTweaker = new CraftTweakerScriptParser();
    scan(
        gameDirectory.resolve("kubejs").resolve("server_scripts"),
        ".js",
        kubeJs::parse,
        recipes,
        removals,
        accumulator);
    scan(
        gameDirectory.resolve("scripts"),
        ".zs",
        craftTweaker::parse,
        recipes,
        removals,
        accumulator);
    return new ScriptRecipeData(
        recipes,
        removals,
        new ScriptRecipeData.Stats(
            accumulator.filesScanned,
            accumulator.filesSkipped,
            accumulator.bytesScanned,
            recipes.size(),
            removals.size(),
            accumulator.skippedUnparsed));
  }

  private interface Parser {
    ScriptParseResult parse(String source, String fileLabel, int fileIndex);
  }

  private static final class Accumulator {
    private int filesScanned;
    private int filesSkipped;
    private long bytesScanned;
    private int skippedUnparsed;
    private int fileIndex;
  }

  private void scan(
      Path directory,
      String extension,
      Parser parser,
      List<UnpackedRecipe> recipes,
      List<ScriptRemoval> removals,
      Accumulator accumulator) {
    if (!Files.isDirectory(directory)) {
      return;
    }
    final List<Path> files;
    try (var walk = Files.walk(directory, MAXIMUM_DEPTH)) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(extension))
              .sorted(Comparator.comparing(Path::toString))
              .toList();
    } catch (IOException | SecurityException failure) {
      return;
    }
    for (var file : files) {
      if (accumulator.filesScanned >= MAXIMUM_FILES
          || accumulator.bytesScanned >= MAXIMUM_TOTAL_BYTES) {
        accumulator.filesSkipped++;
        continue;
      }
      final String source;
      try {
        if (Files.size(file) > MAXIMUM_FILE_BYTES) {
          accumulator.filesSkipped++;
          continue;
        }
        source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      } catch (IOException | SecurityException | OutOfMemoryError failure) {
        accumulator.filesSkipped++;
        continue;
      }
      accumulator.filesScanned++;
      accumulator.bytesScanned += source.length();
      var result = parser.parse(source, file.getFileName().toString(), accumulator.fileIndex++);
      recipes.addAll(result.recipes());
      removals.addAll(result.removals());
      accumulator.skippedUnparsed += result.skippedUnparsed();
    }
  }
}
