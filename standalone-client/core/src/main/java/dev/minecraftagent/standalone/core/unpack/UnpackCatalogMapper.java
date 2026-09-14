package dev.minecraftagent.standalone.core.unpack;

import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.contract.ProcessRecord;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Turns an {@link UnpackedModpack} into a catalog gap-fill contribution. Resources and processes
 * already visible through the live game APIs always win: recipe ids present in the base process set
 * are skipped, and referenced resources reuse the base metadata whenever the base catalog knows the
 * id. Only ids the base catalog cannot resolve are synthesized, at trust level L2 with partial
 * completeness, with display names resolved from the scanned lang files.
 *
 * <p>Resource units mirror the live catalog service conventions: items use unit {@code "item"}.
 * Fluids have no live precedent, so they use unit {@code "millibucket"} (documented here because
 * the heuristic droplet conversion in {@link UnpackProcessMapper} targets millibuckets).
 */
public final class UnpackCatalogMapper {
  public static final String ADAPTER_ID = "mod_archive";

  /** Provider id stamped on processes that came from instance scripts instead of mod archives. */
  public static final String SCRIPT_PROVIDER_ID = "pack_scripts";

  private static final Pattern SYMBOLIC_ID = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");

  /** Answers "resource for kind+id" from the base (registry plus selected process) catalog data. */
  @FunctionalInterface
  public interface BaseResourceLookup {
    Optional<ResourceRef> find(ResourceRef.Kind kind, String id);
  }

  /**
   * Answers "member ids of a live game tag" from the running client (registry-bound tags captured
   * on the client thread). Returned entries are plain resource ids; an entry starting with {@code
   * '#'} references another tag and is followed by the recursive expansion loop like any static tag
   * reference. A {@code null} or empty result means "unknown here" and falls back to the static JAR
   * tags.
   */
  @FunctionalInterface
  public interface LiveTagLookup {
    List<String> values(String tagId);
  }

  public record MappingReport(
      int recipesSeen,
      int processesAdded,
      int skippedVanilla,
      int skippedDynamic,
      int skippedNoItemOutput,
      int skippedInvalidType,
      int skippedDuplicate,
      int skippedBudget,
      int synthesizedResources,
      int scriptRecipesSeen,
      int suppressedByRemovals,
      int removeAllRemovals) {
    public int skippedTotal() {
      return skippedVanilla
          + skippedDynamic
          + skippedNoItemOutput
          + skippedInvalidType
          + skippedDuplicate
          + skippedBudget;
    }
  }

  public record Result(CatalogAdapter.Contribution contribution, MappingReport report) {
    public Result {
      Objects.requireNonNull(contribution, "contribution");
      Objects.requireNonNull(report, "report");
    }
  }

  public Result map(
      UnpackedModpack modpack,
      BaseResourceLookup baseResources,
      Predicate<String> blockExists,
      Set<String> baseProcessIds,
      String gameLocale,
      String generationId,
      int maximumProcesses,
      int maximumResources) {
    return map(
        modpack,
        baseResources,
        blockExists,
        baseProcessIds,
        gameLocale,
        generationId,
        maximumProcesses,
        maximumResources,
        null,
        null);
  }

  /**
   * Maps the modpack with live game tags fused in. For every {@code {tag: ...}} expansion the live
   * lookup is consulted first (a non-empty result wins), the static merged JAR tags second, and
   * otherwise the tag stays unresolved (fail-closed, the recipe degrades to unplannable). Nested
   * {@code #} references inside live results are followed by the same bounded expansion loop (depth
   * {@code <= 8}, 64 alternatives, cycle-safe). Either live lookup may be {@code null}, which
   * reproduces the static-only behavior exactly.
   */
  public Result map(
      UnpackedModpack modpack,
      BaseResourceLookup baseResources,
      Predicate<String> blockExists,
      Set<String> baseProcessIds,
      String gameLocale,
      String generationId,
      int maximumProcesses,
      int maximumResources,
      LiveTagLookup liveItemTags,
      LiveTagLookup liveFluidTags) {
    return map(
        modpack,
        baseResources,
        blockExists,
        baseProcessIds,
        gameLocale,
        generationId,
        maximumProcesses,
        maximumResources,
        liveItemTags,
        liveFluidTags,
        null);
  }

