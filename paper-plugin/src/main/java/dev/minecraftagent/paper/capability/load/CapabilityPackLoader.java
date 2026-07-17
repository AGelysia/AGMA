package dev.minecraftagent.paper.capability.load;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import dev.minecraftagent.paper.capability.argument.CompiledCommandTemplate;
import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic.Code;
import dev.minecraftagent.paper.capability.model.CapabilityDraft;
import dev.minecraftagent.paper.capability.model.CapabilityIdentity;
import dev.minecraftagent.paper.capability.model.CapabilityManifest;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ExecutionSource;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ManifestStatus;
import dev.minecraftagent.paper.capability.model.CapabilityManifest.ReversibilityType;
import dev.minecraftagent.paper.capability.model.EffectiveCapability;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Discovers and validates a complete capability root without publishing partial state. */
public final class CapabilityPackLoader {
  private static final Set<PosixFilePermission> UNSAFE_WRITE_PERMISSIONS =
      Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

  private final CapabilityLoadLimits limits;
  private final InstalledPluginInventory pluginInventory;
  private final CapabilityApprovalStore approvalStore;
  private final CapabilityManifestParser parser = new CapabilityManifestParser();
  private final CapabilityCanonicalizer canonicalizer = new CapabilityCanonicalizer();

  public CapabilityPackLoader(
      CapabilityLoadLimits limits,
      InstalledPluginInventory pluginInventory,
      CapabilityApprovalStore approvalStore) {
    this.limits = Objects.requireNonNull(limits);
    this.pluginInventory = Objects.requireNonNull(pluginInventory);
    this.approvalStore = Objects.requireNonNull(approvalStore);
  }

  public CapabilityPackLoader(
      InstalledPluginInventory pluginInventory, CapabilityApprovalStore approvalStore) {
    this(CapabilityLoadLimits.defaults(), pluginInventory, approvalStore);
  }

  public CapabilityLoadResult load(Path requestedRoot) {
    Objects.requireNonNull(requestedRoot);
    var discovery = new Discovery(requestedRoot).scan();
    if (!discovery.complete()) {
      return new CapabilityLoadResult(
          false, Map.of(), discovery.drafts(), diagnostics(discovery.globalDiagnostics()));
    }

    var candidates = new ArrayList<Candidate>();
    var drafts = new ArrayList<>(discovery.drafts());
    var globalDiagnostics = new ArrayList<CapabilityDiagnostic>();
    var complete = true;
    for (var file : discovery.files()) {
      var parsed = parse(file);
      if (parsed.draft() != null) {
        drafts.add(parsed.draft());
      } else {
        candidates.add(parsed.candidate());
      }
      if (parsed.fatal()) {
        complete = false;
        globalDiagnostics.add(parsed.draft().diagnostics().getFirst());
      }
    }
    if (!complete) {
      candidates.forEach(candidate -> drafts.add(candidate.toDraft()));
      return new CapabilityLoadResult(
          false, Map.of(), sortedDrafts(drafts), diagnostics(globalDiagnostics));
    }

    rejectDuplicateIds(candidates);
    final Map<String, InstalledPluginInventory.InstalledPlugin> installed;
    try {
      installed = installedPlugins();
    } catch (InventoryFailure failure) {
      candidates.forEach(
          candidate ->
              candidate.add(new CapabilityDiagnostic(failure.code(), "requirements.plugins")));
      candidates.forEach(candidate -> drafts.add(candidate.toDraft()));
      return new CapabilityLoadResult(
          false,
          Map.of(),
          sortedDrafts(drafts),
          List.of(new CapabilityDiagnostic(failure.code(), "pluginInventory")));
    }

    for (var candidate : candidates) {
      evaluateCompatibility(candidate, installed);
      if (candidate.diagnostics.isEmpty()) {
        try {
          if (!approvalStore.isApproved(CapabilityApproval.from(candidate.identity))) {
            candidate.add(CapabilityDiagnostic.of(Code.APPROVAL_REQUIRED));
          }
        } catch (RuntimeException exception) {
          complete = false;
          candidate.add(new CapabilityDiagnostic(Code.APPROVAL_SOURCE_UNAVAILABLE, "approval"));
          globalDiagnostics.add(
              new CapabilityDiagnostic(Code.APPROVAL_SOURCE_UNAVAILABLE, "approvalStore"));
        }
      }
    }

    validateReversalTargets(candidates);
    candidates.forEach(candidate -> drafts.add(candidate.toDraft()));
    if (complete) {
      var verification = new Discovery(requestedRoot).scan();
      if (!verification.complete() || !discovery.fingerprint().equals(verification.fingerprint())) {
        complete = false;
        globalDiagnostics.add(new CapabilityDiagnostic(Code.ROOT_CHANGED, "root"));
      }
    }
    if (!complete) {
      return new CapabilityLoadResult(
          false, Map.of(), sortedDrafts(drafts), diagnostics(globalDiagnostics));
    }

    var effective = new TreeMap<String, EffectiveCapability>();
    for (var candidate : candidates) {
      if (candidate.diagnostics.isEmpty()) {
        effective.put(
            candidate.manifest.id(),
            new EffectiveCapability(candidate.identity, candidate.manifest));
      }
    }
    return new CapabilityLoadResult(
        true, effective, sortedDrafts(drafts), diagnostics(globalDiagnostics));
  }

