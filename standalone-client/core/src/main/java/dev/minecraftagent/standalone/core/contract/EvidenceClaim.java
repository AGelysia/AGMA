package dev.minecraftagent.standalone.core.contract;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvidenceClaim(
    String claimId,
    String statement,
    URI sourceUrl,
    String sourceTitle,
    String publisher,
    Instant retrievedAt,
    String evidenceSpanSha256,
    Applicability applicability,
    BigDecimal sourceQuality,
    List<String> conflicts,
    List<String> warnings) {
  public EvidenceClaim {
    claimId = ContractChecks.symbolicId(claimId, "claimId");
    statement = ContractChecks.text(statement, "statement", 2048);
    sourceUrl = ContractChecks.httpsUri(sourceUrl, "sourceUrl");
    sourceTitle = ContractChecks.text(sourceTitle, "sourceTitle", 512);
    publisher = ContractChecks.text(publisher, "publisher", 256);
    Objects.requireNonNull(retrievedAt, "retrievedAt");
    evidenceSpanSha256 = ContractChecks.sha256(evidenceSpanSha256, "evidenceSpanSha256");
    Objects.requireNonNull(applicability, "applicability");
    sourceQuality = ContractChecks.probability(sourceQuality, "sourceQuality");
    conflicts = boundedText(conflicts, "conflicts");
    warnings = boundedText(warnings, "warnings");
  }

  private static List<String> boundedText(List<String> values, String field) {
    return ContractChecks.list(values, field, 32).stream()
        .map(value -> ContractChecks.text(value, field, 1024))
        .toList();
  }

  public record Applicability(
      String minecraftVersion,
      Map<String, String> modVersions,
      String modpackVersion,
      Match match) {
    public Applicability {
      minecraftVersion = ContractChecks.text(minecraftVersion, "minecraftVersion", 64);
      modVersions = ContractChecks.map(modVersions, "modVersions", 64);
      for (var entry : modVersions.entrySet()) {
        ContractChecks.symbolicId(entry.getKey(), "modVersions key");
        ContractChecks.text(entry.getValue(), "modVersions value", 128);
      }
      modpackVersion = ContractChecks.optionalText(modpackVersion, "modpackVersion", 128);
      Objects.requireNonNull(match, "match");
    }
  }

  public enum Match {
    MATCH,
    MISMATCH,
    UNKNOWN
  }
}