  /**
   * Maps the modpack together with the instance script extraction. Script recipes join the gap fill
   * as candidates stamped with provider {@value #SCRIPT_PROVIDER_ID} (same L2 trust and local pack
   * layer as the archive candidates), and script removals suppress archive-derived candidates — by
   * exact or {@code *}-suffixed id, by primary output id, or wholesale for {@code removeAll} —
   * before they are mapped. Base processes (the live truth) are never suppressed: they win by id as
   * usual. Script-added recipes are never suppressed either; {@code scripts} may be {@code null},
   * which reproduces the archive-only behavior exactly.
   */
  public Result map(
      UnpackedModpack modpack,
      BaseResourceLookup baseResources,
      Predicate<String> blockExists,
      Set<String> baseProcessIds,
      String gameLocale,
      String generationId,
      int maximumProcesses,
      int maximumResources,
      LiveTagLookup liveItemTags,
      LiveTagLookup liveFluidTags,
      ScriptRecipeData scripts) {
    Objects.requireNonNull(modpack, "modpack");
    Objects.requireNonNull(baseResources, "baseResources");
    Objects.requireNonNull(blockExists, "blockExists");
    Objects.requireNonNull(baseProcessIds, "baseProcessIds");
    Objects.requireNonNull(generationId, "generationId");
    var itemTags = modpack.mergedTags(UnpackedTag.Kind.ITEM);
    var fluidTags = modpack.mergedTags(UnpackedTag.Kind.FLUID);
    UnpackProcessMapper.TagLookup tags =
        (kind, id) -> {
          var liveValues =
              liveValues(kind == UnpackedTag.Kind.ITEM ? liveItemTags : liveFluidTags, id);
          if (liveValues != null) {
            return liveValues;
          }
          return (kind == UnpackedTag.Kind.ITEM ? itemTags : fluidTags).get(id);
        };
    var removals = scripts == null ? List.<ScriptRemoval>of() : scripts.removals();
    var idRemovals =
        removals.stream().filter(removal -> removal.kind() == ScriptRemoval.Kind.ID).toList();
    var outputRemovals =
        removals.stream().filter(removal -> removal.kind() == ScriptRemoval.Kind.OUTPUT).toList();
    var removeAllRemovals =
        (int) removals.stream().filter(removal -> removal.kind() == ScriptRemoval.Kind.ALL).count();

    var run =
        new MappingRun(
            baseResources,
            new LangNames(modpack, gameLocale),
            generationId,
            baseProcessIds,
            maximumProcesses,
            maximumResources);

    for (var mod : modpack.mods()) {
      for (var recipe : mod.recipes()) {
        run.recipesSeen++;
        if (run.baseProcessIds.contains(recipe.id()) || !run.seen.add(recipe.id())) {
          run.skippedDuplicate++;
          continue;
        }
        if (removeAllRemovals > 0 || matchesAny(idRemovals, recipe.id())) {
          run.suppressedByRemovals++;
          continue;
        }
        var outcome = run.processMapper.map(recipe, tags, blockExists);
        if (outcome instanceof UnpackProcessMapper.Outcome.Skipped skipped) {
          run.countSkipped(skipped.reason());
          continue;
        }
        var candidate = ((UnpackProcessMapper.Outcome.Mapped) outcome).process();
        if (!outputRemovals.isEmpty()
            && !candidate.outputs().isEmpty()
            && matchesAny(outputRemovals, candidate.outputs().get(0).id())) {
          run.suppressedByRemovals++;
          continue;
        }
        run.addCandidate(mod, candidate, ADAPTER_ID);
      }
    }

    var scriptRecipes = scripts == null ? List.<UnpackedRecipe>of() : scripts.recipes();
    if (!scriptRecipes.isEmpty()) {
      var scriptMod =
          new UnpackedMod(
              "pack_scripts",
              "Pack Scripts",
              "local",
              "instance_scripts",
              List.of(),
              List.of(),
              Map.of());
      for (var recipe : scriptRecipes) {
        run.scriptRecipesSeen++;
        if (run.baseProcessIds.contains(recipe.id()) || !run.seen.add(recipe.id())) {
          run.skippedDuplicate++;
          continue;
        }
        var outcome = run.processMapper.map(recipe, tags, blockExists);
        if (outcome instanceof UnpackProcessMapper.Outcome.Skipped skipped) {
          run.countSkipped(skipped.reason());
          continue;
        }
        run.addCandidate(
            scriptMod,
            ((UnpackProcessMapper.Outcome.Mapped) outcome).process(),
            SCRIPT_PROVIDER_ID);
      }
    }

    var contribution =
        new CatalogAdapter.Contribution(
            ADAPTER_ID, generationId, run.resources, run.processes, List.of());
    return new Result(
        contribution,
        new MappingReport(
            run.recipesSeen,
            run.processes.size(),
            run.skippedVanilla,
            run.skippedDynamic,
            run.skippedNoItemOutput,
            run.skippedInvalidType,
            run.skippedDuplicate,
            run.skippedBudget,
            run.synthesized.size(),
            run.scriptRecipesSeen,
            run.suppressedByRemovals,
            removeAllRemovals));
  }

