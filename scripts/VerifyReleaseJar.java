import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class VerifyReleaseJar {
  private static final int MAXIMUM_ENTRIES = 5_000;
  private static final long MAXIMUM_ENTRY_BYTES = 16L * 1024 * 1024;
  private static final long MAXIMUM_TOTAL_BYTES = 64L * 1024 * 1024;

  private enum Kind {
    PAPER,
    FABRIC,
    EMBEDDED_JCS
  }

  private static final class Budget {
    private long totalBytes;

    void add(long bytes) {
      totalBytes += bytes;
      if (totalBytes > MAXIMUM_TOTAL_BYTES) {
        fail("JAR expansion exceeds the total size limit");
      }
    }
  }

  private VerifyReleaseJar() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      fail("usage: VerifyReleaseJar.java <jar> <Paper|Fabric> <extract-dir> <entries-file>");
    }
    var jar = Path.of(args[0]);
    var kind = switch (args[1]) {
      case "Paper" -> Kind.PAPER;
      case "Fabric" -> Kind.FABRIC;
      default -> throw new IllegalStateException("unknown release JAR kind");
    };
    var extractRoot = Path.of(args[2]);
    var entriesFile = Path.of(args[3]);
    if (!Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) {
      fail("release JAR is missing or unsafe");
    }
    Files.createDirectory(extractRoot);
    var entries = inspect(jar, kind, extractRoot, new Budget(), 0);
    entries.sort(Comparator.naturalOrder());
    Files.write(entriesFile, entries, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  private static ArrayList<String> inspect(
      Path jar, Kind kind, Path extractRoot, Budget budget, int depth) throws IOException {
    if (depth > 1) {
      fail("nested JAR depth exceeds the release limit");
    }
    var entries = new ArrayList<String>();
    var names = new HashSet<String>();
    var nested = new ArrayList<Path>();
    try (var archive = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
      if (archive.getComment() != null && !archive.getComment().isEmpty()) {
        fail("JAR comments are forbidden");
      }
      var enumeration = archive.entries();
      while (enumeration.hasMoreElements()) {
        var entry = enumeration.nextElement();
        var name = entry.getName();
        if (entries.size() >= MAXIMUM_ENTRIES) {
          fail("JAR contains too many entries");
        }
        validateName(name, entry.isDirectory());
        if (!names.add(name)) {
          fail("JAR contains a duplicate entry: " + name);
        }
        if (entry.getComment() != null && !entry.getComment().isEmpty()) {
          fail("JAR entry comments are forbidden: " + name);
        }
        if (!allowed(kind, name, entry.isDirectory())) {
          fail("JAR contains an unexpected entry: " + name);
        }
        entries.add(name);
        var output = extractRoot.resolve(name).normalize();
        if (!output.startsWith(extractRoot)) {
          fail("JAR entry escapes extraction root: " + name);
        }
        if (entry.isDirectory()) {
          Files.createDirectories(output);
          continue;
        }
        if (entry.getSize() > MAXIMUM_ENTRY_BYTES) {
          fail("JAR entry exceeds the size limit: " + name);
        }
        Files.createDirectories(output.getParent());
        try (var input = archive.getInputStream(entry);
            var target =
                Files.newOutputStream(
                    output,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
          copyBounded(input, target, budget, name);
        }
        if (kind == Kind.FABRIC && name.equals("META-INF/jars/java-json-canonicalization-1.1.jar")) {
          nested.add(output);
        }
      }
    }
    if (kind == Kind.FABRIC && nested.size() != 1) {
      fail("Fabric JAR must contain the exact embedded canonicalization JAR");
    }
    for (var nestedJar : nested) {
      var nestedRoot = extractRoot.resolve(".expanded-jcs");
      Files.createDirectory(nestedRoot);
      inspect(nestedJar, Kind.EMBEDDED_JCS, nestedRoot, budget, depth + 1);
    }
    return entries;
  }

  private static void copyBounded(
      InputStream input, java.io.OutputStream output, Budget budget, String name) throws IOException {
    var buffer = new byte[8192];
    long entryBytes = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      entryBytes += read;
      if (entryBytes > MAXIMUM_ENTRY_BYTES) {
        fail("JAR entry exceeds the size limit: " + name);
      }
      budget.add(read);
      output.write(buffer, 0, read);
    }
  }

  private static void validateName(String name, boolean directory) {
    if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
      fail("JAR contains an unsafe entry name");
    }
    for (var index = 0; index < name.length(); index++) {
      var value = name.charAt(index);
      if (value > 0x7f || Character.isISOControl(value)) {
        fail("JAR entry names must be printable ASCII");
      }
    }
    var normalized = Path.of(name).normalize().toString().replace('\\', '/');
    if (directory) {
      normalized += "/";
    }
    if (!normalized.equals(name) || name.contains("//")) {
      fail("JAR contains a non-canonical entry name: " + name);
    }
  }

  private static boolean allowed(Kind kind, String name, boolean directory) {
    if (directory) {
      return allowedDirectory(kind, name);
    }
    return switch (kind) {
      case PAPER ->
          name.equals("META-INF/MANIFEST.MF")
              || name.equals("META-INF/LICENSE")
              || name.equals("config-managed.yml")
              || name.equals("config.yml")
              || name.equals("managed-runtime/config.template.yml")
              || name.equals("paper-plugin.yml")
              || isCanonicalizationMetadata(name)
              || classFile(name, "dev/minecraftagent/paper/")
              || classFile(name, "org/erdtman/jcs/")
              || schema(name);
      case FABRIC ->
          name.equals("META-INF/MANIFEST.MF")
              || name.equals("META-INF/jars/java-json-canonicalization-1.1.jar")
              || name.equals("LICENSE_client-mod")
              || name.equals("assets/minecraftagent/lang/en_us.json")
              || name.equals("fabric.mod.json")
              || classFile(name, "dev/minecraftagent/client/")
              || schema(name);
      case EMBEDDED_JCS ->
          name.equals("META-INF/MANIFEST.MF")
              || name.equals("fabric.mod.json")
              || isCanonicalizationMetadata(name)
              || classFile(name, "org/erdtman/jcs/");
    };
  }

  private static boolean allowedDirectory(Kind kind, String name) {
    var common =
        name.equals("META-INF/")
            || name.equals("protocol/")
            || name.equals("protocol/schemas/")
            || name.equals("protocol/schemas/tools/");
    return switch (kind) {
      case PAPER ->
          common
              || canonicalizationMetadataDirectory(name)
              || name.equals("managed-runtime/")
              || name.equals("dev/")
              || name.equals("dev/minecraftagent/")
              || name.startsWith("dev/minecraftagent/paper/")
              || name.equals("org/")
              || name.equals("org/erdtman/")
              || name.equals("org/erdtman/jcs/");
      case FABRIC ->
          common
              || name.equals("META-INF/jars/")
              || name.equals("assets/")
              || name.equals("assets/minecraftagent/")
              || name.equals("assets/minecraftagent/lang/")
              || name.equals("dev/")
              || name.equals("dev/minecraftagent/")
              || name.startsWith("dev/minecraftagent/client/");
      case EMBEDDED_JCS ->
          name.equals("META-INF/")
              || canonicalizationMetadataDirectory(name)
              || name.equals("org/")
              || name.equals("org/erdtman/")
              || name.equals("org/erdtman/jcs/");
    };
  }

  private static boolean classFile(String name, String prefix) {
    return name.startsWith(prefix) && name.endsWith(".class") && name.length() > prefix.length();
  }

  private static boolean schema(String name) {
    return name.startsWith("protocol/schemas/")
        && name.endsWith(".schema.json")
        && name.length() > "protocol/schemas/".length();
  }

  private static boolean canonicalizationMetadataDirectory(String name) {
    return name.equals("META-INF/maven/")
        || name.equals("META-INF/maven/io.github.erdtman/")
        || name.equals("META-INF/maven/io.github.erdtman/java-json-canonicalization/");
  }

  private static boolean isCanonicalizationMetadata(String name) {
    return name.equals("META-INF/maven/io.github.erdtman/java-json-canonicalization/pom.xml")
        || name.equals("META-INF/maven/io.github.erdtman/java-json-canonicalization/pom.properties");
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
