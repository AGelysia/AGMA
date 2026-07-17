package dev.minecraftagent.standalone.supervisor.install;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict loader for the reviewed Node distribution manifest used only at build/package time. */
public final class NodeDistributionCatalog {
  private static final Set<String> ROOT_FIELDS =
      Set.of("schemaVersion", "nodeVersion", "checksumSource", "distributions");
  private static final Set<String> ENTRY_FIELDS =
      Set.of("platform", "archive", "archiveType", "rootDirectory", "url", "sha256");
  private static final Pattern VERSION =
      Pattern.compile("^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)$");
  private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  private final String nodeVersion;
  private final URI checksumSource;
  private final Map<RuntimePlatform, NodeDistribution> distributions;

  private NodeDistributionCatalog(
      String nodeVersion,
      URI checksumSource,
      Map<RuntimePlatform, NodeDistribution> distributions) {
    this.nodeVersion = nodeVersion;
    this.checksumSource = checksumSource;
    this.distributions = Map.copyOf(distributions);
  }

  public static NodeDistributionCatalog load(InputStream input) throws IOException {
    if (input == null) {
      throw invalid();
    }
    try (input;
        var reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      reader.setLenient(false);
      var catalog = readRoot(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw invalid();
      }
      return catalog;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  public String nodeVersion() {
    return nodeVersion;
  }

  public URI checksumSource() {
    return checksumSource;
  }

  public NodeDistribution distribution(RuntimePlatform platform) {
    var value = distributions.get(platform);
    if (value == null) {
      throw new IllegalArgumentException("UNSUPPORTED_RUNTIME_PLATFORM");
    }
    return value;
  }

  public Set<RuntimePlatform> platforms() {
    return distributions.keySet();
  }

  private static NodeDistributionCatalog readRoot(JsonReader reader) throws IOException {
    require(reader.peek() == JsonToken.BEGIN_OBJECT);
    reader.beginObject();
    var seen = new HashSet<String>();
    Integer schemaVersion = null;
    String nodeVersion = null;
    URI checksumSource = null;
    Map<RuntimePlatform, ParsedDistribution> parsedDistributions = null;
    while (reader.hasNext()) {
      var name = reader.nextName();
      require(ROOT_FIELDS.contains(name) && seen.add(name));
      switch (name) {
        case "schemaVersion" -> schemaVersion = integer(reader);
        case "nodeVersion" -> nodeVersion = version(reader);
        case "checksumSource" ->
            checksumSource = officialUri(string(reader, 256), "SHASUMS256.txt");
        case "distributions" -> parsedDistributions = distributions(reader);
        default -> throw invalid();
      }
    }
    reader.endObject();
    require(seen.equals(ROOT_FIELDS));
    require(Integer.valueOf(1).equals(schemaVersion));
    require(nodeVersion != null && checksumSource != null && parsedDistributions != null);
    require(parsedDistributions.keySet().equals(Set.of(RuntimePlatform.values())));
    var expectedChecksum = "/dist/v" + nodeVersion + "/SHASUMS256.txt";
    require(checksumSource.getPath().equals(expectedChecksum));
    var distributions = new EnumMap<RuntimePlatform, NodeDistribution>(RuntimePlatform.class);
    for (var entry : parsedDistributions.entrySet()) {
      var value = entry.getValue();
      require(value.url().getPath().equals("/dist/v" + nodeVersion + "/" + value.archive()));
      distributions.put(
          entry.getKey(),
          new NodeDistribution(
              entry.getKey(),
              nodeVersion,
              value.archive(),
              value.archiveType(),
              value.rootDirectory(),
              value.url(),
              value.sha256()));
    }
    return new NodeDistributionCatalog(nodeVersion, checksumSource, distributions);
  }

  private static Map<RuntimePlatform, ParsedDistribution> distributions(JsonReader reader)
      throws IOException {
    require(reader.peek() == JsonToken.BEGIN_ARRAY);
    reader.beginArray();
    var result = new EnumMap<RuntimePlatform, ParsedDistribution>(RuntimePlatform.class);
    while (reader.hasNext()) {
      require(result.size() < RuntimePlatform.values().length);
      var value = distribution(reader);
      require(result.putIfAbsent(value.platform(), value) == null);
    }
    reader.endArray();
    return result;
  }

  private static ParsedDistribution distribution(JsonReader reader) throws IOException {
    require(reader.peek() == JsonToken.BEGIN_OBJECT);
    reader.beginObject();
    var seen = new HashSet<String>();
    RuntimePlatform platform = null;
    String archive = null;
    NodeDistribution.ArchiveType archiveType = null;
    String rootDirectory = null;
    URI url = null;
    String sha256 = null;
    while (reader.hasNext()) {
      var name = reader.nextName();
      require(ENTRY_FIELDS.contains(name) && seen.add(name));
      switch (name) {
        case "platform" -> platform = RuntimePlatform.fromId(string(reader, 32));
        case "archive" -> archive = fileName(reader);
        case "archiveType" -> archiveType = NodeDistribution.ArchiveType.fromId(string(reader, 16));
        case "rootDirectory" -> rootDirectory = fileName(reader);
        case "url" -> url = officialUri(string(reader, 256), null);
        case "sha256" -> {
          sha256 = string(reader, 64);
          require(SHA256.matcher(sha256).matches());
        }
        default -> throw invalid();
      }
    }
    reader.endObject();
    require(seen.equals(ENTRY_FIELDS));
    require(platform != null && archive != null && archiveType != null);
    require(rootDirectory != null && url != null && sha256 != null);
    require(archive.endsWith("." + archiveType.id()));
    return new ParsedDistribution(platform, archive, archiveType, rootDirectory, url, sha256);
  }

  private static URI officialUri(String value, String requiredSuffix) throws IOException {
    var uri = URI.create(value);
    require(
        "https".equals(uri.getScheme())
            && "nodejs.org".equals(uri.getHost())
            && uri.getPort() == -1
            && uri.getRawUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && (requiredSuffix == null || uri.getPath().endsWith("/" + requiredSuffix)));
    return uri;
  }

  private static String version(JsonReader reader) throws IOException {
    var value = string(reader, 64);
    require(VERSION.matcher(value).matches());
    return value;
  }

  private static String fileName(JsonReader reader) throws IOException {
    var value = string(reader, 128);
    require(FILE_NAME.matcher(value).matches() && !value.contains(".."));
    return value;
  }

  private static String string(JsonReader reader, int maximum) throws IOException {
    require(reader.peek() == JsonToken.STRING);
    var value = reader.nextString();
    require(!value.isEmpty() && value.length() <= maximum);
    require(value.codePoints().noneMatch(Character::isISOControl));
    return value;
  }

  private static int integer(JsonReader reader) throws IOException {
    require(reader.peek() == JsonToken.NUMBER);
    var value = reader.nextString();
    require(value.equals("1"));
    return 1;
  }

  private static void require(boolean condition) throws IOException {
    if (!condition) {
      throw invalid();
    }
  }

  private static IOException invalid() {
    return new IOException("NODE_DISTRIBUTION_MANIFEST_INVALID");
  }

  private record ParsedDistribution(
      RuntimePlatform platform,
      String archive,
      NodeDistribution.ArchiveType archiveType,
      String rootDirectory,
      URI url,
      String sha256) {}
}