  private static boolean matchesAny(List<ScriptRemoval> removals, String id) {
    for (var removal : removals) {
      if (removal.matches(id)) {
        return true;
      }
    }
    return false;
  }

  /** Mutable state of one mapping run: budgets, dedupe sets, counters, and the assembly lists. */
  private static final class MappingRun {
    private final UnpackProcessMapper processMapper = new UnpackProcessMapper();
    private final BaseResourceLookup baseResources;
    private final LangNames names;
    private final String generationId;
    private final Set<String> baseProcessIds;
    private final int maximumProcesses;
    private final int maximumResources;
    private final List<ProcessRecord> processes = new ArrayList<>();
    private final List<ResourceRef> resources = new ArrayList<>();
    private final Map<String, ResourceRef> synthesized = new LinkedHashMap<>();
    private final Set<String> seen = new java.util.HashSet<>();
    private int recipesSeen;
    private int scriptRecipesSeen;
    private int skippedVanilla;
    private int skippedDynamic;
    private int skippedNoItemOutput;
    private int skippedInvalidType;
    private int skippedDuplicate;
    private int skippedBudget;
    private int suppressedByRemovals;

    private MappingRun(
        BaseResourceLookup baseResources,
        LangNames names,
        String generationId,
        Set<String> baseProcessIds,
        int maximumProcesses,
        int maximumResources) {
      this.baseResources = baseResources;
      this.names = names;
      this.generationId = generationId;
      this.baseProcessIds = baseProcessIds;
      this.maximumProcesses = maximumProcesses;
      this.maximumResources = maximumResources;
    }

    private void countSkipped(UnpackProcessMapper.SkipReason reason) {
      switch (reason) {
        case VANILLA_TYPE -> skippedVanilla++;
        case DYNAMIC_TYPE -> skippedDynamic++;
        case NO_ITEM_OUTPUT -> skippedNoItemOutput++;
        case INVALID_TYPE -> skippedInvalidType++;
      }
    }

    /** Builds and appends one candidate; budget overruns roll back and count as budget skips. */
    private void addCandidate(UnpackedMod mod, UnpackedProcess candidate, String providerId) {
      if (processes.size() >= maximumProcesses) {
        skippedBudget++;
        return;
      }
      var context =
          new ProcessContext(
              baseResources, synthesized, names, mod, generationId, candidate, providerId);
      var record = context.build();
      if (record == null) {
        skippedBudget++;
        return;
      }
      if (synthesized.size() > maximumResources) {
        // Roll the partially built process out instead of exceeding the catalog budget.
        synthesized.keySet().removeAll(context.newKeys);
        skippedBudget++;
        return;
      }
      processes.add(record.process());
      resources.addAll(record.newResources());
    }
  }

  /**
   * Resolves a live tag lookup call into tag entries, or {@code null} when the live view has no
   * usable answer (lookup absent, failing, or empty) and the static JAR tags should be consulted.
   */
  private static List<UnpackedTag.Entry> liveValues(LiveTagLookup lookup, String tagId) {
    if (lookup == null) {
      return null;
    }
    final List<String> values;
    try {
      values = lookup.values(tagId);
    } catch (RuntimeException failure) {
      return null;
    }
    if (values == null || values.isEmpty()) {
      return null;
    }
    var entries = new ArrayList<UnpackedTag.Entry>(values.size());
    for (var value : values) {
      if (value != null && !value.isBlank()) {
        entries.add(new UnpackedTag.Entry(value, true));
      }
    }
    return entries.isEmpty() ? null : entries;
  }

