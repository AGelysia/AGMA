package dev.minecraftagent.paper.capability.registry;

import dev.minecraftagent.paper.capability.load.CapabilityLoadResult;
import dev.minecraftagent.paper.capability.model.CapabilityDraft;
import dev.minecraftagent.paper.capability.model.EffectiveCapability;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Lock-free immutable registry with preview/CAS publication and proposal-only unknown lookup. */
public final class CapabilityRegistry {
  private static final Pattern CAPABILITY_ID =
      Pattern.compile("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");

  private final Object registryToken = new Object();
  private final AtomicReference<CapabilityRegistrySnapshot> current =
      new AtomicReference<>(new CapabilityRegistrySnapshot(0, Map.of(), List.of()));

  public CapabilityRegistrySnapshot snapshot() {
    return current.get();
  }

  public CapabilityRegistryPreview preview(CapabilityLoadResult loadResult) {
    Objects.requireNonNull(loadResult);
    var base = current.get();
    var generation = base.generation() == Long.MAX_VALUE ? Long.MAX_VALUE : base.generation() + 1;
    var proposed =
        new CapabilityRegistrySnapshot(
            generation, loadResult.effectiveCapabilities(), loadResult.drafts());
    return new CapabilityRegistryPreview(
        registryToken,
        base,
        proposed,
        diff(base.effectiveCapabilities(), proposed.effectiveCapabilities()),
        loadResult.complete() && base.generation() != Long.MAX_VALUE,
        loadResult.globalDiagnostics());
  }

  public PublishResult publish(CapabilityRegistryPreview preview) {
    Objects.requireNonNull(preview);
    if (preview.registryToken() != registryToken || !preview.publishable()) {
      return new PublishResult(PublishStatus.REJECTED, current.get());
    }
    if (!current.compareAndSet(preview.base(), preview.proposed())) {
      return new PublishResult(PublishStatus.STALE, current.get());
    }
    return new PublishResult(PublishStatus.PUBLISHED, preview.proposed());
  }

  public Lookup lookup(String id) {
    Objects.requireNonNull(id);
    var snapshot = current.get();
    if (id.length() > 128 || !CAPABILITY_ID.matcher(id).matches()) {
      return new Lookup(
          snapshot.generation(), LookupStatus.UNKNOWN_PROPOSAL_ONLY, Optional.empty(), List.of());
    }
    var effective = Optional.ofNullable(snapshot.effectiveCapabilities().get(id));
    if (effective.isPresent()) {
      return new Lookup(snapshot.generation(), LookupStatus.EFFECTIVE, effective, List.of());
    }
    var disabled =
        snapshot.drafts().stream()
            .filter(draft -> !draft.enabled())
            .filter(
                draft -> draft.manifest().map(manifest -> manifest.id().equals(id)).orElse(false))
            .toList();
    if (!disabled.isEmpty()) {
      return new Lookup(
          snapshot.generation(), LookupStatus.DISABLED_DRAFT, Optional.empty(), disabled);
    }
    return new Lookup(
        snapshot.generation(), LookupStatus.UNKNOWN_PROPOSAL_ONLY, Optional.empty(), List.of());
  }

  private CapabilityRegistryDiff diff(
      Map<String, EffectiveCapability> before, Map<String, EffectiveCapability> after) {
    var added = new HashSet<>(after.keySet());
    added.removeAll(before.keySet());
    var removed = new HashSet<>(before.keySet());
    removed.removeAll(after.keySet());
    var changed = new HashSet<String>();
    var unchanged = new HashSet<String>();
    for (var id : before.keySet()) {
      if (!after.containsKey(id)) {
        continue;
      }
      if (before.get(id).identity().equals(after.get(id).identity())) {
        unchanged.add(id);
      } else {
        changed.add(id);
      }
    }
    return new CapabilityRegistryDiff(added, removed, changed, unchanged);
  }

  public enum PublishStatus {
    PUBLISHED,
    STALE,
    REJECTED
  }

  public record PublishResult(PublishStatus status, CapabilityRegistrySnapshot snapshot) {
    public PublishResult {
      Objects.requireNonNull(status);
      Objects.requireNonNull(snapshot);
    }
  }

  public enum LookupStatus {
    EFFECTIVE,
    DISABLED_DRAFT,
    UNKNOWN_PROPOSAL_ONLY
  }

  public record Lookup(
      long generation,
      LookupStatus status,
      Optional<EffectiveCapability> effectiveCapability,
      List<CapabilityDraft> drafts) {
    public Lookup {
      if (generation < 0) {
        throw new IllegalArgumentException("Invalid capability lookup generation");
      }
      Objects.requireNonNull(status);
      effectiveCapability = Objects.requireNonNull(effectiveCapability);
      drafts = List.copyOf(drafts);
    }
  }
}