  private ParsedFile parse(DiscoveredFile file) {
    final byte[] bytes;
    try {
      bytes = readStable(file);
    } catch (IOException | SecurityException exception) {
      return ParsedFile.draft(file.source(), Code.IO_UNAVAILABLE, true);
    }

    final String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      return ParsedFile.draft(file.source(), Code.UTF8_INVALID, false);
    }

    final Object loaded;
    try {
      var yaml = yaml();
      var nodes = yaml.composeAll(new StringReader(source)).iterator();
      if (!nodes.hasNext()
          || aliasCount(nodes.next()) > limits.maximumAliases()
          || nodes.hasNext()) {
        return ParsedFile.draft(file.source(), Code.YAML_INVALID, false);
      }
      var documents = yaml.loadAll(source).iterator();
      if (!documents.hasNext()) {
        return ParsedFile.draft(file.source(), Code.YAML_INVALID, false);
      }
      loaded = documents.next();
      if (documents.hasNext()) {
        return ParsedFile.draft(file.source(), Code.YAML_INVALID, false);
      }
    } catch (RuntimeException exception) {
      return ParsedFile.draft(file.source(), Code.YAML_INVALID, false);
    }

    final CapabilityManifest manifest;
    try {
      manifest = parser.parse(loaded);
      CompiledCommandTemplate.compile(
          manifest.execution().commandRoot(),
          manifest.execution().template(),
          canonicalizer.argumentDescriptors(manifest));
    } catch (CapabilityManifestParser.ManifestException exception) {
      return new ParsedFile(
          null,
          new CapabilityDraft(
              file.source(), Optional.empty(), Optional.empty(), List.of(exception.diagnostic())),
          false);
    } catch (IllegalArgumentException exception) {
      return ParsedFile.draft(file.source(), Code.TEMPLATE_INVALID, false);
    }