  /** Resolved display names: game locale first, then en_us, then the raw id. */
  private static final class LangNames {
    private final Map<String, String> locale;
    private final Map<String, String> fallback;

    private LangNames(UnpackedModpack modpack, String gameLocale) {
      this.locale =
          gameLocale == null || gameLocale.isBlank() ? Map.of() : modpack.mergedLang(gameLocale);
      this.fallback = modpack.mergedLang("en_us");
    }

    private String key(ResourceRef.Kind kind, String id) {
      var path = id.substring(id.indexOf(':') + 1).replace('/', '.');
      var namespace = id.substring(0, id.indexOf(':'));
      return kind.name().toLowerCase(java.util.Locale.ROOT) + "." + namespace + "." + path;
    }

    private String displayName(ResourceRef.Kind kind, String id) {
      var path = id.substring(id.indexOf(':') + 1).replace('/', '.');
      var namespace = id.substring(0, id.indexOf(':'));
      for (var prefix : List.of("item", "block", "fluid")) {
        var key = prefix + "." + namespace + "." + path;
        var value = locale.get(key);
        if (value != null && !value.isBlank()) {
          return value;
        }
        value = fallback.get(key);
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
      return id;
    }
  }

  private record BuiltProcess(ProcessRecord process, List<ResourceRef> newResources) {}

  /** Per-process build context; tracks newly synthesized resources so budget overruns roll back. */
  private static final class ProcessContext {
    private final BaseResourceLookup base;
    private final Map<String, ResourceRef> synthesized;
    private final LangNames names;
    private final UnpackedMod mod;
    private final String generationId;
    private final ResourceRef.Source completeSource;
    private final ResourceRef.Source partialSource;
    private final UnpackedProcess candidate;
    private final List<ResourceRef> newResources = new ArrayList<>();
    private final Set<String> newKeys = new java.util.HashSet<>();

    private ProcessContext(
        BaseResourceLookup base,
        Map<String, ResourceRef> synthesized,
        LangNames names,
        UnpackedMod mod,
        String generationId,
        UnpackedProcess candidate,
        String providerId) {
      this.base = base;
      this.synthesized = synthesized;
      this.names = names;
      this.mod = mod;
      this.generationId = generationId;
      this.completeSource =
          new ResourceRef.Source(
              ResourceRef.Layer.LOCAL_RESOURCE_PACK,
              providerId,
              ResourceRef.Trust.L2,
              ResourceRef.Completeness.COMPLETE,
              generationId);
      this.partialSource =
          new ResourceRef.Source(
              ResourceRef.Layer.LOCAL_RESOURCE_PACK,
              providerId,
              ResourceRef.Trust.L2,
              ResourceRef.Completeness.PARTIAL,
              generationId);
      this.candidate = candidate;
    }

    private BuiltProcess build() {
      var source = candidate.plannable() ? completeSource : partialSource;
      var inputs = new ArrayList<ProcessRecord.InputGroup>();
      var groupIds = new java.util.HashSet<String>();
      for (var index = 0; index < candidate.inputs().size(); index++) {
        var group = candidate.inputs().get(index);
        if (group.alternatives().isEmpty()) {
          continue;
        }
        var alternatives = new ArrayList<ResourceRef>();
        for (var amount : group.alternatives()) {
          alternatives.add(reference(kind(amount.kind()), amount.id(), amount.amount()));
        }
        var groupId = groupId(group.slot(), index, groupIds);
        inputs.add(new ProcessRecord.InputGroup(groupId, alternatives));
      }
      var outputs = new ArrayList<ProcessRecord.Output>();
      var capped = candidate.outputs().subList(0, Math.min(candidate.outputs().size(), 64));
      for (var output : capped) {
        var probability = output.primary() ? BigDecimal.ONE : BigDecimal.valueOf(output.chance());
        outputs.add(
            new ProcessRecord.Output(
                reference(kind(output.kind()), output.id(), output.amount()),
                probability,
                output.primary()));
      }
      if (outputs.isEmpty()) {
        return null;
      }
      var workstations = new ArrayList<ResourceRef>();
      if (candidate.workstationId() != null) {
        workstations.add(
            reference(ResourceRef.Kind.ITEM, candidate.workstationId(), BigDecimal.ONE));
      }
      var catalysts = new ArrayList<ProcessRecord.Catalyst>();
      if (candidate.catalystId() != null) {
        catalysts.add(
            new ProcessRecord.Catalyst(
                reference(ResourceRef.Kind.ITEM, candidate.catalystId(), BigDecimal.ONE),
                false,
                null));
      }
      var primary = outputs.get(0).resource();
      var displayName = displayName(primary, workstations);
      var process =
          new ProcessRecord(
              candidate.processId(),
              candidate.categoryId(),
              displayName,
              workstations,
              inputs,
              catalysts,
              outputs,
              candidate.durationTicks(),
              null,
              candidate.conditions(),
              List.of(),
              source,
              candidate.plannable(),
              candidate.warnings());
      return new BuiltProcess(process, newResources);
    }

