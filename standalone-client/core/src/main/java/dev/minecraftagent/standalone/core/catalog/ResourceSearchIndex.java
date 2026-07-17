package dev.minecraftagent.standalone.core.catalog;

import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ResourceSearchIndex {
  private static final int MAXIMUM_QUERY_CHARACTERS = 256;
  private static final int MAXIMUM_RESULTS = 50;
  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

  private final CatalogSnapshot snapshot;
  private final List<Document> documents;

  public ResourceSearchIndex(CatalogSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.documents = snapshot.resources().stream().map(Document::new).toList();
  }

  public SearchResult search(String generationId, String query, int requestedMaximum) {
    snapshot.requireCurrentGeneration(generationId);
    Objects.requireNonNull(query, "query");
    if (query.isBlank() || query.length() > MAXIMUM_QUERY_CHARACTERS) {
      throw new IllegalArgumentException("query must contain bounded non-blank text");
    }
    if (requestedMaximum < 1 || requestedMaximum > MAXIMUM_RESULTS) {
      throw new IllegalArgumentException("requestedMaximum is out of range");
    }

    var normalized = normalize(query);
    var exact =
        documents.stream()
            .filter(document -> document.resource().id().equals(query.toLowerCase(Locale.ROOT)))
            .map(
                document ->
                    new Candidate(document.resource(), 10_000, Set.of(MatchReason.EXACT_ID)))
            .sorted(Comparator.comparing(candidate -> ResourceKey.from(candidate.resource())))
            .toList();
    if (!exact.isEmpty()) {
      return new SearchResult(
          snapshot.generationId(),
          query,
          exact.size() == 1 ? Resolution.EXACT : Resolution.AMBIGUOUS,
          exact.subList(0, Math.min(requestedMaximum, exact.size())));
    }

    var candidates = new ArrayList<Candidate>();
    for (var document : documents) {
      var candidate = score(document, normalized);
      if (candidate != null) {
        candidates.add(candidate);
      }
    }
    candidates.sort(
        Comparator.comparingInt(Candidate::score)
            .reversed()
            .thenComparing(candidate -> ResourceKey.from(candidate.resource())));
    if (candidates.size() > requestedMaximum) {
      candidates.subList(requestedMaximum, candidates.size()).clear();
    }
    return new SearchResult(
        snapshot.generationId(),
        query,
        candidates.isEmpty() ? Resolution.NOT_FOUND : Resolution.AMBIGUOUS,
        candidates);
  }

  public ResourceRef select(String generationId, ResourceKey selected) {
    snapshot.requireCurrentGeneration(generationId);
    return snapshot
        .resource(selected)
        .orElseThrow(() -> new IllegalArgumentException("selected resource is unavailable"));
  }

  private static Candidate score(Document document, String query) {
    var reasons = new HashSet<MatchReason>();
    var score = 0;
    if (document.normalizedName().equals(query)) {
      score = 9_000;
      reasons.add(MatchReason.EXACT_NAME);
    } else if (document.normalizedName().startsWith(query)) {
      score = 7_500;
      reasons.add(MatchReason.NAME_PREFIX);
    } else if (document.nameTokens().contains(query)) {
      score = 7_000;
      reasons.add(MatchReason.NAME_TOKEN);
    } else if (document.normalizedName().contains(query)) {
      score = 6_000;
      reasons.add(MatchReason.NAME_CONTAINS);
    } else if (document.normalizedId().contains(query)) {
      score = 5_500;
      reasons.add(MatchReason.ID_CONTAINS);
    } else if (document.modTokens().contains(query)) {
      score = 4_000;
      reasons.add(MatchReason.MOD_TOKEN);
    } else if (near(document.normalizedName(), query)) {
      score = 3_000 - Math.abs(document.normalizedName().length() - query.length()) * 100;
      reasons.add(MatchReason.FUZZY_NAME);
    }
    return score == 0 ? null : new Candidate(document.resource(), score, reasons);
  }

  private static boolean near(String value, String query) {
    if (query.length() < 3 || Math.abs(value.length() - query.length()) > 2) {
      return false;
    }
    var previous = new int[query.length() + 1];
    var current = new int[query.length() + 1];
    for (var column = 0; column <= query.length(); column++) {
      previous[column] = column;
    }
    for (var row = 1; row <= value.length(); row++) {
      current[0] = row;
      var rowMinimum = current[0];
      for (var column = 1; column <= query.length(); column++) {
        var substitution = value.charAt(row - 1) == query.charAt(column - 1) ? 0 : 1;
        current[column] =
            Math.min(
                Math.min(current[column - 1] + 1, previous[column] + 1),
                previous[column - 1] + substitution);
        rowMinimum = Math.min(rowMinimum, current[column]);
      }
      if (rowMinimum > 2) {
        return false;
      }
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[query.length()] <= 2;
  }

  static String normalize(String value) {
    var compatibility = Normalizer.normalize(value, Normalizer.Form.NFKD);
    return COMBINING_MARKS
        .matcher(compatibility)
        .replaceAll("")
        .toLowerCase(Locale.ROOT)
        .trim()
        .replaceAll("\\s+", " ");
  }

  private static Set<String> tokens(String value) {
    var result = new HashSet<String>();
    for (var token : TOKEN_SEPARATOR.split(normalize(value))) {
      if (!token.isBlank()) {
        result.add(token);
      }
    }
    return Set.copyOf(result);
  }

  public enum Resolution {
    EXACT,
    AMBIGUOUS,
    NOT_FOUND
  }

  public enum MatchReason {
    EXACT_ID,
    EXACT_NAME,
    NAME_PREFIX,
    NAME_TOKEN,
    NAME_CONTAINS,
    ID_CONTAINS,
    MOD_TOKEN,
    FUZZY_NAME
  }

  public record Candidate(ResourceRef resource, int score, Set<MatchReason> reasons) {
    public Candidate {
      Objects.requireNonNull(resource, "resource");
      if (score < 1 || score > 10_000) {
        throw new IllegalArgumentException("candidate score is out of range");
      }
      reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
      if (reasons.isEmpty()) {
        throw new IllegalArgumentException("candidate requires a match reason");
      }
    }
  }

  public record SearchResult(
      String generationId, String query, Resolution resolution, List<Candidate> candidates) {
    public SearchResult {
      Objects.requireNonNull(generationId, "generationId");
      Objects.requireNonNull(query, "query");
      Objects.requireNonNull(resolution, "resolution");
      candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
      if ((resolution == Resolution.NOT_FOUND) != candidates.isEmpty()
          || (resolution == Resolution.EXACT && candidates.size() != 1)) {
        throw new IllegalArgumentException("search resolution and candidates disagree");
      }
    }

    public boolean requiresPlayerSelection() {
      return resolution == Resolution.AMBIGUOUS;
    }
  }

  private record Document(
      ResourceRef resource,
      String normalizedId,
      String normalizedName,
      Set<String> nameTokens,
      Set<String> modTokens) {
    private Document(ResourceRef resource) {
      this(
          resource,
          normalize(resource.id()),
          normalize(resource.displayName()),
          tokens(resource.displayName()),
          tokens(resource.modId() + " " + resource.modName()));
    }
  }
}