    final CapabilityIdentity identity;
    try {
      identity =
          new CapabilityIdentity(manifest.id(), manifest.version(), canonicalizer.sha256(manifest));
    } catch (IOException | RuntimeException exception) {
      return new ParsedFile(
          null,
          new CapabilityDraft(
              file.source(),
              Optional.of(manifest),
              Optional.empty(),
              List.of(CapabilityDiagnostic.of(Code.CONTENT_HASH_UNAVAILABLE))),
          true);
    }
    return new ParsedFile(new Candidate(file.source(), manifest, identity), null, false);
  }

  private byte[] readStable(DiscoveredFile file) throws IOException {
    var before = attributes(file.path());
    if (!sameFile(file, before) || hardLinkCount(file.path()) != 1) {
      throw new IOException("Capability file changed before read");
    }
    final byte[] bytes;
    try (var input = Files.newInputStream(file.path(), READ, NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(limits.maximumFileBytes() + 1);
    }
    if (bytes.length > limits.maximumFileBytes() || bytes.length != file.size()) {
      throw new IOException("Capability file changed during read");
    }
    var after = attributes(file.path());
    if (!sameFile(file, after) || hardLinkCount(file.path()) != 1) {
      throw new IOException("Capability file changed after read");
    }
    return bytes;
  }

  private boolean sameFile(DiscoveredFile expected, PosixFileAttributes actual) {
    return actual.isRegularFile()
        && !actual.isSymbolicLink()
        && actual.size() == expected.size()
        && Objects.equals(actual.fileKey(), expected.fileKey())
        && actual.owner().equals(expected.owner())
        && privateEnough(actual.permissions());
  }

  private Yaml yaml() {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(limits.maximumAliases());
    options.setNestingDepthLimit(limits.maximumYamlDepth());
    options.setCodePointLimit(limits.maximumFileBytes());
    return new Yaml(new ExactNumberSafeConstructor(options));
  }

  private int aliasCount(Node node) {
    if (node instanceof AnchorNode || node.getAnchor() != null) {
      return 1;
    }
    var count = 0;
    if (node instanceof MappingNode mapping) {
      for (var entry : mapping.getValue()) {
        count = boundedAliasSum(count, aliasCount(entry.getKeyNode()));
        count = boundedAliasSum(count, aliasCount(entry.getValueNode()));
      }
    } else if (node instanceof SequenceNode sequence) {
      for (var child : sequence.getValue()) {
        count = boundedAliasSum(count, aliasCount(child));
      }
    }
    return count;
  }

  private int boundedAliasSum(int left, int right) {
    var sum = (long) left + right;
    return sum > limits.maximumAliases() ? limits.maximumAliases() + 1 : (int) sum;
  }

  private Map<String, InstalledPluginInventory.InstalledPlugin> installedPlugins()
      throws InventoryFailure {
    final Collection<InstalledPluginInventory.InstalledPlugin> snapshot;
    try {
      snapshot = List.copyOf(Objects.requireNonNull(pluginInventory.snapshot()));
    } catch (RuntimeException exception) {
      throw new InventoryFailure(Code.PLUGIN_INVENTORY_UNAVAILABLE);
    }
    var result = new TreeMap<String, InstalledPluginInventory.InstalledPlugin>();
    for (var plugin : snapshot) {
      if (plugin == null
          || !plugin.name().matches("[A-Za-z0-9_.-]{1,64}")
          || plugin.version().isEmpty()
          || plugin.version().length() > 128) {
        throw new InventoryFailure(Code.PLUGIN_INVENTORY_UNAVAILABLE);
      }
      var normalized = plugin.name().toLowerCase(Locale.ROOT);
      if (result.putIfAbsent(normalized, plugin) != null) {
        throw new InventoryFailure(Code.PLUGIN_INVENTORY_AMBIGUOUS);
      }
    }
    return Map.copyOf(result);
  }

  private void rejectDuplicateIds(List<Candidate> candidates) {
    var byId = new HashMap<String, List<Candidate>>();
    candidates.forEach(
        candidate ->
            byId.computeIfAbsent(candidate.manifest.id(), ignored -> new ArrayList<>())
                .add(candidate));
    byId.values().stream()
        .filter(matches -> matches.size() > 1)
        .flatMap(Collection::stream)
        .forEach(candidate -> candidate.add(CapabilityDiagnostic.of(Code.DUPLICATE_ID)));
  }

  private void evaluateCompatibility(
      Candidate candidate, Map<String, InstalledPluginInventory.InstalledPlugin> installed) {
    if (!candidate.diagnostics.isEmpty()) {
      return;
    }
    if (candidate.manifest.status().orElse(null) == ManifestStatus.EXAMPLE) {
      candidate.add(CapabilityDiagnostic.of(Code.EXAMPLE_ONLY));
      return;
    }
    if (candidate.manifest.status().orElse(null) == ManifestStatus.DRAFT) {
      candidate.add(CapabilityDiagnostic.of(Code.DRAFT_ONLY));
      return;
    }
    if (candidate.manifest.execution().source() == ExecutionSource.CONSOLE) {
      candidate.add(new CapabilityDiagnostic(Code.CONSOLE_SOURCE_DISABLED, "execution.source"));
      return;
    }
    for (var requirement : candidate.manifest.pluginRequirements()) {
      var field = "requirements.plugins";
      var plugin = installed.get(requirement.name().toLowerCase(Locale.ROOT));
      if (plugin == null) {
        candidate.add(new CapabilityDiagnostic(Code.PLUGIN_MISSING, field));
        continue;
      }
      if (!plugin.enabled()) {
        candidate.add(new CapabilityDiagnostic(Code.PLUGIN_UNAVAILABLE, field));
        continue;
      }
      if (!DeterministicVersionRange.validInstalledVersion(plugin.version())) {
        candidate.add(new CapabilityDiagnostic(Code.PLUGIN_VERSION_INVALID, field));
        continue;
      }
      var range = DeterministicVersionRange.parse(requirement.versionRange());
      if (!range.includes(plugin.version())) {
        candidate.add(new CapabilityDiagnostic(Code.PLUGIN_VERSION_MISMATCH, field));
      }
    }
  }

  private void validateReversalTargets(List<Candidate> candidates) {
    var groups = new HashMap<String, List<Candidate>>();
    candidates.forEach(
        candidate ->
            groups
                .computeIfAbsent(candidate.manifest.id(), ignored -> new ArrayList<>())
                .add(candidate));
    var unique = new HashMap<String, Candidate>();
    groups.forEach(
        (id, group) -> {
          if (group.size() == 1) {
            unique.put(id, group.getFirst());
          }
        });

    for (var candidate : candidates) {
      if (!candidate.diagnostics.isEmpty()
          || candidate.manifest.reversibility().type() != ReversibilityType.CAPABILITY) {
        continue;
      }
      var targetId = candidate.manifest.reversibility().capabilityId().orElseThrow();
      var target = unique.get(targetId);
      if (target == null) {
        candidate.add(
            new CapabilityDiagnostic(
                groups.containsKey(targetId)
                    ? Code.REVERSAL_TARGET_UNAVAILABLE
                    : Code.REVERSAL_TARGET_MISSING,
                "reversibility.capability"));
      } else if (!compatibleReversal(candidate.manifest, target.manifest)) {
        candidate.add(
            new CapabilityDiagnostic(
                Code.REVERSAL_TARGET_INCOMPATIBLE, "reversibility.capability"));
      }
    }

    propagateUnavailable(candidates, unique);
    markReversalCycles(candidates, unique);
    propagateUnavailable(candidates, unique);
  }

  private void propagateUnavailable(List<Candidate> candidates, Map<String, Candidate> unique) {
    boolean changed;
    do {
      changed = false;
      for (var candidate : candidates) {
        if (!candidate.diagnostics.isEmpty()
            || candidate.manifest.reversibility().type() != ReversibilityType.CAPABILITY) {
          continue;
        }
        var targetId = candidate.manifest.reversibility().capabilityId().orElseThrow();
        var target = unique.get(targetId);
        if (target != null && !target.diagnostics.isEmpty()) {
          changed |=
              candidate.add(
                  new CapabilityDiagnostic(
                      Code.REVERSAL_TARGET_UNAVAILABLE, "reversibility.capability"));
        }
      }
    } while (changed);
  }

  private void markReversalCycles(List<Candidate> candidates, Map<String, Candidate> unique) {
    for (var start : candidates) {
      if (!start.diagnostics.isEmpty()) {
        continue;
      }
      var path = new ArrayList<Candidate>();
      var positions = new HashMap<String, Integer>();
      var current = start;
      while (current.manifest.reversibility().type() == ReversibilityType.CAPABILITY) {
        var prior = positions.putIfAbsent(current.manifest.id(), path.size());
        if (prior != null) {
          for (var index = prior; index < path.size(); index++) {
            path.get(index)
                .add(new CapabilityDiagnostic(Code.REVERSAL_CYCLE, "reversibility.capability"));
          }
          break;
        }
        path.add(current);
        current = unique.get(current.manifest.reversibility().capabilityId().orElseThrow());
        if (current == null || !current.diagnostics.isEmpty()) {
          break;
        }
      }
    }
  }

  private boolean compatibleReversal(CapabilityManifest source, CapabilityManifest target) {
    return source.execution().source() == target.execution().source()
        && source.effects().category() == target.effects().category()
        && source.effects().scope().equals(target.effects().scope())
        && normalizedRequirements(source).equals(normalizedRequirements(target));
  }

  private Map<String, String> normalizedRequirements(CapabilityManifest manifest) {
    var result = new TreeMap<String, String>();
    manifest
        .pluginRequirements()
        .forEach(
            requirement ->
                result.put(
                    requirement.name().toLowerCase(Locale.ROOT), requirement.versionRange()));
    return result;
  }

  private static PosixFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
  }

  private static long hardLinkCount(Path path) throws IOException {
    var value = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
    if (!(value instanceof Number number)) {
      throw new IOException("Hard-link count unavailable");
    }
    return number.longValue();
  }

  private static boolean privateEnough(Set<PosixFilePermission> permissions) {
    return UNSAFE_WRITE_PERMISSIONS.stream().noneMatch(permissions::contains);
  }

  private static List<CapabilityDiagnostic> diagnostics(List<CapabilityDiagnostic> values) {
    return values.stream()
        .sorted(
            Comparator.comparing((CapabilityDiagnostic value) -> value.code().ordinal())
                .thenComparing(CapabilityDiagnostic::field))
        .distinct()
        .toList();
  }

  private static List<CapabilityDraft> sortedDrafts(List<CapabilityDraft> values) {
    return values.stream().sorted(Comparator.comparing(CapabilityDraft::source)).toList();
  }

  private final class Discovery {
    private final Path requestedRoot;
    private final List<DiscoveredFile> files = new ArrayList<>();
    private final List<CapabilityDraft> drafts = new ArrayList<>();
    private final List<CapabilityDiagnostic> globalDiagnostics = new ArrayList<>();
    private final List<EntryFingerprint> fingerprint = new ArrayList<>();
    private Path root;
    private UserPrincipal owner;
    private int entries;
    private int manifestFiles;
    private long totalBytes;
    private boolean complete = true;

    Discovery(Path requestedRoot) {
      this.requestedRoot = requestedRoot;
    }

    DiscoveryResult scan() {
      try {
        var requested = requestedRoot.toAbsolutePath().normalize();
        var rootAttributes = attributes(requested);
        if (rootAttributes.isSymbolicLink()
            || !rootAttributes.isDirectory()
            || rootAttributes.fileKey() == null
            || !privateEnough(rootAttributes.permissions())) {
          fatal(Code.ROOT_UNSAFE, "root");
          return result();
        }
        var resolved = requested.toRealPath(NOFOLLOW_LINKS).normalize();
        var verifiedRoot = attributes(resolved);
        if (verifiedRoot.isSymbolicLink()
            || !verifiedRoot.isDirectory()
            || verifiedRoot.fileKey() == null
            || !Objects.equals(rootAttributes.fileKey(), verifiedRoot.fileKey())
            || !rootAttributes.owner().equals(verifiedRoot.owner())
            || !privateEnough(verifiedRoot.permissions())) {
          fatal(Code.ROOT_UNSAFE, "root");
          return result();
        }
        root = resolved;
        owner = verifiedRoot.owner();
        fingerprint.add(fingerprint(".", verifiedRoot, root));
        scanDirectory(root, 0, verifiedRoot.fileKey());
      } catch (java.nio.file.NoSuchFileException exception) {
        fatal(Code.ROOT_MISSING, "root");
      } catch (IOException | SecurityException | UnsupportedOperationException exception) {
        fatal(Code.ROOT_UNSAFE, "root");
      }
      files.sort(Comparator.comparing(DiscoveredFile::source));
      return result();
    }

    private void scanDirectory(Path directory, int depth, Object expectedFileKey) {
      if (!complete) {
        return;
      }
      try {
        var before = attributes(directory);
        if (!before.isDirectory()
            || before.isSymbolicLink()
            || !Objects.equals(before.fileKey(), expectedFileKey)
            || !before.owner().equals(owner)
            || !privateEnough(before.permissions())) {
          fatal(Code.ROOT_CHANGED, "root");
          return;
        }
      } catch (IOException | SecurityException | UnsupportedOperationException exception) {
        fatal(Code.IO_UNAVAILABLE, "root");
        return;
      }
      var children = new ArrayList<Path>();
      try (var stream = Files.newDirectoryStream(directory)) {
        for (var child : stream) {
          if (++entries > limits.maximumEntries()) {
            fatal(Code.ENTRY_LIMIT_EXCEEDED, "limits.entries");
            return;
          }
          children.add(child);
        }
      } catch (IOException | DirectoryIteratorException | SecurityException exception) {
        fatal(Code.IO_UNAVAILABLE, "root");
        return;
      }
      children.sort(Comparator.comparing(path -> path.getFileName().toString()));
      for (var child : children) {
        if (!complete) {
          return;
        }
        inspect(child, depth + 1);
      }
      try {
        var after = attributes(directory);
        if (!after.isDirectory()
            || after.isSymbolicLink()
            || !Objects.equals(after.fileKey(), expectedFileKey)
            || !after.owner().equals(owner)
            || !privateEnough(after.permissions())) {
          fatal(Code.ROOT_CHANGED, "root");
        }
      } catch (IOException | SecurityException | UnsupportedOperationException exception) {
        fatal(Code.IO_UNAVAILABLE, "root");
      }
    }

    private void inspect(Path child, int depth) {
      var normalized = child.toAbsolutePath().normalize();
      var sourceName = sourceName(normalized);
      var source = sourceName.display();
      if (!normalized.startsWith(root)) {
        drafts.add(disabled(source, Code.PATH_UNSAFE));
        return;
      }
      final PosixFileAttributes value;
      try {
        value = attributes(normalized);
      } catch (IOException | SecurityException | UnsupportedOperationException exception) {
        fatal(Code.IO_UNAVAILABLE, "root");
        return;
      }
      fingerprint.add(fingerprint(source, value, normalized));
      if (!sourceName.safe()) {
        if (value.isDirectory()) {
          fatal(Code.PATH_UNSAFE, "root");
        } else if (manifestFile(normalized)) {
          drafts.add(disabled(source, Code.PATH_UNSAFE));
        }
        return;
      }
      if (value.isSymbolicLink()) {
        drafts.add(disabled(source, Code.PATH_UNSAFE));
        return;
      }
      if (!value.owner().equals(owner) || !privateEnough(value.permissions())) {
        if (value.isDirectory()) {
          fatal(Code.ROOT_UNSAFE, "root");
        } else {
          drafts.add(disabled(source, Code.FILE_NOT_PRIVATE));
        }
        return;
      }
      if (value.isDirectory()) {
        if (depth > limits.maximumDirectoryDepth()) {
          fatal(Code.DIRECTORY_DEPTH_EXCEEDED, "limits.depth");
          return;
        }
        scanDirectory(normalized, depth, value.fileKey());
        return;
      }
      if (!manifestFile(normalized)) {
        return;
      }
      if (++manifestFiles > limits.maximumFiles()) {
        fatal(Code.FILE_COUNT_EXCEEDED, "limits.files");
        return;
      }
      if (!value.isRegularFile() || value.fileKey() == null) {
        drafts.add(disabled(source, Code.FILE_NOT_REGULAR));
        return;
      }
      totalBytes += value.size();
      if (totalBytes < 0 || totalBytes > limits.maximumTotalBytes()) {
        fatal(Code.TOTAL_BYTES_EXCEEDED, "limits.totalBytes");
        return;
      }
      if (value.size() > limits.maximumFileBytes()) {
        drafts.add(disabled(source, Code.FILE_TOO_LARGE));
        return;
      }
      try {
        if (hardLinkCount(normalized) != 1) {
          drafts.add(disabled(source, Code.FILE_HARD_LINKED));
          return;
        }
      } catch (IOException | SecurityException exception) {
        drafts.add(disabled(source, Code.PATH_UNSAFE));
        return;
      }
      files.add(
          new DiscoveredFile(normalized, source, value.fileKey(), value.size(), value.owner()));
    }

    private boolean manifestFile(Path path) {
      var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
      return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private SourceName sourceName(Path path) {
      var components = new ArrayList<String>();
      var safe = true;
      for (var component : root.relativize(path)) {
        var value = component.toString();
        if (!value.matches("[A-Za-z0-9._-]{1,128}") || value.equals(".") || value.equals("..")) {
          safe = false;
        }
        components.add(encodedComponent(value));
      }
      return new SourceName(components.isEmpty() ? "." : String.join("/", components), safe);
    }

    private String encodedComponent(String value) {
      var result = new StringBuilder();
      for (var octet : value.getBytes(StandardCharsets.UTF_8)) {
        var unsigned = Byte.toUnsignedInt(octet);
        if ((unsigned >= 'a' && unsigned <= 'z')
            || (unsigned >= 'A' && unsigned <= 'Z')
            || (unsigned >= '0' && unsigned <= '9')
            || unsigned == '.'
            || unsigned == '_'
            || unsigned == '-') {
          result.append((char) unsigned);
        } else {
          result.append('%');
          result.append("0123456789ABCDEF".charAt(unsigned >>> 4));
          result.append("0123456789ABCDEF".charAt(unsigned & 0x0f));
        }
      }
      return result.toString();
    }

    private void fatal(Code code, String field) {
      complete = false;
      globalDiagnostics.add(new CapabilityDiagnostic(code, field));
    }

    private DiscoveryResult result() {
      return new DiscoveryResult(
          complete,
          List.copyOf(files),
          sortedDrafts(drafts),
          diagnostics(globalDiagnostics),
          fingerprint.stream().sorted(Comparator.comparing(EntryFingerprint::source)).toList());
    }
  }

  private static EntryFingerprint fingerprint(
      String source, PosixFileAttributes attributes, Path path) {
    var type =
        attributes.isSymbolicLink()
            ? "symlink"
            : attributes.isDirectory()
                ? "directory"
                : attributes.isRegularFile() ? "file" : "other";
    return new EntryFingerprint(
        source,
        type,
        String.valueOf(attributes.fileKey()),
        attributes.size(),
        attributes.owner().getName(),
        Set.copyOf(attributes.permissions()),
        linkCountOrUnavailable(path),
        attributes.lastModifiedTime(),
        changeTimeOrUnavailable(path));
  }

  private static long linkCountOrUnavailable(Path path) {
    try {
      return hardLinkCount(path);
    } catch (IOException | SecurityException exception) {
      return -1;
    }
  }

  private static FileTime changeTimeOrUnavailable(Path path) {
    try {
      var value = Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS);
      return value instanceof FileTime time ? time : FileTime.fromMillis(-1);
    } catch (IOException | SecurityException | UnsupportedOperationException exception) {
      return FileTime.fromMillis(-1);
    }
  }

  private static CapabilityDraft disabled(String source, Code code) {
    return new CapabilityDraft(
        source, Optional.empty(), Optional.empty(), List.of(CapabilityDiagnostic.of(code)));
  }

  private record DiscoveredFile(
      Path path, String source, Object fileKey, long size, UserPrincipal owner) {}

  private record DiscoveryResult(
      boolean complete,
      List<DiscoveredFile> files,
      List<CapabilityDraft> drafts,
      List<CapabilityDiagnostic> globalDiagnostics,
      List<EntryFingerprint> fingerprint) {}

  private record SourceName(String display, boolean safe) {}

  private record EntryFingerprint(
      String source,
      String type,
      String fileKey,
      long size,
      String owner,
      Set<PosixFilePermission> permissions,
      long linkCount,
      FileTime modifiedTime,
      FileTime changeTime) {}

  private record ParsedFile(Candidate candidate, CapabilityDraft draft, boolean fatal) {
    static ParsedFile draft(String source, Code code, boolean fatal) {
      return new ParsedFile(null, disabled(source, code), fatal);
    }
  }

  private static final class Candidate {
    private final String source;
    private final CapabilityManifest manifest;
    private final CapabilityIdentity identity;
    private final List<CapabilityDiagnostic> diagnostics = new ArrayList<>();

    Candidate(String source, CapabilityManifest manifest, CapabilityIdentity identity) {
      this.source = source;
      this.manifest = manifest;
      this.identity = identity;
    }

    boolean add(CapabilityDiagnostic diagnostic) {
      if (diagnostics.contains(diagnostic)) {
        return false;
      }
      diagnostics.add(diagnostic);
      return true;
    }

    CapabilityDraft toDraft() {
      return new CapabilityDraft(source, Optional.of(manifest), Optional.of(identity), diagnostics);
    }
  }

  private static final class InventoryFailure extends Exception {
    private final Code code;

    InventoryFailure(Code code) {
      this.code = code;
    }

    Code code() {
      return code;
    }
  }

  /** SafeConstructor variant that preserves decimal lexemes until the JCS round-trip check. */
  private static final class ExactNumberSafeConstructor extends SafeConstructor {
    ExactNumberSafeConstructor(LoaderOptions options) {
      super(options);
      yamlConstructors.put(
          Tag.FLOAT,
          new AbstractConstruct() {
            @Override
            public Object construct(Node node) {
              if (!(node instanceof ScalarNode scalar)) {
                throw new YAMLException("Non-scalar YAML number");
              }
              var value = constructScalar(scalar);
              if (!value.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) {
                throw new YAMLException("Unsupported YAML number form");
              }
              try {
                return new java.math.BigDecimal(value);
              } catch (NumberFormatException exception) {
                throw new YAMLException("Invalid YAML number", exception);
              }
            }
          });
    }
  }
}