    private String displayName(ResourceRef primary, List<ResourceRef> workstations) {
      var name = bounded(primary.displayName(), 200) + " recipe";
      if (!workstations.isEmpty()) {
        name += " (" + bounded(workstations.get(0).displayName(), 48) + ")";
      }
      return bounded(name, 256);
    }

    private static String groupId(String slot, int index, Set<String> used) {
      var candidate = slot != null && SYMBOLIC_ID.matcher(slot).matches() ? slot : "input_" + index;
      while (!used.add(candidate)) {
        candidate = candidate + "_x";
      }
      return candidate;
    }

    private ResourceRef reference(ResourceRef.Kind kind, String id, BigDecimal amount) {
      var found = base.find(kind, id);
      if (found.isPresent()) {
        var existing = found.get();
        return new ResourceRef(
            existing.kind(),
            existing.id(),
            existing.componentsFingerprint(),
            existing.displayName(),
            existing.translationKey(),
            existing.modId(),
            existing.modName(),
            existing.modVersion(),
            amount,
            existing.unit(),
            sourceFor(existing));
      }
      var key = kind + ":" + id;
      var existing = synthesized.get(key);
      if (existing != null) {
        return withAmount(existing, amount);
      }
      var created = synthesize(kind, id, amount);
      synthesized.put(key, created);
      newResources.add(created);
      newKeys.add(key);
      return created;
    }

    private ResourceRef.Source sourceFor(ResourceRef base) {
      // The process pins one snapshot generation; reused base metadata keeps its own trust layer
      // whenever it already belongs to this generation, otherwise it is re-grounded at L2.
      return base.source().generationId().equals(generationId) ? base.source() : partialSource;
    }

    private ResourceRef withAmount(ResourceRef resource, BigDecimal amount) {
      if (resource.amount().equals(amount)) {
        return resource;
      }
      return new ResourceRef(
          resource.kind(),
          resource.id(),
          resource.componentsFingerprint(),
          resource.displayName(),
          resource.translationKey(),
          resource.modId(),
          resource.modName(),
          resource.modVersion(),
          amount,
          resource.unit(),
          resource.source());
    }

    private ResourceRef synthesize(ResourceRef.Kind kind, String id, BigDecimal amount) {
      var namespace = id.substring(0, id.indexOf(':'));
      var safeModId = SYMBOLIC_ID.matcher(namespace).matches() ? namespace : "unknown";
      return new ResourceRef(
          kind,
          id,
          null,
          bounded(names.displayName(kind, id), 512),
          bounded(names.key(kind, id), 256),
          safeModId,
          bounded(mod.modName(), 128),
          bounded(mod.modVersion(), 128),
          amount,
          kind == ResourceRef.Kind.ITEM ? "item" : "millibucket",
          partialSource);
    }

    private static ResourceRef.Kind kind(UnpackedTag.Kind kind) {
      return kind == UnpackedTag.Kind.ITEM ? ResourceRef.Kind.ITEM : ResourceRef.Kind.FLUID;
    }

    private static String bounded(String value, int maximum) {
      var normalized = value == null || value.isBlank() ? "unknown" : value.strip();
      return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
  }
}
