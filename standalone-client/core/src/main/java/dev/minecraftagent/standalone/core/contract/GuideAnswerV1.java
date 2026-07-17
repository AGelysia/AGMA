package dev.minecraftagent.standalone.core.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GuideAnswerV1(
    ResourceRef target,
    TargetResolution targetResolution,
    List<MaterialTotal> materials,
    List<ProcessRecord> processes,
    List<ClaimBinding> bossAndDrops,
    List<ClaimBinding> preparation,
    List<UnknownItem> unknowns,
    List<EvidenceClaim> evidence,
    SnapshotContext snapshot) {
  public static final String SCHEMA_VERSION = "guide-answer-v1";

  public GuideAnswerV1 {
    Objects.requireNonNull(targetResolution, "targetResolution");
    materials = ContractChecks.list(materials, "materials", 2048);
    processes = ContractChecks.list(processes, "processes", 2048);
    bossAndDrops = ContractChecks.list(bossAndDrops, "bossAndDrops", 256);
    preparation = ContractChecks.list(preparation, "preparation", 256);
    unknowns = ContractChecks.list(unknowns, "unknowns", 512);
    evidence = ContractChecks.list(evidence, "evidence", 256);
    Objects.requireNonNull(snapshot, "snapshot");

    var unresolvedTarget =
        targetResolution == TargetResolution.AMBIGUOUS
            || targetResolution == TargetResolution.NOT_FOUND;
    if (unresolvedTarget) {
      if (target != null || !materials.isEmpty() || !processes.isEmpty()) {
        throw new IllegalArgumentException("an unresolved target cannot have a fabricated plan");
      }
    } else {
      Objects.requireNonNull(target, "target");
    }

    var claimIds =
        evidence.stream().map(EvidenceClaim::claimId).collect(java.util.stream.Collectors.toSet());
    if (claimIds.size() != evidence.size()) {
      throw new IllegalArgumentException("evidence claim ids must be unique");
    }
    var generationId = snapshot.generationId();
    if (target != null) {
      requireGeneration(target, generationId);
    }
    materials.forEach(material -> requireGeneration(material.resource(), generationId));
    if (processes.stream()
        .anyMatch(process -> !generationId.equals(process.source().generationId()))) {
      throw new IllegalArgumentException("guide processes must use the answer snapshot generation");
    }
    var referenced =
        java.util.stream.Stream.concat(bossAndDrops.stream(), preparation.stream())
            .flatMap(binding -> binding.claimIds().stream())
            .toList();
    if (!claimIds.containsAll(referenced)) {
      throw new IllegalArgumentException("claim bindings must reference supplied evidence");
    }
  }

  public enum TargetResolution {
    EXACT,
    PLAYER_SELECTED,
    AMBIGUOUS,
    NOT_FOUND
  }

  public enum UnknownDisposition {
    UNVERIFIED,
    CLIENT_NOT_VISIBLE,
    UNSUPPORTED
  }

  public record MaterialTotal(ResourceRef resource, Basis basis) {
    public MaterialTotal {
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(basis, "basis");
    }
  }

  public enum Basis {
    LOCAL_CONFIRMED,
    LOCAL_INFERRED
  }

  public record ClaimBinding(String statement, List<String> claimIds) {
    public ClaimBinding {
      statement = ContractChecks.text(statement, "statement", 2048);
      claimIds =
          ContractChecks.nonEmptyList(claimIds, "claimIds", 16).stream()
              .map(value -> ContractChecks.symbolicId(value, "claimId"))
              .toList();
      if (new java.util.HashSet<>(claimIds).size() != claimIds.size()) {
        throw new IllegalArgumentException("claimIds must be unique");
      }
    }
  }

  public record UnknownItem(UnknownDisposition disposition, String detail) {
    public UnknownItem {
      Objects.requireNonNull(disposition, "disposition");
      detail = ContractChecks.text(detail, "detail", 2048);
    }
  }

  public record SnapshotContext(
      String generationId, String packFingerprint, Instant queriedAt, Visibility visibility) {
    public SnapshotContext {
      generationId = ContractChecks.opaqueId(generationId, "generationId");
      packFingerprint = ContractChecks.sha256(packFingerprint, "packFingerprint");
      Objects.requireNonNull(queriedAt, "queriedAt");
      Objects.requireNonNull(visibility, "visibility");
    }
  }

  public enum Visibility {
    SINGLEPLAYER_AUTHORITATIVE,
    CLIENT_VISIBLE_PARTIAL,
    NO_WORLD
  }

  private static void requireGeneration(ResourceRef resource, String generationId) {
    if (!generationId.equals(resource.source().generationId())) {
      throw new IllegalArgumentException("guide resources must use the answer snapshot generation");
    }
  }
}
