package dev.minecraftagent.standalone.core.unpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Flattens the advancement files found in scanned mod archives into bounded markdown knowledge
 * documents, one document per mod. Only advancements with a {@code display} object are listed;
 * hidden ones stay and are marked {@code [hidden]}. Titles and descriptions resolve their JSON text
 * components through the mod's own lang maps (zh_cn preferred, en_us fallback, the raw key last).
 * Entries group under their root advancement (the displayed ancestor without a parent);
 * advancements whose displayed ancestry cannot be determined land in a trailing ungrouped section.
 * Budget overruns are counted in the result and skipped, never thrown.
 */
public final class AdvancementTreeExtractor {
  public static final int MAXIMUM_ADVANCEMENTS_PER_MOD = 256;
  public static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
  public static final int MAXIMUM_TOTAL_BYTES = 1024 * 1024;
  private static final String PREFERRED_LOCALE = "zh_cn";

  public record Result(List<KnowledgeDocument> documents, int modsSeen, int modsSkippedBudget) {
    public Result {
      documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }
  }

  public Result extract(UnpackedModpack modpack) {
    Objects.requireNonNull(modpack, "modpack");
    var documents = new ArrayList<KnowledgeDocument>();
    var seen = 0;
    var skippedBudget = 0;
    var totalBytes = 0;
    for (var mod : modpack.mods()) {
      var document = modDocument(mod);
      if (document == null) {
        continue;
      }
      seen++;
      var bytes = document.markdown().getBytes(StandardCharsets.UTF_8).length;
      if (totalBytes + bytes > MAXIMUM_TOTAL_BYTES) {
        skippedBudget++;
        continue;
      }
      totalBytes += bytes;
      documents.add(document);
    }
    return new Result(documents, seen, skippedBudget);
  }

  private static KnowledgeDocument modDocument(UnpackedMod mod) {
    var nodes = new LinkedHashMap<String, Node>();
    for (var advancement : mod.advancements()) {
      if (nodes.size() >= MAXIMUM_ADVANCEMENTS_PER_MOD) {
        break;
      }
      var node = node(mod, advancement);
      if (node != null) {
        nodes.put(node.id(), node);
      }
    }
    if (nodes.isEmpty()) {
      return null;
    }
    var groups = new LinkedHashMap<String, List<Node>>();
    var ungrouped = new ArrayList<Node>();
    for (var node : nodes.values()) {
      var root = rootOf(node, nodes);
      if (root == null) {
        ungrouped.add(node);
      } else {
        groups.computeIfAbsent(root.id(), id -> new ArrayList<>()).add(node);
      }
    }
    var roots = new ArrayList<>(groups.keySet());
    roots.sort(String::compareTo);

    var modName = KnowledgeText.sanitized(mod.modName(), 128);
    var title = (modName.isEmpty() ? mod.modId() : modName) + " Advancements";
    var markdown = new StringBuilder();
    var bytes = "# ".length() + title.getBytes(StandardCharsets.UTF_8).length + 2;
    markdown.append("# ").append(title).append("\n\n");
    for (var rootId : roots) {
      var root = nodes.get(rootId);
      var header = "## " + root.title() + " (" + root.id() + ")\n\n";
      var lines = new StringBuilder();
      groups.get(rootId).stream()
          .sorted(Comparator.comparing(Node::id))
          .forEach(node -> lines.append(line(node)).append('\n'));
      lines.append('\n');
      var chunk = header + lines;
      var chunkBytes = chunk.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + chunkBytes > MAXIMUM_DOCUMENT_BYTES) {
        break;
      }
      markdown.append(chunk);
      bytes += chunkBytes;
    }
    if (!ungrouped.isEmpty()) {
      var chunk = new StringBuilder("## Ungrouped\n\n");
      ungrouped.stream()
          .sorted(Comparator.comparing(Node::id))
          .forEach(node -> chunk.append(line(node)).append('\n'));
      var chunkBytes = chunk.toString().getBytes(StandardCharsets.UTF_8).length;
      if (bytes + chunkBytes <= MAXIMUM_DOCUMENT_BYTES) {
        markdown.append(chunk);
      }
    }
    return new KnowledgeDocument(
        KnowledgeDocument.fileName("modadv", mod.modId()), title, markdown.toString());
  }

  private record Node(String id, String parent, boolean hidden, String title, String description) {}

  private static Node node(UnpackedMod mod, UnpackedAdvancement advancement) {
    if (!(advancement.json().get("display") instanceof Map<?, ?> display)) {
      return null;
    }
    var title =
        KnowledgeText.sanitized(
            ModLangText.resolveComponent(mod.lang(), PREFERRED_LOCALE, display.get("title")), 200);
    if (title.isEmpty()) {
      title = advancement.id();
    }
    var description =
        KnowledgeText.sanitized(
            ModLangText.resolveComponent(mod.lang(), PREFERRED_LOCALE, display.get("description")),
            400);
    var parent =
        advancement.json().get("parent") instanceof String value && !value.isBlank()
            ? value.strip()
            : null;
    return new Node(
        advancement.id(), parent, Boolean.TRUE.equals(display.get("hidden")), title, description);
  }

  /** Walks the parent chain to the displayed root; {@code null} when no displayed root exists. */
  private static Node rootOf(Node node, Map<String, Node> nodes) {
    var current = node;
    var visited = new java.util.HashSet<String>();
    while (current.parent() != null && visited.add(current.id())) {
      var parent = nodes.get(current.parent());
      if (parent == null) {
        return null;
      }
      current = parent;
    }
    return current.parent() == null ? current : null;
  }

  private static String line(Node node) {
    var line = new StringBuilder("- ");
    if (node.hidden()) {
      line.append("[hidden] ");
    }
    line.append(node.title());
    if (!node.description().isEmpty()) {
      line.append(" — ").append(node.description());
    }
    line.append(" (id: ").append(node.id());
    if (node.parent() != null) {
      line.append(", parent: ").append(node.parent());
    }
    return line.append(')').toString();
  }
}
