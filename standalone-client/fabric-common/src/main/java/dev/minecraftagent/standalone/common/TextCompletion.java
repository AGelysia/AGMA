package dev.minecraftagent.standalone.common;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validated C1 text completion or a stable recoverable error. */
public record TextCompletion(
    UUID requestId,
    UUID sessionId,
    Status status,
    String text,
    long costMicroUsd,
    CostKind costKind,
    List<Source> sources,
    String errorCode,
    String errorMessage,
    boolean retryable) {
  public static final int MAXIMUM_TEXT_LENGTH = 8192;
  public static final long MAXIMUM_COST_MICRO_USD = 9_007_199_254_740_991L;
  private static final Pattern CLAIM_ID = Pattern.compile("^claim\\.[0-9a-f]{24}$");
  private static final Pattern MOD_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

  public TextCompletion {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(status, "status");
    if (status == Status.COMPLETED) {
      text = Objects.requireNonNull(text, "text");
      if (text.isEmpty()
          || text.codePointCount(0, text.length()) > MAXIMUM_TEXT_LENGTH
          || costMicroUsd < 0
          || costMicroUsd > MAXIMUM_COST_MICRO_USD
          || costKind == null
          || sources == null
          || sources.size() > 5
          || sources.stream().anyMatch(Objects::isNull)
          || sources.stream()
                  .map(Source::claimId)
                  .collect(java.util.stream.Collectors.toSet())
                  .size()
              != sources.size()
          || errorCode != null
          || errorMessage != null
          || retryable) {
        throw new IllegalArgumentException("Completed response is invalid");
      }
      sources = List.copyOf(sources);
    } else {
      if (text != null
          || costMicroUsd != 0
          || costKind != null
          || sources == null
          || !sources.isEmpty()
          || errorCode == null
          || !errorCode.matches("[A-Z][A-Z0-9_]{2,63}")
          || errorMessage == null
          || errorMessage.isEmpty()
          || errorMessage.codePointCount(0, errorMessage.length()) > 512) {
        throw new IllegalArgumentException("Non-completed response is invalid");
      }
    }
  }

  public TextCompletion(UUID requestId, Status status, String text, String errorCode) {
    this(
        requestId,
        null,
        status,
        text,
        0,
        status == Status.COMPLETED ? CostKind.ESTIMATED : null,
        List.of(),
        errorCode,
        status == Status.COMPLETED ? null : "Request failed",
        false);
  }

  public TextCompletion(
      UUID requestId,
      UUID sessionId,
      Status status,
      String text,
      String errorCode,
      String errorMessage,
      boolean retryable) {
    this(
        requestId,
        sessionId,
        status,
        text,
        0,
        status == Status.COMPLETED ? CostKind.ESTIMATED : null,
        List.of(),
        errorCode,
        errorMessage,
        retryable);
  }

  static TextCompletion completed(
      UUID requestId,
      UUID sessionId,
      String text,
      long costMicroUsd,
      CostKind costKind,
      List<Source> sources) {
    return new TextCompletion(
        requestId,
        sessionId,
        Status.COMPLETED,
        text,
        costMicroUsd,
        costKind,
        sources,
        null,
        null,
        false);
  }

  static TextCompletion failed(
      UUID requestId, Status status, String errorCode, String errorMessage, boolean retryable) {
    return new TextCompletion(
        requestId, null, status, null, 0, null, List.of(), errorCode, errorMessage, retryable);
  }

  public record Source(
      String claimId,
      String title,
      URI url,
      String publisher,
      Instant retrievedAt,
      Applicability applicability,
      List<String> warnings) {
    public Source {
      if (claimId == null || !CLAIM_ID.matcher(claimId).matches()) {
        throw new IllegalArgumentException("Completion source claim id is invalid");
      }
      title = bounded(title, 256, "Completion source title");
      Objects.requireNonNull(url, "url");
      if (!"https".equalsIgnoreCase(url.getScheme())
          || url.getHost() == null
          || url.toASCIIString().length() > 2048) {
        throw new IllegalArgumentException("Completion source URL is invalid");
      }
      publisher = bounded(publisher, 256, "Completion source publisher");
      Objects.requireNonNull(retrievedAt, "retrievedAt");
      Objects.requireNonNull(applicability, "applicability");
      Objects.requireNonNull(warnings, "warnings");
      if (warnings.size() > 4 || warnings.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("Completion source warnings are invalid");
      }
      warnings = warnings.stream().map(value -> bounded(value, 256, "Source warning")).toList();
    }
  }

  public record Applicability(
      String minecraftVersion,
      Map<String, String> modVersions,
      String modpackVersion,
      Match match) {
    public Applicability {
      minecraftVersion = bounded(minecraftVersion, 128, "Minecraft version");
      Objects.requireNonNull(modVersions, "modVersions");
      if (modVersions.size() > 64
          || modVersions.entrySet().stream()
              .anyMatch(
                  entry ->
                      entry.getKey() == null
                          || !MOD_ID.matcher(entry.getKey()).matches()
                          || entry.getValue() == null)) {
        throw new IllegalArgumentException("Source mod versions are invalid");
      }
      var checked = new java.util.TreeMap<String, String>();
      modVersions.forEach(
          (key, value) -> checked.put(key, bounded(value, 128, "Source mod version")));
      modVersions = Map.copyOf(checked);
      if (modpackVersion != null) {
        modpackVersion = bounded(modpackVersion, 128, "Modpack version");
      }
      Objects.requireNonNull(match, "match");
    }
  }

  public enum Match {
    MATCH("match"),
    MISMATCH("mismatch"),
    UNKNOWN("unknown");

    private final String wireValue;

    Match(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    static Match fromWire(String value) {
      return switch (value) {
        case "match" -> MATCH;
        case "mismatch" -> MISMATCH;
        case "unknown" -> UNKNOWN;
        default -> throw JsonFields.invalid("/payload/sources/applicability/match");
      };
    }
  }

  private static String bounded(String value, int maximum, String field) {
    Objects.requireNonNull(value, field);
    if (value.isEmpty()
        || value.codePointCount(0, value.length()) > maximum
        || value.codePoints().anyMatch(character -> Character.isISOControl(character))) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  public enum Status {
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    FAILED
  }

  public enum CostKind {
    REPORTED("reported"),
    ESTIMATED("estimated"),
    MIXED("mixed");

    private final String wireValue;

    CostKind(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    static CostKind fromWire(String value) {
      return switch (value) {
        case "reported" -> REPORTED;
        case "estimated" -> ESTIMATED;
        case "mixed" -> MIXED;
        default -> throw JsonFields.invalid("/payload/costKind");
      };
    }
  }
}
