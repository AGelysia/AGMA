package dev.minecraftagent.paper.landmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Immutable, permission-agnostic landmark data loaded before the Paper runtime is enabled. */
public final class LandmarkCatalog {
  private static final int MAX_RESULTS = 16;
  private final List<IndexedLandmark> landmarks;

  public LandmarkCatalog(List<Landmark> landmarks) {
    Objects.requireNonNull(landmarks);
    this.landmarks =
        landmarks.stream()
            .map(IndexedLandmark::new)
            .sorted(Comparator.comparing(row -> row.landmark().id()))
            .toList();
  }

  public static LandmarkCatalog empty() {
    return new LandmarkCatalog(List.of());
  }

  public int size() {
    return landmarks.size();
  }

  public JsonObject search(
      String query,
      String currentDimension,
      double currentX,
      double currentY,
      double currentZ,
      Predicate<String> permissionCheck) {
    Objects.requireNonNull(query);
    Objects.requireNonNull(currentDimension);
    Objects.requireNonNull(permissionCheck);
    var terms = normalizedTerms(query);
    var visible = new ArrayList<SearchResult>();
    for (var indexed : landmarks) {
      var landmark = indexed.landmark();
      if (landmark.permission() != null && !permissionCheck.test(landmark.permission())) {
        continue;
      }
      if (!indexed.matches(terms)) {
        continue;
      }
      var sameDimension = landmark.dimension().equals(currentDimension);
      var distanceSquared =
          sameDimension
              ? squared(landmark.x() - currentX)
                  + squared(landmark.y() - currentY)
                  + squared(landmark.z() - currentZ)
              : Double.POSITIVE_INFINITY;
      visible.add(new SearchResult(landmark, sameDimension, distanceSquared));
    }
    visible.sort(
        Comparator.comparing(SearchResult::sameDimension)
            .reversed()
            .thenComparingDouble(SearchResult::distanceSquared)
            .thenComparing(result -> result.landmark().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(result -> result.landmark().id()));

    var result = new JsonObject();
    result.addProperty("query", query);
    result.addProperty("totalMatches", visible.size());
    result.addProperty("truncated", visible.size() > MAX_RESULTS);
    var rows = new JsonArray();
    visible.stream().limit(MAX_RESULTS).map(LandmarkCatalog::toJson).forEach(rows::add);
    result.add("landmarks", rows);
    return result;
  }

  private static JsonObject toJson(SearchResult result) {
    var landmark = result.landmark();
    var json = new JsonObject();
    json.addProperty("id", landmark.id());
    json.addProperty("name", landmark.name());
    json.add("aliases", strings(landmark.aliases()));
    json.add("tags", strings(landmark.tags()));
    json.addProperty("dimension", landmark.dimension());
    json.addProperty("x", landmark.x());
    json.addProperty("y", landmark.y());
    json.addProperty("z", landmark.z());
    if (result.sameDimension()) {
      json.addProperty("distance", Math.sqrt(result.distanceSquared()));
    } else {
      json.add("distance", JsonNull.INSTANCE);
    }
    return json;
  }

  private static JsonArray strings(List<String> values) {
    var result = new JsonArray();
    values.forEach(result::add);
    return result;
  }

  private static List<String> normalizedTerms(String query) {
    return List.of(normalize(query).split(" ")).stream().filter(term -> !term.isBlank()).toList();
  }

  static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }

  private static double squared(double value) {
    return value * value;
  }

  private record IndexedLandmark(Landmark landmark, String searchable) {
    private IndexedLandmark(Landmark landmark) {
      this(
          landmark,
          normalize(
              String.join(
                  " ",
                  landmark.id(),
                  landmark.name(),
                  String.join(" ", landmark.aliases()),
                  String.join(" ", landmark.tags()))));
    }

    private boolean matches(List<String> terms) {
      return terms.stream().allMatch(searchable::contains);
    }
  }

  private record SearchResult(Landmark landmark, boolean sameDimension, double distanceSquared) {}
}
